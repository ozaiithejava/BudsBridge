package dev.ozaii.budsbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.DrawableRes
import java.io.File

enum class Role(val key: String) { LEFT("left"), RIGHT("right"), CASE("case"), HERO("hero") }

/**
 * Görsel çözümleme sırası: model paketi -> "default" paketi -> yerleşik vektör.
 * Statik (png/jpg) ve animasyonlu (animated webp/gif) dosyalar desteklenir.
 */
object AssetStore {
    val EXT = setOf("png", "webp", "gif", "jpg", "jpeg")
    private const val TAG = "AssetStore"
    private const val MAX_BYTES = 20 * 1024 * 1024

    private fun root(ctx: Context) = File(ctx.filesDir, "packs")
    private fun dir(ctx: Context, id: String) = File(root(ctx), id)

    fun find(ctx: Context, modelId: String?, role: Role): File? {
        val dirs = listOfNotNull(modelId?.let { dir(ctx, it) }, dir(ctx, "default"))
        for (d in dirs) {
            d.listFiles()
                ?.firstOrNull { it.nameWithoutExtension == role.key && it.extension.lowercase() in EXT }
                ?.let { return it }
        }
        return null
    }

    fun drawable(ctx: Context, modelId: String?, role: Role, @DrawableRes fallback: Int): Drawable {
        find(ctx, modelId, role)?.let { f ->
            try {
                return ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                    dec.setTargetSampleSize((info.size.height / 480).coerceAtLeast(1))
                }
            } catch (e: Exception) {
                Log.w(TAG, "decode başarısız: ${f.name}", e)
            }
        }
        return ctx.getDrawable(fallback)!!
    }

    /** Widget için statik bitmap (animasyonlu dosyada ilk kare). */
    fun bitmap(ctx: Context, modelId: String?, role: Role, @DrawableRes fallback: Int, heightPx: Int): Bitmap {
        find(ctx, modelId, role)?.let { f ->
            try {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ ->
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    dec.setTargetSampleSize((info.size.height / heightPx).coerceAtLeast(1))
                }
            } catch (e: Exception) {
                Log.w(TAG, "bitmap decode başarısız: ${f.name}", e)
            }
        }
        val d = ctx.getDrawable(fallback)!!
        val w = (d.intrinsicWidth.toFloat() / d.intrinsicHeight.coerceAtLeast(1) * heightPx).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, heightPx, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, heightPx)
        d.draw(Canvas(bmp))
        return bmp
    }

    fun saveBytes(ctx: Context, modelId: String, role: Role, ext: String, bytes: ByteArray) {
        val d = dir(ctx, modelId)
        d.mkdirs()
        d.listFiles()?.filter { it.nameWithoutExtension == role.key }?.forEach { it.delete() }
        File(d, "${role.key}.$ext").writeBytes(bytes)
    }

    fun clearAll(ctx: Context) {
        root(ctx).deleteRecursively()
    }

    fun roleFromName(name: String): Role? {
        val n = name.lowercase()
        return when {
            "left" in n -> Role.LEFT
            "right" in n -> Role.RIGHT
            "case" in n || "box" in n || "charg" in n -> Role.CASE
            "hero" in n || "main" in n || "product" in n -> Role.HERO
            else -> null
        }
    }

    /** Kullanıcının seçtiği görselleri ("left/right/case" geçen dosya adlarıyla) tüm modeller için varsayılan yapar. */
    fun importUris(ctx: Context, uris: List<Uri>): Int {
        var n = 0
        for (u in uris) {
            val name = displayName(ctx, u) ?: continue
            val role = roleFromName(name) ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in EXT) continue
            val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: continue
            if (bytes.size > MAX_BYTES) continue
            saveBytes(ctx, "default", role, ext, bytes)
            n++
        }
        return n
    }

    private fun displayName(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}
