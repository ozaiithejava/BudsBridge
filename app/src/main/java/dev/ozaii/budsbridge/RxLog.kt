package dev.ozaii.budsbridge

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ham RFCOMM / bildirim / yayın kayıtları. Her log ayrı dosyada, boyut sınırlı. */
object RxLog {
    const val RX = "rx.log"
    const val NOTIF = "notif.log"
    private const val MAX = 60_000

    @Synchronized
    fun add(ctx: Context, line: String, name: String = RX) {
        val f = File(ctx.filesDir, name)
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        f.appendText("$t $line\n")
        if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
    }

    @Synchronized
    fun tail(ctx: Context, name: String = RX, n: Int = 20_000): String {
        val f = File(ctx.filesDir, name)
        return if (f.exists()) f.readText().takeLast(n) else "(boş)"
    }
}
