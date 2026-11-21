/*
 * Copyright (C) 2025 The AxionAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.edgelight

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.android.systemui.res.R
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

class EdgeLightView(context: Context) : FrameLayout(context) {

    var userStrokeWidth: Int = 8
        set(value) {
            field = value.coerceIn(2, 32)
            edgePaint.strokeWidth = field * resources.displayMetrics.density
            invalidate()
        }

    var userSpread: Float = EDGE_LIGHT_DEFAULT_SPREAD
        set(value) {
            field = value.coerceIn(0.05f, 1f)
            invalidate()
        }

    var userIntensity: Float = EDGE_LIGHT_DEFAULT_INTENSITY
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var edgeStyle: String = STYLE_DEFAULT
        set(value) {
            field = value
            if (useRainbowGradient) updateRainbowGradient()
            invalidate()
        }

    var animationEffect: String = EFFECT_NONE

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val effectPath = Path()
    private val pathMeasure = PathMeasure()
    private val shaderMatrix = Matrix()
    private val rainbowMatrix = Matrix()
    private val posScratch = FloatArray(2)
    private val tanScratch = FloatArray(2)

    private val glowStops = FloatArray(5)
    private val glowRevStops = FloatArray(5)
    private val glowForwardColors = IntArray(5)
    private val glowReverseColors = IntArray(5)

    private var cachedGlowLeft: LinearGradient? = null
    private var cachedGlowRight: LinearGradient? = null
    private var cachedGlowTop: LinearGradient? = null
    private var cachedGlowBottom: LinearGradient? = null
    private var cachedGlowW = -1
    private var cachedGlowH = -1
    private var cachedGlowColor = 0
    private var cachedGlowSpread = -1f
    private var cachedGlowIntensity = -1f
    private var cachedGlowStyle = ""

    private var cachedRainbowSweep: SweepGradient? = null
    private var cachedRainbowLinear: LinearGradient? = null
    private var cachedRainbowW = -1
    private var cachedRainbowH = -1

    private var cachedChaseGradient: LinearGradient? = null
    private var cachedChaseH = -1
    private var cachedChaseColor = 0

    private var cachedCometGradient: LinearGradient? = null
    private var cachedCometH = -1
    private var cachedCometColor = 0

    private val updateRainbowRunnable = Runnable { _updateRainbowGradient() }

    private val totalPulseDuration =
        resources.getInteger(R.integer.doze_pulse_duration_visible).toLong()

    var userPulseCount: Int = 3
        set(value) {
            field = value.coerceIn(1, 5)
        }

    private val fadeFraction = 0.2f
    private val MIN_SEGMENTS = 3

    private var pulseAnimator: ValueAnimator? = null
    private var rainbowAnimator: ValueAnimator? = null
    private var effectAnimator: ValueAnimator? = null
    private var rainbowRotation: Float = 0f
    private var effectProgress: Float = 0f

    private val sparkles = mutableListOf<Sparkle>()

    private val roundedPath = Path()
    private val roundedRect = RectF()
    private var cornerRadius: Float = 0f
    private var pathLength: Float = 0f

    private var useRainbowGradient = false

    private var glowBaseColor: Int = Color.WHITE

    var visible: Boolean
        get() = isVisible
        set(value) { isVisible = value }

    var paintColor: Int
        get() = edgePaint.color
        set(value) {
            if (value != COLOR_RAINBOW) {
                useRainbowGradient = false
                edgePaint.shader = null
                edgePaint.color = value
                edgePaint.alpha = 255
                glowBaseColor = resolveGlowBase(value)
            } else {
                useRainbowGradient = true
                glowBaseColor = RAINBOW[0]
                updateRainbowGradient()
            }
            invalidate()
        }

    var pulseRunning: Boolean
        get() = pulseAnimator?.isRunning == true
        set(value) {
            if (value && !pulseRunning) {
                startPulse()
                startEffectAnimation()
                startRainbowAnimation()
            } else if (!value) {
                stopRainbowAnimation()
                stopEffectAnimation()
                stopPulse()
                visible = false
            }
        }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        visible = false

        cornerRadius = try {
            resources.getDimension(
                resources.getIdentifier("rounded_corner_radius", "dimen", "android")
            )
        } catch (e: Exception) {
            32f * resources.displayMetrics.density
        }

        edgePaint.strokeWidth = userStrokeWidth * resources.displayMetrics.density
    }

    private fun resolveGlowBase(color: Int): Int {
        if (color == Color.TRANSPARENT || color == 0) return Color.WHITE
        val opaque = color or 0xFF000000.toInt()
        return if (opaque == Color.BLACK) Color.WHITE else opaque
    }

    private fun startPulse() {
        visible = true
        alpha = 0f

        val totalSegments = maxOf(userPulseCount, MIN_SEGMENTS)
        val active = BooleanArray(totalSegments)

        for (i in 0 until userPulseCount) {
            val idx = (((i + 0.5f) * totalSegments) / userPulseCount).toInt()
                .coerceIn(0, totalSegments - 1)
            active[idx] = true
        }

        pulseAnimator = ValueAnimator.ofFloat(0f, totalSegments.toFloat()).apply {
            duration = totalPulseDuration
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val segmentIndex = progress.toInt().coerceIn(0, totalSegments - 1)
                val fraction = progress - segmentIndex

                if (active[segmentIndex]) {
                    alpha = when {
                        fraction < fadeFraction -> fraction / fadeFraction
                        fraction > 1f - fadeFraction -> (1f - fraction) / fadeFraction
                        else -> 1f
                    }
                } else {
                    alpha = 0f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pulseAnimator = null
                    stopRainbowAnimation()
                    stopEffectAnimation()
                    visible = false
                }

                override fun onAnimationCancel(animation: Animator) {
                    pulseAnimator = null
                    stopRainbowAnimation()
                    stopEffectAnimation()
                    visible = false
                }
            })
            repeatCount = 0
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(updateRainbowRunnable)
        stopRainbowAnimation()
        stopEffectAnimation()
        stopPulse()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        when (edgeStyle) {
            STYLE_ROUNDED -> {
                drawRoundedEdges(canvas)
                drawGlowRounded(canvas)
            }
            else -> {
                drawDefaultEdges(canvas)
                drawGlowDefault(canvas)
            }
        }
    }

    private fun colorWithAlpha(baseColor: Int, alphaFloat: Float): Int {
        val a = (alphaFloat * 255f).toInt().coerceIn(0, 255)
        return (baseColor and 0x00FFFFFF) or (a shl 24)
    }

    private fun currentGlowAlpha(): Float = alpha.coerceIn(0f, 1f)

    private fun rainbowGlowColor(): Int {
        val index = ((rainbowRotation / 360f) * (RAINBOW.size - 1)).toInt()
            .coerceIn(0, RAINBOW.size - 2)
        return RAINBOW[index]
    }

    private fun effectiveGlowBaseColor(): Int =
        if (useRainbowGradient) rainbowGlowColor() else glowBaseColor

    private fun ensureGlowShaders() {
        val base = effectiveGlowBaseColor() and 0x00FFFFFF
        if (cachedGlowW == width &&
            cachedGlowH == height &&
            cachedGlowColor == base &&
            cachedGlowSpread == userSpread &&
            cachedGlowIntensity == userIntensity &&
            cachedGlowStyle == edgeStyle
        ) {
            return
        }
        cachedGlowW = width
        cachedGlowH = height
        cachedGlowColor = base
        cachedGlowSpread = userSpread
        cachedGlowIntensity = userIntensity
        cachedGlowStyle = edgeStyle

        val a = userIntensity.coerceIn(0f, 1f)
        val s = userSpread.coerceIn(0.05f, 1f)
        glowStops[0] = 0f
        glowStops[1] = s * 0.10f
        glowStops[2] = s * 0.30f
        glowStops[3] = s * 0.60f
        glowStops[4] = s
        for (i in 0..4) {
            glowRevStops[i] = 1f - glowStops[4 - i]
        }

        val w0 = a
        val w1 = a * 0.65f
        val w2 = a * 0.28f
        val w3 = a * 0.08f
        val w4 = 0f
        glowForwardColors[0] = colorWithAlpha(base, w0)
        glowForwardColors[1] = colorWithAlpha(base, w1)
        glowForwardColors[2] = colorWithAlpha(base, w2)
        glowForwardColors[3] = colorWithAlpha(base, w3)
        glowForwardColors[4] = colorWithAlpha(base, w4)
        for (i in 0..4) {
            glowReverseColors[i] = glowForwardColors[4 - i]
        }

        val spreadPxH = (width * userSpread).coerceAtLeast(1f)
        cachedGlowLeft = LinearGradient(
            0f, 0f, spreadPxH, 0f,
            glowForwardColors, glowStops, Shader.TileMode.CLAMP
        )
        cachedGlowRight = LinearGradient(
            width - spreadPxH, 0f, width.toFloat(), 0f,
            glowReverseColors, glowRevStops, Shader.TileMode.CLAMP
        )
        if (edgeStyle == STYLE_ROUNDED) {
            val spreadPxV = (height * userSpread).coerceAtLeast(1f)
            cachedGlowTop = LinearGradient(
                0f, 0f, 0f, spreadPxV,
                glowForwardColors, glowStops, Shader.TileMode.CLAMP
            )
            cachedGlowBottom = LinearGradient(
                0f, height - spreadPxV, 0f, height.toFloat(),
                glowReverseColors, glowRevStops, Shader.TileMode.CLAMP
            )
        } else {
            cachedGlowTop = null
            cachedGlowBottom = null
        }
    }

    private fun drawGlowDefault(canvas: Canvas) {
        if (userIntensity == 0f) return
        ensureGlowShaders()
        val spreadPx = width * userSpread
        glowPaint.alpha = (currentGlowAlpha() * 255f).toInt().coerceIn(0, 255)

        glowPaint.shader = cachedGlowLeft
        canvas.drawRect(0f, 0f, spreadPx, height.toFloat(), glowPaint)

        glowPaint.shader = cachedGlowRight
        canvas.drawRect(width - spreadPx, 0f, width.toFloat(), height.toFloat(), glowPaint)
    }

    private fun drawGlowRounded(canvas: Canvas) {
        if (userIntensity == 0f) return
        ensureGlowShaders()
        val spreadPxH = width * userSpread
        val spreadPxV = height * userSpread
        glowPaint.alpha = (currentGlowAlpha() * 255f).toInt().coerceIn(0, 255)

        glowPaint.shader = cachedGlowLeft
        canvas.drawRect(0f, 0f, spreadPxH, height.toFloat(), glowPaint)

        glowPaint.shader = cachedGlowRight
        canvas.drawRect(width - spreadPxH, 0f, width.toFloat(), height.toFloat(), glowPaint)

        glowPaint.shader = cachedGlowTop
        canvas.drawRect(0f, 0f, width.toFloat(), spreadPxV, glowPaint)

        glowPaint.shader = cachedGlowBottom
        canvas.drawRect(0f, height - spreadPxV, width.toFloat(), height.toFloat(), glowPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (useRainbowGradient) {
            updateRainbowGradient()
            invalidate()
        }
    }

    private fun drawDefaultEdges(canvas: Canvas) {
        edgePaint.strokeWidth = userStrokeWidth * resources.displayMetrics.density
        edgePaint.strokeCap = Paint.Cap.BUTT

        when (animationEffect) {
            EFFECT_BREATHING -> {
                applyBreathingEffect()
                val halfStroke = edgePaint.strokeWidth / 2
                canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), edgePaint)
                canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), edgePaint)
            }
            EFFECT_WAVE -> drawWaveEffect(canvas)
            EFFECT_SPARKLE -> drawSparkleEffect(canvas)
            EFFECT_CHASE -> drawChaseEffect(canvas)
            EFFECT_COMET -> drawCometEffect(canvas)
            else -> {
                val halfStroke = edgePaint.strokeWidth / 2
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), edgePaint)
                canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), edgePaint)
            }
        }
    }

    private fun drawRoundedEdges(canvas: Canvas) {
        edgePaint.strokeWidth = userStrokeWidth * resources.displayMetrics.density
        val halfStroke = edgePaint.strokeWidth / 2
        edgePaint.strokeCap = Paint.Cap.ROUND

        roundedRect.set(
            halfStroke,
            halfStroke,
            width.toFloat() - halfStroke,
            height.toFloat() - halfStroke
        )

        roundedPath.reset()
        roundedPath.addRoundRect(
            roundedRect,
            cornerRadius,
            cornerRadius,
            Path.Direction.CW
        )

        when (animationEffect) {
            EFFECT_BREATHING -> {
                applyBreathingEffect()
                canvas.drawPath(roundedPath, edgePaint)
            }
            EFFECT_WAVE -> drawWaveEffectRounded(canvas)
            EFFECT_SPARKLE -> drawSparkleEffectRounded(canvas)
            EFFECT_CHASE -> drawChaseEffectRounded(canvas)
            EFFECT_COMET -> drawCometEffectRounded(canvas)
            else -> {
                edgePaint.alpha = 255
                edgePaint.maskFilter = null
                canvas.drawPath(roundedPath, edgePaint)
            }
        }
    }

    private fun applyBreathingEffect() {
        val cycle = sin(effectProgress * 2 * PI).toFloat()
        val normalizedCycle = (cycle + 1f) / 2f

        edgePaint.alpha = (155 + (normalizedCycle * 100)).toInt().coerceIn(0, 255)
        edgePaint.strokeWidth = (userStrokeWidth * resources.displayMetrics.density) * (0.7f + normalizedCycle * 0.6f)
        edgePaint.maskFilter = null
    }

    private fun drawWaveEffect(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        val segments = 50
        val waveHeight = width * 0.05f

        edgePaint.alpha = 255
        edgePaint.maskFilter = null

        for (i in 0 until segments) {
            val y1 = (i.toFloat() / segments) * height
            val y2 = ((i + 1).toFloat() / segments) * height
            val offset1 = sin((effectProgress * 2 * PI + (i.toFloat() / segments) * 2 * PI)).toFloat() * waveHeight
            val offset2 = sin((effectProgress * 2 * PI + ((i + 1).toFloat() / segments) * 2 * PI)).toFloat() * waveHeight

            canvas.drawLine(halfStroke + offset1, y1, halfStroke + offset2, y2, edgePaint)
        }

        for (i in 0 until segments) {
            val y1 = (i.toFloat() / segments) * height
            val y2 = ((i + 1).toFloat() / segments) * height
            val offset1 = sin((effectProgress * 2 * PI + (i.toFloat() / segments) * 2 * PI)).toFloat() * waveHeight
            val offset2 = sin((effectProgress * 2 * PI + ((i + 1).toFloat() / segments) * 2 * PI)).toFloat() * waveHeight

            canvas.drawLine(width - halfStroke + offset1, y1, width - halfStroke + offset2, y2, edgePaint)
        }
    }

    private fun drawWaveEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 255
        edgePaint.maskFilter = null

        pathMeasure.setPath(roundedPath, false)
        pathLength = pathMeasure.length
        val segments = 100
        val waveAmplitude = edgePaint.strokeWidth * 0.5f

        effectPath.reset()
        for (i in 0..segments) {
            val distance = (i.toFloat() / segments) * pathLength
            pathMeasure.getPosTan(distance, posScratch, tanScratch)

            val waveOffset = sin((effectProgress * 2 * PI + (i.toFloat() / segments) * 4 * PI)).toFloat() * waveAmplitude
            val normalX = -tanScratch[1]
            val normalY = tanScratch[0]

            val x = posScratch[0] + normalX * waveOffset
            val y = posScratch[1] + normalY * waveOffset

            if (i == 0) effectPath.moveTo(x, y) else effectPath.lineTo(x, y)
        }

        canvas.drawPath(effectPath, edgePaint)
    }

    private fun pruneExpiredSparkles() {
        for (i in sparkles.size - 1 downTo 0) {
            if (sparkles[i].lifetime <= 0f) {
                sparkles.removeAt(i)
            }
        }
    }

    private fun drawSparkleEffect(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        edgePaint.alpha = 100
        edgePaint.maskFilter = null

        canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), edgePaint)
        canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), edgePaint)

        pruneExpiredSparkles()

        if (sparkles.size < 15 && Random.nextFloat() < 0.3f) {
            val isLeft = Random.nextBoolean()
            sparkles.add(
                Sparkle(
                    x = if (isLeft) halfStroke else width - halfStroke,
                    y = Random.nextFloat() * height,
                    lifetime = 1f,
                    maxSize = edgePaint.strokeWidth * (2f + Random.nextFloat() * 2f)
                )
            )
        }

        effectPaint.set(edgePaint)
        effectPaint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until sparkles.size) {
            val sparkle = sparkles[i]
            sparkle.lifetime -= 0.03f
            val alpha = (sparkle.lifetime * 255).toInt().coerceIn(0, 255)
            val size = sparkle.maxSize * sin(sparkle.lifetime * PI).toFloat()

            effectPaint.alpha = alpha
            effectPaint.strokeWidth = size
            canvas.drawPoint(sparkle.x, sparkle.y, effectPaint)
        }
    }

    private fun drawSparkleEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 100
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        pathMeasure.setPath(roundedPath, false)
        pathLength = pathMeasure.length

        pruneExpiredSparkles()

        if (sparkles.size < 20 && Random.nextFloat() < 0.3f) {
            val distance = Random.nextFloat() * pathLength
            pathMeasure.getPosTan(distance, posScratch, null)

            sparkles.add(
                Sparkle(
                    x = posScratch[0],
                    y = posScratch[1],
                    lifetime = 1f,
                    maxSize = edgePaint.strokeWidth * (2f + Random.nextFloat() * 2f)
                )
            )
        }

        effectPaint.set(edgePaint)
        effectPaint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until sparkles.size) {
            val sparkle = sparkles[i]
            sparkle.lifetime -= 0.03f
            val alpha = (sparkle.lifetime * 255).toInt().coerceIn(0, 255)
            val size = sparkle.maxSize * sin(sparkle.lifetime * PI).toFloat()

            effectPaint.alpha = alpha
            effectPaint.strokeWidth = size
            canvas.drawPoint(sparkle.x, sparkle.y, effectPaint)
        }
    }

    private fun ensureChaseGradient(segmentLength: Float): LinearGradient {
        val color = edgePaint.color
        val cached = cachedChaseGradient
        if (cached != null && cachedChaseH == height && cachedChaseColor == color) {
            return cached
        }
        val transparent = color and 0x00FFFFFF
        val gradient = LinearGradient(
            0f, -segmentLength,
            0f, segmentLength,
            intArrayOf(transparent, color, transparent),
            CHASE_STOPS,
            Shader.TileMode.CLAMP
        )
        cachedChaseGradient = gradient
        cachedChaseH = height
        cachedChaseColor = color
        return gradient
    }

    private fun drawChaseEffect(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        val segmentLength = (height * 0.15f).coerceAtLeast(1f)
        val numChasers = 3

        edgePaint.alpha = 50
        edgePaint.maskFilter = null
        canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), edgePaint)
        canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), edgePaint)

        val gradient = ensureChaseGradient(segmentLength)
        effectPaint.set(edgePaint)
        effectPaint.shader = gradient
        effectPaint.alpha = 255

        for (i in 0 until numChasers) {
            val offset = (effectProgress + i.toFloat() / numChasers) % 1f
            val centerY = offset * height

            shaderMatrix.setTranslate(0f, centerY)
            gradient.setLocalMatrix(shaderMatrix)

            val top = (centerY - segmentLength).coerceAtLeast(0f)
            val bottom = (centerY + segmentLength).coerceAtMost(height.toFloat())
            canvas.drawLine(halfStroke, top, halfStroke, bottom, effectPaint)
            canvas.drawLine(width - halfStroke, top, width - halfStroke, bottom, effectPaint)
        }
    }

    private fun drawChaseEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 50
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        pathMeasure.setPath(roundedPath, false)
        pathLength = pathMeasure.length
        val segmentLength = pathLength * 0.15f
        val numChasers = 3

        effectPaint.set(edgePaint)
        effectPaint.alpha = 255

        for (i in 0 until numChasers) {
            val offset = (effectProgress + i.toFloat() / numChasers) % 1f
            val centerDistance = offset * pathLength

            effectPath.reset()
            val startDist = (centerDistance - segmentLength).coerceAtLeast(0f)
            val endDist = (centerDistance + segmentLength).coerceAtMost(pathLength)

            pathMeasure.getSegment(startDist, endDist, effectPath, true)
            canvas.drawPath(effectPath, effectPaint)
        }
    }

    private fun ensureCometGradient(tailLength: Float): LinearGradient {
        val color = edgePaint.color
        val cached = cachedCometGradient
        if (cached != null && cachedCometH == height && cachedCometColor == color) {
            return cached
        }
        val transparent = color and 0x00FFFFFF
        val gradient = LinearGradient(
            0f, -tailLength,
            0f, 0f,
            intArrayOf(transparent, color),
            null,
            Shader.TileMode.CLAMP
        )
        cachedCometGradient = gradient
        cachedCometH = height
        cachedCometColor = color
        return gradient
    }

    private fun drawCometEffect(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        val tailLength = (height * 0.25f).coerceAtLeast(1f)

        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        canvas.drawLine(halfStroke, 0f, halfStroke, height.toFloat(), edgePaint)
        canvas.drawLine(width - halfStroke, 0f, width - halfStroke, height.toFloat(), edgePaint)

        val cometY = effectProgress * height
        val gradient = ensureCometGradient(tailLength)
        shaderMatrix.setTranslate(0f, cometY)
        gradient.setLocalMatrix(shaderMatrix)

        effectPaint.set(edgePaint)
        effectPaint.shader = gradient
        effectPaint.alpha = 255
        effectPaint.strokeWidth = edgePaint.strokeWidth * 1.5f

        val top = (cometY - tailLength).coerceAtLeast(0f)
        canvas.drawLine(halfStroke, top, halfStroke, cometY, effectPaint)
        canvas.drawLine(width - halfStroke, top, width - halfStroke, cometY, effectPaint)
    }

    private fun drawCometEffectRounded(canvas: Canvas) {
        edgePaint.alpha = 30
        edgePaint.maskFilter = null
        canvas.drawPath(roundedPath, edgePaint)

        pathMeasure.setPath(roundedPath, false)
        pathLength = pathMeasure.length
        val tailLength = pathLength * 0.2f
        val cometDistance = effectProgress * pathLength

        effectPath.reset()
        val startDist = (cometDistance - tailLength).coerceAtLeast(0f)
        pathMeasure.getSegment(startDist, cometDistance, effectPath, true)

        effectPaint.set(edgePaint)
        effectPaint.alpha = 255
        effectPaint.strokeWidth = edgePaint.strokeWidth * 1.5f
        canvas.drawPath(effectPath, effectPaint)
    }

    private fun updateRainbowGradient() {
        if (width == 0 || height == 0) {
            removeCallbacks(updateRainbowRunnable)
            post(updateRainbowRunnable)
        } else {
            _updateRainbowGradient()
        }
    }

    private fun _updateRainbowGradient() {
        if (width <= 0 || height <= 0) return
        if (cachedRainbowW != width || cachedRainbowH != height) {
            cachedRainbowW = width
            cachedRainbowH = height
            cachedRainbowSweep = SweepGradient(width / 2f, height / 2f, RAINBOW, null)
            cachedRainbowLinear = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                RAINBOW, null, Shader.TileMode.REPEAT
            )
        }
        edgePaint.shader = when (edgeStyle) {
            STYLE_ROUNDED -> {
                rainbowMatrix.setRotate(rainbowRotation, width / 2f, height / 2f)
                cachedRainbowSweep?.also { it.setLocalMatrix(rainbowMatrix) }
            }
            else -> {
                val offset = (rainbowRotation / 360f) * height
                rainbowMatrix.setTranslate(0f, -offset)
                cachedRainbowLinear?.also { it.setLocalMatrix(rainbowMatrix) }
            }
        }
    }

    private fun startRainbowAnimation() {
        if (!useRainbowGradient || edgePaint.shader == null) return
        if (animationEffect in MOVING_EFFECT) return
        if (rainbowAnimator?.isRunning == true) return

        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = totalPulseDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                rainbowRotation = animator.animatedValue as Float
                glowBaseColor = rainbowGlowColor()
                _updateRainbowGradient()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rainbowAnimator = null
                    rainbowRotation = 0f
                }

                override fun onAnimationCancel(animation: Animator) {
                    rainbowAnimator = null
                    rainbowRotation = 0f
                }
            })
            start()
        }
    }

    private fun stopRainbowAnimation() {
        rainbowAnimator?.cancel()
        rainbowAnimator = null
        rainbowRotation = 0f
    }

    private fun startEffectAnimation() {
        if (animationEffect == EFFECT_NONE) return
        if (effectAnimator?.isRunning == true) return

        val duration = when (animationEffect) {
            EFFECT_BREATHING -> 3000L
            EFFECT_WAVE -> 2000L
            EFFECT_SPARKLE -> 100L
            EFFECT_CHASE -> 2500L
            EFFECT_COMET -> 2000L
            else -> 2000L
        }

        effectAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                effectProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    effectAnimator = null
                    effectProgress = 0f
                    sparkles.clear()
                }

                override fun onAnimationCancel(animation: Animator) {
                    effectAnimator = null
                    effectProgress = 0f
                    sparkles.clear()
                }
            })
            start()
        }
    }

    private fun stopEffectAnimation() {
        effectAnimator?.cancel()
        effectAnimator = null
        effectProgress = 0f
        sparkles.clear()
    }

    private data class Sparkle(
        var x: Float,
        var y: Float,
        var lifetime: Float,
        val maxSize: Float
    )

    companion object {
        const val STYLE_DEFAULT = "default"
        const val STYLE_ROUNDED = "rounded"
        const val COLOR_RAINBOW = -1
        const val EFFECT_NONE = "none"
        const val EFFECT_BREATHING = "breathing"
        const val EFFECT_WAVE = "wave"
        const val EFFECT_SPARKLE = "sparkle"
        const val EFFECT_CHASE = "chase"
        const val EFFECT_COMET = "comet"
        private val MOVING_EFFECT = arrayOf(EFFECT_WAVE, EFFECT_SPARKLE, EFFECT_CHASE, EFFECT_COMET)
        private val CHASE_STOPS = floatArrayOf(0f, 0.5f, 1f)
        private val RAINBOW = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(),
            0xFF9400D3.toInt(), 0xFFFF0000.toInt()
        )
    }
}
