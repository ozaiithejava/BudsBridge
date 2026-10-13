package dev.ozaii.budsbridge

import android.content.Context
import android.graphics.drawable.AnimatedImageDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** part_battery_col.xml binding'i: görsel (statik/animasyonlu) + yüzde + çubuk. */
class BatteryCol(
    val root: View,
    private val role: Role,
    @StringRes label: Int,
    @DrawableRes private val fallback: Int,
) {
    private val img: ImageView = root.findViewById(R.id.img)
    private val pct: TextView = root.findViewById(R.id.pct)
    private val bolt: View = root.findViewById(R.id.bolt)
    private val bar: BatteryBarView = root.findViewById(R.id.bar)

    init { root.findViewById<TextView>(R.id.label).setText(label) }

    fun setModel(ctx: Context, modelId: String?) {
        release()
        val d = AssetStore.drawable(ctx, modelId, role, fallback)
        img.setImageDrawable(d)
        (d as? AnimatedImageDrawable)?.apply {
            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            start()
        }
    }

    /** Animasyonu durdurup drawable referansını bırakır (leak önleme). */
    fun release() {
        (img.drawable as? AnimatedImageDrawable)?.stop()
        img.setImageDrawable(null)
    }

    /** Tek ana görsel varsa bud/kutu çizimlerini gizler; sadece yüzde + çubuk kalır. */
    fun setCompact(on: Boolean) { img.visibility = if (on) View.GONE else View.VISIBLE }

    fun bind(level: Int, charging: Boolean) {
        pct.text = formatLevel(level, false)
        bolt.visibility = if (charging && level >= 0) View.VISIBLE else View.GONE
        bar.set(level, charging)
    }

    fun enter(delayMs: Long) {
        val dy = 36 * root.resources.displayMetrics.density
        root.alpha = 0f
        root.translationY = dy
        root.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(420)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }
}
