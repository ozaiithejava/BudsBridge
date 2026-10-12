package dev.ozaii.budsbridge

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** part_battery_col.xml için küçük binding yardımcısı (overlay + ana ekran). */
class BatteryCol(root: View, @DrawableRes image: Int, @StringRes label: Int) {
    private val pct: TextView = root.findViewById(R.id.pct)
    private val bolt: View = root.findViewById(R.id.bolt)
    private val bar: BatteryBarView = root.findViewById(R.id.bar)

    init {
        root.findViewById<ImageView>(R.id.img).setImageResource(image)
        root.findViewById<TextView>(R.id.label).setText(label)
    }

    fun bind(level: Int, charging: Boolean) {
        pct.text = formatLevel(level, false)
        bolt.visibility = if (charging && level >= 0) View.VISIBLE else View.GONE
        bar.set(level, charging)
    }
}
