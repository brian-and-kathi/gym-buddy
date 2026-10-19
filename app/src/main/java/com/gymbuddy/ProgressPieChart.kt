package com.gymbuddy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.min

class ProgressPieChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var onClickListener: (() -> Unit)? = null
    private var onCompletionAnimationFinished: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B6B")
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.seven_segment)
    }
    private var centerCountdown: String? = null
    private var centerCountdownVisible = true
    private var completed = 0
    private var total = 1
    private var isSegmented = false
    private var showSegmentDividers = false
    private var isHighlighted = false
    private var isOutlineEnabled = true
    private var isAnimating = false
    private var isSwelled = false

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.color = Color.parseColor("#BDBDBD") // lighter gray outline

        checkPaint.style = Paint.Style.STROKE
        checkPaint.strokeWidth = 6f
        checkPaint.color = Color.parseColor("#2A2A2A") // dark gray
        checkPaint.strokeCap = Paint.Cap.ROUND
    }

    fun setProgress(completedSets: Int, totalSets: Int) {
        completed = completedSets
        total = totalSets.coerceAtLeast(1)
        invalidate()
    }

    /** Rest countdown drawn in the donut hole. Null clears it. A finished pie keeps its check. */
    fun setCenterCountdown(text: String?) {
        centerCountdown = text
        centerCountdownVisible = true
        invalidate()
    }

    fun setCenterCountdownVisible(visible: Boolean) {
        centerCountdownVisible = visible
        invalidate()
    }

    fun setSegmented(segmented: Boolean) {
        isSegmented = segmented
        invalidate()
    }

    fun setSegmentDividers(enabled: Boolean) {
        showSegmentDividers = enabled
        invalidate()
    }

    fun setHighlighted(highlighted: Boolean) {
        isHighlighted = highlighted
        isOutlineEnabled = highlighted
        invalidate()
    }

    fun setOnClickListener(listener: () -> Unit) {
        onClickListener = listener
    }

    fun setOnCompletionAnimationFinished(listener: () -> Unit) {
        onCompletionAnimationFinished = listener
    }

    fun startCompletionAnimation(onAdjacentViews: (Float) -> Unit, onAnimationEnd: () -> Unit) {
        if (isAnimating) {
            return
        }
        isAnimating = true

        // Scale up to 2x
        val scaleUpX = ObjectAnimator.ofFloat(this, "scaleX", 1f, 2f)
        val scaleUpY = ObjectAnimator.ofFloat(this, "scaleY", 1f, 2f)

        val scaleUpSet = AnimatorSet()
        scaleUpSet.playTogether(scaleUpX, scaleUpY)
        scaleUpSet.duration = 300

        // Push adjacent views
        val pushAnimator = ValueAnimator.ofFloat(0f, 1f)
        pushAnimator.duration = 300
        pushAnimator.addUpdateListener { animator ->
            val progress = animator.animatedValue as Float
            val pushDistance = progress * (width * 0.25f) // Push by 25% of width
            onAdjacentViews(pushDistance)
        }

        val swellSet = AnimatorSet()
        swellSet.playTogether(scaleUpSet, pushAnimator)

        // Burst: instantly change to completed state
        swellSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Change to completed appearance
                completed = total
                invalidate()

                // Scale back down
                val scaleDownX = ObjectAnimator.ofFloat(this@ProgressPieChart, "scaleX", 2f, 1f)
                val scaleDownY = ObjectAnimator.ofFloat(this@ProgressPieChart, "scaleY", 2f, 1f)

                val scaleDownSet = AnimatorSet()
                scaleDownSet.playTogether(scaleDownX, scaleDownY)
                scaleDownSet.duration = 200

                // Return adjacent views
                val returnAnimator = ValueAnimator.ofFloat(1f, 0f)
                returnAnimator.duration = 200
                returnAnimator.addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    val pushDistance = progress * (width * 0.25f)
                    onAdjacentViews(pushDistance)
                }

                val settleSet = AnimatorSet()
                settleSet.playTogether(scaleDownSet, returnAnimator)
                settleSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isAnimating = false
                        onCompletionAnimationFinished?.invoke()
                        onAnimationEnd()
                    }
                })
                settleSet.start()
            }
        })

        swellSet.start()
    }

    fun startSwellAnimation(onAnimationEnd: () -> Unit = {}) {
        if (isSwelled || isAnimating) {
            return
        }
        isAnimating = true

        // Scale up to 1.1x (10% swell)
        val scaleUpX = ObjectAnimator.ofFloat(this, "scaleX", scaleX, 1.2f)
        val scaleUpY = ObjectAnimator.ofFloat(this, "scaleY", scaleY, 1.2f)

        val scaleUpSet = AnimatorSet()
        scaleUpSet.playTogether(scaleUpX, scaleUpY)
        scaleUpSet.duration = 150
        scaleUpSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isAnimating = false
                isSwelled = true
                onAnimationEnd()
            }
        })

        scaleUpSet.start()
    }

    fun startRestoreAnimation(onAnimationEnd: () -> Unit = {}) {
        if (!isSwelled || isAnimating) {
            return
        }
        isAnimating = true

        // Scale back down to 1x
        val scaleDownX = ObjectAnimator.ofFloat(this, "scaleX", 1.1f, 1f)
        val scaleDownY = ObjectAnimator.ofFloat(this, "scaleY", 1.1f, 1f)

        val scaleDownSet = AnimatorSet()
        scaleDownSet.playTogether(scaleDownX, scaleDownY)
        scaleDownSet.duration = 300
        scaleDownSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isAnimating = false
                isSwelled = false
                onAnimationEnd()
            }
        })

        scaleDownSet.start()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP) {
            onClickListener?.invoke()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ProgressPieRenderer.drawPie(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            completedSets = completed,
            totalSets = total,
            segmented = isSegmented,
            outlineEnabled = isOutlineEnabled,
            outlineStroke = paint.strokeWidth,
            segmentDividers = showSegmentDividers
        )
        val label = centerCountdown
        if (!label.isNullOrEmpty() && centerCountdownVisible && completed < total) {
            drawCenterCountdown(canvas, label)
        }
    }

    private fun drawCenterCountdown(canvas: Canvas, text: String) {
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(centerX, centerY) - paint.strokeWidth / 2f
        val holeRadius = radius * 0.36f
        if (holeRadius <= 0f) {
            return
        }
        centerTextPaint.textSize = holeRadius * 0.95f
        val maxWidth = holeRadius * 1.7f
        val measured = centerTextPaint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            centerTextPaint.textSize = centerTextPaint.textSize * maxWidth / measured
        }
        val fontMetrics = centerTextPaint.fontMetrics
        val baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(text, centerX, baseline, centerTextPaint)
    }
}
