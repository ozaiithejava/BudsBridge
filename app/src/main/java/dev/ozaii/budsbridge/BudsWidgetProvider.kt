package dev.ozaii.budsbridge

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
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
            val saved = Prefs(ctx).loadBattery()
            val b = saved ?: Prefs(ctx).loadLastKnown() ?: BudsBattery()
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val prefs = Prefs(ctx)
            val modelId = prefs.lastModel
            val h = (56 * ctx.resources.displayMetrics.density).toInt()
            val rv = RemoteViews(ctx.packageName, R.layout.widget_battery).apply {
                setTextViewText(R.id.tvTitle, DeviceRegistry.byId(modelId)?.displayName ?: Prefs.DEFAULT_NAME)
                setImageViewBitmap(R.id.imgLeft, AssetStore.bitmap(ctx, modelId, Role.LEFT, R.drawable.buds_left, h))
                setImageViewBitmap(R.id.imgCase, AssetStore.bitmap(ctx, modelId, Role.CASE, R.drawable.buds_case, h))
                setImageViewBitmap(R.id.imgRight, AssetStore.bitmap(ctx, modelId, Role.RIGHT, R.drawable.buds_right, h))
                setTextViewText(R.id.tvStatus, if (saved != null) "Bağlı" else "Bağlı değil")
                fill(R.id.tvLeft, R.id.boltLeft, R.id.barLeft, b.left, b.leftCharging)
                fill(R.id.tvBox, R.id.boltBox, R.id.barBox, b.box, b.boxCharging)
                fill(R.id.tvRight, R.id.boltRight, R.id.barRight, b.right, b.rightCharging)
                setOnClickPendingIntent(R.id.widgetRoot, open)
            }
            mgr.updateAppWidget(ids, rv)
        }

        private fun RemoteViews.fill(tv: Int, bolt: Int, bar: Int, level: Int, charging: Boolean) {
            setTextViewText(tv, formatLevel(level, false))
            setViewVisibility(bolt, if (charging && level >= 0) View.VISIBLE else View.GONE)
            setProgressBar(bar, 100, level.coerceAtLeast(0), false)
        }
    }
}
