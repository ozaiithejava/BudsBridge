package dev.ozaii.budsbridge

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** HeyMelody'nin görsellerini küçük resim olarak listeler; dokununca Sol / Sağ / Kutu olarak atanır. */
class AssetPickerActivity : Activity() {

    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private val cache = LruCache<String, Bitmap>(80)
    private var cands: List<HeyMelodyExtractor.Cand> = emptyList()
    private lateinit var modelId: String
    private lateinit var grid: GridView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelId = intent.getStringExtra(EXTRA_MODEL) ?: DeviceRegistry.buds3.id
        val d = resources.displayMetrics.density

        status = TextView(this).apply {
            text = "HeyMelody taranıyor…"
            setTextColor(getColor(R.color.on_surface))
            textSize = 15f
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        grid = GridView(this).apply {
            numColumns = 3
            verticalSpacing = (10 * d).toInt()
            horizontalSpacing = (10 * d).toInt()
            setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (12 * d).toInt())
            clipToPadding = false
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(status)
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        io.execute {
            val list = runCatching { HeyMelodyExtractor.candidates(this) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                cands = list
                status.text = if (list.isEmpty()) "HeyMelody bulunamadı ya da görsel yok."
                else "${DeviceRegistry.byId(modelId)?.displayName}: görsele dokun → Sol / Sağ / Kutu (${list.size})"
                grid.adapter = Adapter()
                grid.setOnItemClickListener { _, _, pos, _ -> assign(cands[pos]) }
            }
        }
    }

    private fun assign(c: HeyMelodyExtractor.Cand) {
        AlertDialog.Builder(this)
            .setTitle(c.name)
            .setItems(arrayOf("Sol", "Sağ", "Kutu", "Ana görsel (tek resim)")) { _, i ->
                val role = Role.entries[i]
                io.execute {
                    val ok = HeyMelodyExtractor.saveCandidate(this, c, modelId, role)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(this, if (ok) "${role.key} atandı" else "Kaydedilemedi (desteklenmeyen format)", Toast.LENGTH_SHORT).show()
                        if (ok) Prefs(this).lastModel = modelId
                    }
                }
            }
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = cands.size
        override fun getItem(position: Int) = cands[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val d = resources.displayMetrics.density
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@AssetPickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.parseColor("#22888888"))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (96 * d).toInt()))
                addView(TextView(context).apply {
                    textSize = 10f
                    maxLines = 2
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.on_surface_variant))
                })
            }
            val iv = cell.getChildAt(0) as ImageView
            val tv = cell.getChildAt(1) as TextView
            val c = cands[position]
            tv.text = c.name
            iv.tag = c.key
            val hit = cache.get(c.key)
            if (hit != null) {
                iv.setImageBitmap(hit)
            } else {
                iv.setImageDrawable(null)
                io.execute {
                    val bmp = HeyMelodyExtractor.thumb(c, 192) ?: return@execute
                    cache.put(c.key, bmp)
                    runOnUiThread { if (!isDestroyed && iv.tag == c.key) iv.setImageBitmap(bmp) }
                }
            }
            return cell
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        grid.adapter = null
        cache.evictAll()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODEL = "model"
    }
}
