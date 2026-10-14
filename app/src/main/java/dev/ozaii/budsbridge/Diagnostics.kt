package dev.ozaii.budsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tek dosyada çok detaylı teşhis: sistem, izinler, Bluetooth, metadata, RFCOMM, HeyMelody, bildirimler, loglar. */
object Diagnostics {

    private fun hex(b: List<Byte>) = b.joinToString(" ") { "%02X".format(it) }

    private fun isConnected(d: BluetoothDevice): Boolean? = runCatching {
        BatteryReader.ensure()
        BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean
    }.getOrNull()

    @SuppressLint("MissingPermission")
    fun build(ctx: Context, prefs: Prefs): String {
        val sb = StringBuilder()
        fun h(t: String) { sb.append("\n==== ").append(t).append(" ====\n") }
        fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

        sb.append("Buds Bridge teşhis raporu — ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')

        h("Sistem")
        sb.append("cihaz: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT} / patch ${Build.VERSION.SECURITY_PATCH}\n")
        sb.append("build: ${Build.DISPLAY}\nfingerprint: ${Build.FINGERPRINT}\n")
        sb.append("uygulama: ${ctx.packageName} v${runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()}\n")

        h("İzinler")
        sb.append("BLUETOOTH_CONNECT: ${granted(Manifest.permission.BLUETOOTH_CONNECT)}\n")
        sb.append("POST_NOTIFICATIONS: ${granted(Manifest.permission.POST_NOTIFICATIONS)}\n")
        sb.append("overlay: ${Settings.canDrawOverlays(ctx)}\n")
        sb.append("bildirim erişimi: ${ListenerSupport.enabled(ctx)}\n")
        if (Build.VERSION.SDK_INT >= 36) {
            sb.append("promoted (Live Alert): ${ctx.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()}\n")
        }

        h("Uygulama durumu")
        sb.append("prefs: ${prefs.debug()}\n")
        val packs = File(ctx.filesDir, "packs")
        sb.append("görsel paketleri:\n")
        packs.walkTopDown().filter { it.isFile }.forEach { sb.append("  ${it.relativeTo(packs)} (${it.length()} B)\n") }

        h("Bluetooth")
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            sb.append("BLUETOOTH_CONNECT yok, cihaz bilgisi alınamadı\n")
        } else {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            sb.append("adapter: ${adapter?.name} açık=${adapter?.isEnabled}\n")
            for (d in adapter?.bondedDevices.orEmpty()) {
                val model = DeviceRegistry.match(d.name)
                sb.append("\n-- ${d.name} / ${d.address} / tip=${d.type} / bond=${d.bondState} / sınıf=${d.bluetoothClass?.majorDeviceClass}:${d.bluetoothClass?.deviceClass}\n")
                sb.append("   model=${model?.id} etkin=${prefs.isEnabled(d.address, d.name)}\n")
                sb.append("   uuids: ${d.uuids?.joinToString { it.uuid.toString() }}\n")
                if (model == null) continue

                sb.append("   bağlı (gizli isConnected): ${isConnected(d)}\n")
                var empty = 0
                for (k in 0..60) {
                    val b = BatteryReader.metadataRaw(d, k)
                    if (b == null || b.isEmpty()) { empty++; continue }
                    val ascii = String(b).filter { it.code in 32..126 }.take(160)
                    sb.append("   meta[$k] len=${b.size} ascii='$ascii' hex=${hex(b.take(32))}\n")
                }
                sb.append("   meta boş/yok: $empty / 61 anahtar\n")
                sb.append("   read(): ${BatteryReader.read(d)}\n")
                sb.append("   getBatteryLevel: ${BatteryReader.singleLevel(d)}\n")
                sb.append("   son metadata hatası: ${BatteryReader.lastError}\n")
                for (u in HeadsetService.RFCOMM_UUIDS) sb.append("   ").append(BatteryReader.probe(d, u, 5_000))
            }
        }

        h("HeyMelody / sistem melody paketleri")
        for (ai in HeyMelodyExtractor.findPackages(ctx)) {
            val v = runCatching { ctx.packageManager.getPackageInfo(ai.packageName, 0).versionName }.getOrNull()
            sb.append("${ai.packageName} v$v\n")
        }
        sb.append(runCatching { HeyMelodyExtractor.run(ctx, DeviceRegistry.byId(prefs.lastModel), apply = false).report.take(30_000) }
            .getOrElse { "kaynak taraması hata: ${it.message}" }).append('\n')

        h("rx.log (RFCOMM / yayınlar)")
        sb.append(RxLog.tail(ctx, RxLog.RX)).append('\n')

        h("notif.log (HeyMelody bildirimleri)")
        sb.append(RxLog.tail(ctx, RxLog.NOTIF)).append('\n')

        h("logcat (yalnızca bu uygulama)")
        sb.append(runCatching {
            val p = ProcessBuilder("logcat", "-d", "-t", "600").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().takeLast(60_000)
        }.getOrElse { "logcat okunamadı: ${it.message}" }).append('\n')

        return sb.toString().take(300_000)
    }

    fun shareIntent(ctx: Context, text: String): Intent {
        val dir = File(ctx.cacheDir, "diag").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = "buds_bridge_teshis_${SimpleDateFormat("MMdd_HHmm", Locale.US).format(Date())}.txt"
        val f = File(dir, name).apply { writeText(text) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        return Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
