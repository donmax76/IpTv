/**
 * Native FM DSP — C++ demodulator for zero-jitter audio on Android.
 *
 * Mirrors the Kotlin FmDemodulator pipeline but runs ~3-5× faster on
 * mid-range phones thanks to native compilation and aggressive opts.
 *
 * Pipeline: IQ bytes → DC removal → IF LPF (64 taps) → Decimate /6 →
 *   FM discriminator → Pilot PLL → Stereo decode → Audio LPF (32 taps) →
 *   Decimate /4 → De-emphasis → Soft-clip → PCM 16-bit
 *
 * RDS path: wideband baseband + pilot phase at buf[0] → JNI getter
 */
#include <jni.h>
#include <cmath>
#include <cstring>
#include <android/log.h>
#include <cstdint>

#define TAG "NativeDSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// 960 kHz instead of 1.152 MHz: the BYD DiLink USB host sustains only
// ~2.26 MB/s, so at 1.152 MHz (2.304 MB/s) ~1.8% of IQ samples were lost
// (measured from field logs) — heard as clicks and killing RDS sync with
// phase discontinuities. 1.92 MB/s leaves ~15% bus headroom; the
// intermediate rate stays 192 kHz so the whole DSP chain is unchanged.
static constexpr int SAMPLE_RATE = 960000;
static constexpr int STAGE1_DEC = 5;
static constexpr int INTERMEDIATE_RATE = SAMPLE_RATE / STAGE1_DEC; // 192000
static constexpr int AUDIO_RATE = 48000;
static constexpr int STAGE2_DEC = INTERMEDIATE_RATE / AUDIO_RATE;  // 4
static constexpr float PI_F = 3.14159265358979323846f;
static constexpr double PI_D = 3.14159265358979323846;

// ========== Byte→Float LUT ==========
static float byteLut[256];
static bool lutInit = false;

static void initLut() {
    if (lutInit) return;
    for (int i = 0; i < 256; i++)
        byteLut[i] = i / 127.5f - 1.0f;  // original symmetric mapping
    lutInit = true;
}

// ========== Filter design ==========
static void designLpf(float* coeffs, int order, float normCutoff) {
    int mid = order / 2;
    float sum = 0;
    for (int i = 0; i < order; i++) {
        int n = i - mid;
        if (n == 0)
            coeffs[i] = 2.0f * normCutoff;
        else
            coeffs[i] = sinf(2.0f * PI_F * normCutoff * n) / (PI_F * n);
        // Blackman-Harris window — ~92 dB stopband
        float w = (float)i / (float)(order - 1);
        coeffs[i] *= 0.35875f - 0.48829f * cosf(2*PI_F*w)
                    + 0.14128f * cosf(4*PI_F*w) - 0.01168f * cosf(6*PI_F*w);
        sum += coeffs[i];
    }
    for (int i = 0; i < order; i++) coeffs[i] /= sum;
}

// ========== Fast atan2 (from rtl_fm) ==========
static inline float fastAtan2(float y, float x) {
    float absX = fabsf(x), absY = fabsf(y);
    if (absX < 1e-12f && absY < 1e-12f) return 0.0f;
    float a = (absX < absY) ? absX / absY : absY / absX;
    float s = a * a;
    float r = ((-0.0464964749f * s + 0.15931422f) * s - 0.327622764f) * s * a + a;
    if (absY > absX) r = PI_F / 2.0f - r;
    if (x < 0) r = PI_F - r;
    if (y < 0) r = -r;
    return r;
}

// ========== Soft-clip limiter ==========
static inline float softClip(float x) {
    float ax = fabsf(x);
    if (ax <= 0.8f) return x;
    float sign = (x >= 0.0f) ? 1.0f : -1.0f;
    float t = (ax - 0.8f) * 5.0f;
    float compressed = t / (1.0f + t);
    float v = 0.8f + 0.2f * compressed;
    if (v > 0.9999695f) v = 0.9999695f;
    return sign * v;
}

// ========== DSP State ==========
struct DspState {
    bool initialized = false;

    // DC removal
    float dcI = 0, dcQ = 0;
    // DC alpha: actual value used is 0.999995 (~0.9 Hz cutoff), set in demodulate()
    // via dcA variable (TEST_DC flag selects between 0.999995 and 0.99995).

    // FM discriminator
    float prevI = 0, prevQ = 0;
    float fmGain;

    // IF LPF (64 taps, double-buffer to eliminate modulo)
    static constexpr int IF_LPF_ORDER = 64;
    float ifCoeffs[IF_LPF_ORDER];
    float ifBufI[IF_LPF_ORDER * 2] = {};
    float ifBufQ[IF_LPF_ORDER * 2] = {};
    int ifBufIdx = 0;
    int stage1Counter = 0;

    // Audio LPF (32 taps, double-buffer)
    // 32 taps was far too short for a 15 kHz cut at 192 kHz: measured, it
    // left the 19 kHz pilot only 10.8 dB down and the bottom of the stereo
    // band 18.3 dB down. Both land in the audio — the pilot as a constant
    // tone, present in MONO too, since it rides on the composite and the
    // stereo decoder has nothing to do with it. 48 taps takes 30 kHz from
    // -39 dB to -92 dB, which is what kills the aliasing; the pilot itself
    // needs the notch below, because no filter of a sane length can put a
    // 15 kHz passband edge and a deep stop 4 kHz apart.
    static constexpr int AUDIO_LPF_ORDER = 48;
    float audioCoeffs[AUDIO_LPF_ORDER];
    // 19 kHz notch on the mono path. Five multiplies a sample against a tone
    // that no FM receiver should ever pass to its output.
    double pnB0 = 1, pnB1 = 0, pnB2 = 0, pnA1 = 0, pnA2 = 0;
    float pnX1 = 0, pnX2 = 0, pnY1 = 0, pnY2 = 0;
    float monoLpfBuf[AUDIO_LPF_ORDER * 2] = {};
    float diffLpfBuf[AUDIO_LPF_ORDER * 2] = {};
    int monoLpfIdx = 0, diffLpfIdx = 0;
    int stage2Counter = 0;

    // Pilot PLL
    double pilotBpfState[4] = {};
    double pilotBpfB0, pilotBpfB2, pilotBpfA1, pilotBpfA2;
    double pilotNcoPhase = 0;
    double pilotNcoFreq;
    double pilotAlpha, pilotBeta;
    double pilotFreqMin, pilotFreqMax;

    // Pilot strength / stereo detection (with hysteresis like Kotlin)
    float pilotStrengthAcc = 0;
    int pilotStrengthCount = 0;
    float pilotStrength = 0;
    bool isStereo = false;
    int pilotDetectWindow;
    // FC0013 has weaker pilot output than R820T. Lock at 0.016 catches
    // real stations, unlock at 0.006 prevents flicker on fading signals.
    // Wide hysteresis = once stereo locks, it stays locked.
    static constexpr float STEREO_LOCK = 0.016f;
    static constexpr float STEREO_UNLOCK = 0.006f;
    float stereoBlend = 0.0f;
    // Slow blend transitions prevent audible "pumping" on marginal signals
    static constexpr float STEREO_BLEND_ATTACK = 0.0003f;   // ~70ms to full stereo
    static constexpr float STEREO_BLEND_RELEASE = 0.0001f;  // ~210ms to mono

