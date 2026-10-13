package dev.ozaii.budsbridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.TypedValue
import java.io.ByteArrayOutputStream

/**
 * Telefonda kurulu HeyMelody / OnePlus Buds uygulamasının KENDİ kaynaklarını çalışma anında okur ve
 * kullanıcının cihazında yerel pakete kopyalar. Hiçbir üçüncü taraf görseli APK'ya gömülmez.
 * Kaynak adları tahmini (heuristik) eşleştirilir; tarama raporu eşleştirmeyi iyileştirmek için paylaşılabilir.
 */
object HeyMelodyExtractor {
    private const val TAG = "HeyMelodyExtractor"

    class Result(val packages: List<String>, val assigned: Map<Role, String>, val report: String)

    class Cand(val pkg: String, val res: Resources, val id: Int, val type: String, val name: String) {
        val key get() = "$pkg:$id"
    }

    private val WANTED = setOf("drawable", "mipmap", "raw")
    private val INTEREST = Regex("(?i)(bud|earph|earb|headset|case|box|tws|one ?plus|nord|left|right|connect|pair|lottie|device|product|model)")
    /** Arayüz kütüphanesi (coui) animasyon kareleri vb. — hiçbir zaman ilgili değil. */
    private val JUNK = Regex("^\\$|coui|__\\d+$")
    private val NEGATIVE = Regex("^ic_|icon|btn|button|bg|background|shadow|selector|notif|widget|shape")
    private val NOT_PRODUCT = Regex("head|wear|detect|person|face|guide|man|woman|user|tip|gesture")
    private val HINT = Regex("bud|ear|headset|tws")

