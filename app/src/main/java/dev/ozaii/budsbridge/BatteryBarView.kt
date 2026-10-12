package dev.ozaii.budsbridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** İnce, yuvarlatılmış batarya çubuğu. Seviye değişimini animasyonlu gösterir. */
class BatteryBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.track) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = 0f
    private var animator: ValueAnimator? = null

    init { fillPaint.color = context.getColor(R.color.accent) }

    fun set(level: Int, charging: Boolean) {
        fillPaint.color = context.getColor(
            when {
                charging -> R.color.ok
                level in 0..15 -> R.color.low
                else -> R.color.accent
            }
        )
        val target = level.coerceAtLeast(0).toFloat()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, trackPaint)
        if (shown > 0f) {
            rect.set(0f, 0f, (width * shown / 100f).coerceAtLeast(height.toFloat()), height.toFloat())
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
