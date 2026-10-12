package dev.ozaii.budsbridge

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private class PermRow(val root: View, val granted: () -> Boolean)

    private lateinit var prefs: Prefs
    private lateinit var colLeft: BatteryCol
    private lateinit var colCase: BatteryCol
    private lateinit var colRight: BatteryCol
    private lateinit var tvStatus: TextView
    private val rows = mutableListOf<PermRow>()

    private val batteryListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.K_BAT) runOnUiThread { renderBattery() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvStatus = findViewById(R.id.tvStatus)
        colLeft = BatteryCol(findViewById(R.id.colLeft), R.drawable.buds_left, R.string.label_left)
        colCase = BatteryCol(findViewById(R.id.colCase), R.drawable.buds_case, R.string.label_box)
        colRight = BatteryCol(findViewById(R.id.colRight), R.drawable.buds_right, R.string.label_right)

        val etName = findViewById<EditText>(R.id.etName).apply { setText(prefs.nameFilter) }
        val etMac = findViewById<EditText>(R.id.etMac).apply { setText(prefs.mac) }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            prefs.nameFilter = etName.text.toString()
            prefs.mac = etMac.text.toString()
            it.clearFocus()
        }
        findViewById<Button>(R.id.btnDemo).setOnClickListener {
            ContextCompat.startForegroundService(this, HeadsetService.demoIntent(this))
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, HeadsetService::class.java))
        }

        buildRows()
    }

    override fun onStart() {
        super.onStart()
        prefs.register(batteryListener)
        renderBattery()
    }

    override fun onStop() {
        prefs.unregister(batteryListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    private fun renderBattery() {
        val b = prefs.loadBattery()
        tvStatus.text = if (b != null) "Bağlı" else "Bağlı değil"
        val s = b ?: BudsBattery()
        colLeft.bind(s.left, s.leftCharging)
        colCase.bind(s.box, s.boxCharging)
        colRight.bind(s.right, s.rightCharging)
    }

    // ---------------- İzin satırları ----------------

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun buildRows() {
        val container = findViewById<LinearLayout>(R.id.permContainer)

        addRow(container, "Bluetooth", "Kulaklık bağlantısını algılamak için",
            granted = { has(Manifest.permission.BLUETOOTH_CONNECT) }) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
        addRow(container, "Bildirimler", "Durum bildirimi ve ANC düğmeleri için",
            granted = { has(Manifest.permission.POST_NOTIFICATIONS) }) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        addRow(container, "Diğer uygulamaların üzerinde göster", "Fast Pair tarzı pop-up için",
            granted = { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 36) {
            addRow(container, "Canlı bildirimler", "Durum çubuğunda Live Alert chip'i için",
                granted = {
                    getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
                }) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
        refreshRows()
    }

    private fun addRow(
        container: LinearLayout, title: String, desc: String,
        granted: () -> Boolean, onGrant: () -> Unit,
    ) {
        val v = layoutInflater.inflate(R.layout.row_permission, container, false)
        v.findViewById<TextView>(R.id.rowTitle).text = title
        v.findViewById<TextView>(R.id.rowDesc).text = desc
        v.findViewById<Button>(R.id.rowBtn).setOnClickListener { onGrant() }
        container.addView(v)
        rows += PermRow(v, granted)
    }

    private fun refreshRows() {
        for (row in rows) {
            val ok = row.granted()
            row.root.findViewById<ImageView>(R.id.rowIcon)
                .setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_error_circle)
            row.root.findViewById<View>(R.id.rowBtn).visibility = if (ok) View.GONE else View.VISIBLE
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshRows()
    }
}
