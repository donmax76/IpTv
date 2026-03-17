package com.fmradio.ui

import com.fmradio.dsp.AudioEqualizer
import com.fmradio.dsp.FmDemodulator
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlSdrNative
import java.awt.*
import java.awt.event.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder
import javax.swing.plaf.basic.BasicSliderUI

/**
 * FM Radio RTL-SDR — Desktop (Windows/Linux/Mac)
 * Uses librtlsdr directly via JNA — no rtl_tcp server needed.
 * Auto-detects and opens RTL-SDR device on startup.
 *
 * Premium car radio head unit UI.
 */
class MainWindow : JFrame("FM Radio RTL-SDR v$VERSION (build $BUILD)") {

    companion object {
        const val VERSION = "1.2"
        const val BUILD = "20260317-3"

        // Color palette
        val BG_TOP = Color(0x1A, 0x1A, 0x2E)
        val BG_BOTTOM = Color(0x0D, 0x0D, 0x1A)
        val FREQ_GREEN = Color(0x00, 0xFF, 0x80)
        val FREQ_GREEN_DIM = Color(0x00, 0x80, 0x40)
        val FREQ_GLOW = Color(0x00, 0xFF, 0x80, 40)
        val CYAN = Color(0x00, 0xE5, 0xFF)
        val CYAN_DIM = Color(0x00, 0x72, 0x80)
        val AMBER = Color(0xFF, 0xC8, 0x00)
        val RED_SOFT = Color(0xFF, 0x64, 0x64)
        val GREEN_BTN = Color(0x00, 0xC8, 0x64)
        val GREEN_BTN_HOVER = Color(0x00, 0xE0, 0x78)
        val PANEL_BG = Color(0x12, 0x12, 0x22)
        val PANEL_BORDER = Color(0x30, 0x30, 0x50)
        val BTN_TOP = Color(0x3A, 0x3A, 0x55)
        val BTN_BOTTOM = Color(0x22, 0x22, 0x38)
        val BTN_BORDER = Color(0x55, 0x55, 0x78)
        val BTN_HOVER_TOP = Color(0x50, 0x50, 0x70)
        val BTN_HOVER_BOTTOM = Color(0x30, 0x30, 0x4C)
        val TEXT_DIM = Color(0x88, 0x88, 0xA0)
        val TEXT_LIGHT = Color(0xCC, 0xCC, 0xDD)
        val SLIDER_TRACK = Color(0x30, 0x30, 0x50)
        val SLIDER_FILL = Color(0x00, 0xB0, 0x60)
        val SIGNAL_GREEN = Color(0x00, 0xE0, 0x70)
        val SIGNAL_YELLOW = Color(0xFF, 0xD0, 0x00)
        val SIGNAL_RED = Color(0xFF, 0x40, 0x40)
    }

    // RTL-SDR direct access
    private val sdr = RtlSdrNative()
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
    private var signalStrength = 0  // 0..5

    // RDS scrolling state
    private var radioText = ""
    private var radioTextScrollPos = 0
    private var rdsScrollTimer: Timer? = null

    // UI components
    private val freqLabel = JLabel("100.0", SwingConstants.CENTER)
    private val mhzLabel = JLabel("MHz", SwingConstants.LEFT)
    private val statusLabel = JLabel("Starting...")
    private val rdsLabel = JLabel("---")
    private val rtLabel = JLabel(" ")
    private val stereoLabel = JLabel("MONO")
    private val ptyLabel = JLabel("")
    private val signalPanel = SignalStrengthPanel()
    private val playBtn = JButton("\u25B6")
    private val volumeSlider = JSlider(0, 100, 80)
    private val bassSlider = JSlider(0, 20, 10)
    private val trebleSlider = JSlider(0, 20, 10)
    private val bassValueLabel = JLabel("0 dB")
    private val trebleValueLabel = JLabel("0 dB")

