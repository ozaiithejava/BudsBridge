package dev.ozaii.budsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.graphics.drawable.AnimatedImageDrawable
import androidx.core.app.NotificationManagerCompat
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class PermRow(val root: View, val granted: () -> Boolean)

    private lateinit var prefs: Prefs
    private lateinit var colLeft: BatteryCol
    private lateinit var colCase: BatteryCol
    private lateinit var colRight: BatteryCol
    private lateinit var tvStatus: TextView
    private lateinit var tvDevice: TextView
    private lateinit var heroImg: ImageView
    private val rows = mutableListOf<PermRow>()
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private val batteryListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.K_BAT) runOnUiThread { renderBattery() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvDevice = findViewById(R.id.tvDevice)
        heroImg = findViewById(R.id.heroImg)
        colLeft = BatteryCol(findViewById(R.id.colLeft), Role.LEFT, R.string.label_left, R.drawable.buds_left)
        colCase = BatteryCol(findViewById(R.id.colCase), Role.CASE, R.string.label_box, R.drawable.buds_case)
        colRight = BatteryCol(findViewById(R.id.colRight), Role.RIGHT, R.string.label_right, R.drawable.buds_right)

        findViewById<Button>(R.id.btnDemo).setOnClickListener {
            ContextCompat.startForegroundService(this, HeadsetService.demoIntent(this))
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, HeadsetService::class.java))
        }
        findViewById<Button>(R.id.btnHey).setOnClickListener { pickModel { runExtract(it) } }
        findViewById<Button>(R.id.btnReport).setOnClickListener { runReport() }
        findViewById<Button>(R.id.btnPick).setOnClickListener {
            pickModel { m ->
                startActivity(Intent(this, AssetPickerActivity::class.java).putExtra(AssetPickerActivity.EXTRA_MODEL, m.id))
            }
        }
        findViewById<Button>(R.id.btnMeta).setOnClickListener { fetchSystemIcons() }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            AssetStore.clearAll(this)
            applyAssets()
            BudsWidgetProvider.refreshAll(this)
            toast("Görseller sıfırlandı")
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        }

        buildRows()
    }

    override fun onStart() {
        super.onStart()
        prefs.register(batteryListener)
        applyAssets()
        renderBattery()
    }

    override fun onStop() {
        prefs.unregister(batteryListener)
        listOf(colLeft, colCase, colRight).forEach { it.release() }
        (heroImg.drawable as? AnimatedImageDrawable)?.stop()
        heroImg.setImageDrawable(null)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
        refreshDevices()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------- Görseller ----------------

    private fun applyAssets() {
        val id = prefs.lastModel
        tvDevice.text = DeviceRegistry.byId(id)?.displayName ?: Prefs.DEFAULT_NAME
        colLeft.setModel(this, id)
        colCase.setModel(this, id)
        colRight.setModel(this, id)
        val hasHero = AssetStore.find(this, id, Role.HERO) != null
        if (hasHero) {
            val d = AssetStore.drawable(this, id, Role.HERO, R.drawable.buds_case)
            heroImg.setImageDrawable(d)
            (d as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
            heroImg.visibility = View.VISIBLE
        } else {
            heroImg.visibility = View.GONE
        }
        listOf(colLeft, colCase, colRight).forEach { it.setCompact(hasHero) }
    }

    private fun renderBattery() {
        val b = prefs.loadBattery()
        tvStatus.text = if (b != null) "Bağlı" else "Bağlı değil"
        val s = b ?: BudsBattery()
        colLeft.bind(s.left, s.leftCharging)
        colCase.bind(s.box, s.boxCharging)
        colRight.bind(s.right, s.rightCharging)
    }

    private fun pickModel(onPick: (BudsModel) -> Unit) {
        val models = DeviceRegistry.all
        AlertDialog.Builder(this)
            .setTitle("Hangi model için?")
            .setItems(models.map { it.displayName }.toTypedArray()) { _, i -> onPick(models[i]) }
            .show()
    }

    private fun runExtract(model: BudsModel) {
        toast("Taranıyor…")
        io.execute {
            val r = runCatching { HeyMelodyExtractor.run(this, model, apply = true) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { res ->
                    if (res.assigned.isNotEmpty()) prefs.lastModel = model.id
                    toast(
                        if (res.assigned.isEmpty()) "Uygun görsel bulunamadı (${res.packages.size} paket tarandı). Raporu paylaş."
                        else "${res.assigned.size}/3 görsel alındı"
                    )
                    applyAssets()
                    BudsWidgetProvider.refreshAll(this)
                }.onFailure { toast("Hata: ${it.message}") }
            }
        }
    }

    private fun runReport() {
        toast("Detaylı teşhis hazırlanıyor (~30 sn). Kulaklık bağlı ve HeyMelody açık olsun.")
        io.execute {
            val r = runCatching { Diagnostics.build(this, prefs) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { text ->
                    startActivity(Intent.createChooser(Diagnostics.shareIntent(this, text), "Teşhis raporu"))
                }.onFailure { toast("Hata: ${it.message}") }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchSystemIcons() {
        if (!has(Manifest.permission.BLUETOOTH_CONNECT)) { toast("Önce Bluetooth izni ver"); return }
        io.execute {
            var n = 0
            runCatching {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                for (d in adapter?.bondedDevices.orEmpty()) {
                    val m = DeviceRegistry.match(d.name) ?: continue
                    val k = BatteryReader.fetchIcons(this, d, m.id, overwrite = true)
                    if (k > 0) { n += k; prefs.lastModel = m.id }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(if (n > 0) "Sistemden $n görsel alındı" else "Sistem görsel sağlamıyor. Elle seç ya da raporu paylaş.")
                applyAssets()
                BudsWidgetProvider.refreshAll(this)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.data?.let { uris += it }
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        io.execute {
            val n = runCatching { AssetStore.importUris(this, uris) }.getOrDefault(0)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                toast(if (n > 0) "$n görsel içe aktarıldı" else "Dosya adlarında left / right / case geçmeli")
                applyAssets()
                BudsWidgetProvider.refreshAll(this)
            }
        }
    }

    // ---------------- Cihazlar ----------------

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        val c = findViewById<LinearLayout>(R.id.deviceContainer)
        c.removeAllViews()
        if (!has(Manifest.permission.BLUETOOTH_CONNECT)) {
            addInfo(c, "Bluetooth izni verilince eşleşmiş cihazlar burada görünür.")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = adapter?.bondedDevices.orEmpty().sortedBy { DeviceRegistry.match(it.name) == null }
        if (bonded.isEmpty()) {
            addInfo(c, "Eşleşmiş cihaz yok. Kulaklığı önce Bluetooth ayarlarından eşleştir.")
            return
        }
        for (d in bonded) {
            val model = DeviceRegistry.match(d.name)
            val v = layoutInflater.inflate(R.layout.device_row, c, false)
            v.findViewById<TextView>(R.id.devTitle).text = d.name ?: d.address
            v.findViewById<TextView>(R.id.devDesc).text =
                (if (model != null) "${model.displayName} · otomatik algılandı" else "Diğer cihaz") + " · ${d.address}"
            v.findViewById<Switch>(R.id.devSwitch).apply {
                isChecked = prefs.isEnabled(d.address, d.name)
                setOnCheckedChangeListener { _, on -> prefs.setEnabled(d.address, on) }
            }
            c.addView(v)
        }
    }

    private fun addInfo(c: LinearLayout, text: String) {
        c.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.on_surface_variant))
            textSize = 13f
            setPadding(0, 10, 0, 10)
        })
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
        addRow(container, "Bildirim erişimi", "HeyMelody bildirimlerinden kutu bataryasını okumak için",
            granted = { NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addRow(container, "Diğer uygulamaların üzerinde göster", "Fast Pair tarzı pop-up için",
            granted = { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 36) {
            addRow(container, "Canlı bildirimler", "Durum çubuğunda Live Alert chip'i için",
                granted = { getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
        refreshRows()
    }

    private fun addRow(container: LinearLayout, title: String, desc: String, granted: () -> Boolean, onGrant: () -> Unit) {
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
        refreshDevices()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_PICK = 42
    }
}
