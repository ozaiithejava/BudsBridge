package dev.ozaii.budsbridge

/**
 * !!! MOCK PROTOKOL !!!
 * Gerçek OPlus/HeyMelody paket formatı resmi olarak belgelenmemiş. Gerçek frame'leri
 * Geliştirici Seçenekleri > "Bluetooth HCI snoop log" ile HeyMelody çalışırken yakalayıp
 * (Wireshark) aşağıdaki sabitleri ve parse mantığını güncelle.
 *
 * Varsayılan frame:  AA | LEN | OP | payload[LEN] | XOR(AA..payload)
 *   OP 0x0A  battery:  payload = [L, R, BOX]   (bit7 = şarj oluyor, bit0-6 = yüzde)
 *   OP 0x0B  anc:      payload = [mode]        (0 off, 1 anc, 2 transparency)
 */
object Protocol {
    const val HEADER = 0xAA
    const val OP_BATTERY = 0x0A
    const val OP_ANC = 0x0B
    const val OP_REQ_BATTERY = 0x1A
    const val OP_SET_ANC = 0x1B

    fun build(op: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(payload.size + 4)
        out[0] = HEADER.toByte()
        out[1] = payload.size.toByte()
        out[2] = op.toByte()
        payload.copyInto(out, 3)
        var x = 0
        for (i in 0 until out.size - 1) x = x xor (out[i].toInt() and 0xFF)
        out[out.size - 1] = x.toByte()
        return out
    }

    fun requestBattery() = build(OP_REQ_BATTERY)
    fun setAnc(mode: AncMode) = build(OP_SET_ANC, byteArrayOf(mode.code.toByte()))

    private fun valid(f: ByteArray): Boolean {
        if (f.size < 4 || (f[0].toInt() and 0xFF) != HEADER) return false
        if (f.size != (f[1].toInt() and 0xFF) + 4) return false
        var x = 0
        for (i in 0 until f.size - 1) x = x xor (f[i].toInt() and 0xFF)
        return (x and 0xFF) == (f.last().toInt() and 0xFF)
    }

    fun parseBattery(f: ByteArray): BudsBattery? {
        if (!valid(f) || (f[2].toInt() and 0xFF) != OP_BATTERY || f.size < 7) return null
        fun lvl(b: Byte): Int = (b.toInt() and 0x7F).let { if (it in 0..100) it else -1 }
        fun chg(b: Byte): Boolean = (b.toInt() and 0x80) != 0
        return BudsBattery(
            left = lvl(f[3]), right = lvl(f[4]), box = lvl(f[5]),
            leftCharging = chg(f[3]), rightCharging = chg(f[4]), boxCharging = chg(f[5]),
        )
    }

    fun parseAnc(f: ByteArray): AncMode? {
        if (!valid(f) || (f[2].toInt() and 0xFF) != OP_ANC || f.size < 5) return null
        return AncMode.fromCode(f[3].toInt() and 0xFF)
    }

    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}

/** RFCOMM stream'inden parçalı gelen frame'leri birleştirir. */
class FrameAssembler {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray, n: Int): List<ByteArray> {
        buf += data.copyOf(n)
        val out = mutableListOf<ByteArray>()
        while (true) {
            val start = buf.indexOfFirst { (it.toInt() and 0xFF) == Protocol.HEADER }
            if (start < 0) { buf = ByteArray(0); break }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 2) break
            val total = (buf[1].toInt() and 0xFF) + 4
            if (buf.size < total) break
            out += buf.copyOfRange(0, total)
            buf = buf.copyOfRange(total, buf.size)
        }
        if (buf.size > 1024) buf = ByteArray(0)
        return out
    }
}