    // De-emphasis
    float deEmphAlpha;
    float deEmphStateL = 0, deEmphStateR = 0;

    // ===== Reception quality, measured from ultrasonic noise =====
    //
    // Everything a station transmits lives below ~60 kHz in the multiplex:
    // mono to 15 kHz, pilot at 19, the stereo subcarrier to 53, RDS at 57.
    // Above that there is nothing but receiver noise, and because FM noise
    // density rises with frequency it is the most sensitive indicator of
    // signal quality available — which is what a car radio uses to decide how
    // much stereo and how much treble it can afford.
    //
    // The band is 84 kHz. Two earlier choices were wrong and both were caught
    // by measurement rather than reasoning:
    //
    //   * 74-82 kHz carries the 2nd harmonic of the 38 kHz stereo subcarrier,
    //     a demodulator artefact whose level follows programme loudness (it
    //     moved 4x between a loud and a quiet passage at the same signal). A
    //     metric that tracks loudness collapses the stereo image on every loud
    //     passage.
    //   * 62-70 kHz looked ideal on a synthetic signal — 10x contrast between
    //     a weak and a clean station, 1.1x with loudness — but real stations
    //     carry SCA/DARC subcarriers around 67 kHz. A Windows field log showed
    //     a station at -8.8 dB reading 0.22 while a STRONGER one at -7.9 dB
    //     read 0.016: the measure was following what each station broadcasts,
    //     not how well it arrives. A synthesized 67 kHz subcarrier at 10%
    //     injection reproduced it exactly, forcing a perfectly clean station
    //     to mono with a 5 kHz high-cut.
    //
    // 84 kHz sits above every subcarrier in use and below the 90 kHz IF edge.
    double nzHpB0, nzHpB1, nzHpB2, nzHpA1, nzHpA2;
    double nzX1 = 0, nzX2 = 0, nzY1 = 0, nzY2 = 0;
    double nzAcc = 0;
    int    nzCount = 0;
    int    nzWindow;                 // samples per measurement
    float  noiseLevel = 0.0f;        // smoothed ultrasonic noise RMS
    float  noiseEma = 0.0f;
    static constexpr float NOISE_EMA_A = 0.15f;

    // Blend to mono as noise rises. Below FULL the signal is clean enough for
    // full separation; above NONE the L-R path carries more noise than
    // information and is dropped entirely. Between the two the separation is
    // scaled linearly — the gradual behaviour that makes a factory radio sound
    // steady while driving instead of switching audibly in and out of stereo.
    // Calibrated against the bench. The metric has a floor that comes from the
    // fast-atan2 approximation rather than from the air, so the thresholds sit
    // above it with margin — real hardware can only read higher than this
    // idealised case, and the value is logged so it can be retuned from a
    // field log rather than guessed at:
    //   C/N 40 dB -> 0.0074   C/N 20 dB -> 0.0119   C/N 11 dB -> 0.0278
    //   C/N 25 dB -> 0.0090   C/N 17 dB -> 0.0152   C/N  8 dB -> 0.0388
    // Calibrated to what this receiver actually reports, with the sharpened
    // 84 kHz filter. Eleven readings from the field across strong and weak
    // stations ran 0.028 to 0.118 — so the old 0.012 "clean" and 0.040
    // "hopeless" were both below the BEST reception this hardware ever sees,
    // which is why every station was being treated as hopeless and why the
    // signal-strength override then had to switch the protection off wholesale
    // to get the sound back. With the scale right, the measure can do its job
    // and the override can go back to being a floor rather than a veto.
    static constexpr float NOISE_STEREO_FULL = 0.030f;
    static constexpr float NOISE_STEREO_NONE = 0.110f;
    float snrBlend = 1.0f;

    // Progressive treble roll-off. FM noise is worst at the top of the audio
    // band, so trading high frequencies for quiet is a good deal once the
    // signal degrades — again, standard car-radio behaviour.
    // Above SIG_CLEAN_DB the carrier is strong enough that the audio cannot be
    // noisy, whatever the ultrasonic measure says; below SIG_DEGRADE_DB the
    // measure is on its own. In between the two are mixed. This exists because
    // the ultrasonic measure cannot be fully separated from programme content
    // in the 96 kHz the intermediate rate leaves us, so it must not be the only
    // vote.
    static constexpr float SIG_CLEAN_DB   = -25.0f;
    static constexpr float SIG_DEGRADE_DB = -45.0f;
    static constexpr float NOISE_HICUT_START = 0.045f;   // still full bandwidth
    static constexpr float NOISE_HICUT_FULL  = 0.130f;   // hardest roll-off
    static constexpr float HICUT_MAX_HZ = 15000.0f;
    static constexpr float HICUT_MIN_HZ = 3200.0f;
    float hiCutHz = HICUT_MAX_HZ;
    float hiCutAlpha = 1.0f;         // one-pole coefficient at AUDIO_RATE
    float hiCutStateL = 0, hiCutStateR = 0;

    // ===== Impulse noise blanker (operates on raw IQ) =====
    // Ignition sparks and multipath transients arrive as short bursts that
    // momentarily break the FM carrier's constant envelope. Working on |IQ|
    // squared before any filtering, a burst is still only a few samples wide
    // and can be gated out cleanly.
    float nbAvg = 0.2f;              // slow average of |IQ|^2
    static constexpr float NB_AVG_A = 0.0005f;
    static constexpr float NB_THRESHOLD = 9.0f;   // multiples of mean power
    static constexpr int   NB_MAX_RUN = 8;        // 8 us at 960 kHz
    int   nbRun = 0;
    float nbLastI = 0.0f, nbLastQ = 0.0f;
    long  nbBlanked = 0;             // diagnostics
    long  softClipHits = 0;          // audio samples that reached the limiter knee
    long  softClipTotal = 0;

    // Signal strength
    double sigPowerAcc = 0;
    int sigPowerCount = 0;
    float signalDb = -100.0f;
    int sigPowerWindow;

    // ===== ADC loading, for the tuner gain loop =====
    // Measured on the RAW bytes, before DC removal and filtering, because what
    // matters is how much of the RTL2832U's 8-bit converter range the signal
    // occupies. The gain was previously nailed to maximum for the whole
    // session; in a moving vehicle the received level swings by tens of dB, so
    // near a transmitter the converter clips and the audio turns harsh, while
    // any fixed lower setting costs sensitivity everywhere else. Reporting the
    // real loading lets the Kotlin side close the loop instead of guessing.
    unsigned adcSubsample = 0;  // only every 4th sample is metered
    double adcAcc = 0;          // sum of squares of |sample| in full-scale units
    long   adcCount = 0;
    long   adcClipCount = 0;    // samples at the very ends of the range
    float  adcRms = 0.0f;       // 0..1, RMS of the IQ magnitude
    float  adcClipPct = 0.0f;   // percentage of samples pinned at 0 or 255

