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

#define TAG "NativeDSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static constexpr int SAMPLE_RATE = 1152000;
static constexpr int STAGE1_DEC = 6;
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
        byteLut[i] = i / 127.5f - 1.0f;
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
    static constexpr float dcAlpha = 0.999995f;

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
    static constexpr int AUDIO_LPF_ORDER = 32;
    float audioCoeffs[AUDIO_LPF_ORDER];
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
    static constexpr float STEREO_LOCK = 0.001f;
    static constexpr float STEREO_UNLOCK = 0.0003f;
    // Smooth blend
    float stereoBlend = 0.0f;
    static constexpr float STEREO_BLEND_ATTACK = 0.002f;
    static constexpr float STEREO_BLEND_RELEASE = 0.0005f;

    // De-emphasis
    float deEmphAlpha;
    float deEmphStateL = 0, deEmphStateR = 0;

    // Signal strength
    double sigPowerAcc = 0;
    int sigPowerCount = 0;
    float signalDb = -100.0f;
    int sigPowerWindow;

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

    void init() {
        if (initialized) return;
        initialized = true;

        fmGain = (float)INTERMEDIATE_RATE / (2.0f * PI_F * 75000.0f) * 0.82f;

        // IF filter: 120 kHz cutoff (full FM broadcast bandwidth)
        designLpf(ifCoeffs, IF_LPF_ORDER, 120000.0f / SAMPLE_RATE);
        // Audio filter: 15 kHz cutoff
        designLpf(audioCoeffs, AUDIO_LPF_ORDER, 15000.0f / INTERMEDIATE_RATE);

        // Pilot BPF (Q=80, 19 kHz)
        double w0 = 2.0 * PI_D * 19000.0 / INTERMEDIATE_RATE;
        double bpfAlpha = sin(w0) / (2.0 * 80.0);
        double a0 = 1.0 + bpfAlpha;
        pilotBpfB0 = bpfAlpha / a0;
        pilotBpfB2 = -bpfAlpha / a0;
        pilotBpfA1 = (-2.0 * cos(w0)) / a0;
        pilotBpfA2 = (1.0 - bpfAlpha) / a0;

        // PLL: critically damped, 1 Hz loop bandwidth
        pilotNcoFreq = 2.0 * PI_D * 19000.0 / INTERMEDIATE_RATE;
        double loopBw = 2.0 * PI_D * 1.0 / INTERMEDIATE_RATE;
        double damp = 0.707;
        pilotAlpha = 2.0 * damp * loopBw;
        pilotBeta = loopBw * loopBw;
        pilotFreqMin = 2.0 * PI_D * 18500.0 / INTERMEDIATE_RATE;
        pilotFreqMax = 2.0 * PI_D * 19500.0 / INTERMEDIATE_RATE;

        pilotDetectWindow = INTERMEDIATE_RATE / 4;

        // De-emphasis 50µs (Europe/Russia)
        float tau = 50e-6f;
        float dt = 1.0f / AUDIO_RATE;
        deEmphAlpha = dt / (tau + dt);

        sigPowerWindow = INTERMEDIATE_RATE / 3;
        warmupThreshold = INTERMEDIATE_RATE / 10;

        muteRampUp = 1.0f / (0.05f * AUDIO_RATE);

        LOGI("Native DSP initialized: rate=%d intermediate=%d audio=%d if_taps=%d audio_taps=%d",
             SAMPLE_RATE, INTERMEDIATE_RATE, AUDIO_RATE, IF_LPF_ORDER, AUDIO_LPF_ORDER);
    }

    void reset() {
        dcI = dcQ = 0;
        prevI = prevQ = 0;
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
        deEmphStateL = deEmphStateR = 0;
        sigPowerAcc = 0;
        sigPowerCount = 0;
        signalDb = -100.0f;
        warmupSamples = 0;
        muteRamp = 0.0f;
        wbCount = 0;
        wbStartPilotPhase = 0.0;
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
    // Returns the pilot phase aligned with wbBuf[0] for the most recent
    // demodulate() call — what RdsDecoder needs to reconstruct the carrier.
    return g_dsp.wbStartPilotPhase;
}

