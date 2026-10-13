package dev.ozaii.budsbridge

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
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

class HeadsetService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private var currentAddress: String? = null

    @Volatile private var socket: BluetoothSocket? = null
    private val writeLock = Any()

    private lateinit var overlay: OverlayController
    private lateinit var prefs: Prefs

    private var deviceName = Prefs.DEFAULT_NAME
    private var battery = BudsBattery()
    private var anc: AncMode? = null
    private var model: BudsModel = DeviceRegistry.generic
    private var firstFrame = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        overlay = OverlayController(applicationContext)
        NotificationHelper.ensureChannel(this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_ANC -> {
                val mode = AncMode.fromCode(intent.getIntExtra(EXTRA_ANC, -1))
                if (mode != null) setAnc(mode)
            }

            ACTION_DEMO -> {
                model = DeviceRegistry.byId(prefs.lastModel) ?: DeviceRegistry.buds3
                deviceName = model.displayName
                prefs.lastModel = model.id
                startFg()
                sessionJob?.cancel()
                currentAddress = "demo"
                overlay.show(deviceName, model.id)
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
                startFg()
                overlay.show(deviceName, model.id)
                sessionJob?.cancel()
                sessionJob = scope.launch { runSession(device) }
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

    // ---------------- RFCOMM oturumu ----------------

    @SuppressLint("MissingPermission")
    private suspend fun runSession(device: BluetoothDevice) {
        var attempt = 0
        while (currentCoroutineContext().isActive && attempt < MAX_RETRY) {
            var s: BluetoothSocket? = null
            try {
                s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = s
                s.connect()
                attempt = 0
                send(Protocol.requestBattery())
                readLoop(s)
            } catch (e: IOException) {
                Log.w(TAG, "RFCOMM hata (deneme ${attempt + 1})", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "BLUETOOTH_CONNECT izni yok", e)
                break
            } finally {
                runCatching { s?.close() }
                if (socket === s) socket = null
            }
            attempt++
            delay(2_000L * attempt)
        }
    }

    private suspend fun readLoop(s: BluetoothSocket) {
        val input = s.inputStream
        val buf = ByteArray(256)
        val assembler = FrameAssembler()
        while (currentCoroutineContext().isActive) {
            val n = input.read(buf)          // blocking; socket.close() ile kırılır
            if (n < 0) break
            for (frame in assembler.feed(buf, n)) {
                Log.d(TAG, "RX ${Protocol.hex(frame)}")
                Protocol.parseBattery(frame)?.let(::onBattery)
                Protocol.parseAnc(frame)?.let(::onAnc)
            }
        }
    }

    private fun send(frame: ByteArray) {
        val s = socket ?: return
        scope.launch {
            try {
                synchronized(writeLock) {
                    s.outputStream.write(frame)
                    s.outputStream.flush()
                }
            } catch (e: IOException) {
                Log.w(TAG, "TX hata", e)
            }
        }
    }

    private fun setAnc(mode: AncMode) {
        if (currentAddress == "demo") onAnc(mode) else send(Protocol.setAnc(mode))
    }

    // ---------------- Durum güncellemeleri ----------------

    private fun onBattery(b: BudsBattery) {
        battery = b
        prefs.saveBattery(b)
        BudsWidgetProvider.refreshAll(this)
        overlay.update(b)
        if (firstFrame) {
            firstFrame = false
            overlay.dismissAfter(POPUP_VISIBLE_MS)   // veri gelince 3-4 sn sonra kapan
        }
        notifyUpdate()
    }

    private fun onAnc(mode: AncMode) {
        anc = mode
        notifyUpdate()
    }

    private fun notifyUpdate() {
        getSystemService(NotificationManager::class.java)
            .notify(NotificationHelper.NOTIF_ID, NotificationHelper.build(this, deviceName, battery, anc, model.hasAnc))
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        scope.cancel()
        runCatching { socket?.close() }
        socket = null
        overlay.destroy()
        prefs.saveBattery(null)
        BudsWidgetProvider.refreshAll(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HeadsetService"
        private const val MAX_RETRY = 5
        private const val POPUP_VISIBLE_MS = 3_500L

        const val ACTION_SET_ANC = "dev.ozaii.budsbridge.SET_ANC"
        const val ACTION_DEMO = "dev.ozaii.budsbridge.DEMO"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_ANC = "anc"

        /** TODO: HeyMelody trafiğinden doğrulanacak gerçek SPP UUID. Şimdilik standart SPP. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun connectIntent(ctx: Context, d: BluetoothDevice) =
            Intent(ctx, HeadsetService::class.java).putExtra(EXTRA_DEVICE, d)

        fun ancIntent(ctx: Context, mode: AncMode) =
            Intent(ctx, HeadsetService::class.java).setAction(ACTION_SET_ANC).putExtra(EXTRA_ANC, mode.code)

        fun demoIntent(ctx: Context) =
            Intent(ctx, HeadsetService::class.java).setAction(ACTION_DEMO)
    }
}
