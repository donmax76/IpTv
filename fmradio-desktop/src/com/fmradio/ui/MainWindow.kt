package com.fmradio.ui

import com.fmradio.dsp.AudioEqualizer
import com.fmradio.dsp.FmDemodulator
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlTcpClient
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * FM Radio RTL-SDR — Desktop (Windows/Linux/Mac)
 * Connects to rtl_tcp server for RTL-SDR access.
 */
class MainWindow : JFrame("FM Radio RTL-SDR") {

    // RTL-TCP connection
    private val rtlClient = RtlTcpClient()
    private var streamingThread: Thread? = null

    // DSP
    private var demodulator: FmDemodulator? = null
    private var rdsDecoder: RdsDecoder? = null
    private var equalizer: AudioEqualizer? = null
    private var audioPlayer: DesktopAudioPlayer? = null

    // State
    @Volatile private var isPlaying = false
    private var currentFrequency = 100_000_000L  // 100.0 MHz
    private val stepHz = 100_000L  // 100 kHz step

    // UI components
    private val freqLabel = JLabel("100.0", SwingConstants.CENTER)
    private val mhzLabel = JLabel("MHz", SwingConstants.LEFT)
    private val statusLabel = JLabel("Disconnected")
    private val rdsLabel = JLabel("RDS: ---")
    private val rtLabel = JLabel(" ")
    private val stereoLabel = JLabel("MONO")
    private val ptyLabel = JLabel("")
    private val hostField = JTextField("127.0.0.1", 12)
    private val portField = JTextField("1234", 5)
    private val connectBtn = JButton("Connect")
    private val playBtn = JButton("PLAY")
    private val volumeSlider = JSlider(0, 100, 80)
    private val bassSlider = JSlider(0, 20, 10)
    private val trebleSlider = JSlider(0, 20, 10)

