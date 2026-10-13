package dev.ozaii.budsbridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** RFCOMM'den gelen ham veriyi ve bağlantı denemelerini kaydeder (protokol çözmek için rapora eklenir). */
object RxLog {
    private const val MAX = 40_000

    @Synchronized
    fun add(ctx: Context, line: String) {
        val f = File(ctx.filesDir, "rx.log")
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        f.appendText("$t $line\n")
        if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
    }

    @Synchronized
    fun tail(ctx: Context): String {
        val f = File(ctx.filesDir, "rx.log")
        return if (f.exists()) f.readText().takeLast(8_000) else "(boş)"
    }
}
