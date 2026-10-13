package dev.ozaii.budsbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ID = "buds_status_high"
    const val NOTIF_ID = 4201

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /** Android 16+ (OxygenOS 16 Live Alerts) -> promoted ongoing; aksi halde özel RemoteViews. */
    fun build(ctx: Context, deviceName: String, b: BudsBattery, anc: AncMode?, showAnc: Boolean = true): Notification {
        if (Build.VERSION.SDK_INT >= 36) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.canPostPromotedNotifications()) return buildPromoted(ctx, deviceName, b, anc, showAnc)
        }
        return buildCustom(ctx, deviceName, b, anc, showAnc)
    }

    private fun summary(b: BudsBattery) =
        "L ${formatLevel(b.left, b.leftCharging)} · R ${formatLevel(b.right, b.rightCharging)} · Kutu ${formatLevel(b.box, b.boxCharging)}"

    private fun modeLabel(ctx: Context, anc: AncMode?) = when (anc) {
        AncMode.ANC -> ctx.getString(R.string.anc_on)
        AncMode.TRANSPARENCY -> ctx.getString(R.string.anc_transparency)
        AncMode.OFF -> ctx.getString(R.string.anc_off)
        null -> "–"
    }

    /**
     * Live Alerts / Live Updates: özel RemoteViews DESTEKLENMEZ. Standart/BigTextStyle +
     * ongoing + contentTitle + setRequestPromotedOngoing gerekir. Durum çubuğu chip'i = shortCriticalText.
     */
    @android.annotation.SuppressLint("NewApi")
    private fun buildPromoted(ctx: Context, deviceName: String, b: BudsBattery, anc: AncMode?, showAnc: Boolean): Notification {
        val text = summary(b)
        val lowest = listOf(b.left, b.right).filter { it >= 0 }.minOrNull()
        val chip = if (lowest != null) "$lowest%" else "…"
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(mode: AncMode, label: Int) = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_bud), ctx.getString(label), ancAction(ctx, mode)
        ).build()

        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bud)
            .setContentTitle(deviceName)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text + "\n" + ctx.getString(R.string.anc_mode_fmt, modeLabel(ctx, anc))))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
            .setShortCriticalText(chip)
            .apply {
                if (showAnc) {
                    addAction(action(AncMode.ANC, R.string.anc_on))
                    addAction(action(AncMode.TRANSPARENCY, R.string.anc_transparency))
                    addAction(action(AncMode.OFF, R.string.anc_off))
                }
            }
            .build()
    }

    private fun buildCustom(ctx: Context, deviceName: String, b: BudsBattery, anc: AncMode?, showAnc: Boolean): Notification {
        val collapsed = RemoteViews(ctx.packageName, R.layout.notif_collapsed).also { bind(ctx, it, b) }

        val expanded = RemoteViews(ctx.packageName, R.layout.notif_expanded).also { rv ->
            bind(ctx, rv, b)
            val modeText = when (anc) {
                AncMode.ANC -> ctx.getString(R.string.anc_on)
                AncMode.TRANSPARENCY -> ctx.getString(R.string.anc_transparency)
                AncMode.OFF -> ctx.getString(R.string.anc_off)
                null -> "–"
            }
            rv.setTextViewText(R.id.tvAnc, ctx.getString(R.string.anc_mode_fmt, modeText))
            rv.setOnClickPendingIntent(R.id.btnAnc, ancAction(ctx, AncMode.ANC))
            rv.setOnClickPendingIntent(R.id.btnTransparency, ancAction(ctx, AncMode.TRANSPARENCY))
            rv.setOnClickPendingIntent(R.id.btnOff, ancAction(ctx, AncMode.OFF))
            if (!showAnc) {
                listOf(R.id.btnAnc, R.id.btnTransparency, R.id.btnOff, R.id.tvAnc)
                    .forEach { rv.setViewVisibility(it, View.GONE) }
            }
        }

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bud)
            .setContentTitle(deviceName)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)        // güncellemelerde tekrar heads-up çıkmasın
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun bind(ctx: Context, rv: RemoteViews, b: BudsBattery) {
        rv.setTextViewText(R.id.tvLeft, "L " + formatLevel(b.left, b.leftCharging))
        rv.setTextViewText(R.id.tvRight, "R " + formatLevel(b.right, b.rightCharging))
        rv.setTextViewText(R.id.tvBox, formatLevel(b.box, b.boxCharging))
    }

    private fun ancAction(ctx: Context, mode: AncMode): PendingIntent = PendingIntent.getService(
        ctx, mode.ordinal, HeadsetService.ancIntent(ctx, mode),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