    // Squelch — mutes noise on empty/weak frequencies.
    // Uses modulation-level measurement with hysteresis to avoid chattering.
    double sqQualityAcc = 0;
    int sqQualityCount = 0;
    bool squelchOpen = true;
    float squelchLevel = 1.0f;
    static constexpr float SQ_OPEN_THRESH = 0.03f;
    static constexpr float SQ_CLOSE_THRESH = 0.008f;
    float squelchAttack;   // rate to open (per intermediate sample)
    float squelchRelease;  // rate to close

    // Warmup
    int warmupSamples = 0;
    int warmupThreshold;

    // Mute ramp
    float muteRamp = 0.0f;
    float muteRampUp;

    // Wideband buffer for RDS — pilot phase captured at buf[0]
    float wbBuf[6000];
    int wbCount = 0;
    double wbStartPilotPhase = 0.0;

    // Periodic diagnostic logging
    int logCounter = 0;
    static constexpr int LOG_INTERVAL = 5 * AUDIO_RATE;  // every 5 sec worth of audio samples

    // ========== Runtime A/B test flags (bitfield) ==========
    // Bit 0 = TEST_GAIN   — lower fmGain 0.82 → 0.65 (more headroom, softer peaks)
    // Bit 1 = TEST_NOTCH  — enable 19 kHz biquad notch on audio (kill pilot residue)
    // Bit 2 = TEST_PLL    — faster pilot PLL 1 Hz → 5 Hz (less jitter on fading)
    // Bit 3 = TEST_DC     — aggressive IQ DC blocker 0.999995 → 0.99995
    int testFlags = 0;
    static constexpr int TEST_GAIN = 0x01;
    static constexpr int TEST_NOTCH = 0x02;
    static constexpr int TEST_PLL = 0x04;
    static constexpr int TEST_DC = 0x08;
    // Bit 4 = TEST_NB_OFF — legacy, kept so an existing setting still parses
    static constexpr int TEST_NB_OFF = 0x10;
    // Bit 5 = TEST_NB_ON  — the impulse blanker must now be asked for.
    //
    // It is OFF by default. Its benefit against real ignition noise was never
    // demonstrated — I could not reproduce spark interference faithfully enough
    // to measure it — while its cost is real: a magnitude, a compare and an
    // average update on every sample at 960 kHz. A field log then showed the
    // DSP running 15 buffers (~255 ms) behind the USB stream on a head unit
    // where it used to keep up exactly, with RDS unable to acquire block sync
    // at all on a strong, stereo-locked signal. Unproven work that costs
    // real-time budget on the target device does not stay switched on.
    static constexpr int TEST_NB_ON = 0x20;
    // Bit 6 = force mono, whatever the signal looks like. Stereo costs about
    // 20 dB of noise for the separation it buys, and on a marginal station
    // that is a trade only the person listening can judge. Every automatic
    // rule tried here has been wrong for someone: too eager and clean
    // stations were flattened, too shy and noisy ones hissed. This hands the
    // decision over.
    static constexpr int TEST_FORCE_MONO = 0x40;

    // Two pre-computed gain values, selected by TEST_GAIN flag at runtime.
    float fmGainDefault;
    float fmGainLow;

    // Pilot PLL gears: Fast = acquisition, Slow = tracking (see init()).
    double pilotAlphaSlow, pilotBetaSlow;
    double pilotAlphaFast, pilotBetaFast;
    // false = acquiring (wide loop), true = tracking (narrow loop)
    bool pllTrackGear = false;
    // How long the pilot has been continuously present, in 192 kHz samples.
    int pllLockSamples = 0;
    // Hold the pilot for this long before narrowing the loop. 0.4 s is well
    // past the wide loop's settling time and short enough that a listener
    // never hears the acquisition phase.
    static constexpr int PLL_LOCK_HOLD = INTERMEDIATE_RATE * 2 / 5;

    // 19 kHz notch biquads (separate state for L/R channels).
    // Applied AFTER the stereo matrix but BEFORE de-emphasis.
    double notchB0 = 1.0, notchB1 = 0.0, notchB2 = 0.0;
    double notchA1 = 0.0, notchA2 = 0.0;
    double notchLX1 = 0, notchLX2 = 0, notchLY1 = 0, notchLY2 = 0;
    double notchRX1 = 0, notchRX2 = 0, notchRY1 = 0, notchRY2 = 0;

