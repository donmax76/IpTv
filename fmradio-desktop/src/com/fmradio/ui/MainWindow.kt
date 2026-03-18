package com.fmradio.ui

import com.fmradio.dsp.AmDemodulator
import com.fmradio.dsp.AudioEqualizer
import com.fmradio.dsp.FmDemodulator
import com.fmradio.dsp.RdsDecoder
import com.fmradio.rtlsdr.RtlSdrNative
import java.awt.*
import java.awt.event.*
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.plaf.basic.BasicSliderUI
import org.json.JSONArray
import org.json.JSONObject

/**
 * FM Radio RTL-SDR — Desktop (Windows/Linux/Mac)
 * Uses librtlsdr directly via JNA — no rtl_tcp server needed.
 * Auto-detects and opens RTL-SDR device on startup.
 *
 * Premium car radio head unit UI — unified design with Android version.
 */
class MainWindow : JFrame("FM Radio RTL-SDR v$VERSION (build $BUILD)") {

    companion object {
        const val VERSION = "1.6"
        const val BUILD = "20260318-5"

        // FM band range (extended: OIRT 65.8-74 + CCIR 87.5-108)
        const val FM_MIN_HZ = 76_000_000L
        const val FM_MAX_HZ = 108_000_000L

        // Color palette — lighter theme, readable text
        val BG_TOP = Color(0x2A, 0x2A, 0x40)
        val BG_BOTTOM = Color(0x1C, 0x1C, 0x30)
        val FREQ_GREEN = Color(0x00, 0xFF, 0x80)
        val FREQ_GREEN_DIM = Color(0x00, 0xA0, 0x50)
        val CYAN = Color(0x00, 0xE5, 0xFF)
        val CYAN_DIM = Color(0x00, 0x90, 0xAA)
        val AMBER = Color(0xFF, 0xD0, 0x20)
        val RED_SOFT = Color(0xFF, 0x64, 0x64)
        val GREEN_BTN = Color(0x00, 0xC8, 0x64)
        val GREEN_BTN_HOVER = Color(0x00, 0xE0, 0x78)
        val PANEL_BG = Color(0x22, 0x22, 0x36)
        val PANEL_BORDER = Color(0x48, 0x48, 0x68)
        val BTN_TOP = Color(0x50, 0x50, 0x6C)
        val BTN_BOTTOM = Color(0x38, 0x38, 0x50)
        val BTN_BORDER = Color(0x68, 0x68, 0x88)
        val BTN_HOVER_TOP = Color(0x64, 0x64, 0x82)
        val BTN_HOVER_BOTTOM = Color(0x44, 0x44, 0x5E)
        val TEXT_DIM = Color(0xA0, 0xA0, 0xB8)
        val TEXT_LIGHT = Color(0xE8, 0xE8, 0xF0)
        val SLIDER_TRACK = Color(0x40, 0x40, 0x60)
        val SLIDER_FILL = Color(0x00, 0xB0, 0x60)
        val SIGNAL_GREEN = Color(0x00, 0xE0, 0x70)
        val SIGNAL_YELLOW = Color(0xFF, 0xD0, 0x00)
        val SIGNAL_RED = Color(0xFF, 0x40, 0x40)
        val LIST_ITEM_BG = Color(0x24, 0x24, 0x3A)
        val LIST_ITEM_SELECTED = Color(0x2E, 0x3E, 0x2E)
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
    private var signalStrength = 0  // 0..5
    private var afEnabled = false
    private var taEnabled = false

    // Band definitions: name, min Hz, max Hz, step Hz, modulation ("FM" or "AM"), direct sampling mode
    data class BandDef(val name: String, val minHz: Long, val maxHz: Long,
                       val stepHz: Long, val modulation: String, val directSampling: Int = 0)

    private val bands = arrayOf(
        BandDef("FM",  FM_MIN_HZ, FM_MAX_HZ, 100_000L, "FM"),
        BandDef("AIR", 108_000_000L, 137_000_000L, 25_000L, "AM"),
        BandDef("SW1", 3_000_000L, 10_000_000L, 5_000L, "AM", 2),
        BandDef("SW2", 10_000_000L, 30_000_000L, 5_000L, "AM", 2)
    )
    private var currentBand = 0
    private val bandNames get() = bands.map { it.name }.toTypedArray()
    private val bandRanges get() = bands.map { it.minHz to it.maxHz }.toTypedArray()
    private val currentBandDef get() = bands[currentBand]

    private var amDemodulator: AmDemodulator? = null
    private var lastRdsData: RdsDecoder.RdsData? = null

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

    // Frequency tuning slider (76.0 - 108.0 MHz, step 0.1 MHz)
    private val tuningSlider = JSlider((FM_MIN_HZ / 100_000).toInt(), (FM_MAX_HZ / 100_000).toInt(), 1000)
    private val tuningMinLabel = JLabel("${FM_MIN_HZ / 1_000_000}").apply {
        font = Font("Monospaced", Font.PLAIN, 11)
        foreground = TEXT_DIM
    }
    private val tuningMaxLabel = JLabel("${FM_MAX_HZ / 1_000_000}").apply {
        font = Font("Monospaced", Font.PLAIN, 11)
        foreground = TEXT_DIM
    }

    // Rotary tuning knob
    private val tuningKnob = RotaryKnob(FM_MIN_HZ, FM_MAX_HZ, 100_000_000L)

    // Function buttons
    private val btnScan = createMetallicButton("SCAN", 70, 36, "Auto-scan for stations")
    private val btnAf = createMetallicButton("AF", 50, 36, "Alternate Frequency: auto-switch to stronger signal")
    private val btnTa = createMetallicButton("TA", 50, 36, "Traffic Announcements: auto-raise volume for traffic news")
    private val btnPty = createMetallicButton("PTY", 55, 36, "Program Type: show/filter by genre")
    private val btnBand = createMetallicButton("FM", 70, 36, "Switch band range")

    // Expandable presets list
    private val presetListModel = DefaultListModel<PresetEntry>()
    private val presetList = JList(presetListModel)
    private var presetsExpanded = true
    private var stationsExpanded = true

    // Stations list (found by scan)
    private val stationListModel = DefaultListModel<StationEntry>()
    private val stationList = JList(stationListModel)

    // Storage files
    private val storageFile = File(System.getProperty("user.home"), ".fmradio-presets.json")
    private val settingsFile = File(System.getProperty("user.home"), ".fmradio-settings.json")

    data class PresetEntry(val frequencyHz: Long, val name: String = "") {
        override fun toString() = if (name.isNotEmpty()) "$name (${formatFreq(frequencyHz)})" else "${formatFreq(frequencyHz)} MHz"
        companion object {
            fun formatFreq(hz: Long) = String.format("%.1f", hz / 1_000_000.0)
        }
    }

    data class StationEntry(val frequencyHz: Long, val name: String = "", val signalStrength: Float = 0f) {
        override fun toString() = if (name.isNotEmpty()) "$name (${String.format("%.1f", frequencyHz / 1e6)})" else "${String.format("%.1f", frequencyHz / 1e6)} MHz"
    }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        preferredSize = Dimension(1100, 600)
        minimumSize = Dimension(900, 520)
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

        // Load saved presets and settings
        loadPresetsFromFile()
        loadSettings()

        // Auto-connect on startup
        SwingUtilities.invokeLater { autoConnect() }
    }

