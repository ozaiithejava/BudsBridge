package dev.ozaii.budsbridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class BudsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        update(context, mgr, ids)
    }

    companion object {
        fun refreshAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BudsWidgetProvider::class.java))
            if (ids.isNotEmpty()) update(ctx, mgr, ids)
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val b = Prefs(ctx).loadBattery() ?: BudsBattery()
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val rv = RemoteViews(ctx.packageName, R.layout.widget_battery).apply {
                setTextViewText(R.id.tvLeft, formatLevel(b.left, b.leftCharging))
                setTextViewText(R.id.tvRight, formatLevel(b.right, b.rightCharging))
                setTextViewText(R.id.tvBox, formatLevel(b.box, b.boxCharging))
                setOnClickPendingIntent(R.id.widgetRoot, open)
            }
            mgr.updateAppWidget(ids, rv)
        }
    }
}
