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
 * Realistic 3D volume knob — freepik-style dark metallic design.
 *
 * Visual style: dark glossy body, chrome beveled ring, smooth surface,
 * bright white indicator line, scale dots around the edge, strong
 * specular highlight on top-left simulating studio lighting.
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

    private var angle = valueToAngle(initialValue)
    private val minAngle = -2.4
    private val maxAngle = 2.4

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
        val outerR = size / 2.0f - 8

        // ========== 1. DROP SHADOW (soft, offset down-right) ==========
        for (s in 5 downTo 1) {
            val a = (20 - s * 3).coerceAtLeast(2)
            g2.color = Color(0, 0, 0, a)
            g2.fill(Ellipse2D.Float(
                cx - outerR - s + 2, cy - outerR - s + 4,
                (outerR + s) * 2, (outerR + s) * 2))
        }

        // ========== 2. OUTER CHROME RING (beveled edge) ==========
        val chromeGrad = RadialGradientPaint(
            cx - outerR * 0.25f, cy - outerR * 0.3f, outerR * 2.0f,
            floatArrayOf(0.0f, 0.3f, 0.5f, 0.7f, 1.0f),
            arrayOf(
                Color(0xE0, 0xE0, 0xE8),  // bright chrome highlight
                Color(0x9A, 0x9A, 0xA8),  // mid chrome
                Color(0x60, 0x60, 0x70),  // dark chrome
                Color(0x40, 0x40, 0x4C),  // shadow
                Color(0x28, 0x28, 0x32)   // deep shadow
            )
        )
        g2.paint = chromeGrad
        g2.fill(Ellipse2D.Float(cx - outerR, cy - outerR, outerR * 2, outerR * 2))

        // Chrome ring highlight arc (top edge catch light)
        g2.stroke = BasicStroke(1.5f)
        g2.color = Color(255, 255, 255, 60)
        g2.drawArc(
            (cx - outerR + 2).toInt(), (cy - outerR + 2).toInt(),
            ((outerR - 2) * 2).toInt(), ((outerR - 2) * 2).toInt(),
            30, 120)

        // ========== 3. SCALE DOTS around the outside ==========
        val dotRadius = outerR + 5
        val numDots = 31
        for (i in 0 until numDots) {
            val frac = i.toDouble() / (numDots - 1)
            val dotAngle = minAngle + frac * (maxAngle - minAngle)
            val dx = cx + dotRadius * sin(dotAngle).toFloat()
            val dy = cy - dotRadius * cos(dotAngle).toFloat()
            val isMajor = i % 5 == 0
            val dotSize = if (isMajor) 2.5f else 1.5f
            g2.color = if (isMajor) Color(0xCC, 0xCC, 0xDD) else Color(0x77, 0x77, 0x88)
            g2.fill(Ellipse2D.Float(dx - dotSize / 2, dy - dotSize / 2, dotSize, dotSize))
        }

        // ========== 4. KNOB BODY (dark glossy, like black anodized aluminum) ==========
        val bodyR = outerR - 5
        // Base: deep dark gradient
        val bodyGrad = RadialGradientPaint(
            cx - bodyR * 0.3f, cy - bodyR * 0.35f, bodyR * 2.2f,
            floatArrayOf(0.0f, 0.2f, 0.5f, 0.8f, 1.0f),
            arrayOf(
                Color(0x55, 0x55, 0x62),  // top-left highlight area
                Color(0x3A, 0x3A, 0x44),  // mid-light
                Color(0x28, 0x28, 0x30),  // main body (very dark)
                Color(0x1A, 0x1A, 0x22),  // dark side
                Color(0x12, 0x12, 0x18)   // bottom-right shadow
            )
        )
        g2.paint = bodyGrad
        g2.fill(Ellipse2D.Float(cx - bodyR, cy - bodyR, bodyR * 2, bodyR * 2))

        // ========== 5. CONCENTRIC GROOVES (subtle machined texture) ==========
        g2.stroke = BasicStroke(0.3f)
        var r = bodyR * 0.35f
        while (r < bodyR - 4) {
            g2.color = Color(255, 255, 255, 6)
            g2.draw(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
            r += 1.8f
            g2.color = Color(0, 0, 0, 12)
            g2.draw(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
            r += 1.8f
        }

        // ========== 6. TOP-LEFT SPECULAR HIGHLIGHT (glossy dome effect) ==========
        val specCx = cx - bodyR * 0.28f
        val specCy = cy - bodyR * 0.32f
        val specGrad = RadialGradientPaint(
            specCx, specCy, bodyR * 0.7f,
            floatArrayOf(0.0f, 0.3f, 0.7f, 1.0f),
            arrayOf(
                Color(255, 255, 255, 65),
                Color(255, 255, 255, 25),
                Color(255, 255, 255, 5),
                Color(0, 0, 0, 0)
            )
        )
        g2.paint = specGrad
        g2.fill(Ellipse2D.Float(cx - bodyR, cy - bodyR, bodyR * 2, bodyR * 2))

        // Secondary highlight — small bright dot (studio light reflection)
        val dotGrad = RadialGradientPaint(
            cx - bodyR * 0.2f, cy - bodyR * 0.25f, bodyR * 0.2f,
            floatArrayOf(0.0f, 0.5f, 1.0f),
            arrayOf(Color(255, 255, 255, 90), Color(255, 255, 255, 20), Color(0, 0, 0, 0))
        )
        g2.paint = dotGrad
        g2.fill(Ellipse2D.Float(cx - bodyR, cy - bodyR, bodyR * 2, bodyR * 2))

        // ========== 7. INNER CHROME RING (separation between body and cap) ==========
        val innerRingR = bodyR * 0.45f
        g2.color = Color(0x60, 0x60, 0x70, 120)
        g2.stroke = BasicStroke(1.2f)
        g2.draw(Ellipse2D.Float(cx - innerRingR, cy - innerRingR, innerRingR * 2, innerRingR * 2))
        // Light edge on top
        g2.color = Color(255, 255, 255, 30)
        g2.drawArc(
            (cx - innerRingR + 1).toInt(), (cy - innerRingR + 1).toInt(),
            ((innerRingR - 1) * 2).toInt(), ((innerRingR - 1) * 2).toInt(),
            30, 120)

        // ========== 8. INDICATOR LINE (bright white with subtle glow) ==========
        val pointerStart = bodyR * 0.5f
        val pointerEnd = bodyR - 3
        val pxS = cx + pointerStart * sin(angle).toFloat()
        val pyS = cy - pointerStart * cos(angle).toFloat()
        val pxE = cx + pointerEnd * sin(angle).toFloat()
        val pyE = cy - pointerEnd * cos(angle).toFloat()

        // Glow layer
        g2.color = Color(255, 255, 255, if (isDragging) 50 else 30)
        g2.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(pxS, pyS, pxE, pyE))

        // Mid layer
        g2.color = Color(255, 255, 255, if (isDragging) 160 else 120)
        g2.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(pxS, pyS, pxE, pyE))

        // Core (bright white line)
        g2.color = Color(255, 255, 255, if (isDragging) 240 else 210)
        g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(Line2D.Float(pxS, pyS, pxE, pyE))

        // ========== 9. CENTER CAP (domed metallic, slightly raised) ==========
        val capR = bodyR * 0.2f
        // Cap shadow ring
        g2.color = Color(0, 0, 0, 40)
        g2.fill(Ellipse2D.Float(cx - capR - 1, cy - capR + 1, (capR + 1) * 2, (capR + 1) * 2))

        // Cap body
        val capGrad = RadialGradientPaint(
            cx - capR * 0.25f, cy - capR * 0.3f, capR * 2.0f,
            floatArrayOf(0.0f, 0.3f, 0.65f, 1.0f),
            arrayOf(
                Color(0x80, 0x80, 0x90),  // highlight
                Color(0x50, 0x50, 0x5C),  // mid
                Color(0x30, 0x30, 0x3A),  // dark
                Color(0x20, 0x20, 0x28)   // edge
            )
        )
        g2.paint = capGrad
        g2.fill(Ellipse2D.Float(cx - capR, cy - capR, capR * 2, capR * 2))

        // Cap chrome edge
        g2.color = Color(0x55, 0x55, 0x65, 150)
        g2.stroke = BasicStroke(0.8f)
        g2.draw(Ellipse2D.Float(cx - capR, cy - capR, capR * 2, capR * 2))

        // Cap specular dot
        val specR = capR * 0.3f
        g2.color = Color(255, 255, 255, 50)
        g2.fill(Ellipse2D.Float(
            cx - specR - capR * 0.12f, cy - specR - capR * 0.15f,
            specR * 2, specR * 2))

        // ========== 10. OUTER EDGE HIGHLIGHT (bottom rim light) ==========
        g2.color = Color(255, 255, 255, 15)
        g2.stroke = BasicStroke(0.8f)
        g2.drawArc(
            (cx - bodyR + 1).toInt(), (cy - bodyR + 1).toInt(),
            ((bodyR - 1) * 2).toInt(), ((bodyR - 1) * 2).toInt(),
            200, 140)
    }
}