JNIEXPORT jint JNICALL
Java_com_fmradio_dsp_NativeFmDsp_getWbCount(JNIEnv*, jobject) {
    return g_dsp.wbCount;
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
        float iSample = byteLut[(uint8_t)iq[i*2]];
        float qSample = byteLut[(uint8_t)iq[i*2+1]];

        // DC removal (IIR high-pass)
        d.dcI = d.dcAlpha * d.dcI + (1.0f - d.dcAlpha) * iSample;
        d.dcQ = d.dcAlpha * d.dcQ + (1.0f - d.dcAlpha) * qSample;
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

        // Pilot PLL
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

        // Smooth stereo blend
        if (d.isStereo && d.stereoBlend < 1.0f) {
            d.stereoBlend = fminf(d.stereoBlend + DspState::STEREO_BLEND_ATTACK, 1.0f);
        } else if (!d.isStereo && d.stereoBlend > 0.0f) {
            d.stereoBlend = fmaxf(d.stereoBlend - DspState::STEREO_BLEND_RELEASE, 0.0f);
        }

        // Signal strength
        d.sigPowerAcc += (double)(filtI*filtI + filtQ*filtQ);
        if (++d.sigPowerCount >= d.sigPowerWindow) {
            double avg = d.sigPowerAcc / d.sigPowerCount;
            d.signalDb = (float)(10.0 * log10(avg + 1e-10));
            d.sigPowerAcc = 0;
            d.sigPowerCount = 0;
        }

        // Wideband for RDS — capture pilot phase at first sample
        if (wb && d.wbCount < (int)wbLen) {
            if (d.wbCount == 0) d.wbStartPilotPhase = d.pilotNcoPhase;
            wb[d.wbCount++] = rawBB;
        }

        // Stereo decode
        float baseband = rawBB * d.fmGain;
        float stereoCarrier = (float)cos(2.0 * d.pilotNcoPhase);
        float diff = baseband * stereoCarrier * 2.0f;

        // Audio LPF double-buffer write
        d.monoLpfBuf[d.monoLpfIdx] = baseband;
        d.monoLpfBuf[d.monoLpfIdx + DspState::AUDIO_LPF_ORDER] = baseband;
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

        // Stereo matrix with smooth blend
        float left  = filtMono + filtDiff * d.stereoBlend * 0.5f;
        float right = filtMono - filtDiff * d.stereoBlend * 0.5f;

        // De-emphasis (50µs) — separate state for L/R
        d.deEmphStateL += d.deEmphAlpha * (left - d.deEmphStateL);
        d.deEmphStateR += d.deEmphAlpha * (right - d.deEmphStateR);

        // Mute ramp on startup
        if (d.muteRamp < 1.0f) {
            d.muteRamp += d.muteRampUp;
            if (d.muteRamp > 1.0f) d.muteRamp = 1.0f;
        }

        // Soft-clip + PCM scale
        float gain = d.muteRamp * 28000.0f;
        float clippedL = softClip(d.deEmphStateL * gain / 32767.0f) * 32767.0f;
        float clippedR = softClip(d.deEmphStateR * gain / 32767.0f) * 32767.0f;
        int sL = (int)clippedL;
        int sR = (int)clippedR;
        if (sL > 32767) sL = 32767; else if (sL < -32767) sL = -32767;
        if (sR > 32767) sR = 32767; else if (sR < -32767) sR = -32767;

        if (audioCount + 1 < audioLen) {
            audio[audioCount++] = (jshort)sL;
            audio[audioCount++] = (jshort)sR;
        }
    }

    // Release arrays
    env->ReleaseByteArrayElements(iqArray, iq, JNI_ABORT);
    env->ReleaseShortArrayElements(audioArray, audio, 0); // copy back
    if (wb) env->ReleaseFloatArrayElements(wbArray, wb, 0);

    return audioCount;
}

} // extern "C"
