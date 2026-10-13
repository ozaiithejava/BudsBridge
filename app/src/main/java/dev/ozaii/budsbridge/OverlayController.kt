package dev.ozaii.budsbridge

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.graphics.drawable.AnimatedImageDrawable

/**
 * Fast Pair tarzı alt sheet. Tüm çağrılar main thread'e post edilir.
 * Context olarak applicationContext kullanır; destroy() view, animasyon ve callback'leri temizler.
 */
class OverlayController(context: Context) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density

    private var root: View? = null
    private var countdown: View? = null
    private var cols: List<BatteryCol> = emptyList()
    private var hero: ImageView? = null
    private var colLeft: BatteryCol? = null
    private var colCase: BatteryCol? = null
    private var colRight: BatteryCol? = null
    private val hideRunnable = Runnable { hide() }

    fun show(deviceName: String, modelId: String?) = main.post {
        if (root != null || !Settings.canDrawOverlays(ctx)) return@post

        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_popup, null)
        v.findViewById<TextView>(R.id.tvName).text = deviceName
        val l = BatteryCol(v.findViewById(R.id.colLeft), Role.LEFT, R.string.label_left, R.drawable.buds_left)
        val c = BatteryCol(v.findViewById(R.id.colCase), Role.CASE, R.string.label_box, R.drawable.buds_case)
        val r = BatteryCol(v.findViewById(R.id.colRight), Role.RIGHT, R.string.label_right, R.drawable.buds_right)
        colLeft = l; colCase = c; colRight = r
        cols = listOf(l, c, r)
        cols.forEach { it.setModel(ctx, modelId); it.bind(-1, false) }
        val heroView = v.findViewById<ImageView>(R.id.heroImg)
        if (AssetStore.find(ctx, modelId, Role.HERO) != null) {
            val d = AssetStore.drawable(ctx, modelId, Role.HERO, R.drawable.buds_case)
            heroView.setImageDrawable(d)
            (d as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
            heroView.visibility = View.VISIBLE
            cols.forEach { it.setCompact(true) }
            hero = heroView
        }
        countdown = v.findViewById(R.id.countdown)

        v.findViewById<View>(R.id.btnDone).setOnClickListener { hide() }
        v.findViewById<View>(R.id.btnSettings).setOnClickListener {
            hide()
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        attachDrag(v, v.findViewById(R.id.card))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        v.translationY = ctx.resources.displayMetrics.heightPixels.toFloat()
        v.alpha = 0f
        runCatching { wm.addView(v, lp) }.onFailure { releaseCols(); return@post }
        root = v
        v.animate().translationY(0f).alpha(1f).setDuration(420)
            .setInterpolator(DecelerateInterpolator(1.8f)).start()
        cols.forEachIndexed { i, col -> col.enter(140L + i * 80L) }
        scheduleHide(10_000)
    }

    fun update(b: BudsBattery) = main.post {
        colLeft?.bind(b.left, b.leftCharging)
        colCase?.bind(b.box, b.boxCharging)
        colRight?.bind(b.right, b.rightCharging)
    }

    fun dismissAfter(ms: Long) = main.post { if (root != null) scheduleHide(ms) }

    private fun attachDrag(rootView: View, card: View) {
        var startY = 0f
        card.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; pauseHide(); true }
                MotionEvent.ACTION_MOVE -> { rootView.translationY = (e.rawY - startY).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (rootView.translationY > 72 * density) hide()
                    else { rootView.animate().translationY(0f).setDuration(180).start(); scheduleHide(3_000) }
                    true
                }
                else -> false
            }
        }
    }

    private fun scheduleHide(ms: Long) {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, ms)
        countdown?.let {
            it.animate().cancel()
            it.pivotX = 0f
            it.scaleX = 1f
            it.animate().scaleX(0f).setDuration(ms).setInterpolator(LinearInterpolator()).start()
        }
    }

    private fun pauseHide() {
        main.removeCallbacks(hideRunnable)
        countdown?.animate()?.cancel()
        countdown?.scaleX = 1f
    }

    private fun releaseHero(h: ImageView?) {
        (h?.drawable as? AnimatedImageDrawable)?.stop()
        h?.setImageDrawable(null)
    }

    private fun releaseCols() {
        releaseHero(hero); hero = null
        cols.forEach { it.release() }
        cols = emptyList(); colLeft = null; colCase = null; colRight = null
    }

    private fun hide() {
        val v = root ?: return
        root = null
        main.removeCallbacks(hideRunnable)
        countdown?.animate()?.cancel()
        countdown = null
        val toRelease = cols
        val heroToRelease = hero
        hero = null
        cols = emptyList(); colLeft = null; colCase = null; colRight = null
        v.animate().translationY(v.height.toFloat() + 120f).alpha(0f).setDuration(280)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                toRelease.forEach { it.release() }
                releaseHero(heroToRelease)
                runCatching { wm.removeViewImmediate(v) }
            }
            .start()
    }

    fun destroy() = main.post {
        main.removeCallbacks(hideRunnable)
        countdown?.animate()?.cancel()
        root?.let { v ->
            v.animate().cancel()
            runCatching { wm.removeViewImmediate(v) }
        }
        root = null
        countdown = null
        releaseCols()
    }
}
