package dev.ozaii.budsbridge

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

/**
 * Fast Pair tarzı alt pop-up. Tüm çağrılar main thread'e post edilir.
 * Context olarak applicationContext ver; destroy() view'ı ve callback'leri temizler.
 */
class OverlayController(context: Context) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hideRunnable = Runnable { hide() }

    fun show(deviceName: String) = main.post {
        if (view != null || !Settings.canDrawOverlays(ctx)) return@post

        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_popup, null)
        v.findViewById<TextView>(R.id.tvName).text = deviceName
        v.setOnClickListener { hide() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        v.translationY = ctx.resources.displayMetrics.heightPixels.toFloat()
        runCatching { wm.addView(v, lp) }.onFailure { return@post }
        view = v
        v.animate().translationY(0f).setDuration(380)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        scheduleHide(10_000)   // veri hiç gelmezse de kapansın
    }

    fun update(b: BudsBattery) = main.post {
        val v = view ?: return@post
        v.findViewById<TextView>(R.id.tvLeft).text = formatLevel(b.left, b.leftCharging)
        v.findViewById<TextView>(R.id.tvRight).text = formatLevel(b.right, b.rightCharging)
        v.findViewById<TextView>(R.id.tvBox).text = formatLevel(b.box, b.boxCharging)
    }

    fun dismissAfter(ms: Long) = main.post { scheduleHide(ms) }

    private fun scheduleHide(ms: Long) {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, ms)
    }

    private fun hide() {
        val v = view ?: return
        view = null
        main.removeCallbacks(hideRunnable)
        v.animate().translationY(v.height.toFloat() + 100f).setDuration(260)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { runCatching { wm.removeViewImmediate(v) } }
            .start()
    }

    fun destroy() = main.post {
        main.removeCallbacks(hideRunnable)
        view?.let { v ->
            v.animate().cancel()
            runCatching { wm.removeViewImmediate(v) }
        }
        view = null
    }
}
