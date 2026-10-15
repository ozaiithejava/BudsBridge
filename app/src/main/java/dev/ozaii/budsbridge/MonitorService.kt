package dev.ozaii.budsbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Sessiz, düşük öncelikli izleme servisi: ACL bağlan/kop olaylarını çalışma zamanında dinler.
 * Manifest alıcılarına göre (OxygenOS bunları geciktirebilir/kısıtlayabilir) anında tepki verir.
 * Zaten bağlı kulaklık varsa açılışta onu da yakalar.
 */
class MonitorService : Service() {
    private lateinit var prefs: Prefs

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (!prefs.matches(d)) return
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED ->
                    ContextCompat.startForegroundService(c, HeadsetService.connectIntent(c, d))
                BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    c.stopService(Intent(c, HeadsetService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Kulaklık izleme", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bud)
            .setContentTitle("Buds Bridge")
            .setContentText("Kulaklık bağlantısı izleniyor")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        ConnectedScan.run(this) { d ->
            if (prefs.matches(d)) ContextCompat.startForegroundService(this, HeadsetService.connectIntent(this, d))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(receiver) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "buds_monitor"
        private const val ID = 4202
        @Volatile var running = false
    }
}
