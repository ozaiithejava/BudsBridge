package dev.ozaii.budsbridge

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("buds_bridge", Context.MODE_PRIVATE)

    /** Son bağlanan modelin id'si (widget ve ana ekran görselleri için). */
    var lastModel: String?
        get() = sp.getString(K_MODEL, null)
        set(v) = sp.edit().putString(K_MODEL, v).apply()

    private fun set(key: String): Set<String> = sp.getStringSet(key, emptySet()) ?: emptySet()

    /** Otomatik: OnePlus ailesinden bir isim ya da kullanıcının elle açtığı cihaz. Elle kapatılanlar hariç. */
    fun isEnabled(address: String, name: String?): Boolean {
        val a = address.uppercase()
        if (a in set(K_BLOCKED)) return false
        if (a in set(K_TRUSTED)) return true
        return DeviceRegistry.match(name) != null
    }

    fun setEnabled(address: String, enabled: Boolean) {
        val a = address.uppercase()
        val trusted = set(K_TRUSTED).toMutableSet()
        val blocked = set(K_BLOCKED).toMutableSet()
        if (enabled) { trusted += a; blocked -= a } else { blocked += a; trusted -= a }
        sp.edit().putStringSet(K_TRUSTED, trusted).putStringSet(K_BLOCKED, blocked).apply()
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun matches(device: android.bluetooth.BluetoothDevice): Boolean {
        val name = try { device.name } catch (_: SecurityException) { null }
        return isEnabled(device.address, name)
    }

    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    // --- Widget için son bilinen batarya ---
    fun saveBattery(b: BudsBattery?) {
        sp.edit().apply {
            if (b == null) remove(K_BAT)
            else putString(K_BAT, "${b.left},${b.right},${b.box},${b.leftCharging},${b.rightCharging},${b.boxCharging}")
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
        const val DEFAULT_NAME = "OnePlus Buds"
        const val K_BAT = "battery"
        private const val K_MODEL = "last_model"
        private const val K_TRUSTED = "trusted"
        private const val K_BLOCKED = "blocked"
    }
}
