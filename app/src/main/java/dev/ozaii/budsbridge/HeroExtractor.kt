package dev.ozaii.budsbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * HeyMelody cihaz sayfasının ekran görüntüsünden ürün görselini otomatik keser ve arka planını şeffaf yapar.
 * 1) Zemin rengi sol kenardan bulunur  2) Satır bölütleri çıkarılır, en yüksek bölüt ürün görselidir
 * 3) Kenardan flood-fill ile yalnızca dış zemin silinir (ürünün koyu iç kısımları korunur)  4) Kenar yumuşatılır.
 */
object HeroExtractor {
    private const val T = 16          // zemin sayılan renk farkı eşiği
    private const val GAP = 14        // aynı bölütte sayılacak boş satır toleransı
    private const val PAD = 8

    fun fromScreenshot(ctx: Context, uri: Uri, modelId: String): Boolean {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, _, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        // zemin rengi: sol kenar, ekranın orta bölgesinde en sık renk
        val counts = HashMap<Int, Int>()
        for (y in (h * 0.2).toInt() until (h * 0.7).toInt()) {
            val c = px[y * w + 6] and 0xFFFFFF
            counts[c] = (counts[c] ?: 0) + 1
        }
        val bg = counts.maxByOrNull { it.value }?.key ?: return false
        val br = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bb = bg and 0xFF
        fun diff(c: Int): Int = max(abs(((c shr 16) and 0xFF) - br), max(abs(((c shr 8) and 0xFF) - bgG), abs((c and 0xFF) - bb)))

        // satır satır "içerik var mı"
        val xa = (w * 0.03).toInt()
        val xb = (w * 0.97).toInt()
        val topCut = (h * 0.06).toInt()
        val botCut = (h * 0.94).toInt()
        val active = BooleanArray(h)
        for (y in topCut until botCut) {
            var c = 0
            val base = y * w
            for (x in xa until xb) if (diff(px[base + x]) > T) c++
            active[y] = c > w * 0.01
        }

        // en yüksek bölüt = ürün görseli
        var bs = -1
        var be = -1
        var y = 0
        while (y < h) {
            if (active[y]) {
                val s = y
                var last = y
                while (y < h && (active[y] || y - last <= GAP)) {
                    if (active[y]) last = y
                    y++
                }
                if (bs < 0 || last - s > be - bs) { bs = s; be = last }
            } else y++
        }
        if (bs < 0 || be - bs < h * 0.08) return false

        // sütun sınırları
        var cx0 = w
        var cx1 = -1
        for (x in 0 until w) {
            var c = 0
            for (yy in bs..be) if (diff(px[yy * w + x]) > T) { c++; if (c > 1) break }
            if (c > 1) { if (x < cx0) cx0 = x; if (x > cx1) cx1 = x }
        }
        if (cx1 < 0) return false
        val x0 = max(0, cx0 - PAD)
        val x1 = minOf(w - 1, cx1 + PAD)
        val y0 = max(0, bs - PAD)
        val y1 = minOf(h - 1, be + PAD)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1

        // kırpılan bölge + zemin maskesi
        val crop = IntArray(cw * ch)
        val d = IntArray(cw * ch)
        for (yy in 0 until ch) for (xx in 0 until cw) {
            val c = px[(y0 + yy) * w + (x0 + xx)]
            crop[yy * cw + xx] = c
            d[yy * cw + xx] = diff(c)
        }

        // kenardan flood-fill: yalnızca dışarıyla bağlantılı zemin silinir
        val vis = BooleanArray(cw * ch)
        val queue = IntArray(cw * ch)
        var qh = 0
        var qt = 0
        fun push(i: Int) {
            if (!vis[i] && d[i] <= T) { vis[i] = true; queue[qt++] = i }
        }
        for (xx in 0 until cw) { push(xx); push((ch - 1) * cw + xx) }
        for (yy in 0 until ch) { push(yy * cw); push(yy * cw + cw - 1) }
        while (qh < qt) {
            val i = queue[qh++]
            val cxx = i % cw
            val cyy = i / cw
            if (cxx > 0) push(i - 1)
            if (cxx < cw - 1) push(i + 1)
            if (cyy > 0) push(i - cw)
            if (cyy < ch - 1) push(i + cw)
        }

        // çıktı + kenar yumuşatma (dış zemine 2 px yakın koyu pikseller)
        val out = IntArray(cw * ch)
        for (yy in 0 until ch) for (xx in 0 until cw) {
            val i = yy * cw + xx
            val rgb = crop[i] and 0xFFFFFF
            val a = when {
                vis[i] -> 0
                d[i] < 48 && nearOutside(vis, cw, ch, xx, yy) -> (d[i] * 255 / 48).coerceIn(0, 255)
                else -> 255
            }
            out[i] = (a shl 24) or rgb
        }
        var result = Bitmap.createBitmap(out, cw, ch, Bitmap.Config.ARGB_8888)
        if (ch > 900) {
            val s = 900f / ch
            result = Bitmap.createScaledBitmap(result, (cw * s).toInt().coerceAtLeast(1), 900, true)
        }
        val bos = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, bos)
        AssetStore.saveBytes(ctx, modelId, Role.HERO, "png", bos.toByteArray())
        return true
    }

    private fun nearOutside(vis: BooleanArray, w: Int, h: Int, x: Int, y: Int): Boolean {
        for (dy in -2..2) for (dx in -2..2) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until w && ny in 0 until h && vis[ny * w + nx]) return true
        }
        return false
    }
}
