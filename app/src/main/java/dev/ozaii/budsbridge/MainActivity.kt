package dev.ozaii.budsbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val name = EditText(this).apply { hint = "Cihaz adı filtresi"; setText(prefs.nameFilter) }
        val mac = EditText(this).apply { hint = "MAC (opsiyonel) AA:BB:CC:DD:EE:FF"; setText(prefs.mac) }

        val save = Button(this).apply {
            text = "Kaydet"
            setOnClickListener {
                prefs.nameFilter = name.text.toString()
                prefs.mac = mac.text.toString()
            }
        }
        val perms = Button(this).apply { text = "İzinleri ver"; setOnClickListener { requestAll() } }
        val demo = Button(this).apply {
            text = "Demo (sahte veriyle test)"
            setOnClickListener {
                ContextCompat.startForegroundService(this@MainActivity, HeadsetService.demoIntent(this@MainActivity))
            }
        }
        val live = Button(this).apply {
            text = "Bildirim / Live Alerts ayarları"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        val stop = Button(this).apply {
            text = "Servisi durdur"
            setOnClickListener { stopService(Intent(this@MainActivity, HeadsetService::class.java)) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = "Buds Bridge"; textSize = 22f })
            listOf(name, mac, save, perms, live, demo, stop).forEach {
                addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        })
    }

    private fun requestAll() {
        requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS), 1
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
}
