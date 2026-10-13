package dev.ozaii.budsbridge

/** Servis dışındaki kaynaklardan (bildirim dinleyici) gelen kısmi batarya verisini servise iletir. */
object BatteryBus {
    @Volatile var sink: ((BudsBattery) -> Unit)? = null
    fun post(b: BudsBattery) { sink?.invoke(b) }
}
