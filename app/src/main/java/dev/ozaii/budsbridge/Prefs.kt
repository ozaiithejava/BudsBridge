package dev.ozaii.budsbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("buds_bridge", Context.MODE_PRIVATE)

    var nameFilter: String
        get() = sp.getString(K_NAME, DEFAULT_NAME) ?: DEFAULT_NAME
        set(v) = sp.edit().putString(K_NAME, v.trim()).apply()

    /** Opsiyonel. Doluysa isimden önce MAC eşleşmesi kullanılır. */
    var mac: String
        get() = sp.getString(K_MAC, "") ?: ""
        set(v) = sp.edit().putString(K_MAC, v.trim().uppercase()).apply()

    @SuppressLint("MissingPermission")
    fun matches(device: BluetoothDevice): Boolean {
        val m = mac
        if (m.isNotEmpty()) return device.address.equals(m, ignoreCase = true)
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        } ?: return false
        return nameFilter.isNotEmpty() && name.contains(nameFilter, ignoreCase = true)
    }

    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    // --- Widget için son bilinen batarya ---
    fun saveBattery(b: BudsBattery?) {
        sp.edit().apply {
            if (b == null) {
                remove(K_BAT)
            } else {
                putString(K_BAT, "${b.left},${b.right},${b.box},${b.leftCharging},${b.rightCharging},${b.boxCharging}")
            }
        }.apply()
    }

    fun loadBattery(): BudsBattery? {
        val p = sp.getString(K_BAT, null)?.split(",") ?: return null
        if (p.size != 6) return null
        return BudsBattery(
            p[0].toIntOrNull() ?: -1, p[1].toIntOrNull() ?: -1, p[2].toIntOrNull() ?: -1,
            p[3].toBoolean(), p[4].toBoolean(), p[5].toBoolean(),
        )
    }

    companion object {
        const val DEFAULT_NAME = "OnePlus Buds 3"
        private const val K_NAME = "name"
        private const val K_MAC = "mac"
        const val K_BAT = "battery"
    }
}
