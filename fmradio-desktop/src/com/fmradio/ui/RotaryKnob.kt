package com.fmradio.ui

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import kotlin.math.*

/**
 * Metallic rotary knob for smooth manual frequency tuning.
 * Emulates a real radio tuning knob with:
 * - Brushed metal appearance with 3D shading
 * - Notch markers around the edge
 * - Smooth drag rotation
 * - Mouse wheel support
 * - Momentum/inertia for smooth feel
 */
class RotaryKnob(
    private var minValue: Long = 0L,
    private var maxValue: Long = 1000L,
    initialValue: Long = 500L
) : JComponent() {

    // Colors
    private val knobOuterRing = Color(0x55, 0x55, 0x70)
    private val knobBodyTop = Color(0x70, 0x70, 0x88)
    private val knobBodyBottom = Color(0x38, 0x38, 0x4C)
    private val knobHighlight = Color(0xAA, 0xAA, 0xCC)
    private val knobShadow = Color(0x18, 0x18, 0x28)
    private val notchColor = Color(0x44, 0x44, 0x5C)
    private val indicatorColor = Color(0x00, 0xFF, 0x80)  // green pointer
    private val indicatorGlow = Color(0x00, 0xFF, 0x80, 60)
    private val grooveColor = Color(0x50, 0x50, 0x68)
    private val grooveHighlight = Color(0x88, 0x88, 0xA8)

    var value: Long = initialValue.coerceIn(minValue, maxValue)
        set(v) {
            val clamped = v.coerceIn(minValue, maxValue)
            if (field != clamped) {
                field = clamped
                angle = valueToAngle(clamped)
                repaint()
            }
        }

    var onValueChanged: ((Long) -> Unit)? = null

    // Rotation state
    private var angle = valueToAngle(initialValue)  // radians, 0 = top
    private val minAngle = -2.4  // ~-137 degrees (7 o'clock)
    private val maxAngle = 2.4   // ~137 degrees (5 o'clock)

    // Drag state
    private var isDragging = false
    private var dragStartAngle = 0.0
    private var dragStartMouseAngle = 0.0

    // Step for mouse wheel
    var stepSize: Long = 1L

    init {
        preferredSize = Dimension(120, 120)
        minimumSize = Dimension(80, 80)
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val mouseHandler = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                isDragging = true
                dragStartAngle = angle
                dragStartMouseAngle = getMouseAngle(e)
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
                repaint()
            }

            override fun mouseDragged(e: MouseEvent) {
                if (!isDragging) return
                val currentMouseAngle = getMouseAngle(e)
                var delta = currentMouseAngle - dragStartMouseAngle

                // Handle wrapping around -PI/PI boundary
                if (delta > PI) delta -= 2 * PI
                if (delta < -PI) delta += 2 * PI

                // Ctrl+drag = fine tuning (4x slower rotation)
                val sensitivity = if (e.isControlDown) 0.25 else 1.0
                val newAngle = (dragStartAngle + delta * sensitivity).coerceIn(minAngle, maxAngle)
                if (newAngle != angle) {
                    angle = newAngle
                    var newValue = angleToValue(angle)
                    // Snap to step grid
                    if (stepSize > 1) {
                        newValue = ((newValue + stepSize / 2) / stepSize) * stepSize
                        newValue = newValue.coerceIn(minValue, maxValue)
                    }
                    if (newValue != value) {
                        value = newValue
                        onValueChanged?.invoke(value)
                    }
                    repaint()
                }
            }

            override fun mouseWheelMoved(e: MouseWheelEvent) {
                // Ctrl+scroll = fine tuning (1/10 step), normal scroll = full step
                val actualStep = if (e.isControlDown) (stepSize / 10).coerceAtLeast(1L) else stepSize
                val delta = -e.wheelRotation.toLong() * actualStep
                val newValue = (value + delta).coerceIn(minValue, maxValue)
                if (newValue != value) {
                    value = newValue
                    angle = valueToAngle(value)
                    onValueChanged?.invoke(value)
                    repaint()
                }
            }
        }

        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
        addMouseWheelListener(mouseHandler)
    }

    fun setRange(min: Long, max: Long) {
        minValue = min
        maxValue = max
        value = value.coerceIn(min, max)
        angle = valueToAngle(value)
        repaint()
    }

    private fun getMouseAngle(e: MouseEvent): Double {
        val cx = width / 2.0
        val cy = height / 2.0
        return atan2(e.x - cx, -(e.y - cy))
    }

    private fun valueToAngle(v: Long): Double {
        if (maxValue == minValue) return 0.0
        val fraction = (v - minValue).toDouble() / (maxValue - minValue)
        return minAngle + fraction * (maxAngle - minAngle)
    }

    private fun angleToValue(a: Double): Long {
        val fraction = (a - minAngle) / (maxAngle - minAngle)
        return minValue + (fraction * (maxValue - minValue)).toLong()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        val size = minOf(width, height)
        val cx = width / 2.0f
        val cy = height / 2.0f
        val radius = size / 2.0f - 8

        // --- Outer shadow/glow ---
        if (isDragging) {
            g2.color = Color(0x00, 0xFF, 0x80, 20)
            g2.fill(Ellipse2D.Float(cx - radius - 6, cy - radius - 6,
                (radius + 6) * 2, (radius + 6) * 2))
        }

        // --- Outer ring (bezel) ---
        val outerGrad = RadialGradientPaint(
            cx, cy, radius + 4,
            floatArrayOf(0.0f, 0.85f, 1.0f),
            arrayOf(Color(0x60, 0x60, 0x78), Color(0x40, 0x40, 0x58), knobShadow)
        )
        g2.paint = outerGrad
        g2.fill(Ellipse2D.Float(cx - radius - 4, cy - radius - 4,
            (radius + 4) * 2, (radius + 4) * 2))

        // --- Notch marks around the dial ---
        val numNotches = 31
        for (i in 0 until numNotches) {
            val frac = i.toDouble() / (numNotches - 1)
            val notchAngle = minAngle + frac * (maxAngle - minAngle)
            val r1 = radius + 1
            val r2 = radius - 4
            val x1 = cx + r1 * sin(notchAngle).toFloat()
            val y1 = cy - r1 * cos(notchAngle).toFloat()
            val x2 = cx + r2 * sin(notchAngle).toFloat()
            val y2 = cy - r2 * cos(notchAngle).toFloat()
            g2.color = if (i % 5 == 0) grooveHighlight else notchColor
            g2.stroke = BasicStroke(if (i % 5 == 0) 1.8f else 0.8f)
            g2.draw(Line2D.Float(x1, y1, x2, y2))
        }

        // --- Knob body (brushed metal) ---
        val knobRadius = radius - 8
        val bodyGrad = RadialGradientPaint(
            cx - knobRadius * 0.3f, cy - knobRadius * 0.3f, knobRadius * 2,
            floatArrayOf(0.0f, 0.4f, 0.8f, 1.0f),
            arrayOf(knobHighlight, knobBodyTop, knobBodyBottom, knobShadow)
        )
        g2.paint = bodyGrad
        g2.fill(Ellipse2D.Float(cx - knobRadius, cy - knobRadius,
            knobRadius * 2, knobRadius * 2))

        // --- Concentric grooves (brushed metal texture) ---
        g2.stroke = BasicStroke(0.5f)
        for (r in generateSequence(knobRadius * 0.3f) { it + 3f }.takeWhile { it < knobRadius - 3 }) {
            g2.color = Color(0xFF, 0xFF, 0xFF, 8)
            g2.draw(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
        }

        // --- Edge highlight (top-left light source) ---
        val highlightGrad = RadialGradientPaint(
            cx - knobRadius * 0.35f, cy - knobRadius * 0.35f, knobRadius * 1.2f,
            floatArrayOf(0.0f, 0.5f, 1.0f),
            arrayOf(Color(0xFF, 0xFF, 0xFF, 50), Color(0xFF, 0xFF, 0xFF, 15), Color(0, 0, 0, 0))
        )
        g2.paint = highlightGrad
        g2.fill(Ellipse2D.Float(cx - knobRadius, cy - knobRadius,
            knobRadius * 2, knobRadius * 2))

        // --- Knob edge ring ---
        g2.color = Color(0x55, 0x55, 0x70, 180)
        g2.stroke = BasicStroke(2f)
        g2.draw(Ellipse2D.Float(cx - knobRadius + 1, cy - knobRadius + 1,
            (knobRadius - 1) * 2, (knobRadius - 1) * 2))

        // --- Indicator line (pointer) ---
        val pointerInner = knobRadius * 0.25f
        val pointerOuter = knobRadius - 4
        val pxI = cx + pointerInner * sin(angle).toFloat()
        val pyI = cy - pointerInner * cos(angle).toFloat()
        val pxO = cx + pointerOuter * sin(angle).toFloat()
        val pyO = cy - pointerOuter * cos(angle).toFloat()

        // Glow
        g2.color = indicatorGlow
        g2.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(pxI, pyI, pxO, pyO))

        // Main line
        g2.color = indicatorColor
        g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(pxI, pyI, pxO, pyO))

        // --- Center cap ---
        val capRadius = knobRadius * 0.18f
        val capGrad = RadialGradientPaint(
            cx - capRadius * 0.3f, cy - capRadius * 0.3f, capRadius * 1.5f,
            floatArrayOf(0.0f, 0.6f, 1.0f),
            arrayOf(Color(0x90, 0x90, 0xAA), Color(0x55, 0x55, 0x70), Color(0x30, 0x30, 0x45))
        )
        g2.paint = capGrad
        g2.fill(Ellipse2D.Float(cx - capRadius, cy - capRadius, capRadius * 2, capRadius * 2))
    }
}