    // =========================================================================
    //  UI CONSTRUCTION — Unified layout matching Android
    // =========================================================================

    private fun buildUI() {
        val root = GradientPanel(BG_TOP, BG_BOTTOM).apply {
            layout = BorderLayout(0, 0)
            border = EmptyBorder(10, 12, 8, 12)
        }

        // ---- Top: status bar ----
        val topBar = createStatusBar()

        // ---- Main content: left controls (65%) + right lists (35%) ----
        val mainContent = JPanel(GridBagLayout()).apply { isOpaque = false }
        val gbc = GridBagConstraints()

        // Left side: frequency display + controls + function buttons
        val leftPanel = createLeftPanel()
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.65; gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH; gbc.insets = Insets(0, 0, 0, 6)
        mainContent.add(leftPanel, gbc)

        // Right side: presets list + stations list
        val rightPanel = createRightPanel()
        gbc.gridx = 1; gbc.weightx = 0.35; gbc.insets = Insets(0, 6, 0, 0)
        mainContent.add(rightPanel, gbc)

        // ---- Bottom: Volume + Bass + Treble bar (like Android) ----
        val bottomBar = createBottomEqBar()

        root.add(topBar, BorderLayout.NORTH)
        root.add(mainContent, BorderLayout.CENTER)
        root.add(bottomBar, BorderLayout.SOUTH)

        contentPane = root

        // Actions
        playBtn.addActionListener { togglePlayback() }

        // Keyboard shortcuts: arrows = full step, Ctrl+arrows = fine step (1/10)
        rootPane.registerKeyboardAction({ tuneFrequency(currentFrequency - currentBandDef.stepHz) },
            KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        rootPane.registerKeyboardAction({ tuneFrequency(currentFrequency + currentBandDef.stepHz) },
            KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        rootPane.registerKeyboardAction({
            val fineStep = (currentBandDef.stepHz / 10).coerceAtLeast(1000L)
            tuneFrequency(currentFrequency - fineStep)
        }, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW)
        rootPane.registerKeyboardAction({
            val fineStep = (currentBandDef.stepHz / 10).coerceAtLeast(1000L)
            tuneFrequency(currentFrequency + fineStep)
        }, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW)
        rootPane.registerKeyboardAction({ togglePlayback() },
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)

        updateFreqDisplay()
    }

    private fun createStatusBar(): JPanel {
        val bar = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            border = EmptyBorder(0, 0, 8, 0)
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
                val grad = GradientPaint(0f, 0f, Color(0x48, 0x48, 0x68, 0), width * 0.2f, 0f, PANEL_BORDER)
                g2.paint = grad
                g2.fillRect(0, 0, width / 2, height)
                val grad2 = GradientPaint(width * 0.5f, 0f, PANEL_BORDER, width.toFloat(), 0f, Color(0x48, 0x48, 0x68, 0))
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

    private fun createLeftPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 6)).apply { isOpaque = false }

        // LCD display area
        val freqDisplayPanel = createFreqDisplay()

        // Tuning controls row: seek buttons + play + rotary knob
        val tuneRow = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
            isOpaque = false
            border = EmptyBorder(4, 0, 2, 0)
        }

        val seekBackBtn = createMetallicButton("\u23EA", 44, 44, "Seek Back")
        val freqDownBtn = createMetallicButton("-", 44, 44, "Freq Down")
        val freqUpBtn = createMetallicButton("+", 44, 44, "Freq Up")
        val seekFwdBtn = createMetallicButton("\u23E9", 44, 44, "Seek Forward")

        stylePlayButton(playBtn)

        seekBackBtn.addActionListener { seekStation(false) }
        freqDownBtn.addActionListener { tuneFrequency(currentFrequency - currentBandDef.stepHz) }
        freqUpBtn.addActionListener { tuneFrequency(currentFrequency + currentBandDef.stepHz) }
        seekFwdBtn.addActionListener { seekStation(true) }

        // Setup rotary knob
        tuningKnob.stepSize = currentBandDef.stepHz
        tuningKnob.onValueChanged = { newFreq ->
            tuneFrequency(newFreq)
        }
        tuningKnob.preferredSize = Dimension(110, 110)
        tuningKnob.toolTipText = "Drag to tune frequency smoothly"

        // Left column: seek/play buttons
        val buttonsCol = JPanel(GridBagLayout()).apply { isOpaque = false }
        val gbc2 = GridBagConstraints().apply { insets = Insets(2, 2, 2, 2) }
        gbc2.gridx = 0; gbc2.gridy = 0; buttonsCol.add(seekBackBtn, gbc2)
        gbc2.gridx = 1; buttonsCol.add(freqDownBtn, gbc2)
        gbc2.gridx = 2; buttonsCol.add(playBtn, gbc2)
        gbc2.gridx = 3; buttonsCol.add(freqUpBtn, gbc2)
        gbc2.gridx = 4; buttonsCol.add(seekFwdBtn, gbc2)

        tuneRow.add(buttonsCol)
        tuneRow.add(tuningKnob)

