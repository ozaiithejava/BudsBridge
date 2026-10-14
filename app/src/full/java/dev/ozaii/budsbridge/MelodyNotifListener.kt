package dev.ozaii.budsbridge

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView

/**
 * HeyMelody / Kablosuz Kulaklık bildirimlerini okur: hepsini notif.log'a yazar (teşhis) ve içinden
 * sol/sağ/kutu bataryası çıkarabilirse BatteryBus ile servise iletir. Kullanıcı "Bildirim erişimi" vermeli.
 */
class MelodyNotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        RxLog.add(this, "listener bağlandı", RxLog.NOTIF)
        runCatching { activeNotifications?.forEach(::handle) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    @Suppress("DEPRECATION")
    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (pkg !in PKGS && !LOOKS_RELEVANT.containsMatchIn("$title $text")) return

        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("|").orEmpty()
        RxLog.add(this, "NOTIF $pkg ch=${n.channelId} id=${sbn.id} title='$title' text='$text' big='$big' sub='$sub' lines='$lines'", RxLog.NOTIF)

        var l = parseText(LEFT, "$title $text $big $lines")
        var r = parseText(RIGHT, "$title $text $big $lines")
        var b = parseText(BOX, "$title $text $big $lines")

        val views = listOfNotNull(n.contentView, n.bigContentView, n.headsUpContentView)
        for ((i, rv) in views.withIndex()) {
            val found = inspect(rv, i)
            if (found.left >= 0) l = found.left
            if (found.right >= 0) r = found.right
            if (found.box >= 0) b = found.box
        }
        if (l >= 0 || r >= 0 || b >= 0) {
            RxLog.add(this, "  -> ayrıştırılan batarya L=$l R=$r BOX=$b", RxLog.NOTIF)
            BatteryBus.post(BudsBattery(l, r, b))
        }
    }

    private fun parseText(rx: Regex, s: String): Int =
        rx.find(s)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 } ?: -1

    private fun inspect(rv: RemoteViews, idx: Int): BudsBattery {
        var l = -1; var r = -1; var b = -1
        val out = StringBuilder()
        try {
            val root = rv.apply(applicationContext, FrameLayout(applicationContext))
            fun walk(v: View) {
                val name = if (v.id != View.NO_ID) runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() else null
                val low = name?.lowercase().orEmpty()
                when (v) {
                    is TextView -> {
                        out.append("[$name:'${v.text}'] ")
                        val num = Regex("(\\d{1,3})").find(v.text)?.value?.toIntOrNull()?.takeIf { it in 0..100 }
                        if (num != null) when {
                            "left" in low -> l = num
                            "right" in low -> r = num
                            "box" in low || "case" in low -> b = num
                        }
                    }
                    is ProgressBar -> {
                        val p = if (v.max > 0) v.progress * 100 / v.max else -1
                        out.append("[$name:progress=${v.progress}/${v.max}] ")
                        if (p in 0..100) when {
                            "left" in low -> l = p
                            "right" in low -> r = p
                            "box" in low || "case" in low -> b = p
                        }
                    }
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        } catch (e: Throwable) {
            out.append("RemoteViews okunamadı: ${e.javaClass.simpleName}: ${e.message}")
        }
        RxLog.add(this, "  rv[$idx] $out", RxLog.NOTIF)
        return BudsBattery(l, r, b)
    }

    companion object {
        private val PKGS = setOf("com.heytap.headset", "com.oplus.melody")
        private val LOOKS_RELEVANT = Regex("(?i)buds|melody|oneplus|kulaklık")
        private val LEFT = Regex("(?i)(?:\\bL\\b|left|sol)\\s*[:\\-]?\\s*(\\d{1,3})\\s*%")
        private val RIGHT = Regex("(?i)(?:\\bR\\b|right|sağ|sag)\\s*[:\\-]?\\s*(\\d{1,3})\\s*%")
        private val BOX = Regex("(?i)(?:case|box|kutu)\\s*[:\\-]?\\s*(\\d{1,3})\\s*%")
    }
}
