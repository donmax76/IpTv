package com.fmradio.ui

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import javax.swing.JComponent
import kotlin.math.*

/**
 * Realistic 3D rotary tuning knob.
 *
 * Inspired by high-end audio equipment — brushed aluminum body,
 * ribbed grip edge, top-lit metallic shading, glowing indicator.
 */
class RotaryKnob(
    private var minValue: Long = 0L,
    private var maxValue: Long = 1000L,
    initialValue: Long = 500L
) : JComponent() {

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
    private var angle = valueToAngle(initialValue)
    private val minAngle = -2.4
    private val maxAngle = 2.4

    // Drag state
    private var isDragging = false
    private var dragStartAngle = 0.0
    private var dragStartMouseAngle = 0.0

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

                if (delta > PI) delta -= 2 * PI
                if (delta < -PI) delta += 2 * PI

                val sensitivity = if (e.isControlDown) 0.25 else 1.0
                val newAngle = (dragStartAngle + delta * sensitivity).coerceIn(minAngle, maxAngle)
                if (newAngle != angle) {
                    angle = newAngle
                    var newValue = angleToValue(angle)
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
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val size = minOf(width, height)
        val cx = width / 2.0f
        val cy = height / 2.0f
        val outerRadius = size / 2.0f - 6

        // ===== 1. DROP SHADOW =====
        val shadowAlpha = if (isDragging) 35 else 25
        for (s in 3 downTo 1) {
            g2.color = Color(0, 0, 0, shadowAlpha - s * 6)
            g2.fill(Ellipse2D.Float(
                cx - outerRadius - s + 2, cy - outerRadius - s + 3,
                (outerRadius + s) * 2, (outerRadius + s) * 2))
        }

        // ===== 2. OUTER BEZEL (dark ring) =====
        val bezelGrad = RadialGradientPaint(
            cx, cy - outerRadius * 0.2f, outerRadius * 1.3f,
            floatArrayOf(0.0f, 0.7f, 0.9f, 1.0f),
            arrayOf(Color(0x58, 0x58, 0x6E), Color(0x40, 0x40, 0x56), Color(0x30, 0x30, 0x44), Color(0x20, 0x20, 0x30))
        )
        g2.paint = bezelGrad
        g2.fill(Ellipse2D.Float(cx - outerRadius, cy - outerRadius,
            outerRadius * 2, outerRadius * 2))

        // ===== 3. RIBBED GRIP EDGE (vertical lines around circumference) =====
        val gripRadius = outerRadius - 1.5f
        val innerGripRadius = outerRadius - 8f
        val numRibs = 60
        g2.stroke = BasicStroke(1.0f)
        for (i in 0 until numRibs) {
            val ribAngle = 2.0 * PI * i / numRibs
            val x1 = cx + gripRadius * sin(ribAngle).toFloat()
            val y1 = cy - gripRadius * cos(ribAngle).toFloat()
            val x2 = cx + innerGripRadius * sin(ribAngle).toFloat()
            val y2 = cy - innerGripRadius * cos(ribAngle).toFloat()
            // Alternate light/dark for 3D ribbed effect
            val shade = if (i % 2 == 0) 0x50 else 0x38
            g2.color = Color(shade, shade, shade + 0x14, 180)
            g2.draw(Line2D.Float(x1, y1, x2, y2))
        }

        // ===== 4. SCALE MARKERS around the outside =====
        val markerOuterR = outerRadius + 2
        val markerInnerR = outerRadius - 3
        val numMarkers = 21
        for (i in 0 until numMarkers) {
            val frac = i.toDouble() / (numMarkers - 1)
            val markAngle = minAngle + frac * (maxAngle - minAngle)
            val isMajor = i % 5 == 0
            val r1 = if (isMajor) markerOuterR else markerOuterR - 1
            val r2 = if (isMajor) markerInnerR - 2 else markerInnerR
            val x1 = cx + r1 * sin(markAngle).toFloat()
            val y1 = cy - r1 * cos(markAngle).toFloat()
            val x2 = cx + r2 * sin(markAngle).toFloat()
            val y2 = cy - r2 * cos(markAngle).toFloat()
            g2.color = if (isMajor) Color(0xAA, 0xAA, 0xC0) else Color(0x66, 0x66, 0x80)
            g2.stroke = BasicStroke(if (isMajor) 2.0f else 1.0f)
            g2.draw(Line2D.Float(x1, y1, x2, y2))
        }

        // ===== 5. KNOB BODY (brushed aluminum) =====
        val bodyRadius = outerRadius - 10
        // Main body: radial gradient simulating top-left light source
        val bodyGrad = RadialGradientPaint(
            cx - bodyRadius * 0.3f, cy - bodyRadius * 0.4f,
            bodyRadius * 2.2f,
            floatArrayOf(0.0f, 0.25f, 0.55f, 0.85f, 1.0f),
            arrayOf(
                Color(0xC8, 0xC8, 0xD8),  // bright highlight
                Color(0xA0, 0xA0, 0xB4),  // light metal
                Color(0x78, 0x78, 0x8E),  // mid metal
                Color(0x50, 0x50, 0x64),  // dark side
                Color(0x38, 0x38, 0x4A)   // shadow edge
            )
        )
        g2.paint = bodyGrad
        g2.fill(Ellipse2D.Float(cx - bodyRadius, cy - bodyRadius,
            bodyRadius * 2, bodyRadius * 2))

        // ===== 6. CONCENTRIC BRUSHED TEXTURE =====
        g2.stroke = BasicStroke(0.4f)
        var r = bodyRadius * 0.25f
        while (r < bodyRadius - 2) {
            g2.color = Color(0xFF, 0xFF, 0xFF, 10)
            g2.draw(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
            r += 2.5f
        }

        // ===== 7. TOP HIGHLIGHT (specular reflection) =====
        val specGrad = RadialGradientPaint(
            cx - bodyRadius * 0.25f, cy - bodyRadius * 0.35f,
            bodyRadius * 0.8f,
            floatArrayOf(0.0f, 0.4f, 1.0f),
            arrayOf(Color(0xFF, 0xFF, 0xFF, 80), Color(0xFF, 0xFF, 0xFF, 20), Color(0, 0, 0, 0))
        )
        g2.paint = specGrad
        g2.fill(Ellipse2D.Float(cx - bodyRadius, cy - bodyRadius,
            bodyRadius * 2, bodyRadius * 2))

        // ===== 8. EDGE RING (metallic border) =====
        g2.color = Color(0x50, 0x50, 0x66, 200)
        g2.stroke = BasicStroke(1.8f)
        g2.draw(Ellipse2D.Float(cx - bodyRadius + 0.5f, cy - bodyRadius + 0.5f,
            (bodyRadius - 0.5f) * 2, (bodyRadius - 0.5f) * 2))

        // ===== 9. INDICATOR LINE (green pointer with glow) =====
        val pointerInner = bodyRadius * 0.2f
        val pointerOuter = bodyRadius - 3
        val pxI = cx + pointerInner * sin(angle).toFloat()
        val pyI = cy - pointerInner * cos(angle).toFloat()
        val pxO = cx + pointerOuter * sin(angle).toFloat()
        val pyO = cy - pointerOuter * cos(angle).toFloat()

        // Glow (wider, transparent)
        g2.color = Color(0x00, 0xFF, 0x80, if (isDragging) 90 else 50)
        g2.stroke = BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(pxI, pyI, pxO, pyO))

        // Mid glow
        g2.color = Color(0x00, 0xFF, 0x80, if (isDragging) 140 else 100)
        g2.stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(pxI, pyI, pxO, pyO))

        // Core line (bright)
        g2.color = Color(0x00, 0xFF, 0x80)
        g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(pxI, pyI, pxO, pyO))

        // ===== 10. CENTER CAP (domed, metallic) =====
        val capRadius = bodyRadius * 0.22f
        // Cap base
        val capGrad = RadialGradientPaint(
            cx - capRadius * 0.3f, cy - capRadius * 0.4f, capRadius * 1.8f,
            floatArrayOf(0.0f, 0.35f, 0.7f, 1.0f),
            arrayOf(
                Color(0xBB, 0xBB, 0xCC),
                Color(0x88, 0x88, 0x9C),
                Color(0x55, 0x55, 0x68),
                Color(0x35, 0x35, 0x48)
            )
        )
        g2.paint = capGrad
        g2.fill(Ellipse2D.Float(cx - capRadius, cy - capRadius, capRadius * 2, capRadius * 2))
        // Cap edge
        g2.color = Color(0x44, 0x44, 0x58, 180)
        g2.stroke = BasicStroke(1.2f)
        g2.draw(Ellipse2D.Float(cx - capRadius + 0.5f, cy - capRadius + 0.5f,
            (capRadius - 0.5f) * 2, (capRadius - 0.5f) * 2))
        // Cap specular dot
        val dotR = capRadius * 0.25f
        g2.color = Color(0xFF, 0xFF, 0xFF, 60)
        g2.fill(Ellipse2D.Float(cx - dotR - capRadius * 0.15f, cy - dotR - capRadius * 0.2f,
            dotR * 2, dotR * 2))
    }
}
