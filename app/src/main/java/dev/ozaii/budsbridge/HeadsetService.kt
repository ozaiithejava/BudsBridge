package dev.ozaii.budsbridge

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class HeadsetService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private var metaJob: Job? = null
    private var currentAddress: String? = null

    @Volatile private var socket: BluetoothSocket? = null
    private val writeLock = Any()
    private val seq = AtomicInteger(0)
    @Volatile private var haveProtocol = false
    @Volatile private var handshakeDone = false

    private lateinit var overlay: OverlayController
    private lateinit var prefs: Prefs

    private var deviceName = Prefs.DEFAULT_NAME
    private var battery = BudsBattery()
    private var anc: AncMode? = null
    private var model: BudsModel = DeviceRegistry.generic
    private var firstFrame = true

    private val levelReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val ex = i.extras?.keySet()?.joinToString { k -> "$k=${i.extras?.get(k)}" }
            RxLog.add(c, "BROADCAST ${i.action} $ex")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        overlay = OverlayController(applicationContext)
        battery = prefs.loadLastKnown() ?: BudsBattery()
        NotificationHelper.ensureChannel(this)
        BatteryBus.sink = { b -> onBattery(b) }
        ContextCompat.registerReceiver(
            this, levelReceiver,
            IntentFilter("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_ANC -> {
                val mode = AncMode.fromCode(intent.getIntExtra(EXTRA_ANC, -1))
                if (mode != null) setAnc(mode)
            }

            ACTION_REFRESH -> notifyUpdate()

            ACTION_DEMO -> {
                model = DeviceRegistry.byId(prefs.lastModel) ?: DeviceRegistry.buds3
                deviceName = model.displayName
                prefs.lastModel = model.id
                startFg()
                sessionJob?.cancel()
                metaJob?.cancel()
                currentAddress = "demo"
                if (prefs.popup) overlay.show(deviceName, model.id)
                sessionJob = scope.launch {
                    delay(800)
                    onBattery(BudsBattery(82, 79, 64, leftCharging = false, rightCharging = true))
                }
            }

            else -> {
                val device = intent?.getParcelableExtra(EXTRA_DEVICE, BluetoothDevice::class.java)
                if (device == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // ACL_CONNECTED LE + BR/EDR için iki kez gelebilir
                if (sessionJob?.isActive == true && currentAddress == device.address) {
                    startFg()
                    return START_NOT_STICKY
                }
                deviceName = try { device.name } catch (_: SecurityException) { null } ?: deviceName
                model = DeviceRegistry.match(deviceName) ?: DeviceRegistry.generic
                prefs.lastModel = model.id
                currentAddress = device.address
                firstFrame = true
                haveProtocol = false
                startFg()
                if (prefs.popup) overlay.show(deviceName, model.id)
                if (battery.left >= 0 || battery.right >= 0 || battery.box >= 0) overlay.update(battery)
                sessionJob?.cancel()
                sessionJob = scope.launch { runSession(device) }
                metaJob?.cancel()
                metaJob = scope.launch { pollFallback(device) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        ServiceCompat.startForeground(
            this, NotificationHelper.NOTIF_ID,
            NotificationHelper.build(this, deviceName, battery, anc, model.hasAnc),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    // ---------------- OPO RFCOMM oturumu ----------------

    @SuppressLint("MissingPermission")
    private suspend fun runSession(device: BluetoothDevice) {
        var attempt = 0
        while (currentCoroutineContext().isActive && attempt < MAX_RETRY) {
            var s: BluetoothSocket? = null
            var worker: Job? = null
            try {
                val uuid = if (attempt < 3) RFCOMM_UUIDS[0] else RFCOMM_UUIDS[attempt % RFCOMM_UUIDS.size]
                RxLog.add(this, "bağlanıyor $uuid")
                s = device.createRfcommSocketToServiceRecord(uuid)
                socket = s
                s.connect()
                attempt = 0
                rfcommUp = true
                handshakeDone = false
                RxLog.add(this, "bağlandı $uuid")
                worker = scope.launch {
                    initSequence()
                    while (isActive) {
                        delay(BATTERY_POLL_MS)
                        sendCmd(Opo.CMD_BATTERY)
                    }
                }
                readLoop(s)
            } catch (e: IOException) {
                Log.w(TAG, "RFCOMM hata (deneme ${attempt + 1})", e)
                RxLog.add(this, "hata: ${e.message}")
            } catch (e: SecurityException) {
                Log.e(TAG, "BLUETOOTH_CONNECT izni yok", e)
                break
            } finally {
                rfcommUp = false
                worker?.cancel()
                runCatching { s?.close() }
                if (socket === s) socket = null
            }
            attempt++
            delay(400L * attempt)
        }
    }

    /** Belgelenmiş açılış sırası: el sıkışma, ürün, yayın kodları, abonelik, ANC, batarya, takılma. */
    private suspend fun initSequence() {
        // el sıkışma yanıtı gelir gelmez (en fazla 500 ms) devam et; batarya sorgusu en öne alındı
        sendCmd(Opo.CMD_HANDSHAKE)
        var waited = 0
        while (!handshakeDone && waited < 500) { delay(15); waited += 15 }
        val steps = listOf(
            Opo.CMD_BATTERY to ByteArray(0),
            Opo.CMD_SUBSCRIBE to byteArrayOf(0x03, 0x01, 0x02, 0x03),  // pil + takılma + ANC bildirimleri
            Opo.CMD_ANC_QUERY to byteArrayOf(0x01, 0x01),
            Opo.CMD_WEAR to ByteArray(0),
            Opo.CMD_PRODUCT to ByteArray(0),
            Opo.CMD_BCAST_CODES to ByteArray(0),
        )
        for ((cmd, payload) in steps) {
            sendCmd(cmd, payload)
            delay(40)
        }
    }

    private suspend fun readLoop(s: BluetoothSocket) {
        val input = s.inputStream
        val buf = ByteArray(512)
        val assembler = FrameAssembler()
        while (currentCoroutineContext().isActive) {
            val n = input.read(buf)          // blocking; socket.close() ile kırılır
            if (n < 0) break
            for (f in assembler.feed(buf, n)) {
                RxLog.add(this, "RX cmd=0x%04X seq=%d pl=%s".format(f.cmd, f.seq, Protocol.hex(f.payload)))
                if (f.cmd == (Opo.CMD_HANDSHAKE or Opo.RESP)) handshakeDone = true
                Opo.parseBattery(f)?.let {
                    haveProtocol = true
                    onBattery(it)
                }
                Opo.parseAnc(f)?.let(::onAnc)
            }
        }
    }

    private fun nextSeq(): Int = ((seq.incrementAndGet() - 1) % 0xFE) + 1

    private fun sendCmd(cmd: Int, payload: ByteArray = ByteArray(0)) {
        val frame = Opo.build(cmd, nextSeq(), payload)
        val s = socket ?: return
        try {
            synchronized(writeLock) {
                s.outputStream.write(frame)
                s.outputStream.flush()
            }
            RxLog.add(this, "TX " + Protocol.hex(frame))
        } catch (e: IOException) {
            Log.w(TAG, "TX hata", e)
        }
    }

    private fun setAnc(mode: AncMode) {
        if (currentAddress == "demo") {
            onAnc(mode)
            return
        }
        scope.launch {
            sendCmd(Opo.CMD_SET_ANC, Opo.ancPayload(mode))
            delay(400)
            sendCmd(Opo.CMD_ANC_QUERY, byteArrayOf(0x01, 0x01))
        }
    }

    /** Protokol çalışmazsa (kanal başka uygulamada): sistemin tek değerli bataryası. */
    @SuppressLint("MissingPermission")
    private suspend fun pollFallback(device: BluetoothDevice) {
        while (currentCoroutineContext().isActive) {
            delay(1_500)
            if (!haveProtocol) BatteryReader.singleLevel(device)?.let { onBattery(BudsBattery(it, it, -1)) }
        }
    }

    // ---------------- Durum güncellemeleri ----------------

    /** Gelen değerde olmayan alanlar (kutu kapalıyken sessiz) önceki bilinen değerle korunur. */
    private fun merge(b: BudsBattery): BudsBattery {
        val o = battery
        return BudsBattery(
            left = if (b.left >= 0) b.left else o.left,
            right = if (b.right >= 0) b.right else o.right,
            box = if (b.box >= 0) b.box else o.box,
            leftCharging = if (b.left >= 0) b.leftCharging else o.leftCharging,
            rightCharging = if (b.right >= 0) b.rightCharging else o.rightCharging,
            boxCharging = if (b.box >= 0) b.boxCharging else o.boxCharging,
        )
    }

    @Synchronized
    private fun onBattery(raw: BudsBattery) {
        val b = merge(raw)
        if (!firstFrame && b == battery) return
        battery = b
        prefs.saveBattery(b)
        prefs.saveLastKnown(b)
        BudsWidgetProvider.refreshAll(this)
        overlay.update(b)
        if (firstFrame) {
            firstFrame = false
            overlay.dismissAfter(POPUP_VISIBLE_MS)
        }
        notifyUpdate()
    }

    private fun onAnc(mode: AncMode) {
        if (anc == mode) return
        anc = mode
        notifyUpdate()
    }

    private fun notifyUpdate() {
        getSystemService(NotificationManager::class.java)
            .notify(NotificationHelper.NOTIF_ID, NotificationHelper.build(this, deviceName, battery, anc, model.hasAnc))
    }

    override fun onDestroy() {
        running = false
        BatteryBus.sink = null
        runCatching { unregisterReceiver(levelReceiver) }
        sessionJob?.cancel()
        scope.cancel()
        runCatching { socket?.close() }
        socket = null
        rfcommUp = false
        overlay.destroy()
        prefs.saveBattery(null)
        BudsWidgetProvider.refreshAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HeadsetService"
        private const val MAX_RETRY = 8
        private const val POPUP_VISIBLE_MS = 3_500L
        private const val BATTERY_POLL_MS = 45_000L

        @Volatile var rfcommUp = false
        @Volatile var running = false

        const val ACTION_SET_ANC = "dev.ozaii.budsbridge.SET_ANC"
        const val ACTION_DEMO = "dev.ozaii.budsbridge.DEMO"
        const val ACTION_REFRESH = "dev.ozaii.budsbridge.REFRESH"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_ANC = "anc"

        /** İlki gerçek OPO kanalı (bu cihazda bağlanıyor); ikincisi çoğu cihazda bağlanmaz. */
        val RFCOMM_UUIDS: List<UUID> = listOf(
            UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5"),
            UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c"),
        )

        fun connectIntent(ctx: Context, d: BluetoothDevice) =
            Intent(ctx, HeadsetService::class.java).putExtra(EXTRA_DEVICE, d)

        fun ancIntent(ctx: Context, mode: AncMode) =
            Intent(ctx, HeadsetService::class.java).setAction(ACTION_SET_ANC).putExtra(EXTRA_ANC, mode.code)

        fun demoIntent(ctx: Context) =
            Intent(ctx, HeadsetService::class.java).setAction(ACTION_DEMO)
    }
}
