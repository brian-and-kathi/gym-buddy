package com.gymbuddy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object ProgressPieRenderer {
    private val outlineColor = Color.parseColor("#BDBDBD")
    private val backgroundColor = Color.parseColor("#2A2A2A")
    private val completedFill = Color.parseColor("#4CBB17")
    private val segmentDone = Color.parseColor("#4CBB17")
    private val segmentTodo = Color.parseColor("#424242")
    private val checkColor = Color.parseColor("#2A2A2A")
    private const val HOLE_RADIUS_FRACTION = 0.36f

    fun drawPie(
        canvas: Canvas,
        width: Float,
        height: Float,
        completedSets: Int,
        totalSets: Int,
        segmented: Boolean,
        outlineEnabled: Boolean,
        outlineStroke: Float = 8f,
        segmentDividers: Boolean = false
    ) {
        val completed = completedSets
        val total = totalSets.coerceAtLeast(1)
        val centerX = width / 2f
        val centerY = height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = outlineStroke
        }
        val radius = min(centerX, centerY) - outlineStroke / 2f

        val cutOutCenter = segmented && segmentDividers
        if (!cutOutCenter) {
            paint.color = backgroundColor
            paint.style = Paint.Style.FILL
            canvas.drawCircle(centerX, centerY, radius, paint)
        }

        if (outlineEnabled) {
            paint.color = outlineColor
            paint.style = Paint.Style.STROKE
            canvas.drawCircle(centerX, centerY, radius, paint)
        }

        if (cutOutCenter) {
            drawSegmentRing(
                canvas = canvas,
                centerX = centerX,
                centerY = centerY,
                radius = radius,
                outlineStroke = outlineStroke,
                completed = completed,
                total = total,
                drawDividers = true
            )
            if (completed >= total) {
                val holeRadius = radius * HOLE_RADIUS_FRACTION
                drawCheck(
                    canvas = canvas,
                    centerX = centerX,
                    centerY = centerY,
                    markColor = segmentDone,
                    checkSize = holeRadius * 1.86f,
                    markStroke = holeRadius * 0.22f
                )
            }
        } else if (completed >= total) {
            paint.color = completedFill
            paint.style = Paint.Style.FILL
            canvas.drawCircle(centerX, centerY, radius - outlineStroke, paint)

            drawCheck(
                canvas = canvas,
                centerX = centerX,
                centerY = centerY,
                markColor = checkColor,
                checkSize = radius * 0.6f,
                markStroke = radius * 0.06f
            )
        } else if (segmented) {
            paint.style = Paint.Style.FILL
            val anglePerSegment = 360f / total
            for (i in 0 until total) {
                val startAngle = -90f + i * anglePerSegment
                paint.color = if (i < completed) segmentDone else segmentTodo
                canvas.drawArc(
                    centerX - radius + outlineStroke,
                    centerY - radius + outlineStroke,
                    centerX + radius - outlineStroke,
                    centerY + radius - outlineStroke,
                    startAngle,
                    anglePerSegment,
                    true,
                    paint
                )
            }
        } else {
            val progress = completed.toFloat() / total
            if (progress > 0f) {
                paint.color = completedFill
                paint.style = Paint.Style.FILL
                canvas.drawArc(
                    centerX - radius + outlineStroke,
                    centerY - radius + outlineStroke,
                    centerX + radius - outlineStroke,
                    centerY + radius - outlineStroke,
                    -90f,
                    progress * 360f,
                    true,
                    paint
                )
            }
        }
    }

    fun createWidgetBitmap(
        sizePx: Int,
        completedSets: Int,
        totalSets: Int
    ): Bitmap {
        val size = sizePx.coerceAtLeast(64)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawPie(
            canvas = canvas,
            width = size.toFloat(),
            height = size.toFloat(),
            completedSets = completedSets,
            totalSets = totalSets,
            segmented = true,
            outlineEnabled = true
        )
        return bitmap
    }

    private fun drawSegmentRing(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        outlineStroke: Float,
        completed: Int,
        total: Int,
        drawDividers: Boolean
    ) {
        val outer = radius - outlineStroke
        val inner = radius * HOLE_RADIUS_FRACTION
        if (outer <= inner) {
            return
        }
        val stroke = outer - inner
        val mid = inner + stroke / 2f
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
        }
        val anglePerSegment = 360f / total
        if (total == 1) {
            ringPaint.color = if (completed >= 1) segmentDone else segmentTodo
            canvas.drawCircle(centerX, centerY, mid, ringPaint)
        } else {
            for (i in 0 until total) {
                ringPaint.color = if (i < completed) segmentDone else segmentTodo
                canvas.drawArc(
                    centerX - mid,
                    centerY - mid,
                    centerX + mid,
                    centerY + mid,
                    -90f + i * anglePerSegment,
                    anglePerSegment,
                    false,
                    ringPaint
                )
            }
        }
        if (drawDividers && total > 1) {
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = segmentTodo
                style = Paint.Style.STROKE
                strokeWidth = (radius * 0.02f).coerceIn(2.5f, 5f)
                strokeCap = Paint.Cap.BUTT
            }
            for (i in 0 until total) {
                val angle = Math.toRadians(-90.0 + i * anglePerSegment)
                val cosAngle = cos(angle).toFloat()
                val sinAngle = sin(angle).toFloat()
                canvas.drawLine(
                    centerX + inner * cosAngle,
                    centerY + inner * sinAngle,
                    centerX + outer * cosAngle,
                    centerY + outer * sinAngle,
                    dividerPaint
                )
            }
        }
        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = segmentTodo
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, inner + 1f, holePaint)
    }

    private fun drawCheck(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        markColor: Int,
        checkSize: Float,
        markStroke: Float
    ) {
        val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = markColor
            strokeCap = Paint.Cap.ROUND
            strokeWidth = markStroke
        }
        val checkPath = Path()
        checkPath.moveTo(centerX - checkSize * 0.3f, centerY)
        checkPath.lineTo(centerX - checkSize * 0.1f, centerY + checkSize * 0.2f)
        checkPath.lineTo(centerX + checkSize * 0.3f, centerY - checkSize * 0.2f)
        canvas.drawPath(checkPath, checkPaint)
    }
}