    void init() {
        if (initialized) return;
        initialized = true;

        // Two fmGain presets, selected at runtime by TEST_GAIN flag
        float fmGainBase = (float)INTERMEDIATE_RATE / (2.0f * PI_F * 75000.0f);
        fmGainDefault = fmGainBase * 0.75f;  // ORIGINAL value (0.82 caused compression at soft-clip knee)
        fmGainLow     = fmGainBase * 0.65f;  // TEST 1: more headroom
        fmGain = fmGainDefault;

        // IF filter: 90 kHz cutoff — must stay below the post-decimation
        // Nyquist (192 kHz / 2 = 96 kHz); a 120 kHz cutoff aliased the
        // 96-120 kHz band (noise + adjacent channels) into the signal.
        designLpf(ifCoeffs, IF_LPF_ORDER, 90000.0f / SAMPLE_RATE);
        // Audio filter: 15 kHz cutoff
        designLpf(audioCoeffs, AUDIO_LPF_ORDER, 15000.0f / INTERMEDIATE_RATE);
        {
            // Q=40: 19 kHz annihilated, and only about 0.4 dB of ripple by
            // 15 kHz, so nothing audible is touched.
            const double w0 = 2.0 * PI_D * 19000.0 / INTERMEDIATE_RATE;
            const double al = sin(w0) / (2.0 * 40.0);
            const double a0 = 1.0 + al;
            pnB0 = 1.0 / a0;
            pnB1 = (-2.0 * cos(w0)) / a0;
            pnB2 = 1.0 / a0;
            pnA1 = (-2.0 * cos(w0)) / a0;
            pnA2 = (1.0 - al) / a0;
        }

        // Noise-measuring bandpass: 84 kHz centre, ~6 kHz wide (RBJ constant
        // skirt gain), running on the discriminator output.
        {
            // 84 kHz, above every subcarrier a station may transmit. 65 kHz
            // was tried first and is wrong: SCA/DARC services sit around
            // 67 kHz, so the reading followed what the station broadcast
            // rather than how well it was received. A Windows field log made
            // this unmistakable — a station at -8.8 dB read 0.22 while a
            // STRONGER one at -7.9 dB read 0.016 — and a synthesized 67 kHz
            // subcarrier at 10% injection reproduced it exactly, forcing a
            // perfectly clean station to mono with a 5 kHz high-cut.
            // 76 kHz is unusable too (2nd harmonic of the stereo subcarrier,
            // an artefact that follows programme loudness), so 84 kHz it is.
            double w0n = 2.0 * PI_D * 84000.0 / INTERMEDIATE_RATE;
            double cw = cos(w0n), sw = sin(w0n);
            // Q was 84/6 = 14, which is a 6 kHz-wide filter: the 76 kHz second
            // harmonic of the stereo subcarrier sits only 8 kHz away and was
            // attenuated by just 9 dB. That harmonic follows programme
            // loudness, so the "noise" reading followed loudness too — a field
            // report from a -12.9 dB station (very strong) read 0.034, which
            // collapsed stereo to 21% and rolled the treble off at 9.4 kHz on a
            // signal with nothing wrong with it. Q=48 puts the harmonic ~19 dB
            // down. The coefficients are computed and applied in double, so the
            // narrower band costs nothing in numerical accuracy.
            double q = 48.0;
            double al = sw / (2.0 * q);
            double a0n = 1.0 + al;
            nzHpB0 = al / a0n;
            nzHpB1 = 0.0;
            nzHpB2 = -al / a0n;
            nzHpA1 = (-2.0 * cw) / a0n;
            nzHpA2 = (1.0 - al) / a0n;
        }
        nzWindow = INTERMEDIATE_RATE / 100;   // 10 ms per measurement

        // Pilot BPF (Q=80, 19 kHz)
        double w0 = 2.0 * PI_D * 19000.0 / INTERMEDIATE_RATE;
        double bpfAlpha = sin(w0) / (2.0 * 80.0);
        double a0p = 1.0 + bpfAlpha;
        pilotBpfB0 = bpfAlpha / a0p;
        pilotBpfB2 = -bpfAlpha / a0p;
        pilotBpfA1 = (-2.0 * cos(w0)) / a0p;
        pilotBpfA2 = (1.0 - bpfAlpha) / a0p;

        // Pilot PLL, two gears.
        //
        // The loop used to run permanently at 1 Hz. Measured on a synthesized
        // station (bench: 2 s blocks, exact 19 kHz pilot), stereo separation
        // after a reset came up like this:
        //
        //   after 1 s : -0.1 dB   (channels completely mixed)
        //   after 3 s :  4.2 dB
        //   after 5 s : 12.6 dB
        //   after 8 s : 14.0 dB   (design maximum is 15.1 dB)
        //
        // Every retune calls reset(), so for the first several seconds on a new
        // station there was no stereo separation at all — while the noisy L-R
        // path was already being mixed into the audio. On a moving vehicle,
        // where the loop is disturbed by every fade, it rarely got to settle.
        //
        // The same bench at 5 Hz reaches 12 dB in 2 s with IDENTICAL settled
        // separation (14.1 dB) and IDENTICAL audio SNR at 40/20/14 dB C/N — so
        // the old comment's claim that 1 Hz "rejects noise" better did not hold
        // up. Rather than pick one compromise, acquire wide and track narrow:
        // ACQ locks quickly after a retune or a fade, TRACK keeps phase jitter
        // low once the pilot is being followed reliably.
        pilotNcoFreq = 2.0 * PI_D * 19000.0 / INTERMEDIATE_RATE;
        double damp = 0.707;
        double loopBwAcq   = 2.0 * PI_D * 25.0 / INTERMEDIATE_RATE;  // acquisition
        double loopBwTrack = 2.0 * PI_D * 3.0  / INTERMEDIATE_RATE;  // steady state
        pilotAlphaFast = 2.0 * damp * loopBwAcq;
        pilotBetaFast  = loopBwAcq * loopBwAcq;
        pilotAlphaSlow = 2.0 * damp * loopBwTrack;
        pilotBetaSlow  = loopBwTrack * loopBwTrack;
        // Start wide; switchToTrackGear() narrows once the pilot is held.
        pilotAlpha = pilotAlphaFast;
        pilotBeta  = pilotBetaFast;
        pllTrackGear = false;
        pllLockSamples = 0;

        // 19 kHz biquad notch (RBJ cookbook). Q=10 → ~1.9 kHz wide notch at
        // AUDIO_RATE = 48 kHz. Runs on the final L/R audio samples when
        // TEST_NOTCH is enabled.
        {
            double w0n = 2.0 * PI_D * 19000.0 / AUDIO_RATE;
            double cw0 = cos(w0n);
            double sw0 = sin(w0n);
            double Q = 6.0;  // reduced from 10 for stability near Nyquist
            double alphaN = sw0 / (2.0 * Q);
            double a0n = 1.0 + alphaN;
            notchB0 = 1.0 / a0n;
            notchB1 = (-2.0 * cw0) / a0n;
            notchB2 = 1.0 / a0n;
            notchA1 = (-2.0 * cw0) / a0n;
            notchA2 = (1.0 - alphaN) / a0n;
        }
        pilotFreqMin = 2.0 * PI_D * 18500.0 / INTERMEDIATE_RATE;
        pilotFreqMax = 2.0 * PI_D * 19500.0 / INTERMEDIATE_RATE;

        pilotDetectWindow = INTERMEDIATE_RATE / 2;  // 500ms averaging — more stable stereo detection

        // De-emphasis 50µs (Europe/Russia)
        float tau = 50e-6f;
        float dt = 1.0f / AUDIO_RATE;
        deEmphAlpha = dt / (tau + dt);

        sigPowerWindow = INTERMEDIATE_RATE / 3;
        squelchAttack  = 1.0f / (0.1f * INTERMEDIATE_RATE);   // 100ms to open
        squelchRelease = 1.0f / (0.3f * INTERMEDIATE_RATE);   // 300ms to close
        warmupThreshold = INTERMEDIATE_RATE / 10;

        muteRampUp = 1.0f / (0.1f * AUDIO_RATE);  // 100ms ramp — avoids click on start

        LOGI("Native DSP initialized: rate=%d intermediate=%d audio=%d if_taps=%d audio_taps=%d",
             SAMPLE_RATE, INTERMEDIATE_RATE, AUDIO_RATE, IF_LPF_ORDER, AUDIO_LPF_ORDER);
    }

    void reset() {
        dcI = dcQ = 0;
        prevI = prevQ = 0;
        pnX1 = 0; pnX2 = 0; pnY1 = 0; pnY2 = 0;   // pilot notch state
        memset(ifBufI, 0, sizeof(ifBufI));
        memset(ifBufQ, 0, sizeof(ifBufQ));
        ifBufIdx = 0;
        stage1Counter = 0;
        memset(monoLpfBuf, 0, sizeof(monoLpfBuf));
        memset(diffLpfBuf, 0, sizeof(diffLpfBuf));
        monoLpfIdx = diffLpfIdx = 0;
        stage2Counter = 0;
        memset(pilotBpfState, 0, sizeof(pilotBpfState));
        pilotNcoPhase = 0;
        pilotNcoFreq = 2.0 * PI_D * 19000.0 / INTERMEDIATE_RATE;
        pilotStrengthAcc = 0;
        pilotStrengthCount = 0;
        pilotStrength = 0;
        isStereo = false;
        stereoBlend = 0.0f;
        // Back to the wide loop: a retune is exactly when fast acquisition
        // matters, and reset() is what a retune calls.
        pllTrackGear = false;
        pllLockSamples = 0;
        pilotAlpha = pilotAlphaFast;
        pilotBeta  = pilotBetaFast;
        deEmphStateL = deEmphStateR = 0;
        nzX1 = nzX2 = nzY1 = nzY2 = 0;
        nzAcc = 0; nzCount = 0;
        noiseLevel = 0.0f; noiseEma = 0.0f;
        snrBlend = 1.0f;
        hiCutHz = HICUT_MAX_HZ; hiCutAlpha = 1.0f;
        hiCutStateL = hiCutStateR = 0;
        nbAvg = 0.2f; nbRun = 0; nbLastI = nbLastQ = 0.0f; nbBlanked = 0;
        softClipHits = 0; softClipTotal = 0;
        sigPowerAcc = 0;
        sigPowerCount = 0;
        signalDb = -100.0f;
        adcAcc = 0; adcCount = 0; adcClipCount = 0; adcSubsample = 0;
        adcRms = 0.0f; adcClipPct = 0.0f;
        sqQualityAcc = 0; sqQualityCount = 0;
        squelchOpen = true; squelchLevel = 1.0f;
        warmupSamples = 0;
        muteRamp = 0.0f;
        wbCount = 0;
        wbStartPilotPhase = 0.0;
        notchLX1 = notchLX2 = notchLY1 = notchLY2 = 0;
        notchRX1 = notchRX2 = notchRY1 = notchRY2 = 0;
        // Don't reset testFlags — user controls them from UI
    }

