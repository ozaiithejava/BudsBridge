package dev.ozaii.budsbridge

data class BudsBattery(
    val left: Int = -1,
    val right: Int = -1,
    val box: Int = -1,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val boxCharging: Boolean = false,
)

enum class AncMode(val code: Int) {
    OFF(0), ANC(1), TRANSPARENCY(2);

    companion object {
        fun fromCode(code: Int): AncMode? = entries.firstOrNull { it.code == code }
    }
}

fun formatLevel(level: Int, charging: Boolean): String = when {
    level < 0 -> "–"
    charging -> "$level%⚡"
    else -> "$level%"
}
