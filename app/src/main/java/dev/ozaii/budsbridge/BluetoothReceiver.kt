package dev.ozaii.budsbridge

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra(
            BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        ) ?: return
        if (!Prefs(context).matches(device)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED ->
                ContextCompat.startForegroundService(context, HeadsetService.connectIntent(context, device))

            // stopService arka plandan da güvenli
            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                context.stopService(Intent(context, HeadsetService::class.java))
        }
    }
}