    void applyTestFlags() {
        fmGain = (testFlags & TEST_GAIN) ? fmGainLow : fmGainDefault;
        // TEST_PLL now forces the wide acquisition loop permanently, for
        // comparing against the automatic gear change.
        if (testFlags & TEST_PLL) {
            pllTrackGear = false;
            pilotAlpha = pilotAlphaFast;
            pilotBeta  = pilotBetaFast;
        }
    }

    /** Widen or narrow the loop according to how long the pilot has held. */
    inline void updatePllGear(bool pilotPresent) {
        if (testFlags & TEST_PLL) return;   // forced wide for A/B testing
        if (pilotPresent) {
            if (!pllTrackGear && ++pllLockSamples >= PLL_LOCK_HOLD) {
                pllTrackGear = true;
                pilotAlpha = pilotAlphaSlow;
                pilotBeta  = pilotBetaSlow;
            }
        } else {
            // Pilot gone — a fade, or the listener retuned. Go wide again so
            // the loop can re-acquire in a fraction of a second instead of
            // dragging a narrow loop back over several seconds.
            pllLockSamples = 0;
            if (pllTrackGear) {
                pllTrackGear = false;
                pilotAlpha = pilotAlphaFast;
                pilotBeta  = pilotBetaFast;
            }
        }
    }

    inline double notchL(double x) {
        double y = notchB0 * x + notchB1 * notchLX1 + notchB2 * notchLX2
                 - notchA1 * notchLY1 - notchA2 * notchLY2;
        notchLX2 = notchLX1; notchLX1 = x;
        notchLY2 = notchLY1; notchLY1 = y;
        return y;
    }

    inline double notchR(double x) {
        double y = notchB0 * x + notchB1 * notchRX1 + notchB2 * notchRX2
                 - notchA1 * notchRY1 - notchA2 * notchRY2;
        notchRX2 = notchRX1; notchRX1 = x;
        notchRY2 = notchRY1; notchRY1 = y;
        return y;
    }

    inline double pilotBpf(double input) {
        double y = pilotBpfB0 * input + pilotBpfB2 * pilotBpfState[1]
                 - pilotBpfA1 * pilotBpfState[2] - pilotBpfA2 * pilotBpfState[3];
        pilotBpfState[1] = pilotBpfState[0];
        pilotBpfState[0] = input;
        pilotBpfState[3] = pilotBpfState[2];
        pilotBpfState[2] = y;
        return y;
    }
};

static DspState g_dsp;

// ========== JNI Functions ==========