        // Frequency tuning slider row
        val tuningRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = EmptyBorder(2, 12, 0, 12)
        }

        styleSlider(tuningSlider, FREQ_GREEN)
        tuningSlider.preferredSize = Dimension(400, 30)
        tuningSlider.value = (currentFrequency / 100_000).toInt()
        var tuningSliderUpdating = false
        tuningSlider.addChangeListener {
            if (!tuningSliderUpdating) {
                val sliderStep = currentBandDef.stepHz.coerceAtLeast(5_000L)
                val freqHz = tuningSlider.value.toLong() * sliderStep
                tuneFrequency(freqHz)
            }
        }

        tuningRow.add(tuningMinLabel, BorderLayout.WEST)
        tuningRow.add(tuningSlider, BorderLayout.CENTER)
        tuningRow.add(tuningMaxLabel, BorderLayout.EAST)

        // Function buttons row: SCAN | AF | TA | PTY | BAND
        val funcRow = JPanel(FlowLayout(FlowLayout.CENTER, 6, 0)).apply {
            isOpaque = false
            border = EmptyBorder(2, 0, 0, 0)
        }
        btnScan.addActionListener { startScan() }
        btnAf.addActionListener { toggleAf() }
        btnTa.addActionListener { toggleTa() }
        btnPty.addActionListener { showPtyMenu() }
        btnBand.addActionListener { switchBand() }

        funcRow.add(btnScan)
        funcRow.add(btnAf)
        funcRow.add(btnTa)
        funcRow.add(btnPty)
        funcRow.add(btnBand)

        // Bottom part of left panel: tune row + slider + func buttons
        val bottomControls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        tuneRow.alignmentX = Component.CENTER_ALIGNMENT
        tuningRow.alignmentX = Component.CENTER_ALIGNMENT
        funcRow.alignmentX = Component.CENTER_ALIGNMENT
        bottomControls.add(tuneRow)
        bottomControls.add(tuningRow)
        bottomControls.add(funcRow)

        panel.add(freqDisplayPanel, BorderLayout.CENTER)
        panel.add(bottomControls, BorderLayout.SOUTH)

        return panel
    }

    private fun createFreqDisplay(): JPanel {
        val freqDisplayPanel = object : JPanel(BorderLayout(0, 6)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val bg = GradientPaint(0f, 0f, Color(0x14, 0x14, 0x24), 0f, height.toFloat(), Color(0x1A, 0x1A, 0x2C))
                g2.paint = bg
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 18f, 18f))
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

        val freqGlowPanel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
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

        // Right side info: MHz, stereo, signal
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

        return freqDisplayPanel
    }

    private fun createRightPanel(): JPanel {
        val panel = object : JPanel(BorderLayout(0, 0)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val bg = GradientPaint(0f, 0f, Color(0x22, 0x22, 0x38), 0f, height.toFloat(), Color(0x1A, 0x1A, 0x2E))
                g2.paint = bg
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f))
                g2.color = PANEL_BORDER
                g2.stroke = BasicStroke(1.2f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 16f, 16f))
            }
        }.apply {
            isOpaque = false
            border = EmptyBorder(10, 10, 10, 10)
        }

        val innerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // ---- PRESETS SECTION ----
        val presetsHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 30)
        }
        val presetsLabel = JLabel("PRESETS \u25BC").apply {
            font = Font("SansSerif", Font.BOLD, 14)
            foreground = CYAN
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val addPresetBtn = createMetallicButton("+", 32, 26, "Save current frequency as preset")
        addPresetBtn.font = Font("SansSerif", Font.BOLD, 16)

        presetsHeader.add(presetsLabel, BorderLayout.CENTER)
        presetsHeader.add(addPresetBtn, BorderLayout.EAST)

        // Preset list
        presetList.apply {
            isOpaque = false
            background = Color(0, 0, 0, 0)
            foreground = TEXT_LIGHT
            font = Font("Monospaced", Font.PLAIN, 14)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = 40
            cellRenderer = PresetCellRenderer()
        }
        val presetScroll = JScrollPane(presetList).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = EmptyBorder(4, 0, 4, 0)
            verticalScrollBar.unitIncrement = 16
            preferredSize = Dimension(0, 160)
        }

        // Preset click handlers
        presetList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = presetList.locationToIndex(e.point)
                if (index < 0) return
                val preset = presetListModel.getElementAt(index)
                if (e.button == MouseEvent.BUTTON1) {
                    tuneFrequency(preset.frequencyHz)
                } else if (e.button == MouseEvent.BUTTON3) {
                    showPresetContextMenu(e, preset, index)
                }
            }
        })

        addPresetBtn.addActionListener {
            addPreset(currentFrequency)
        }

        // Toggle presets expand/collapse
        presetsLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                presetsExpanded = !presetsExpanded
                presetScroll.isVisible = presetsExpanded
                presetsLabel.text = "PRESETS ${if (presetsExpanded) "\u25BC" else "\u25B6"}"
                panel.revalidate()
            }
        })

        // ---- STATIONS SECTION ----
        val stationsHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 30)
        }
        val stationsLabel = JLabel("STATIONS \u25BC").apply {
            font = Font("SansSerif", Font.BOLD, 14)
            foreground = AMBER
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        stationsHeader.add(stationsLabel, BorderLayout.CENTER)

        // Station list
        stationList.apply {
            isOpaque = false
            background = Color(0, 0, 0, 0)
            foreground = TEXT_LIGHT
            font = Font("Monospaced", Font.PLAIN, 14)
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = 44
            cellRenderer = StationCellRenderer()
        }
        val stationScroll = JScrollPane(stationList).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = EmptyBorder(4, 0, 4, 0)
            verticalScrollBar.unitIncrement = 16
        }

        stationList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = stationList.locationToIndex(e.point)
                if (index < 0) return
                val station = stationListModel.getElementAt(index)
                if (e.button == MouseEvent.BUTTON1) {
                    tuneFrequency(station.frequencyHz)
                } else if (e.button == MouseEvent.BUTTON3) {
                    showStationContextMenu(e, station, index)
                }
            }
        })

        // Toggle stations expand/collapse
        stationsLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                stationsExpanded = !stationsExpanded
                stationScroll.isVisible = stationsExpanded
                stationsLabel.text = "STATIONS ${if (stationsExpanded) "\u25BC" else "\u25B6"}"
                panel.revalidate()
            }
        })

        presetsHeader.alignmentX = Component.LEFT_ALIGNMENT
        presetScroll.alignmentX = Component.LEFT_ALIGNMENT
        stationsHeader.alignmentX = Component.LEFT_ALIGNMENT
        stationScroll.alignmentX = Component.LEFT_ALIGNMENT

        innerPanel.add(presetsHeader)
        innerPanel.add(Box.createVerticalStrut(4))
        innerPanel.add(presetScroll)
        innerPanel.add(Box.createVerticalStrut(8))
        innerPanel.add(stationsHeader)
        innerPanel.add(Box.createVerticalStrut(4))
        innerPanel.add(stationScroll)

        panel.add(innerPanel, BorderLayout.CENTER)
        return panel
    }

    private fun createBottomEqBar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
            isOpaque = false
            border = EmptyBorder(8, 0, 0, 0)
        }

        // Wrap in a styled panel
        val styledBar = object : JPanel(FlowLayout(FlowLayout.CENTER, 12, 4)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val bg = GradientPaint(0f, 0f, Color(0x22, 0x22, 0x38), 0f, height.toFloat(), Color(0x1A, 0x1A, 0x2E))
                g2.paint = bg
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 14f, 14f))
                g2.color = PANEL_BORDER
                g2.stroke = BasicStroke(1.2f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 14f, 14f))
            }
        }.apply {
            isOpaque = false
            border = EmptyBorder(6, 16, 8, 16)
        }

        // Volume
        val volLabel = JLabel("VOL").apply {
            font = Font("SansSerif", Font.BOLD, 13)
            foreground = AMBER
        }
        styleSlider(volumeSlider, SLIDER_FILL)
        volumeSlider.preferredSize = Dimension(140, 30)
        val volValueLabel = JLabel("${volumeSlider.value}").apply {
            font = Font("Monospaced", Font.BOLD, 13)
            foreground = AMBER
            preferredSize = Dimension(28, 20)
        }
        volumeSlider.addChangeListener {
            audioPlayer?.setVolume(volumeSlider.value / 100f)
            volValueLabel.text = "${volumeSlider.value}"
        }

        // Separator
        val sep1 = JPanel().apply {
            preferredSize = Dimension(1, 20)
            background = TEXT_DIM
            isOpaque = true
        }

        // Bass
        val bassLabel = JLabel("BASS").apply {
            font = Font("SansSerif", Font.BOLD, 13)
            foreground = CYAN
        }
        styleSlider(bassSlider, Color(0x00, 0xE5, 0xFF))
        bassSlider.preferredSize = Dimension(100, 30)
        bassValueLabel.apply {
            font = Font("Monospaced", Font.BOLD, 13)
            foreground = CYAN
            preferredSize = Dimension(40, 20)
        }
        bassSlider.addChangeListener {
            val db = bassSlider.value - 10
            equalizer?.bassGainDb = db.toFloat()
            bassValueLabel.text = "${if (db >= 0) "+" else ""}$db"
        }

        // Separator
        val sep2 = JPanel().apply {
            preferredSize = Dimension(1, 20)
            background = TEXT_DIM
            isOpaque = true
        }

        // Treble
        val trebleLabel = JLabel("TREB").apply {
            font = Font("SansSerif", Font.BOLD, 13)
            foreground = CYAN
        }
        styleSlider(trebleSlider, Color(0x00, 0xE5, 0xFF))
        trebleSlider.preferredSize = Dimension(100, 30)
        trebleValueLabel.apply {
            font = Font("Monospaced", Font.BOLD, 13)
            foreground = CYAN
            preferredSize = Dimension(40, 20)
        }
        trebleSlider.addChangeListener {
            val db = trebleSlider.value - 10
            equalizer?.trebleGainDb = db.toFloat()
            trebleValueLabel.text = "${if (db >= 0) "+" else ""}$db"
        }

        styledBar.add(volLabel)
        styledBar.add(volumeSlider)
        styledBar.add(volValueLabel)
        styledBar.add(sep1)
        styledBar.add(bassLabel)
        styledBar.add(bassSlider)
        styledBar.add(bassValueLabel)
        styledBar.add(sep2)
        styledBar.add(trebleLabel)
        styledBar.add(trebleSlider)
        styledBar.add(trebleValueLabel)

        bar.add(styledBar)
        return bar
    }

    // =========================================================================
    //  PRESET / STATION LIST RENDERERS
    // =========================================================================

    private inner class PresetCellRenderer : ListCellRenderer<PresetEntry> {
        override fun getListCellRendererComponent(
            list: JList<out PresetEntry>, value: PresetEntry, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            return object : JPanel(BorderLayout(8, 0)) {
                init {
                    isOpaque = false
                    border = EmptyBorder(4, 8, 4, 8)
                }
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val bg = if (value.frequencyHz == currentFrequency) LIST_ITEM_SELECTED else LIST_ITEM_BG
                    g2.color = bg
                    g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 10f, 10f))
                    if (isSelected) {
                        g2.color = CYAN_DIM
                        g2.stroke = BasicStroke(1.2f)
                        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 10f, 10f))
                    }
                }
            }.apply {
                val indexLabel = JLabel("P${index + 1}").apply {
                    font = Font("SansSerif", Font.PLAIN, 11)
                    foreground = TEXT_DIM
                    preferredSize = Dimension(28, 20)
                }
                val freqLabel = JLabel("${PresetEntry.formatFreq(value.frequencyHz)} MHz").apply {
                    font = Font("Monospaced", Font.BOLD, 14)
                    foreground = if (value.frequencyHz == currentFrequency) FREQ_GREEN else TEXT_LIGHT
                }
                val nameLabel = JLabel(value.name).apply {
                    font = Font("SansSerif", Font.PLAIN, 12)
                    foreground = AMBER
                }

                val textPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                }
                textPanel.add(freqLabel)
                if (value.name.isNotEmpty()) textPanel.add(nameLabel)

                add(indexLabel, BorderLayout.WEST)
                add(textPanel, BorderLayout.CENTER)
            }
        }
    }

    private inner class StationCellRenderer : ListCellRenderer<StationEntry> {
        override fun getListCellRendererComponent(
            list: JList<out StationEntry>, value: StationEntry, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            return object : JPanel(BorderLayout(8, 0)) {
                init {
                    isOpaque = false
                    border = EmptyBorder(4, 8, 4, 8)
                }
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val bg = if (value.frequencyHz == currentFrequency) LIST_ITEM_SELECTED else LIST_ITEM_BG
                    g2.color = bg
                    g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 10f, 10f))
                    if (isSelected) {
                        g2.color = AMBER
                        g2.stroke = BasicStroke(1.2f)
                        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 10f, 10f))
                    }
                }
            }.apply {
                val freqLabel = JLabel("${String.format("%.1f", value.frequencyHz / 1e6)} MHz").apply {
                    font = Font("Monospaced", Font.BOLD, 14)
                    foreground = if (value.frequencyHz == currentFrequency) FREQ_GREEN else TEXT_LIGHT
                }
                val nameLabel = JLabel(if (value.name.isNotEmpty()) value.name else "---").apply {
                    font = Font("SansSerif", Font.PLAIN, 12)
                    foreground = AMBER
                }
                val signalLabel = JLabel(getSignalBars(value.signalStrength)).apply {
                    font = Font("Monospaced", Font.PLAIN, 12)
                    foreground = SIGNAL_GREEN
                }

                val textPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                }
                textPanel.add(freqLabel)
                textPanel.add(nameLabel)

                add(textPanel, BorderLayout.CENTER)
                add(signalLabel, BorderLayout.EAST)
            }
        }

        private fun getSignalBars(strength: Float): String = when {
            strength > -5f -> "\u2588\u2588\u2588\u2588\u2588"
            strength > -10f -> "\u2588\u2588\u2588\u2588"
            strength > -15f -> "\u2588\u2588\u2588"
            strength > -20f -> "\u2588\u2588"
            else -> "\u2588"
        }
    }

    // =========================================================================
    //  PRESET / STATION CONTEXT MENUS
    // =========================================================================

    private fun showPresetContextMenu(e: MouseEvent, preset: PresetEntry, index: Int) {
        val menu = JPopupMenu()
        menu.background = Color(0x30, 0x30, 0x44)

        val renameItem = JMenuItem("Rename").apply {
            foreground = TEXT_LIGHT
            background = Color(0x30, 0x30, 0x44)
        }
        renameItem.addActionListener {
            val name = JOptionPane.showInputDialog(this, "Preset name:", preset.name)
            if (name != null) {
                presetListModel.set(index, preset.copy(name = name.trim()))
                savePresetsToFile()
            }
        }

        val deleteItem = JMenuItem("Delete").apply {
            foreground = RED_SOFT
            background = Color(0x30, 0x30, 0x44)
        }
        deleteItem.addActionListener {
            presetListModel.remove(index)
            savePresetsToFile()
        }

        menu.add(renameItem)
        menu.add(deleteItem)
        menu.show(e.component, e.x, e.y)
    }

    private fun showStationContextMenu(e: MouseEvent, station: StationEntry, index: Int) {
        val menu = JPopupMenu()
        menu.background = Color(0x30, 0x30, 0x44)

        val saveAsPreset = JMenuItem("Save as Preset").apply {
            foreground = CYAN
            background = Color(0x30, 0x30, 0x44)
        }
        saveAsPreset.addActionListener {
            addPreset(station.frequencyHz, station.name)
        }

        val deleteItem = JMenuItem("Delete").apply {
            foreground = RED_SOFT
            background = Color(0x30, 0x30, 0x44)
        }
        deleteItem.addActionListener {
            stationListModel.remove(index)
        }

        menu.add(saveAsPreset)
        menu.add(deleteItem)
        menu.show(e.component, e.x, e.y)
    }

    // =========================================================================
    //  PRESET STORAGE (JSON file)
    // =========================================================================

    private fun addPreset(frequencyHz: Long, name: String = "") {
        // Remove duplicate
        for (i in presetListModel.size() - 1 downTo 0) {
            if (presetListModel.getElementAt(i).frequencyHz == frequencyHz) {
                presetListModel.remove(i)
            }
        }
        presetListModel.addElement(PresetEntry(frequencyHz, name))
        savePresetsToFile()
        statusLabel.text = "Preset saved: ${formatFreq(frequencyHz)} MHz"
    }

    private fun savePresetsToFile() {
        try {
            val arr = JSONArray()
            for (i in 0 until presetListModel.size()) {
                val p = presetListModel.getElementAt(i)
                arr.put(JSONObject().apply {
                    put("frequencyHz", p.frequencyHz)
                    put("name", p.name)
                })
            }
            storageFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadPresetsFromFile() {
        presetListModel.clear()
        if (!storageFile.exists()) return
        try {
            val arr = JSONArray(storageFile.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                presetListModel.addElement(PresetEntry(
                    frequencyHz = obj.getLong("frequencyHz"),
                    name = obj.optString("name", "")
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =========================================================================
    //  SETTINGS PERSISTENCE
    // =========================================================================

    private fun saveSettings() {
        try {
            val obj = JSONObject().apply {
                put("volume", volumeSlider.value)
                put("bass", bassSlider.value)
                put("treble", trebleSlider.value)
                put("band", currentBand)
                put("frequencyHz", currentFrequency)
                put("afEnabled", afEnabled)
                put("taEnabled", taEnabled)
            }
            settingsFile.writeText(obj.toString(2))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadSettings() {
        if (!settingsFile.exists()) return
        try {
            val obj = JSONObject(settingsFile.readText())
            volumeSlider.value = obj.optInt("volume", 80)
            bassSlider.value = obj.optInt("bass", 10)
            trebleSlider.value = obj.optInt("treble", 10)

            val savedBand = obj.optInt("band", 0)
            if (savedBand in bands.indices && savedBand != 0) {
                // Switch to saved band (0 is default FM, already set)
                currentBand = savedBand - 1  // switchBand() increments
                switchBand()
            }

            val savedFreq = obj.optLong("frequencyHz", currentFrequency)
            if (savedFreq in currentBandDef.minHz..currentBandDef.maxHz) {
                currentFrequency = savedFreq
                updateFreqDisplay()
                val sliderStep = currentBandDef.stepHz.coerceAtLeast(5_000L)
                tuningSlider.value = (currentFrequency / sliderStep).toInt()
                tuningKnob.value = currentFrequency
            }

            if (obj.optBoolean("afEnabled", false)) toggleAf()
            if (obj.optBoolean("taEnabled", false)) toggleTa()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =========================================================================
    //  FUNCTION BUTTONS
    // =========================================================================

    // ---- AF: Alternate Frequency ----
    // When enabled, automatically tries alternate frequencies from RDS AF list
    // if signal drops below threshold, switching to a stronger one.
    private fun toggleAf() {
        afEnabled = !afEnabled
        btnAf.foreground = if (afEnabled) FREQ_GREEN else TEXT_LIGHT
        if (afEnabled) {
            statusLabel.text = "AF ON: will auto-switch to stronger signal"
            // Start AF monitoring
            startAfMonitor()
        } else {
            statusLabel.text = "AF OFF"
        }
    }

    private fun startAfMonitor() {
        if (!afEnabled || !isPlaying) return
        // Check AF list periodically via a timer
        val afTimer = Timer(5000) {
            if (!afEnabled || !isPlaying) return@Timer
            val rds = lastRdsData ?: return@Timer
            if (rds.afList.isEmpty()) return@Timer

            // Check current signal strength
            if (signalStrength <= 1) {
                // Signal is weak, try alternate frequencies
                Thread({
                    val tempDemod = FmDemodulator()
                    var bestFreq = currentFrequency
                    var bestPower = -100f
                    val origFreq = currentFrequency

                    for (afMhz in rds.afList) {
                        val afHz = (afMhz * 1_000_000).toLong()
                        if (afHz == currentFrequency) continue
                        sdr.setFrequency(afHz)
                        Thread.sleep(80)
                        val samples = sdr.readSamples(65536)
                        if (samples != null) {
                            val power = tempDemod.measureSignalStrength(samples)
                            if (power > bestPower) {
                                bestPower = power
                                bestFreq = afHz
                            }
                        }
                    }

                    // Switch if found better signal
                    if (bestFreq != origFreq && bestPower > -10f) {
                        SwingUtilities.invokeLater {
                            statusLabel.text = "AF: switched to ${formatFreq(bestFreq)} MHz (stronger)"
                            tuneFrequency(bestFreq)
                        }
                    } else {
                        // Restore original
                        sdr.setFrequency(origFreq)
                    }
                }, "AF-Check").start()
            }
        }
        afTimer.isRepeats = true
        afTimer.start()
    }

    // ---- TA: Traffic Announcements ----
    // When enabled, boosts volume when station broadcasts traffic announcements (RDS TA flag)
    private var taVolumeBoost = false
    private var taOriginalVolume = 80

    private fun toggleTa() {
        taEnabled = !taEnabled
        btnTa.foreground = if (taEnabled) FREQ_GREEN else TEXT_LIGHT
        if (taEnabled) {
            statusLabel.text = "TA ON: volume will boost for traffic news"
            taOriginalVolume = volumeSlider.value
        } else {
            statusLabel.text = "TA OFF"
            // Restore volume if it was boosted
            if (taVolumeBoost) {
                volumeSlider.value = taOriginalVolume
                taVolumeBoost = false
            }
        }
    }

    private fun checkTaStatus(data: RdsDecoder.RdsData) {
        if (!taEnabled) return
        if (data.ta && !taVolumeBoost) {
            // Traffic announcement started — boost volume
            taOriginalVolume = volumeSlider.value
            val boosted = (taOriginalVolume + 25).coerceAtMost(100)
            volumeSlider.value = boosted
            taVolumeBoost = true
            statusLabel.text = "TA: Traffic announcement! Volume boosted"
            statusLabel.foreground = AMBER
        } else if (!data.ta && taVolumeBoost) {
            // Traffic announcement ended — restore volume
            volumeSlider.value = taOriginalVolume
            taVolumeBoost = false
            statusLabel.text = "TA: Traffic announcement ended"
        }
    }

    // ---- PTY: Program Type ----
    // Shows current station info from RDS
    private fun showPtyMenu() {
        val rds = lastRdsData
        val menu = JPopupMenu()
        menu.background = Color(0x30, 0x30, 0x44)

        val currentPty = if (rds != null && rds.ptyName.isNotBlank() && rds.ptyName != "None")
            rds.ptyName else "---"
        val stationName = if (rds != null && rds.ps.isNotBlank()) rds.ps.trim() else "---"
        val piText = if (rds != null && rds.pi != 0) String.format("%04X", rds.pi) else "---"
        val tpText = if (rds?.tp == true) "Yes" else "No"
        val afCount = rds?.afList?.size ?: 0

        for (line in listOf(
            "Station: $stationName" to CYAN,
            "Genre: $currentPty" to TEXT_LIGHT,
            "PI Code: $piText" to TEXT_LIGHT,
            "Traffic Program: $tpText" to TEXT_LIGHT,
            "Alternate Freq: $afCount" to TEXT_LIGHT,
            "Freq: ${formatFreq(currentFrequency)} MHz" to AMBER
        )) {
            menu.add(JMenuItem(line.first).apply {
                foreground = line.second
                background = Color(0x30, 0x30, 0x44)
                isEnabled = false
            })
        }

        menu.show(btnPty, 0, btnPty.height)
    }

    // ---- BAND: Switch band range ----
    private fun switchBand() {
        val wasPlaying = isPlaying
        if (wasPlaying) stopPlayback()

        currentBand = (currentBand + 1) % bands.size
        val band = currentBandDef
        btnBand.text = band.name
        // Highlight active band button
        btnBand.foreground = FREQ_GREEN

        // Update tuning slider range (use band step for slider resolution)
        val sliderStep = band.stepHz.coerceAtLeast(5_000L)
        tuningSlider.minimum = (band.minHz / sliderStep).toInt()
        tuningSlider.maximum = (band.maxHz / sliderStep).toInt()

        // Update rotary knob range
        tuningKnob.setRange(band.minHz, band.maxHz)
        tuningKnob.stepSize = band.stepHz

        // Update range labels
        if (band.modulation == "AM" && band.minHz < 50_000_000L) {
            tuningMinLabel.text = "${band.minHz / 1_000} kHz"
            tuningMaxLabel.text = "${band.maxHz / 1_000} kHz"
        } else {
            tuningMinLabel.text = "${band.minHz / 1_000_000}"
            tuningMaxLabel.text = "${band.maxHz / 1_000_000}"
        }

        // Configure direct sampling for HF/SW bands
        if (sdr.isOpen) {
            sdr.setDirectSampling(band.directSampling)
        }

        // Set frequency to band start
        currentFrequency = band.minHz
        if (sdr.isOpen) sdr.setFrequency(currentFrequency)
        updateFreqDisplay()
        tuningSlider.value = (currentFrequency / sliderStep).toInt()

        val modStr = if (band.modulation == "AM") "AM" else "FM"
        statusLabel.text = "${band.name}: ${formatFreq(band.minHz)}-${formatFreq(band.maxHz)} MHz ($modStr)"

        if (wasPlaying) startPlayback()
    }

    private fun startScan() {
        if (!sdr.isOpen) {
            statusLabel.text = "Connect RTL-SDR first!"
            return
        }
        val wasPlaying = isPlaying
        if (wasPlaying) stopPlayback()

        val band = currentBandDef
        val modStr = if (band.modulation == "AM") "AM" else "FM"
        statusLabel.text = "Scanning ${band.name} band ($modStr)..."
        stationListModel.clear()
        btnScan.isEnabled = false
        btnScan.foreground = AMBER  // highlight during scanning

        Thread({
            val tempFmDemod = if (band.modulation == "FM") FmDemodulator() else null
            val tempAmDemod = if (band.modulation == "AM") AmDemodulator() else null
            sdr.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
            sdr.setAutoGain(true)
            sdr.setDirectSampling(band.directSampling)
            sdr.resetBuffer()

            val (startHz, endHz) = bandRanges[currentBand]
            val scanStep = if (band.modulation == "AM") band.stepHz else 100_000L
            var freq = startHz

            // First pass: collect noise floor estimate
            sdr.setFrequency(startHz)
            Thread.sleep(50)
            sdr.resetBuffer()
            val noiseSample = sdr.readSamples(65536)
            val noiseFloor = if (noiseSample != null) {
                (tempAmDemod?.measureSignalStrength(noiseSample)
                    ?: tempFmDemod?.measureSignalStrength(noiseSample) ?: -30f)
            } else -30f
            val powerThreshold = (noiseFloor + 6f).coerceAtLeast(-18f)  // 6 dB above noise

            while (freq <= endHz) {
                sdr.setFrequency(freq)
                Thread.sleep(50)   // longer settle time
                sdr.resetBuffer()
                Thread.sleep(60)   // longer dwell time

                // Read three samples for accurate measurement
                val samples1 = sdr.readSamples(65536)
                val samples2 = sdr.readSamples(65536)
                val samples3 = sdr.readSamples(65536)
                if (samples1 != null && samples2 != null && samples3 != null) {
                    val power1 = tempAmDemod?.measureSignalStrength(samples1)
                        ?: tempFmDemod?.measureSignalStrength(samples1) ?: -50f
                    val power2 = tempAmDemod?.measureSignalStrength(samples2)
                        ?: tempFmDemod?.measureSignalStrength(samples2) ?: -50f
                    val power3 = tempAmDemod?.measureSignalStrength(samples3)
                        ?: tempFmDemod?.measureSignalStrength(samples3) ?: -50f
                    val avgPower = (power1 + power2 + power3) / 3f

                    // For FM: also check modulation quality; for AM: adaptive threshold
                    val isStation = if (tempFmDemod != null) {
                        val quality = tempFmDemod.measureSignalQuality(samples2)
                        avgPower > powerThreshold && quality > 0.003f
                    } else {
                        avgPower > powerThreshold
                    }

                    if (isStation) {
                        val foundFreq = freq
                        val foundPower = avgPower
                        SwingUtilities.invokeLater {
                            stationListModel.addElement(StationEntry(foundFreq, signalStrength = foundPower))
                        }
                    }
                }
                val progress = ((freq - startHz).toFloat() / (endHz - startHz) * 100).toInt()
                val f = freq
                SwingUtilities.invokeLater {
                    statusLabel.text = "Scanning: ${formatFreq(f)} MHz ($progress%)"
                }
                freq += scanStep
            }

            SwingUtilities.invokeLater {
                btnScan.isEnabled = true
                btnScan.foreground = TEXT_LIGHT  // reset color
                statusLabel.text = "Scan complete: ${stationListModel.size()} stations found"
                if (wasPlaying) startPlayback()
            }
        }, "ScanThread").start()
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

                g2.color = Color(255, 255, 255, if (hover) 30 else 18)
                g2.fillRect(4, 2, width - 8, 1)

                g2.color = if (hover) Color(0x77, 0x77, 0x99) else BTN_BORDER
                g2.stroke = BasicStroke(1.3f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, 14f, 14f))

                g2.font = font
                // Use button's foreground color for active state indication
                g2.color = if (hover) Color.WHITE else foreground
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
            font = Font("SansSerif", Font.BOLD, 12)
            foreground = TEXT_LIGHT
            toolTipText = tooltip
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        return btn
    }

    private fun stylePlayButton(btn: JButton) {
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

                    g2.color = Color(0x00, 0xFF, 0x80, if (hover) 50 else 25)
                    g2.stroke = BasicStroke(3f)
                    g2.draw(RoundRectangle2D.Float(0f, 0f, w - 1f, h - 1f, 22f, 22f))

                    g2.color = Color(0x00, 0xA0, 0x50)
                    g2.stroke = BasicStroke(1.5f)
                    g2.draw(RoundRectangle2D.Float(1f, 1f, w - 2.5f, h - 2.5f, 20f, 20f))

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
                    g2.color = SLIDER_TRACK
                    g2.fill(RoundRectangle2D.Float(trackBounds.x.toFloat(), (cy - th / 2).toFloat(),
                        trackBounds.width.toFloat(), th.toFloat(), th.toFloat(), th.toFloat()))
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
                    g2.color = Color(0, 0, 0, 50)
                    g2.fillOval(x + 1, y + 1, size, size)
                    val grad = GradientPaint(x.toFloat(), y.toFloat(), Color(0xDD, 0xDD, 0xEE),
                        x.toFloat(), (y + size).toFloat(), Color(0x99, 0x99, 0xAA))
                    g2.paint = grad
                    g2.fillOval(x, y, size, size)
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

    private class GradientPanel(val topColor: Color, val bottomColor: Color) : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.paint = GradientPaint(0f, 0f, topColor, 0f, height.toFloat(), bottomColor)
            g2.fillRect(0, 0, width, height)
        }
    }

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
                    i >= signalStrength -> Color(0x40, 0x40, 0x60)
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
        val band = currentBandDef
        if (band.modulation == "AM" && currentFrequency < 50_000_000L) {
            // Show kHz for shortwave bands
            freqLabel.text = String.format("%.1f", currentFrequency / 1_000.0)
            mhzLabel.text = "kHz"
        } else {
            freqLabel.text = formatFreq(currentFrequency)
            mhzLabel.text = "MHz"
        }
    }

    private fun formatFreq(hz: Long): String = String.format("%.1f", hz / 1_000_000.0)

    private fun updateRds(data: RdsDecoder.RdsData) {
        lastRdsData = data

        if (data.ps.isNotBlank()) {
            rdsLabel.text = data.ps.trim()
            rdsLabel.foreground = CYAN
        }
        if (data.rt.isNotBlank()) {
            radioText = data.rt
            radioTextScrollPos = 0
        }
        if (data.ptyName.isNotBlank() && data.ptyName != "None") {
            ptyLabel.text = data.ptyName
        }

        // AF indicator: show count of alternate frequencies
        if (data.afList.isNotEmpty() && afEnabled) {
            btnAf.text = "AF:${data.afList.size}"
        }

        // TA: check traffic announcement status
        checkTaStatus(data)

        // Update station name in stations list if RDS PS is available
        if (data.ps.isNotBlank()) {
            for (i in 0 until stationListModel.size()) {
                val station = stationListModel.getElementAt(i)
                if (station.frequencyHz == currentFrequency && station.name.isBlank()) {
                    stationListModel.set(i, station.copy(name = data.ps.trim()))
                    break
                }
            }
        }
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

    private fun updateSignalStrength(power: Float) {
        // RTL-SDR 8-bit IQ: noise floor ~ -25 to -35 dB, strong stations ~ -3 to -8 dB
        signalStrength = when {
            power > -6f -> 5
            power > -10f -> 4
            power > -15f -> 3
            power > -20f -> 2
            power > -28f -> 1
            else -> 0
        }
        signalPanel.repaint()
    }

    // =========================================================================
    //  FUNCTIONAL CODE
    // =========================================================================

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

        val band = currentBandDef
        val sampleRate = FmDemodulator.RECOMMENDED_SAMPLE_RATE
        val isAm = band.modulation == "AM"

        // Configure direct sampling for HF/SW bands
        sdr.setDirectSampling(band.directSampling)

        if (isAm) {
            amDemodulator = AmDemodulator(inputSampleRate = sampleRate, audioSampleRate = 48000)
            demodulator = null
            rdsDecoder = null
        } else {
            demodulator = FmDemodulator(inputSampleRate = sampleRate, audioSampleRate = 48000)
            amDemodulator = null
            rdsDecoder = RdsDecoder(sampleRate / 6).also { rds ->
                rds.listener = object : RdsDecoder.RdsListener {
                    override fun onRdsData(data: RdsDecoder.RdsData) {
                        SwingUtilities.invokeLater { updateRds(data) }
                    }
                }
            }
            demodulator?.widebandListener = { wb -> rdsDecoder?.process(wb) }
        }

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
            var audioSamples = if (isAm) {
                amDemodulator?.demodulate(iqData)
            } else {
                demodulator?.demodulate(iqData)
            }
            if (audioSamples != null && audioSamples.isNotEmpty()) {
                val eq = equalizer
                if (eq != null) audioSamples = eq.process(audioSamples)
                audioPlayer?.writeSamples(audioSamples)
            }
            val power = if (isAm) {
                amDemodulator?.measureSignalStrength(iqData) ?: -50f
            } else {
                demodulator?.measureSignalStrength(iqData) ?: -50f
            }
            val stereoNow = if (!isAm) demodulator?.isStereo == true else false
            SwingUtilities.invokeLater {
                stereoLabel.text = if (isAm) "AM" else if (stereoNow) "STEREO" else "MONO"
                stereoLabel.foreground = if (isAm) CYAN else if (stereoNow) FREQ_GREEN else AMBER
                updateSignalStrength(power)
            }
        }

        playBtn.text = "\u25A0"
        val modStr = if (isAm) "AM" else "FM"
        statusLabel.text = "Playing ${formatFreq(currentFrequency)} MHz ($modStr) \u2014 ${sdr.deviceName}"
    }

    private fun stopPlayback() {
        isPlaying = false
        sdr.stopStreaming()
        try { streamingThread?.join(500) } catch (_: Exception) {}
        streamingThread = null
        audioPlayer?.stop()
        audioPlayer = null
        demodulator?.widebandListener = null
        demodulator?.reset()
        demodulator = null
        amDemodulator?.reset()
        amDemodulator = null
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
        lastRdsData = null
        btnAf.text = "AF"
        if (sdr.isOpen) statusLabel.text = "Connected: ${sdr.deviceName} \u2014 Stopped"
    }

    private fun tuneFrequency(freqHz: Long) {
        val band = currentBandDef
        val clamped = freqHz.coerceIn(band.minHz, band.maxHz)
        currentFrequency = clamped
        sdr.setFrequency(clamped)
        rdsDecoder?.reset()
        rdsLabel.text = "---"
        rtLabel.text = " "
        ptyLabel.text = ""
        radioText = ""
        updateFreqDisplay()
        // Update tuning slider and knob without triggering listener loop
        val sliderStep = band.stepHz.coerceAtLeast(5_000L)
        tuningSlider.value = (clamped / sliderStep).toInt()
        tuningKnob.value = clamped
        // Refresh lists to update selected highlight
        presetList.repaint()
        stationList.repaint()
        if (isPlaying) {
            val modStr = if (band.modulation == "AM") "AM" else "FM"
            statusLabel.text = "Playing ${formatFreq(clamped)} MHz ($modStr)"
        }
    }

    private fun seekStation(forward: Boolean) {
        if (!sdr.isOpen) return
        statusLabel.text = "Seeking..."
        val wasPlaying = isPlaying
        if (wasPlaying) stopPlayback()

        Thread({
            val band = currentBandDef
            val isAm = band.modulation == "AM"
            val tempFmDemod = if (!isAm) FmDemodulator() else null
            val tempAmDemod = if (isAm) AmDemodulator() else null
            sdr.setSampleRate(FmDemodulator.RECOMMENDED_SAMPLE_RATE)
            sdr.setAutoGain(true)
            sdr.setDirectSampling(band.directSampling)
            sdr.resetBuffer()
            val step = if (isAm) band.stepHz else 100_000L
            var freq = currentFrequency + if (forward) step else -step
            var found: Long? = null

            val (bandMin, bandMax) = bandRanges[currentBand]
            val totalSteps = ((bandMax - bandMin) / step).toInt()
            for (i in 0 until totalSteps) {
                if (freq > bandMax) freq = bandMin
                if (freq < bandMin) freq = bandMax
                sdr.setFrequency(freq)
                Thread.sleep(50)
                sdr.resetBuffer()
                Thread.sleep(60)
                val samples1 = sdr.readSamples(65536)
                val samples2 = sdr.readSamples(65536)
                if (samples1 != null && samples2 != null) {
                    val power1 = tempAmDemod?.measureSignalStrength(samples1)
                        ?: tempFmDemod?.measureSignalStrength(samples1) ?: -50f
                    val power2 = tempAmDemod?.measureSignalStrength(samples2)
                        ?: tempFmDemod?.measureSignalStrength(samples2) ?: -50f
                    val power = (power1 + power2) / 2f
                    val isStation = if (tempFmDemod != null) {
                        val quality = tempFmDemod.measureSignalQuality(samples2)
                        power > -15f && quality > 0.003f
                    } else {
                        power > -18f
                    }
                    if (isStation) { found = freq; break }
                }
                val f = freq
                SwingUtilities.invokeLater {
                    statusLabel.text = "Seeking: ${formatFreq(f)} MHz"
                }
                freq += if (forward) step else -step
            }

            val sliderStep = band.stepHz.coerceAtLeast(5_000L)
            SwingUtilities.invokeLater {
                if (found != null) {
                    currentFrequency = found
                    updateFreqDisplay()
                    tuningSlider.value = (found / sliderStep).toInt()
                    statusLabel.text = "Found: ${formatFreq(found)} MHz"
                } else {
                    statusLabel.text = "No station found"
                }
                if (wasPlaying) startPlayback()
            }
        }, "SeekThread").start()
    }

    private fun shutdown() {
        saveSettings()
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

    UIManager.put("ToolTip.background", Color(0x30, 0x30, 0x44))
    UIManager.put("ToolTip.foreground", Color(0xE8, 0xE8, 0xF0))
    UIManager.put("ToolTip.border", LineBorder(Color(0x50, 0x50, 0x70), 1))
    UIManager.put("OptionPane.background", Color(0x2A, 0x2A, 0x40))
    UIManager.put("Panel.background", Color(0x2A, 0x2A, 0x40))
    UIManager.put("OptionPane.messageForeground", Color(0xE8, 0xE8, 0xF0))

    SwingUtilities.invokeLater {
        MainWindow().isVisible = true
    }
}
