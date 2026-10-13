package dev.ozaii.budsbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Android'in kendi "untethered headset" metadata'sını okur (Ayarlar > Bağlı cihazlar'ın kullandığı kaynak).
 * HeyMelody / OPlus servisi bu alanları dolduruyorsa sol/sağ/kutu bataryası ve ikonlar buradan gelir;
 * özel RFCOMM protokolüne gerek kalmaz. Gizli API olduğu için reflection + hidden-api bypass kullanılır.
 * Anahtar numaraları AOSP BluetoothDevice.METADATA_* sabitlerinden; cihazda dolu gelmeyebilir.
 */
object BatteryReader {
    private const val TAG = "BatteryReader"
    private const val L_ICON = 7
    private const val R_ICON = 8
    private const val C_ICON = 9
    private const val L_BAT = 10
    private const val R_BAT = 11
    private const val C_BAT = 12
    private const val L_CHG = 13
    private const val R_CHG = 14
    private const val C_CHG = 15

    @Volatile private var exempted = false
    @Volatile var lastError: String = "yok"

    private fun ensure() {
        if (exempted) return
        exempted = true
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
            .onFailure { Log.w(TAG, "hidden api bypass başarısız", it) }
    }

    @SuppressLint("MissingPermission")
    fun metadata(d: BluetoothDevice, key: Int): String? = try {
        ensure()
        val m = BluetoothDevice::class.java.getMethod("getMetadata", Int::class.javaPrimitiveType)
        (m.invoke(d, key) as? ByteArray)?.let { String(it) }
    } catch (e: Throwable) {
        lastError = "${e.javaClass.simpleName}: ${e.cause?.javaClass?.simpleName ?: e.message}"
        Log.w(TAG, "getMetadata($key) hata: $lastError")
        null
    }

    /** Sistemin tek değerli kulaklık bataryası (HFP/GATT). Sol/sağ/kutu ayrımı yok. */
    fun singleLevel(d: BluetoothDevice): Int? = try {
        ensure()
        val m = BluetoothDevice::class.java.getMethod("getBatteryLevel")
        (m.invoke(d) as? Int)?.takeIf { it in 0..100 }
    } catch (e: Throwable) {
        lastError = "getBatteryLevel: ${e.javaClass.simpleName}: ${e.cause?.javaClass?.simpleName ?: e.message}"
        null
    }

    /** Özel RFCOMM kanalını pasif dinler (veri göndermez). Başka uygulama kanalı tutuyorsa bağlantı hata verir. */
    @SuppressLint("MissingPermission")
    private fun probe(d: BluetoothDevice, uuid: java.util.UUID, ms: Long): String {
        val sb = StringBuilder("probe $uuid: ")
        val sock = try {
            d.createRfcommSocketToServiceRecord(uuid)
        } catch (e: Exception) {
            return sb.append("soket açılamadı: ${e.message}\n").toString()
        }
        val closer = Thread {
            try { Thread.sleep(ms + 6000) } catch (_: InterruptedException) { return@Thread }
            runCatching { sock.close() }
        }.apply { isDaemon = true }
        try {
            closer.start()
            sock.connect()
            sb.append("bağlandı; ")
            val ins = sock.inputStream
            val end = System.currentTimeMillis() + ms
            val buf = ByteArray(512)
            var got = 0
            while (System.currentTimeMillis() < end) {
                if (ins.available() > 0) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    sb.append("RX ${Protocol.hex(buf.copyOf(n))}; ")
                    got++
                } else Thread.sleep(100)
            }
            if (got == 0) sb.append("veri gelmedi")
        } catch (e: Exception) {
            sb.append("hata: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { sock.close() }
            closer.interrupt()
        }
        return sb.append("\n").toString()
    }

    fun read(d: BluetoothDevice): BudsBattery? {
        fun lvl(k: Int) = metadata(d, k)?.trim()?.toIntOrNull()?.takeIf { it in 0..100 } ?: -1
        fun chg(k: Int) = metadata(d, k)?.trim().equals("true", ignoreCase = true)
        val b = BudsBattery(lvl(L_BAT), lvl(R_BAT), lvl(C_BAT), chg(L_CHG), chg(R_CHG), chg(C_CHG))
        return if (b.left < 0 && b.right < 0 && b.box < 0) null else b
    }

    private fun extOf(b: ByteArray): String? = when {
        b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
        b.size > 12 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() -> "webp"
        b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "gif"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        else -> null
    }

    /** Sistemin kayıtlı kulaklık ikonlarını (varsa) model paketine kaydeder. Mevcut görselin üzerine yazmaz. */
    fun fetchIcons(ctx: Context, d: BluetoothDevice, modelId: String, overwrite: Boolean = false): Int {
        var n = 0
        for ((role, key) in listOf(Role.LEFT to L_ICON, Role.RIGHT to R_ICON, Role.CASE to C_ICON)) {
            if (!overwrite && AssetStore.find(ctx, modelId, role) != null) continue
            val s = metadata(d, key)?.trim().orEmpty()
            if (s.isEmpty()) continue
            try {
                val bytes = ctx.contentResolver.openInputStream(Uri.parse(s))?.use { it.readBytes() } ?: continue
                val ext = extOf(bytes) ?: continue
                AssetStore.saveBytes(ctx, modelId, role, ext, bytes)
                n++
            } catch (e: Exception) {
                Log.w(TAG, "ikon okunamadı ($role): ${e.javaClass.simpleName}")
            }
        }
        return n
    }

    @SuppressLint("MissingPermission")
    fun dump(ctx: Context): String {
        val sb = StringBuilder("== Bluetooth teşhis ==\n")
        if (ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return sb.append("BLUETOOTH_CONNECT izni yok\n").toString()
        }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        for (d in adapter?.bondedDevices.orEmpty()) {
            if (DeviceRegistry.match(d.name) == null) continue
            sb.append("\n${d.name} ${d.address}\n")
            sb.append("uuids: ${d.uuids?.joinToString { it.uuid.toString() }}\n")
            for (k in 0..30) {
                val v = metadata(d, k) ?: continue
                sb.append("meta[$k]=${v.take(120)}\n")
            }
            sb.append("okunan batarya: ${read(d)}\n")
            sb.append("tek seviye (getBatteryLevel): ${singleLevel(d)}\n")
            sb.append("son hata: $lastError\n")
            for (u in HeadsetService.RFCOMM_UUIDS) sb.append(probe(d, u, 5_000))
            sb.append("-- rx.log --\n${RxLog.tail(ctx)}\n")
        }
        return sb.toString()
    }
}