extern "C" {

JNIEXPORT void JNICALL
Java_com_fmradio_dsp_NativeFmDsp_init(JNIEnv*, jobject) {
    initLut();
    g_dsp.init();
}

JNIEXPORT void JNICALL
Java_com_fmradio_dsp_NativeFmDsp_reset(JNIEnv*, jobject) {
    g_dsp.reset();
}

JNIEXPORT jfloat JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getSignalDb(JNIEnv*, jobject) {
    return g_dsp.signalDb;
}

JNIEXPORT jboolean JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getIsStereo(JNIEnv*, jobject) {
    return g_dsp.isStereo;
}

JNIEXPORT jdouble JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getPilotPhase(JNIEnv*, jobject) {
    return g_dsp.wbStartPilotPhase;
}

JNIEXPORT jdouble JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getPilotFreq(JNIEnv*, jobject) {
    return g_dsp.pilotNcoFreq;
}

JNIEXPORT jint JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getWbCount(JNIEnv*, jobject) {
    return g_dsp.wbCount;
}

/** RMS of the IQ magnitude as a fraction of ADC full scale (0..1). */
JNIEXPORT jfloat JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getAdcRms(JNIEnv*, jobject) {
    return g_dsp.adcRms;
}

/** Percentage of samples pinned at the ends of the ADC range. */
JNIEXPORT jfloat JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getAdcClipPct(JNIEnv*, jobject) {
    return g_dsp.adcClipPct;
}

/** Ultrasonic noise level — the reception-quality metric (lower is better). */
JNIEXPORT jfloat JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getNoiseLevel(JNIEnv*, jobject) {
    return g_dsp.noiseLevel;
}

/** Current stereo separation actually in use, 0 (mono) to 1 (full). */
JNIEXPORT jfloat JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getStereoBlend(JNIEnv*, jobject) {
    return g_dsp.stereoBlend;
}

/** Current audio high-cut corner in Hz. */
JNIEXPORT jfloat JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getHiCutHz(JNIEnv*, jobject) {
    return g_dsp.hiCutHz;
}

/** Total samples suppressed by the impulse blanker since reset. */
JNIEXPORT jlong JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getBlankedCount(JNIEnv*, jobject) {
    return (jlong)g_dsp.nbBlanked;
}

JNIEXPORT jfloat JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getSoftClipPct(JNIEnv*, jobject) {
    if (g_dsp.softClipTotal <= 0) return 0.0f;
    return (jfloat)(100.0 * (double)g_dsp.softClipHits / (double)g_dsp.softClipTotal);
}

JNIEXPORT void JNICALL
Java_com_fmradio_dsp_NativeFmDsp_setTestFlags(JNIEnv*, jobject, jint flags) {
    g_dsp.testFlags = flags;
    // Clear notch state when disabling to avoid thump on re-enable
    if (!(flags & DspState::TEST_NOTCH)) {
        g_dsp.notchLX1 = g_dsp.notchLX2 = g_dsp.notchLY1 = g_dsp.notchLY2 = 0;
        g_dsp.notchRX1 = g_dsp.notchRX2 = g_dsp.notchRY1 = g_dsp.notchRY2 = 0;
    }
}

JNIEXPORT jint JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getTestFlags(JNIEnv*, jobject) {
    return g_dsp.testFlags;
}

/**
 * Main demodulation function.
 * Input: byte[] IQ data (unsigned 8-bit, interleaved I,Q,I,Q,...)
 * Output: short[] PCM audio (interleaved L,R,L,R,...)
 * Also fills wideband buffer for RDS.
 * Returns number of audio samples written.
 */
JNIEXPORT jint JNICALL
Java_com_fmradio_dsp_NativeFmDsp_demodulate(
    JNIEnv* env, jobject,
    jbyteArray iqArray, jshortArray audioArray, jfloatArray wbArray)
{
    DspState& d = g_dsp;
    if (!d.initialized) { d.init(); initLut(); }

    // Apply current runtime test flags (cheap, just picks pre-computed values)
    d.applyTestFlags();
    // TEST_DC: more aggressive IQ DC blocker (0.99995 vs 0.999995)
    const float dcA = (d.testFlags & DspState::TEST_DC) ? 0.99995f : 0.999995f;
    jsize iqLen = env->GetArrayLength(iqArray);
    jsize audioLen = env->GetArrayLength(audioArray);
    int numIqSamples = iqLen / 2;

    // Pin arrays for direct access
    jbyte* iq = env->GetByteArrayElements(iqArray, nullptr);
    jshort* audio = env->GetShortArrayElements(audioArray, nullptr);

    jfloat* wb = nullptr;
    jsize wbLen = 0;
    if (wbArray) {
        wb = env->GetFloatArrayElements(wbArray, nullptr);
        wbLen = env->GetArrayLength(wbArray);
    }
    d.wbCount = 0;

    int audioCount = 0;

    for (int i = 0; i < numIqSamples; i++) {
        uint8_t rawI = (uint8_t)iq[i*2];
        uint8_t rawQ = (uint8_t)iq[i*2+1];
        float iSample = byteLut[rawI];
        float qSample = byteLut[rawQ];

        // ===== Impulse blanker =====
        // Runs HERE, on the raw IQ, and not further down the chain: the IF
        // filter is 64 taps at 960 kHz, i.e. 67 us long, so by the time an
        // ignition spark reaches the discriminator it has been smeared into
        // something that is no longer an impulse and can no longer be gated
        // out. Before the filter it is still a few microseconds wide.
        //
        // An FM carrier has a constant envelope, so any sudden jump in |IQ| is
        // interference by definition. Hold the previous sample through it. The
        // run limit means a real signal can never be gated away for long: after
        // NB_MAX_RUN samples the input is trusted again regardless.
        float mag2Raw = iSample * iSample + qSample * qSample;
        {
            const float mag2 = mag2Raw;
            if ((d.testFlags & DspState::TEST_NB_ON) != 0 &&
                mag2 > d.nbAvg * DspState::NB_THRESHOLD && d.nbRun < DspState::NB_MAX_RUN) {
                // Zero, not hold. Holding freezes the phase, which the
                // discriminator reads as an abrupt jump to zero frequency —
                // a click in its own right. Zeroed samples are instead filled
                // in by the 64-tap IF filter from their neighbours.
                iSample = 0.0f;
                qSample = 0.0f;
                d.nbRun++;
                d.nbBlanked++;
            } else {
                d.nbRun = 0;
                d.nbLastI = iSample;
                d.nbLastQ = qSample;
                d.nbAvg += DspState::NB_AVG_A * (mag2 - d.nbAvg);
                if (d.nbAvg < 1e-6f) d.nbAvg = 1e-6f;
            }
        }

        // ADC loading for the gain loop. Reuses mag2 from the blanker above
        // instead of squaring the same two samples a second time, and samples
        // every 4th point rather than every one.
        //
        // This runs at 960 kHz, where every operation costs. A field log showed
        // the DSP falling 15 buffers (~255 ms) behind the USB stream where it
        // had been keeping up exactly, with the bus itself at 100% and no
        // dropped packets — the work added here is what pushed it over on this
        // head unit, and a DSP that cannot keep up destroys RDS block sync.
        // A quarter of the samples is a statistically identical measurement of
        // a level that is averaged over 200 ms anyway.
        if ((d.adcSubsample++ & 3) == 0) {
            d.adcAcc += (double)mag2Raw;
            d.adcCount++;
            if (rawI <= 1 || rawI >= 254 || rawQ <= 1 || rawQ >= 254) d.adcClipCount++;
        }

        // DC removal (IIR high-pass) — alpha controlled by TEST_DC
        d.dcI = dcA * d.dcI + (1.0f - dcA) * iSample;
        d.dcQ = dcA * d.dcQ + (1.0f - dcA) * qSample;
        iSample -= d.dcI;
        qSample -= d.dcQ;

        // IF filter double-buffer write (no modulo in convolution)
        d.ifBufI[d.ifBufIdx] = iSample;
        d.ifBufI[d.ifBufIdx + DspState::IF_LPF_ORDER] = iSample;
        d.ifBufQ[d.ifBufIdx] = qSample;
        d.ifBufQ[d.ifBufIdx + DspState::IF_LPF_ORDER] = qSample;
        d.ifBufIdx = (d.ifBufIdx + 1) % DspState::IF_LPF_ORDER;

        // Stage 1 decimation
        if (++d.stage1Counter < STAGE1_DEC) continue;
        d.stage1Counter = 0;

        // Apply IF LPF — contiguous reads via double-buffer
        float filtI = 0, filtQ = 0;
        const float* bI = &d.ifBufI[d.ifBufIdx];
        const float* bQ = &d.ifBufQ[d.ifBufIdx];
        const float* coeffs = d.ifCoeffs;
        for (int j = 0; j < DspState::IF_LPF_ORDER; j++) {
            int p = DspState::IF_LPF_ORDER - 1 - j;
            filtI += bI[p] * coeffs[j];
            filtQ += bQ[p] * coeffs[j];
        }

        // FM discriminator: conjugate multiply + atan2
        float realProd = filtI * d.prevI + filtQ * d.prevQ;
        float imagProd = filtQ * d.prevI - filtI * d.prevQ;
        d.prevI = filtI;
        d.prevQ = filtQ;
        float rawBB = fastAtan2(imagProd, realProd);

        // ===== Ultrasonic noise measurement (70 kHz highpass) =====
        {
            double x = (double)rawBB;
            double y = d.nzHpB0 * x + d.nzHpB1 * d.nzX1 + d.nzHpB2 * d.nzX2
                     - d.nzHpA1 * d.nzY1 - d.nzHpA2 * d.nzY2;
            d.nzX2 = d.nzX1; d.nzX1 = x;
            d.nzY2 = d.nzY1; d.nzY1 = y;
            d.nzAcc += y * y;
            if (++d.nzCount >= d.nzWindow) {
                float rms = (float)sqrt(d.nzAcc / d.nzCount);
                d.nzAcc = 0; d.nzCount = 0;
                d.noiseEma += DspState::NOISE_EMA_A * (rms - d.noiseEma);
                d.noiseLevel = d.noiseEma;

                // Separation the signal can support
                // How much the carrier alone vouches for the signal: 1 when it
                // is strong enough that the audio cannot be noisy, 0 when it is
                // weak enough to tell us nothing.
                float strong = (d.signalDb - DspState::SIG_DEGRADE_DB) /
                               (DspState::SIG_CLEAN_DB - DspState::SIG_DEGRADE_DB);
                if (strong < 0) strong = 0; else if (strong > 1) strong = 1;

                float t = (d.noiseLevel - DspState::NOISE_STEREO_FULL) /
                          (DspState::NOISE_STEREO_NONE - DspState::NOISE_STEREO_FULL);
                if (t < 0) t = 0; else if (t > 1) t = 1;
                // Both have to agree the signal is poor before separation is
                // given up: whichever is more generous wins.
                // A floor, not a veto: a strong carrier guarantees at least
                // half separation, but no longer forces full stereo over a
                // measurement that says the signal cannot carry it. Full stereo
                // is about 20 dB noisier than mono, so overriding all the way to
                // 1.0 on a station reading 0.068 put that noise straight into
                // the audio — which is what came back from the field as "still
                // hissing".
                d.snrBlend = fmaxf(1.0f - t, strong * 0.5f);
                if (d.testFlags & DspState::TEST_FORCE_MONO) d.snrBlend = 0.0f;

                // Audio bandwidth the signal can support
                float h = (d.noiseLevel - DspState::NOISE_HICUT_START) /
                          (DspState::NOISE_HICUT_FULL - DspState::NOISE_HICUT_START);
                if (h < 0) h = 0; else if (h > 1) h = 1;
                float hiCutFromNoise = DspState::HICUT_MAX_HZ +
                            h * (DspState::HICUT_MIN_HZ - DspState::HICUT_MAX_HZ);
                // Same idea: a strong carrier is worth at least 10 kHz of
                // audio, not automatically the full 15.
                float hiCutFromSignal = DspState::HICUT_MIN_HZ +
                            strong * (10000.0f - DspState::HICUT_MIN_HZ);
                d.hiCutHz = fmaxf(hiCutFromNoise, hiCutFromSignal);
                float a = 1.0f - expf(-2.0f * PI_F * d.hiCutHz / (float)AUDIO_RATE);
                if (a > 1.0f) a = 1.0f;
                d.hiCutAlpha = a;
            }
        }

        // Warmup: skip output but still run PLL so it locks
        if (d.warmupSamples < d.warmupThreshold) {
            d.warmupSamples++;
            double ps = d.pilotBpf((double)rawBB);
            double pe = ps * cos(d.pilotNcoPhase);
            double newFreq = d.pilotNcoFreq + d.pilotBeta * pe;
            if (newFreq < d.pilotFreqMin) newFreq = d.pilotFreqMin;
            else if (newFreq > d.pilotFreqMax) newFreq = d.pilotFreqMax;
            d.pilotNcoFreq = newFreq;
            d.pilotNcoPhase += d.pilotNcoFreq + d.pilotAlpha * pe;
            if (d.pilotNcoPhase > 2*PI_D) d.pilotNcoPhase -= 2*PI_D;
            if (d.pilotNcoPhase < 0) d.pilotNcoPhase += 2*PI_D;
            continue;
        }

        // Pilot PLL. The pre-update NCO phase corresponds to THIS sample;
        // the post-update phase is one step (~71° at 38 kHz) ahead — using it
        // rotated the stereo constellation nearly onto the orthogonal axis,
        // swapping left/right channels (verified by simulation).
        double pilotPhaseThisSample = d.pilotNcoPhase;
        double pilotSig = d.pilotBpf((double)rawBB);
        double pilotErr = pilotSig * cos(d.pilotNcoPhase);
        double newFreq = d.pilotNcoFreq + d.pilotBeta * pilotErr;
        if (newFreq < d.pilotFreqMin) newFreq = d.pilotFreqMin;
        else if (newFreq > d.pilotFreqMax) newFreq = d.pilotFreqMax;
        d.pilotNcoFreq = newFreq;
        d.pilotNcoPhase += d.pilotNcoFreq + d.pilotAlpha * pilotErr;
        if (d.pilotNcoPhase > 2*PI_D) d.pilotNcoPhase -= 2*PI_D;
        if (d.pilotNcoPhase < 0) d.pilotNcoPhase += 2*PI_D;

        // Pilot strength with hysteresis
        d.pilotStrengthAcc += (float)(pilotSig * pilotSig);
        if (++d.pilotStrengthCount >= d.pilotDetectWindow) {
            d.pilotStrength = d.pilotStrengthAcc / d.pilotStrengthCount;
            if (d.isStereo) {
                d.isStereo = d.pilotStrength > DspState::STEREO_UNLOCK;
            } else {
                d.isStereo = d.pilotStrength > DspState::STEREO_LOCK;
            }
            d.pilotStrengthAcc = 0;
            d.pilotStrengthCount = 0;
        }
        // Loop gear follows pilot presence: wide while acquiring or after a
        // fade, narrow once the pilot has been held (see updatePllGear).
        d.updatePllGear(d.isStereo);

        // Smooth stereo blend. The target is now the SMALLER of "is there a
        // pilot at all" and "how much separation can this signal support" —
        // a pilot arriving through a noisy path used to buy full stereo and
        // all of the L-R path's noise with it.
        float blendTarget = d.isStereo ? d.snrBlend : 0.0f;
        if (d.stereoBlend < blendTarget) {
            d.stereoBlend = fminf(d.stereoBlend + DspState::STEREO_BLEND_ATTACK, blendTarget);
        } else if (d.stereoBlend > blendTarget) {
            d.stereoBlend = fmaxf(d.stereoBlend - DspState::STEREO_BLEND_RELEASE, blendTarget);
        }

        // Signal strength
        d.sigPowerAcc += (double)(filtI*filtI + filtQ*filtQ);
        if (++d.sigPowerCount >= d.sigPowerWindow) {
            double avg = d.sigPowerAcc / d.sigPowerCount;
            d.signalDb = (float)(10.0 * log10(avg + 1e-10));
            d.sigPowerAcc = 0;
            d.sigPowerCount = 0;
        }

        // Squelch based on signal power (not modulation level).
        // Only mutes on truly empty frequencies (very low signal),
        // not on weak/multipath stations.
        d.sqQualityAcc += (double)(filtI*filtI + filtQ*filtQ);
        if (++d.sqQualityCount >= d.sigPowerWindow) {
            double sqAvg = d.sqQualityAcc / d.sqQualityCount;
            float sqDb = (float)(10.0 * log10(sqAvg + 1e-10));
            if (d.squelchOpen) {
                if (sqDb < -45.0f) d.squelchOpen = false;
            } else {
                if (sqDb > -40.0f) d.squelchOpen = true;
            }
            d.sqQualityAcc = 0;
            d.sqQualityCount = 0;
        }
        if (d.squelchOpen) {
            if (d.squelchLevel < 1.0f) d.squelchLevel += d.squelchAttack;
            if (d.squelchLevel > 1.0f) d.squelchLevel = 1.0f;
        } else {
            if (d.squelchLevel > 0.0f) d.squelchLevel -= d.squelchRelease;
            if (d.squelchLevel < 0.0f) d.squelchLevel = 0.0f;
        }

        // Wideband for RDS — capture pilot phase at first sample
        if (wb && d.wbCount < (int)wbLen) {
            if (d.wbCount == 0) d.wbStartPilotPhase = pilotPhaseThisSample;
            wb[d.wbCount++] = rawBB;
        }

        // Stereo decode. sin(2φ) with the PRE-update phase: the detector
        // locks sin(φ) to the pilot and the standard's 38 kHz subcarrier is
        // sin(2φ). The old cos(2φ_post-update) recovered L-R at -0.947 —
        // stereo played with the channels swapped (plus 5% loss); the fixed
        // carrier recovers +1.000 exactly.
        float baseband = rawBB * d.fmGain;
        float stereoCarrier = (float)sin(2.0 * pilotPhaseThisSample);
        float diff = baseband * stereoCarrier * 2.0f;

        // Take the pilot out of the audio. It is sampled for the PLL from the
        // composite well before this point, so removing it here costs the
        // stereo decoder nothing.
        float monoIn;
        {
            const float x = baseband;
            const double y = d.pnB0 * x + d.pnB1 * d.pnX1 + d.pnB2 * d.pnX2
                             - d.pnA1 * d.pnY1 - d.pnA2 * d.pnY2;
            d.pnX2 = d.pnX1; d.pnX1 = x;
            d.pnY2 = d.pnY1; d.pnY1 = (float)y;
            monoIn = (float)y;
        }

        // Audio LPF double-buffer write
        d.monoLpfBuf[d.monoLpfIdx] = monoIn;
        d.monoLpfBuf[d.monoLpfIdx + DspState::AUDIO_LPF_ORDER] = monoIn;
        d.diffLpfBuf[d.diffLpfIdx] = diff;
        d.diffLpfBuf[d.diffLpfIdx + DspState::AUDIO_LPF_ORDER] = diff;
        d.monoLpfIdx = (d.monoLpfIdx + 1) % DspState::AUDIO_LPF_ORDER;
        d.diffLpfIdx = (d.diffLpfIdx + 1) % DspState::AUDIO_LPF_ORDER;

        // Stage 2 decimation
        if (++d.stage2Counter < STAGE2_DEC) continue;
        d.stage2Counter = 0;

        // Apply audio LPFs — contiguous reads
        float filtMono = 0, filtDiff = 0;
        const float* mB = &d.monoLpfBuf[d.monoLpfIdx];
        const float* dB = &d.diffLpfBuf[d.diffLpfIdx];
        const float* aC = d.audioCoeffs;
        for (int j = 0; j < DspState::AUDIO_LPF_ORDER; j++) {
            int p = DspState::AUDIO_LPF_ORDER - 1 - j;
            filtMono += mB[p] * aC[j];
            filtDiff += dB[p] * aC[j];
        }

        // Stereo matrix — blend factor ramps smoothly based on pilot detection.
        // Diff gain 0.7 for good stereo separation while avoiding excessive
        // noise on marginal signals.
        float diffGain = d.stereoBlend * 0.7f;
        float left  = filtMono + filtDiff * diffGain;
        float right = filtMono - filtDiff * diffGain;

        // 19 kHz notch DISABLED — even Q=6 caused ringing at 79% Nyquist.
        // The audio LPF at 15 kHz should be sufficient. If pilot leaks, it's
        // above most speakers' reproduction range anyway.
        // {
        //     left  = (float)d.notchL((double)left);
        //     right = (float)d.notchR((double)right);
        // }

        // De-emphasis (50µs) — separate state for L/R
        d.deEmphStateL += d.deEmphAlpha * (left - d.deEmphStateL);
        d.deEmphStateR += d.deEmphAlpha * (right - d.deEmphStateR);

        // Progressive high-cut. At a clean signal hiCutAlpha is ~1 and this is
        // a no-op; as noise rises the corner walks down to 3.2 kHz, trading
        // the top of the band — where FM noise is worst — for quiet.
        d.hiCutStateL += d.hiCutAlpha * (d.deEmphStateL - d.hiCutStateL);
        d.hiCutStateR += d.hiCutAlpha * (d.deEmphStateR - d.hiCutStateR);

        // Mute ramp on startup
        if (d.muteRamp < 1.0f) {
            d.muteRamp += d.muteRampUp;
            if (d.muteRamp > 1.0f) d.muteRamp = 1.0f;
        }

        // 28000 of 32767 put the audio at 0.855 of full scale before the soft
        // clipper, whose knee is at 0.8. On loud stereo material — which is
        // most of it, broadcasters process hard — mono plus difference reaches
        // that knee and the limiter works on the PROGRAMME rather than on rare
        // peaks. It is heard as grit that appears with the music and vanishes
        // in the pauses, which is exactly what came back from the car: "in
        // silence there is no noise, only when there is sound".
        //
        // 22000 puts a fully modulated signal at about half the knee. It costs
        // 2.1 dB of loudness, which the volume control covers, and it is the
        // difference between a limiter that catches transients and one that is
        // always on.
        float gain = d.muteRamp * d.squelchLevel * 22000.0f;
        const float preL = d.hiCutStateL * gain / 32767.0f;
        const float preR = d.hiCutStateR * gain / 32767.0f;
        // Count it rather than assume it: if the knee is never reached, the
        // distortion is somewhere else and this was the wrong tree.
        if (fabsf(preL) > 0.8f || fabsf(preR) > 0.8f) d.softClipHits++;
        d.softClipTotal++;
        float clippedL = softClip(preL) * 32767.0f;
        float clippedR = softClip(preR) * 32767.0f;
        int sL = (int)clippedL;
        int sR = (int)clippedR;
        if (sL > 32767) sL = 32767; else if (sL < -32767) sL = -32767;
        if (sR > 32767) sR = 32767; else if (sR < -32767) sR = -32767;

        if (audioCount + 1 < audioLen) {
            audio[audioCount++] = (jshort)sL;
            audio[audioCount++] = (jshort)sR;
        }
    }

    // Periodic diagnostic logging (~every 5 seconds of audio)
    d.logCounter += audioCount / 2;  // audioCount is L+R interleaved, so /2 for frames
    if (d.logCounter >= DspState::LOG_INTERVAL) {
        d.logCounter -= DspState::LOG_INTERVAL;
        LOGI("DSP: audio=%d sig=%.1f stereo=%d blend=%.2f noise=%.4f hicut=%.0f nb=%ld adc=%.3f/%.2f%% pilot=%.4f wb=%d flags=0x%x",
             audioCount, d.signalDb, (int)d.isStereo, d.stereoBlend,
             d.noiseLevel, d.hiCutHz, d.nbBlanked, d.adcRms, d.adcClipPct,
             d.pilotNcoFreq, d.wbCount, d.testFlags);
    }

    // Publish ADC loading for this block, then start a fresh measurement.
    if (d.adcCount > 0) {
        d.adcRms = (float)sqrt(d.adcAcc / (double)d.adcCount / 2.0);
        d.adcClipPct = (float)(100.0 * (double)d.adcClipCount / (double)d.adcCount);
        d.adcAcc = 0; d.adcCount = 0; d.adcClipCount = 0;
    }

    // Release arrays
    env->ReleaseByteArrayElements(iqArray, iq, JNI_ABORT);
    env->ReleaseShortArrayElements(audioArray, audio, 0); // copy back
    if (wb) env->ReleaseFloatArrayElements(wbArray, wb, 0);

    return audioCount;
}

} // extern "C"
