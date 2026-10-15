package dev.ozaii.budsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager

/** Uygulama açılmadan önce zaten bağlı olan kulaklıkları (A2DP / HFP profilleri) bulur. */
object ConnectedScan {
    @SuppressLint("MissingPermission")
    fun run(ctx: Context, onDevice: (BluetoothDevice) -> Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            adapter.getProfileProxy(ctx.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        proxy.connectedDevices.forEach(onDevice)
                    } finally {
                        adapter.closeProfileProxy(p, proxy)
                    }
                }

                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
    }
}