    // Presets
    private val presetFreqs = longArrayOf(
        87_500_000, 91_000_000, 95_000_000,
        100_000_000, 104_000_000, 107_000_000
    )
    private val presetButtons = Array(6) { JButton("P${it + 1}") }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        preferredSize = Dimension(900, 520)
        minimumSize = Dimension(900, 520)
        isResizable = false
        buildUI()
        pack()
        setLocationRelativeTo(null)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                shutdown()
            }
        })

        // Start RDS scroll timer
        rdsScrollTimer = Timer(250) { scrollRadioText() }
        rdsScrollTimer?.start()

        // Auto-connect on startup
        SwingUtilities.invokeLater { autoConnect() }
    }

    // =========================================================================
    //  UI CONSTRUCTION
    // =========================================================================

    private fun buildUI() {
        val root = GradientPanel(BG_TOP, BG_BOTTOM).apply {
            layout = BorderLayout(0, 0)
            border = EmptyBorder(14, 18, 10, 18)
        }

        // ---- Top: status bar ----
        val topBar = createStatusBar()

        // ---- Main content: left display area + right controls ----
        val mainContent = JPanel(BorderLayout(16, 0)).apply { isOpaque = false }

        // Left side: frequency display, RDS, signal
        val displayPanel = createDisplayPanel()

        // Right side: controls column
        val controlsPanel = createControlsPanel()

        mainContent.add(displayPanel, BorderLayout.CENTER)
        mainContent.add(controlsPanel, BorderLayout.EAST)

        // ---- Bottom: presets row ----
        val presetsRow = createPresetsPanel()

        root.add(topBar, BorderLayout.NORTH)
        root.add(mainContent, BorderLayout.CENTER)
        root.add(presetsRow, BorderLayout.SOUTH)

        contentPane = root

        // Actions
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

    private fun createStatusBar(): JPanel {
        val bar = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            border = EmptyBorder(0, 0, 10, 0)
        }

        statusLabel.apply {
            foreground = AMBER
            font = Font("SansSerif", Font.PLAIN, 13)
        }

        val versionLabel = JLabel("v$VERSION build $BUILD").apply {
            foreground = TEXT_DIM
            font = Font("SansSerif", Font.PLAIN, 11)
        }

        val separator = object : JPanel() {
            init {
                preferredSize = Dimension(0, 1)
                maximumSize = Dimension(Int.MAX_VALUE, 1)
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val grad = GradientPaint(0f, 0f, Color(0x40, 0x40, 0x60, 0), width * 0.2f, 0f, PANEL_BORDER)
                g2.paint = grad
                g2.fillRect(0, 0, width / 2, height)
                val grad2 = GradientPaint(width * 0.5f, 0f, PANEL_BORDER, width.toFloat(), 0f, Color(0x40, 0x40, 0x60, 0))
                g2.paint = grad2
                g2.fillRect(width / 2, 0, width / 2, height)
            }
        }

        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.CENTER)
            add(versionLabel, BorderLayout.EAST)
        }

        bar.add(topRow, BorderLayout.CENTER)
        bar.add(separator, BorderLayout.SOUTH)
        return bar
    }

    private fun createDisplayPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 8)).apply { isOpaque = false }

        // ---- Frequency display area (dark inset panel) ----
        val freqDisplayPanel = object : JPanel(BorderLayout(0, 6)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Inset background
                val bg = GradientPaint(0f, 0f, Color(0x0A, 0x0A, 0x18), 0f, height.toFloat(), Color(0x10, 0x10, 0x20))
                g2.paint = bg
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 18f, 18f))
                // Border
                g2.color = PANEL_BORDER
                g2.stroke = BasicStroke(1.5f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 18f, 18f))
            }
        }.apply {
            isOpaque = false
            border = EmptyBorder(16, 24, 12, 24)
        }

        // Frequency + MHz + stereo row
        val freqRow = JPanel(BorderLayout(0, 0)).apply { isOpaque = false }

        freqLabel.apply {
            font = Font("Monospaced", Font.BOLD, 72)
            foreground = FREQ_GREEN
        }

        // Wrap freq label to draw glow
        val freqGlowPanel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                // Draw glow behind the text
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                // Glow effect: multiple layers
                val text = freqLabel.text
                val fm = g2.getFontMetrics(freqLabel.font)
                val tx = (width - fm.stringWidth(text)) / 2
                val ty = (height + fm.ascent - fm.descent) / 2
                g2.font = freqLabel.font
                for (r in listOf(12, 8, 4)) {
                    g2.color = Color(0x00, 0xFF, 0x80, 12)
                    g2.drawString(text, tx - r, ty)
                    g2.drawString(text, tx + r, ty)
                    g2.drawString(text, tx, ty - r)
                    g2.drawString(text, tx, ty + r)
                }
            }
        }.apply {
            isOpaque = false
            add(freqLabel, BorderLayout.CENTER)
        }

        // Right side info column: MHz, stereo, signal
        val infoColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = EmptyBorder(8, 8, 0, 0)
        }

        mhzLabel.apply {
            font = Font("SansSerif", Font.BOLD, 24)
            foreground = FREQ_GREEN_DIM
            alignmentX = Component.LEFT_ALIGNMENT
        }

        stereoLabel.apply {
            font = Font("Monospaced", Font.BOLD, 16)
            foreground = AMBER
            alignmentX = Component.LEFT_ALIGNMENT
        }

        signalPanel.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(80, 22)
            maximumSize = Dimension(80, 22)
        }

        infoColumn.add(mhzLabel)
        infoColumn.add(Box.createVerticalStrut(4))
        infoColumn.add(stereoLabel)
        infoColumn.add(Box.createVerticalStrut(6))
        infoColumn.add(signalPanel)
        infoColumn.add(Box.createVerticalGlue())

        freqRow.add(freqGlowPanel, BorderLayout.CENTER)
        freqRow.add(infoColumn, BorderLayout.EAST)

        // RDS info area
        val rdsArea = JPanel(BorderLayout(0, 2)).apply {
            isOpaque = false
            border = EmptyBorder(6, 0, 0, 0)
        }

        rdsLabel.apply {
            font = Font("SansSerif", Font.BOLD, 24)
            foreground = CYAN
        }

        val rdsBottomRow = JPanel(BorderLayout()).apply { isOpaque = false }
        rtLabel.apply {
            font = Font("Monospaced", Font.PLAIN, 14)
            foreground = TEXT_LIGHT
        }
        ptyLabel.apply {
            font = Font("SansSerif", Font.PLAIN, 13)
            foreground = CYAN_DIM
        }
        rdsBottomRow.add(rtLabel, BorderLayout.CENTER)
        rdsBottomRow.add(ptyLabel, BorderLayout.EAST)

        rdsArea.add(rdsLabel, BorderLayout.NORTH)
        rdsArea.add(rdsBottomRow, BorderLayout.CENTER)

        freqDisplayPanel.add(freqRow, BorderLayout.CENTER)
        freqDisplayPanel.add(rdsArea, BorderLayout.SOUTH)

        // ---- Tuning controls row ----
        val tuneRow = JPanel(FlowLayout(FlowLayout.CENTER, 12, 0)).apply {
            isOpaque = false
            border = EmptyBorder(10, 0, 0, 0)
        }

        val seekBackBtn = createMetallicButton("\u23EA", 50, 50, "Seek Back")
        val freqDownBtn = createMetallicButton("-0.1", 50, 50, "Freq Down")
        val freqUpBtn = createMetallicButton("+0.1", 50, 50, "Freq Up")
        val seekFwdBtn = createMetallicButton("\u23E9", 50, 50, "Seek Forward")

        // Play button: large green
        stylePlayButton(playBtn)

        seekBackBtn.addActionListener { seekStation(false) }
        freqDownBtn.addActionListener { tuneFrequency(currentFrequency - stepHz) }
        freqUpBtn.addActionListener { tuneFrequency(currentFrequency + stepHz) }
        seekFwdBtn.addActionListener { seekStation(true) }

        tuneRow.add(seekBackBtn)
        tuneRow.add(freqDownBtn)
        tuneRow.add(playBtn)
        tuneRow.add(freqUpBtn)
        tuneRow.add(seekFwdBtn)

        panel.add(freqDisplayPanel, BorderLayout.CENTER)
        panel.add(tuneRow, BorderLayout.SOUTH)

        return panel
    }

    private fun createControlsPanel(): JPanel {
        val panel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val bg = GradientPaint(0f, 0f, Color(0x14, 0x14, 0x26), 0f, height.toFloat(), Color(0x0E, 0x0E, 0x1C))
                g2.paint = bg
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f))
                g2.color = PANEL_BORDER
                g2.stroke = BasicStroke(1.2f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 16f, 16f))
            }
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = EmptyBorder(16, 16, 16, 16)
            preferredSize = Dimension(220, 0)
        }

        // Volume
        val volLabel = JLabel("VOLUME").apply {
            font = Font("SansSerif", Font.BOLD, 12)
            foreground = TEXT_DIM
            alignmentX = Component.LEFT_ALIGNMENT
        }

        styleSlider(volumeSlider, SLIDER_FILL)
        volumeSlider.alignmentX = Component.LEFT_ALIGNMENT
        volumeSlider.maximumSize = Dimension(Int.MAX_VALUE, 36)
        volumeSlider.addChangeListener {
            audioPlayer?.setVolume(volumeSlider.value / 100f)
        }

        val volValueLabel = JLabel("${volumeSlider.value}%").apply {
            font = Font("Monospaced", Font.BOLD, 13)
            foreground = TEXT_LIGHT
            alignmentX = Component.LEFT_ALIGNMENT
        }
        volumeSlider.addChangeListener {
            volValueLabel.text = "${volumeSlider.value}%"
        }

        // Bass
        val bassLabel = JLabel("BASS").apply {
            font = Font("SansSerif", Font.BOLD, 12)
            foreground = TEXT_DIM
            alignmentX = Component.LEFT_ALIGNMENT
        }

        styleSlider(bassSlider, Color(0xFF, 0x80, 0x40))
        bassSlider.alignmentX = Component.LEFT_ALIGNMENT
        bassSlider.maximumSize = Dimension(Int.MAX_VALUE, 36)
        bassSlider.addChangeListener {
            val db = bassSlider.value - 10
            equalizer?.bassGainDb = db.toFloat()
            bassValueLabel.text = "${if (db >= 0) "+" else ""}$db dB"
        }
        bassValueLabel.apply {
            font = Font("Monospaced", Font.BOLD, 13)
            foreground = TEXT_LIGHT
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Treble
        val trebleLabel = JLabel("TREBLE").apply {
            font = Font("SansSerif", Font.BOLD, 12)
            foreground = TEXT_DIM
            alignmentX = Component.LEFT_ALIGNMENT
        }

        styleSlider(trebleSlider, Color(0x60, 0xA0, 0xFF))
        trebleSlider.alignmentX = Component.LEFT_ALIGNMENT
        trebleSlider.maximumSize = Dimension(Int.MAX_VALUE, 36)
        trebleSlider.addChangeListener {
            val db = trebleSlider.value - 10
            equalizer?.trebleGainDb = db.toFloat()
            trebleValueLabel.text = "${if (db >= 0) "+" else ""}$db dB"
        }
        trebleValueLabel.apply {
            font = Font("Monospaced", Font.BOLD, 13)
            foreground = TEXT_LIGHT
            alignmentX = Component.LEFT_ALIGNMENT
        }

        panel.add(volLabel)
        panel.add(Box.createVerticalStrut(4))
        panel.add(volumeSlider)
        panel.add(volValueLabel)
        panel.add(Box.createVerticalStrut(18))
        panel.add(bassLabel)
        panel.add(Box.createVerticalStrut(4))
        panel.add(bassSlider)
        panel.add(bassValueLabel)
        panel.add(Box.createVerticalStrut(18))
        panel.add(trebleLabel)
        panel.add(Box.createVerticalStrut(4))
        panel.add(trebleSlider)
        panel.add(trebleValueLabel)
        panel.add(Box.createVerticalGlue())

        return panel
    }

    private fun createPresetsPanel(): JPanel {
        val wrapper = JPanel(BorderLayout(0, 0)).apply {
            isOpaque = false
            border = EmptyBorder(12, 0, 0, 0)
        }

        val presetRow = JPanel(FlowLayout(FlowLayout.CENTER, 10, 0)).apply {
            isOpaque = false
        }

        val label = JLabel("PRESETS").apply {
            font = Font("SansSerif", Font.BOLD, 12)
            foreground = TEXT_DIM
            border = EmptyBorder(0, 0, 0, 8)
        }
        presetRow.add(label)

        for (i in 0 until 6) {
            val btn = presetButtons[i]
            stylePresetButton(btn, i)
            btn.addActionListener { tuneFrequency(presetFreqs[i]) }
            btn.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON3) {
                        presetFreqs[i] = currentFrequency
                        updatePresetLabels()
                        statusLabel.text = "Preset ${i + 1} saved: ${formatFreq(currentFrequency)} MHz"
                    }
                }
            })
            presetRow.add(btn)
        }
        updatePresetLabels()

        wrapper.add(presetRow, BorderLayout.CENTER)
        return wrapper
    }

    // =========================================================================
    //  BUTTON / SLIDER STYLING
    // =========================================================================

    private fun createMetallicButton(text: String, w: Int, h: Int, tooltip: String): JButton {
        val btn = object : JButton(text) {
            var hover = false

            init {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                })
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val top = if (hover) BTN_HOVER_TOP else BTN_TOP
                val bot = if (hover) BTN_HOVER_BOTTOM else BTN_BOTTOM
                val grad = GradientPaint(0f, 0f, top, 0f, height.toFloat(), bot)
                g2.paint = grad
                g2.fill(RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, 14f, 14f))

                // highlight line at top
                g2.color = Color(255, 255, 255, if (hover) 30 else 18)
                g2.fillRect(4, 2, width - 8, 1)

                // border
                g2.color = if (hover) Color(0x77, 0x77, 0x99) else BTN_BORDER
                g2.stroke = BasicStroke(1.3f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 14f, 14f))

                // text
                g2.font = font
                g2.color = if (hover) Color.WHITE else TEXT_LIGHT
                val fm = g2.fontMetrics
                val tx = (width - fm.stringWidth(getText())) / 2
                val ty = (height + fm.ascent - fm.descent) / 2
                g2.drawString(getText(), tx, ty)
            }
        }
        btn.apply {
            preferredSize = Dimension(w, h)
            minimumSize = Dimension(w, h)
            maximumSize = Dimension(w, h)
            font = Font("SansSerif", Font.BOLD, 16)
            toolTipText = tooltip
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        return btn
    }

    private fun stylePlayButton(btn: JButton) {
        val playButton = object : JButton() {
            var hover = false

            init {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                })
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val bg = if (hover) GREEN_BTN_HOVER else GREEN_BTN
                val grad = GradientPaint(0f, 0f, bg.brighter(), 0f, height.toFloat(), bg.darker())
                g2.paint = grad
                g2.fill(RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, 20f, 20f))

                // glow ring
                g2.color = Color(0x00, 0xFF, 0x80, if (hover) 50 else 25)
                g2.stroke = BasicStroke(3f)
                g2.draw(RoundRectangle2D.Float(0f, 0f, width - 1f, height - 1f, 22f, 22f))

                // border
                g2.color = Color(0x00, 0xA0, 0x50)
                g2.stroke = BasicStroke(1.5f)
                g2.draw(RoundRectangle2D.Float(1f, 1f, width - 2.5f, height - 2.5f, 20f, 20f))

                // text
                g2.font = font
                g2.color = Color.WHITE
                val fm = g2.fontMetrics
                val tx = (width - fm.stringWidth(getText())) / 2
                val ty = (height + fm.ascent - fm.descent) / 2
                g2.drawString(getText(), tx, ty)
            }
        }
        // We cannot replace playBtn reference, so style it directly instead
        btn.apply {
            text = "\u25B6"
            preferredSize = Dimension(70, 70)
            minimumSize = Dimension(70, 70)
            maximumSize = Dimension(70, 70)
            font = Font("SansSerif", Font.BOLD, 22)
            toolTipText = "Play/Stop"
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // Custom paint via UI override
            setUI(object : javax.swing.plaf.basic.BasicButtonUI() {
                var hover = false

                override fun installListeners(b: AbstractButton) {
                    super.installListeners(b)
                    b.addMouseListener(object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) { hover = true; b.repaint() }
                        override fun mouseExited(e: MouseEvent) { hover = false; b.repaint() }
                    })
                }

                override fun paint(g: Graphics, c: JComponent) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    val w = c.width; val h = c.height

                    val bg = if (hover) GREEN_BTN_HOVER else GREEN_BTN
                    val grad = GradientPaint(0f, 0f, bg.brighter(), 0f, h.toFloat(), bg.darker())
                    g2.paint = grad
                    g2.fill(RoundRectangle2D.Float(1f, 1f, w - 2f, h - 2f, 20f, 20f))

                    // glow ring
                    g2.color = Color(0x00, 0xFF, 0x80, if (hover) 50 else 25)
                    g2.stroke = BasicStroke(3f)
                    g2.draw(RoundRectangle2D.Float(0f, 0f, w - 1f, h - 1f, 22f, 22f))

                    // border
                    g2.color = Color(0x00, 0xA0, 0x50)
                    g2.stroke = BasicStroke(1.5f)
                    g2.draw(RoundRectangle2D.Float(1f, 1f, w - 2.5f, h - 2.5f, 20f, 20f))

                    // text
                    val b = c as AbstractButton
                    g2.font = b.font
                    g2.color = Color.WHITE
                    val fm = g2.fontMetrics
                    val tx = (w - fm.stringWidth(b.text)) / 2
                    val ty = (h + fm.ascent - fm.descent) / 2
                    g2.drawString(b.text, tx, ty)
                }
            })
        }
    }

    private fun stylePresetButton(btn: JButton, index: Int) {
        btn.apply {
            preferredSize = Dimension(100, 44)
            minimumSize = Dimension(100, 44)
            font = Font("Monospaced", Font.BOLD, 14)
            toolTipText = "Click: tune | Right-click: save to P${index + 1}"
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            setUI(object : javax.swing.plaf.basic.BasicButtonUI() {
                var hover = false

                override fun installListeners(b: AbstractButton) {
                    super.installListeners(b)
                    b.addMouseListener(object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent) { hover = true; b.repaint() }
                        override fun mouseExited(e: MouseEvent) { hover = false; b.repaint() }
                    })
                }

                override fun paint(g: Graphics, c: JComponent) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    val w = c.width; val h = c.height

                    val top = if (hover) BTN_HOVER_TOP else BTN_TOP
                    val bot = if (hover) BTN_HOVER_BOTTOM else BTN_BOTTOM
                    val grad = GradientPaint(0f, 0f, top, 0f, h.toFloat(), bot)
                    g2.paint = grad
                    g2.fill(RoundRectangle2D.Float(1f, 1f, w - 2f, h - 2f, 12f, 12f))

                    // highlight
                    g2.color = Color(255, 255, 255, if (hover) 25 else 12)
                    g2.fillRect(3, 2, w - 6, 1)

                    // border
                    g2.color = if (hover) CYAN_DIM else BTN_BORDER
                    g2.stroke = BasicStroke(1.2f)
                    g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 12f, 12f))

                    // Preset number small
                    g2.font = Font("SansSerif", Font.PLAIN, 9)
                    g2.color = TEXT_DIM
                    g2.drawString("P${index + 1}", 6, 12)

                    // Frequency text
                    val b = c as AbstractButton
                    g2.font = b.font
                    g2.color = if (hover) CYAN else TEXT_LIGHT
                    val fm = g2.fontMetrics
                    val tx = (w - fm.stringWidth(b.text)) / 2
                    val ty = (h + fm.ascent - fm.descent) / 2 + 2
                    g2.drawString(b.text, tx, ty)
                }
            })
        }
    }

    private fun styleSlider(slider: JSlider, fillColor: Color) {
        slider.apply {
            isOpaque = false
            isFocusable = false
            setUI(object : BasicSliderUI(slider) {
                override fun paintTrack(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val trackBounds = trackRect
                    val cy = trackBounds.y + trackBounds.height / 2
                    val th = 6
                    // Background track
                    g2.color = SLIDER_TRACK
                    g2.fill(RoundRectangle2D.Float(trackBounds.x.toFloat(), (cy - th / 2).toFloat(),
                        trackBounds.width.toFloat(), th.toFloat(), th.toFloat(), th.toFloat()))
                    // Fill track
                    val fillWidth = thumbRect.x - trackBounds.x + thumbRect.width / 2
                    g2.color = fillColor
                    g2.fill(RoundRectangle2D.Float(trackBounds.x.toFloat(), (cy - th / 2).toFloat(),
                        fillWidth.toFloat(), th.toFloat(), th.toFloat(), th.toFloat()))
                }

                override fun paintThumb(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val r = thumbRect
                    val size = 18
                    val x = r.x + r.width / 2 - size / 2
                    val y = r.y + r.height / 2 - size / 2
                    // Shadow
                    g2.color = Color(0, 0, 0, 50)
                    g2.fillOval(x + 1, y + 1, size, size)
                    // Thumb
                    val grad = GradientPaint(x.toFloat(), y.toFloat(), Color(0xDD, 0xDD, 0xEE),
                        x.toFloat(), (y + size).toFloat(), Color(0x99, 0x99, 0xAA))
                    g2.paint = grad
                    g2.fillOval(x, y, size, size)
                    // Inner dot
                    g2.color = fillColor
                    g2.fillOval(x + size / 2 - 3, y + size / 2 - 3, 6, 6)
                }

                override fun getThumbSize(): Dimension = Dimension(18, 18)
            })
        }
    }

    // =========================================================================
    //  CUSTOM PANELS
    // =========================================================================

    /** Gradient background panel. */
    private class GradientPanel(val topColor: Color, val bottomColor: Color) : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.paint = GradientPaint(0f, 0f, topColor, 0f, height.toFloat(), bottomColor)
            g2.fillRect(0, 0, width, height)
        }
    }

    /** Signal strength bar display. */
    inner class SignalStrengthPanel : JPanel() {
        init { isOpaque = false }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val barCount = 5
            val barW = 10
            val gap = 3
            val maxH = height - 4
            for (i in 0 until barCount) {
                val barH = maxH * (i + 1) / barCount
                val x = i * (barW + gap) + 2
                val y = height - barH - 2
                val color = when {
                    i >= signalStrength -> Color(0x30, 0x30, 0x50)
                    i < 2 -> SIGNAL_GREEN
                    i < 4 -> SIGNAL_YELLOW
                    else -> SIGNAL_RED
                }
                g2.color = color
                g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), barW.toFloat(), barH.toFloat(), 3f, 3f))
            }
        }
    }

    // =========================================================================
    //  DISPLAY UPDATE METHODS
    // =========================================================================

    private fun updateFreqDisplay() {
        freqLabel.text = formatFreq(currentFrequency)
    }

    private fun formatFreq(hz: Long): String = String.format("%.1f", hz / 1_000_000.0)

    private fun updateRds(data: RdsDecoder.RdsData) {
        if (data.ps.isNotBlank()) rdsLabel.text = data.ps.trim()
        if (data.rt.isNotBlank()) {
            radioText = data.rt
            radioTextScrollPos = 0
        }
        if (data.ptyName.isNotBlank()) ptyLabel.text = "[${data.ptyName}]"
    }

    private fun scrollRadioText() {
        if (radioText.isBlank() || radioText.length <= 30) {
            if (radioText.isNotBlank()) rtLabel.text = radioText
            return
        }
        val display = radioText + "     " + radioText
        val window = 38
        val end = (radioTextScrollPos + window).coerceAtMost(display.length)
        rtLabel.text = display.substring(radioTextScrollPos, end)
        radioTextScrollPos++
        if (radioTextScrollPos >= radioText.length + 5) radioTextScrollPos = 0
    }

    private fun updatePresetLabels() {
        for (i in 0 until 6) {
            presetButtons[i].text = formatFreq(presetFreqs[i])
        }
    }

    private fun updateSignalStrength(power: Float) {
        signalStrength = when {
            power > -5f -> 5
            power > -10f -> 4
            power > -15f -> 3
            power > -25f -> 2
            power > -35f -> 1
            else -> 0
        }
        signalPanel.repaint()
    }

    // =========================================================================
    //  FUNCTIONAL CODE (autoConnect, playback, tuning, seek, shutdown)
    // =========================================================================

    /**
     * Auto-detect and open RTL-SDR device, then start playback.
     */
    private fun autoConnect() {
        statusLabel.text = "Detecting RTL-SDR device..."
        Thread({
            val ok = sdr.open(0)
            if (ok) {
                sdr.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
                sdr.setAutoGain(true)
                sdr.setFrequency(currentFrequency)

                SwingUtilities.invokeLater {
                    statusLabel.text = "Connected: ${sdr.deviceName} (${sdr.tunerName})"
                    statusLabel.foreground = Color(100, 200, 100)
                    // Auto-start playback
                    startPlayback()
                }
            } else {
                SwingUtilities.invokeLater {
                    statusLabel.text = "RTL-SDR not found"
                    statusLabel.foreground = RED_SOFT
                    JOptionPane.showMessageDialog(
                        this,
                        "RTL-SDR device not found!\n\n" +
                            "Make sure:\n" +
                            "1. RTL-SDR dongle is connected via USB\n" +
                            "2. Drivers are installed:\n" +
                            "   Windows: Zadig (WinUSB driver)\n" +
                            "   Linux: sudo apt install librtlsdr-dev\n" +
                            "3. No other program is using the device\n\n" +
                            "The app needs librtlsdr.dll (Windows)\n" +
                            "or librtlsdr.so (Linux) to work.",
                        "FM Radio RTL-SDR",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }, "AutoConnectThread").start()
    }

    private fun togglePlayback() {
        if (isPlaying) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        if (!sdr.isOpen) {
            statusLabel.text = "RTL-SDR not connected!"
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

        sdr.setFrequency(currentFrequency)
        isPlaying = true

        streamingThread = sdr.startStreaming(65536) { iqData ->
            var audioSamples = demodulator?.demodulate(iqData)
            if (audioSamples != null && audioSamples.isNotEmpty()) {
                val eq = equalizer
                if (eq != null) audioSamples = eq.process(audioSamples)
                audioPlayer?.writeSamples(audioSamples)
            }
            val stereoNow = demodulator?.isStereo == true
            val power = demodulator?.measureSignalStrength(iqData) ?: -50f
            SwingUtilities.invokeLater {
                stereoLabel.text = if (stereoNow) "STEREO" else "MONO"
                stereoLabel.foreground = if (stereoNow) FREQ_GREEN else AMBER
                updateSignalStrength(power)
            }
        }

        playBtn.text = "\u25A0"
        statusLabel.text = "Playing ${formatFreq(currentFrequency)} MHz \u2014 ${sdr.deviceName}"
    }

    private fun stopPlayback() {
        isPlaying = false
        sdr.stopStreaming()
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

        playBtn.text = "\u25B6"
        rdsLabel.text = "---"
        rtLabel.text = " "
        ptyLabel.text = ""
        radioText = ""
        stereoLabel.text = "MONO"
        signalStrength = 0
        signalPanel.repaint()
        if (sdr.isOpen) statusLabel.text = "Connected: ${sdr.deviceName} \u2014 Stopped"
    }

    private fun tuneFrequency(freqHz: Long) {
        val clamped = freqHz.coerceIn(87_500_000L, 108_000_000L)
        currentFrequency = clamped
        sdr.setFrequency(clamped)
        rdsDecoder?.reset()
        rdsLabel.text = "---"
        rtLabel.text = " "
        ptyLabel.text = ""
        radioText = ""
        updateFreqDisplay()
        if (isPlaying) statusLabel.text = "Playing ${formatFreq(clamped)} MHz"
    }

    private fun seekStation(forward: Boolean) {
        if (!sdr.isOpen) return
        statusLabel.text = "Seeking..."
        val wasPlaying = isPlaying
        if (wasPlaying) stopPlayback()

        Thread({
            val tempDemod = FmDemodulator()
            sdr.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
            sdr.setAutoGain(true)
            sdr.resetBuffer()
            val step = 100_000L
            var freq = currentFrequency + if (forward) step else -step
            var found: Long? = null

            for (i in 0 until 200) {
                if (freq > 108_000_000L) freq = 87_500_000L
                if (freq < 87_500_000L) freq = 108_000_000L
                sdr.setFrequency(freq)
                Thread.sleep(50)
                val samples = sdr.readSamples(65536)
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

    private fun shutdown() {
        rdsScrollTimer?.stop()
        stopPlayback()
        sdr.close()
    }
}

fun main() {
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")

    try {
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
    } catch (_: Exception) {}

    // Set dark defaults for tooltips and dialogs
    UIManager.put("ToolTip.background", Color(0x22, 0x22, 0x33))
    UIManager.put("ToolTip.foreground", Color(0xCC, 0xCC, 0xDD))
    UIManager.put("ToolTip.border", LineBorder(Color(0x44, 0x44, 0x66), 1))
    UIManager.put("OptionPane.background", Color(0x1A, 0x1A, 0x2E))
    UIManager.put("Panel.background", Color(0x1A, 0x1A, 0x2E))
    UIManager.put("OptionPane.messageForeground", Color(0xCC, 0xCC, 0xDD))

    SwingUtilities.invokeLater {
        MainWindow().isVisible = true
    }
}