    @Suppress("DEPRECATION")
    fun findPackages(ctx: Context): List<ApplicationInfo> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).filter { ai ->
            if (ai.packageName == ctx.packageName) return@filter false
            val p = ai.packageName.lowercase()
            val l = pm.getApplicationLabel(ai).toString().lowercase()
            p.contains("melody") || p.contains("twspods") || (p.contains("heytap") && p.contains("headset")) ||
                l.contains("melody") || l.contains("oneplus buds")
        }
    }

    private fun collect(ctx: Context, ai: ApplicationInfo): List<Cand> {
        val res = try { ctx.packageManager.getResourcesForApplication(ai) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<Cand>()
        for (type in 1..0x30) {
            var miss = 0
            var entry = 0
            var typeChecked = false
            while (miss < 1500 && entry <= 0xFFFF) {
                val id = (0x7f shl 24) or (type shl 16) or entry
                try {
                    val t = res.getResourceTypeName(id)
                    val n = res.getResourceEntryName(id)
                    miss = 0
                    if (!typeChecked) {
                        typeChecked = true
                        if (t !in WANTED) break
                    }
                    out += Cand(ai.packageName, res, id, t, n)
                } catch (_: Resources.NotFoundException) {
                    miss++
                }
                entry++
            }
        }
        return out
    }

    private fun roleScore(n: String, role: Role): Int {
        val lr = Regex("(^|_)l(_|$)")
        val rr = Regex("(^|_)r(_|$)")
        return when (role) {
            Role.LEFT -> (if ("left" in n) 4 else 0) + (if (lr.containsMatchIn(n)) 2 else 0)
            Role.RIGHT -> (if ("right" in n) 4 else 0) + (if (rr.containsMatchIn(n)) 2 else 0)
            Role.CASE -> if ("case" in n || "box" in n || "charg" in n) 4 else 0
        }
    }

    private fun score(c: Cand, model: BudsModel?, role: Role): Int {
        val n = c.name.lowercase()
        var s = roleScore(n, role)
        if (s == 0) return 0
        val flat = n.filter { it.isLetterOrDigit() }
        if (model != null && model.key.isNotEmpty() && flat.contains(model.key)) s += 3
        if (model != null && !model.key.contains("pro") && flat.contains("pro")) s -= 4
        if (HINT.containsMatchIn(n)) s += 1
        if (NEGATIVE.containsMatchIn(n)) s -= 3
        if (NOT_PRODUCT.containsMatchIn(n)) s -= 5
        return s
    }

    fun saveCandidate(ctx: Context, c: Cand, modelId: String, role: Role): Boolean {
        val tv = TypedValue()
        try {
            c.res.getValue(c.id, tv, true)
            val path = tv.string?.toString().orEmpty()
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext in AssetStore.EXT) {
                val bytes = c.res.openRawResource(c.id, tv).use { it.readBytes() }
                AssetStore.saveBytes(ctx, modelId, role, ext, bytes)
                return true
            }
            if (c.type != "raw") {
                val d = c.res.getDrawable(c.id, null) ?: return false
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 512
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 512
                val k = (640f / h).coerceAtMost(4f)
                val bmp = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, bmp.width, bmp.height)
                d.draw(Canvas(bmp))
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                AssetStore.saveBytes(ctx, modelId, role, "png", bos.toByteArray())
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "kaydedilemedi: ${c.name}", e)
        }
        return false
    }

    /** Elle seçim ekranı için: ilgili adlar önce, geri kalanlar sonra (en fazla 700 adet). */
    fun candidates(ctx: Context): List<Cand> {
        val all = findPackages(ctx).flatMap { collect(ctx, it) }.filter { !JUNK.containsMatchIn(it.name) }
        val (a, b) = all.partition { INTEREST.containsMatchIn(it.name) }
        return (a + b).take(700)
    }

    /** Küçük önizleme; çözülemezse null. */
    fun thumb(c: Cand, heightPx: Int): Bitmap? = try {
        val tv = TypedValue()
        c.res.getValue(c.id, tv, true)
        val ext = tv.string?.toString().orEmpty().substringAfterLast('.', "").lowercase()
        if (ext in AssetStore.EXT) {
            val bytes = c.res.openRawResource(c.id, tv).use { it.readBytes() }
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            o.inSampleSize = (o.outHeight / heightPx).coerceAtLeast(1)
            o.inJustDecodeBounds = false
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        } else if (c.type != "raw") {
            val d = c.res.getDrawable(c.id, null)
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: heightPx
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: heightPx
            val k = heightPx.toFloat() / h
            val bmp = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), heightPx, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, bmp.width, bmp.height)
            d.draw(Canvas(bmp))
            bmp
        } else null
    } catch (e: Throwable) {
        null
    }

    /** apply=true ise bulunan en iyi görselleri [model] paketine kaydeder; her durumda rapor üretir. */
    fun run(ctx: Context, model: BudsModel?, apply: Boolean): Result {
        val pkgs = findPackages(ctx)
        val sb = StringBuilder()
        val assigned = linkedMapOf<Role, String>()
        sb.append("Buds Bridge tarama raporu\nModel: ${model?.displayName ?: "-"}\n")
        sb.append("Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n")
        if (pkgs.isEmpty()) sb.append("HeyMelody benzeri paket bulunamadı.\n")

        for (ai in pkgs) {
            val label = ctx.packageManager.getApplicationLabel(ai)
            val ver = runCatching { ctx.packageManager.getPackageInfo(ai.packageName, 0).versionName }.getOrNull()
            val all = collect(ctx, ai)
            val cands = all.filter { !JUNK.containsMatchIn(it.name) }
            sb.append("== ${ai.packageName} ($label, v$ver): toplam ${all.size}, arayüz-dışı ${cands.size}\n")
            sb.append("türler: ${cands.groupingBy { it.type }.eachCount()}\n")

            if (apply && model != null) {
                for (role in Role.entries) {
                    if (assigned.containsKey(role)) continue
                    val ranked = cands.map { it to score(it, model, role) }
                        .filter { it.second >= 5 }
                        .sortedByDescending { it.second }
                    for ((c, _) in ranked) {
                        if (saveCandidate(ctx, c, model.id, role)) {
                            assigned[role] = "${c.pkg}:${c.type}/${c.name}"
                            break
                        }
                    }
                }
            }
            sb.append("-- ilgili adlar --\n")
            cands.filter { INTEREST.containsMatchIn(it.name) }.take(400)
                .forEach { sb.append("  ${it.type}/${it.name}\n") }
            sb.append("-- diğer adlar (ilk 250) --\n")
            cands.filter { !INTEREST.containsMatchIn(it.name) }.take(250)
                .forEach { sb.append("  ${it.type}/${it.name}\n") }
        }
        if (assigned.isNotEmpty()) {
            sb.append("\nAtananlar:\n")
            assigned.forEach { (r, s) -> sb.append("  ${r.key} <- $s\n") }
        }
        return Result(pkgs.map { it.packageName }, assigned, sb.toString().take(60_000))
    }
}
