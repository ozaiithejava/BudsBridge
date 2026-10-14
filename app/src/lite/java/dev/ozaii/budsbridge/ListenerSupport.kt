package dev.ozaii.budsbridge

import android.content.Context

/** lite sürüm: hiçbir bildirim-dinleyici kodu içermez (Play Protect hassas-izin engeline takılmamak için). */
object ListenerSupport {
    fun enabled(ctx: Context): Boolean = false
    fun openSettings(ctx: Context) {}
}
