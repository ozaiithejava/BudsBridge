package dev.ozaii.budsbridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Açılışta / güncellemeden sonra izleme servisini yeniden başlatır (ayar açıksa). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).monitor) return
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
    }
}