    // Presets
    private val presetFreqs = longArrayOf(
        87_500_000, 91_000_000, 95_000_000,
        100_000_000, 104_000_000, 107_000_000
    )
    private val presetButtons = Array(6) { JButton("P${it + 1}") }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        preferredSize = Dimension(600, 480)
        buildUI()
        pack()
        setLocationRelativeTo(null)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                shutdown()
            }
        })
    }

    private fun buildUI() {
        val root = JPanel(BorderLayout(8, 8)).apply {
            border = EmptyBorder(12, 12, 12, 12)
            background = Color(30, 30, 36)
        }

        // Top: connection panel
        val connPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            background = Color(40, 40, 48)
            add(JLabel("Host:").apply { foreground = Color.LIGHT_GRAY })
            add(hostField)
            add(JLabel("Port:").apply { foreground = Color.LIGHT_GRAY })
            add(portField)
            add(connectBtn)
            add(statusLabel.apply { foreground = Color(100, 200, 100) })
        }

        // Center: frequency display + controls
        val centerPanel = JPanel(BorderLayout(8, 8)).apply { isOpaque = false }

        // Frequency display
        val freqPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            background = Color(20, 25, 30)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0, 180, 255), 1),
                EmptyBorder(10, 20, 10, 20)
            )
        }
        freqLabel.font = Font("Monospaced", Font.BOLD, 56)
        freqLabel.foreground = Color(0, 255, 128)
        mhzLabel.font = Font("Monospaced", Font.PLAIN, 20)
        mhzLabel.foreground = Color(0, 200, 100)
        stereoLabel.font = Font("Monospaced", Font.BOLD, 14)
        stereoLabel.foreground = Color(200, 200, 0)

        freqPanel.add(freqLabel)
        freqPanel.add(mhzLabel)
        freqPanel.add(Box.createHorizontalStrut(20))
        freqPanel.add(stereoLabel)

        // Frequency +/- buttons
        val freqCtrlPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply { isOpaque = false }
        val seekBackBtn = JButton("<<").apply { toolTipText = "Seek Back" }
        val freqDownBtn = JButton("< -0.1").apply { toolTipText = "Freq Down" }
        val freqUpBtn = JButton("+0.1 >").apply { toolTipText = "Freq Up" }
        val seekFwdBtn = JButton(">>").apply { toolTipText = "Seek Forward" }

        freqDownBtn.addActionListener { tuneFrequency(currentFrequency - stepHz) }
        freqUpBtn.addActionListener { tuneFrequency(currentFrequency + stepHz) }
        seekBackBtn.addActionListener { seekStation(false) }
        seekFwdBtn.addActionListener { seekStation(true) }

        freqCtrlPanel.add(seekBackBtn)
        freqCtrlPanel.add(freqDownBtn)
        freqCtrlPanel.add(playBtn.apply { font = Font("SansSerif", Font.BOLD, 16) })
        freqCtrlPanel.add(freqUpBtn)
        freqCtrlPanel.add(seekFwdBtn)

        // RDS display
        val rdsPanel = JPanel(GridLayout(2, 1)).apply {
            background = Color(20, 25, 30)
            border = EmptyBorder(4, 8, 4, 8)
        }
        rdsLabel.font = Font("Monospaced", Font.BOLD, 16)
        rdsLabel.foreground = Color(255, 200, 0)
        rtLabel.font = Font("Monospaced", Font.PLAIN, 12)
        rtLabel.foreground = Color(180, 180, 180)
        ptyLabel.font = Font("Monospaced", Font.PLAIN, 11)
        ptyLabel.foreground = Color(120, 180, 255)
        rdsPanel.add(rdsLabel)
        val rtPtyPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            isOpaque = false; add(rtLabel); add(Box.createHorizontalStrut(10)); add(ptyLabel)
        }
        rdsPanel.add(rtPtyPanel)

        centerPanel.add(freqPanel, BorderLayout.NORTH)
        centerPanel.add(freqCtrlPanel, BorderLayout.CENTER)
        centerPanel.add(rdsPanel, BorderLayout.SOUTH)

        // Bottom: presets + EQ
        val bottomPanel = JPanel(GridLayout(3, 1, 4, 4)).apply { isOpaque = false }

        // Presets
        val presetPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply { isOpaque = false }
        presetPanel.add(JLabel("PRESETS:").apply { foreground = Color.LIGHT_GRAY })
        for (i in 0 until 6) {
            presetButtons[i].apply {
                toolTipText = "Click: tune | Right-click: save"
                addActionListener { tuneFrequency(presetFreqs[i]) }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.button == MouseEvent.BUTTON3) {
                            presetFreqs[i] = currentFrequency
                            updatePresetLabels()
                            statusLabel.text = "Preset ${i + 1} saved: ${formatFreq(currentFrequency)}"
                        }
                    }
                })
            }
            presetPanel.add(presetButtons[i])
        }
        updatePresetLabels()

        // Volume
        val volPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply { isOpaque = false }
        volPanel.add(JLabel("VOL:").apply { foreground = Color.LIGHT_GRAY })
        volumeSlider.addChangeListener {
            audioPlayer?.setVolume(volumeSlider.value / 100f)
        }
        volPanel.add(volumeSlider)

        // EQ
        val eqPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply { isOpaque = false }
        eqPanel.add(JLabel("BASS:").apply { foreground = Color.LIGHT_GRAY })
        bassSlider.addChangeListener {
            equalizer?.bassGainDb = (bassSlider.value - 10).toFloat()
        }
        eqPanel.add(bassSlider)
        eqPanel.add(JLabel("TREBLE:").apply { foreground = Color.LIGHT_GRAY })
        trebleSlider.addChangeListener {
            equalizer?.trebleGainDb = (trebleSlider.value - 10).toFloat()
        }
        eqPanel.add(trebleSlider)

        bottomPanel.add(presetPanel)
        bottomPanel.add(volPanel)
        bottomPanel.add(eqPanel)

        root.add(connPanel, BorderLayout.NORTH)
        root.add(centerPanel, BorderLayout.CENTER)
        root.add(bottomPanel, BorderLayout.SOUTH)

        contentPane = root

        // Actions
        connectBtn.addActionListener { toggleConnect() }
        playBtn.addActionListener { togglePlayback() }

        // Keyboard shortcuts
        rootPane.registerKeyboardAction({ tuneFrequency(currentFrequency - stepHz) },
            KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        rootPane.registerKeyboardAction({ tuneFrequency(currentFrequency + stepHz) },
            KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        rootPane.registerKeyboardAction({ togglePlayback() },
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)

        updateFreqDisplay()
    }

    private fun toggleConnect() {
        if (rtlClient.isConnected) {
            stopPlayback()
            rtlClient.disconnect()
            statusLabel.text = "Disconnected"
            statusLabel.foreground = Color(200, 100, 100)
            connectBtn.text = "Connect"
        } else {
            val host = hostField.text.trim()
            val port = portField.text.trim().toIntOrNull() ?: 1234
            statusLabel.text = "Connecting..."
            Thread({
                val ok = rtlClient.connect(host, port)
                SwingUtilities.invokeLater {
                    if (ok) {
                        statusLabel.text = "Connected (tuner=${rtlClient.tunerType})"
                        statusLabel.foreground = Color(100, 200, 100)
                        connectBtn.text = "Disconnect"
                        // Set initial params
                        rtlClient.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
                        rtlClient.setAutoGain(true)
                        rtlClient.setFrequency(currentFrequency)
                    } else {
                        statusLabel.text = "Connection failed"
                        statusLabel.foreground = Color(255, 100, 100)
                    }
                }
            }, "ConnectThread").start()
        }
    }

    private fun togglePlayback() {
        if (isPlaying) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        if (!rtlClient.isConnected) {
            statusLabel.text = "Connect to rtl_tcp first!"
            return
        }
        if (isPlaying) return

        val sampleRate = FmDemodulator.RECOMMENDED_SAMPLE_RATE
        demodulator = FmDemodulator(inputSampleRate = sampleRate, audioSampleRate = 48000)
        rdsDecoder = RdsDecoder(sampleRate / 6).also { rds ->
            rds.listener = object : RdsDecoder.RdsListener {
                override fun onRdsData(data: RdsDecoder.RdsData) {
                    SwingUtilities.invokeLater { updateRds(data) }
                }
            }
        }
        demodulator?.widebandListener = { wb -> rdsDecoder?.process(wb) }

        equalizer = AudioEqualizer(48000).also {
            it.bassGainDb = (bassSlider.value - 10).toFloat()
            it.trebleGainDb = (trebleSlider.value - 10).toFloat()
        }

        audioPlayer = DesktopAudioPlayer(48000).also {
            it.setVolume(volumeSlider.value / 100f)
            it.start()
        }

        rtlClient.setFrequency(currentFrequency)
        isPlaying = true

        streamingThread = rtlClient.startStreaming(65536) { iqData ->
            var audioSamples = demodulator?.demodulate(iqData)
            if (audioSamples != null && audioSamples.isNotEmpty()) {
                val eq = equalizer
                if (eq != null) audioSamples = eq.process(audioSamples)
                audioPlayer?.writeSamples(audioSamples)
            }
            val stereoNow = demodulator?.isStereo == true
            SwingUtilities.invokeLater {
                stereoLabel.text = if (stereoNow) "STEREO" else "MONO"
                stereoLabel.foreground = if (stereoNow) Color(0, 255, 0) else Color(200, 200, 0)
            }
        }

        playBtn.text = "STOP"
        statusLabel.text = "Playing ${formatFreq(currentFrequency)} MHz"
    }

    private fun stopPlayback() {
        isPlaying = false
        rtlClient.stopStreaming()
        streamingThread = null
        audioPlayer?.stop()
        audioPlayer = null
        demodulator?.widebandListener = null
        demodulator?.reset()
        demodulator = null
        rdsDecoder?.reset()
        rdsDecoder = null
        equalizer?.reset()
        equalizer = null

        playBtn.text = "PLAY"
        rdsLabel.text = "RDS: ---"
        rtLabel.text = " "
        ptyLabel.text = ""
        stereoLabel.text = "MONO"
        if (rtlClient.isConnected) statusLabel.text = "Connected — Stopped"
    }

    private fun tuneFrequency(freqHz: Long) {
        val clamped = freqHz.coerceIn(87_500_000L, 108_000_000L)
        currentFrequency = clamped
        rtlClient.setFrequency(clamped)
        rdsDecoder?.reset()
        rdsLabel.text = "RDS: ---"
        rtLabel.text = " "
        ptyLabel.text = ""
        updateFreqDisplay()
        if (isPlaying) statusLabel.text = "Playing ${formatFreq(clamped)} MHz"
    }

    private fun seekStation(forward: Boolean) {
        if (!rtlClient.isConnected) return
        statusLabel.text = "Seeking..."
        val wasPlaying = isPlaying
        if (wasPlaying) stopPlayback()

        Thread({
            val tempDemod = FmDemodulator()
            rtlClient.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
            rtlClient.setAutoGain(true)
            val step = 100_000L
            var freq = currentFrequency + if (forward) step else -step
            var found: Long? = null

            for (i in 0 until 200) {
                if (freq > 108_000_000L) freq = 87_500_000L
                if (freq < 87_500_000L) freq = 108_000_000L
                rtlClient.setFrequency(freq)
                Thread.sleep(50)
                val samples = rtlClient.readSamples(65536)
                if (samples != null) {
                    val power = tempDemod.measureSignalStrength(samples)
                    if (power > -15f) { found = freq; break }
                }
                freq += if (forward) step else -step
            }

            SwingUtilities.invokeLater {
                if (found != null) {
                    currentFrequency = found
                    updateFreqDisplay()
                    statusLabel.text = "Found: ${formatFreq(found)} MHz"
                } else {
                    statusLabel.text = "No station found"
                }
                if (wasPlaying) startPlayback()
            }
        }, "SeekThread").start()
    }

    private fun updateFreqDisplay() {
        freqLabel.text = formatFreq(currentFrequency)
    }

    private fun formatFreq(hz: Long): String = String.format("%.1f", hz / 1_000_000.0)

    private fun updateRds(data: RdsDecoder.RdsData) {
        if (data.ps.isNotBlank()) rdsLabel.text = "RDS: ${data.ps}"
        if (data.rt.isNotBlank()) rtLabel.text = data.rt
        if (data.ptyName.isNotBlank()) ptyLabel.text = "[${data.ptyName}]"
    }

    private fun updatePresetLabels() {
        for (i in 0 until 6) {
            presetButtons[i].text = formatFreq(presetFreqs[i])
        }
    }

    private fun shutdown() {
        stopPlayback()
        rtlClient.disconnect()
    }
}

fun main() {
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")

    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (_: Exception) {}

    SwingUtilities.invokeLater {
        MainWindow().isVisible = true
    }
}
