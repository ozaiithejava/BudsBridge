package dev.ozaii.budsbridge

object Protocol {
    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}

/**
 * OPPO / OnePlus / realme "OPO" RFCOMM protokolü (UUID 0000079A-D102-11E1-9B23-00025B00A5A5).
 * Çerçeve: AA <TotalLen> 00 00 <Cmd LE> <Seq> <PayLen LE> <Payload>   (TotalLen = toplam bayt - 2)
 * Kaynak: açık kaynak çalışmalar (QuickBuds PROTOCOL.md, OppoPodsManager) + bu cihazdan yakalanan veri.
 */
object Opo {
    const val CMD_HANDSHAKE = 0x0100
    const val CMD_PRODUCT = 0x0103
    const val CMD_BATTERY = 0x0106
    const val CMD_WEAR = 0x0109
    const val CMD_ANC_QUERY = 0x010C
    const val CMD_BCAST_CODES = 0x0200
    const val CMD_PUSH = 0x0204
    const val CMD_SUBSCRIBE = 0x0205
    const val CMD_SET_ANC = 0x0404
    const val RESP = 0x8000

    class Frame(val cmd: Int, val seq: Int, val payload: ByteArray)

    fun build(cmd: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val total = 7 + payload.size
        require(total < 128) { "LEB128 uzunluk gerekir" }
        val out = ByteArray(2 + total)
        out[0] = 0xAA.toByte()
        out[1] = total.toByte()
        out[4] = (cmd and 0xFF).toByte()
        out[5] = ((cmd shr 8) and 0xFF).toByte()
        out[6] = seq.toByte()
        out[7] = (payload.size and 0xFF).toByte()
        out[8] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, 9)
        return out
    }

    /** ANC set yükü: 01 01 <bit>  (Off=01, On=02, Transparency=04) */
    fun ancPayload(mode: AncMode) = byteArrayOf(
        0x01, 0x01,
        when (mode) {
            AncMode.OFF -> 0x01
            AncMode.ANC -> 0x02
            AncMode.TRANSPARENCY -> 0x04
        }
    )

    private fun pairsAt(p: ByteArray, o: Int): BudsBattery? {
        if (o >= p.size) return null
        val count = p[o].toInt() and 0xFF
        if (count !in 1..3 || o + 1 + 2 * count > p.size) return null
        var l = -1; var r = -1; var b = -1
        var lc = false; var rc = false; var bc = false
        for (i in 0 until count) {
            val idx = p[o + 1 + 2 * i].toInt() and 0xFF
            val raw = p[o + 2 + 2 * i].toInt() and 0xFF
            val lvl = raw and 0x7F
            val chg = (raw and 0x80) != 0
            if (lvl > 100) return null
            when (idx) {
                1 -> { l = lvl; lc = chg }
                2 -> { r = lvl; rc = chg }
                3 -> { b = lvl; bc = chg }
                else -> return null
            }
        }
        return BudsBattery(l, r, b, lc, rc, bc)
    }

    /** 0x8106 yanıtı: [status][count]{idx,raw}...   |   0x0204 push alt tip 01: [01][count]{idx,raw}... */
    fun parseBattery(f: Frame): BudsBattery? {
        val p = f.payload
        return when (f.cmd) {
            CMD_BATTERY or RESP -> pairsAt(p, 1) ?: pairsAt(p, 0)
            CMD_PUSH -> if (p.isNotEmpty() && p[0].toInt() == 0x01) pairsAt(p, 1) else null
            else -> null
        }
    }

    private fun le(lo: Byte, hi: Byte) = (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)

    /** 0x810C yanıtı: 00 01 01 LO HI   |   0x0204 push alt tip 03: 03 01 01 LO HI */
    fun parseAnc(f: Frame): AncMode? {
        val p = f.payload
        val mask = when {
            f.cmd == (CMD_ANC_QUERY or RESP) && p.size >= 5 && p[1].toInt() == 1 && p[2].toInt() == 1 -> le(p[3], p[4])
            f.cmd == CMD_PUSH && p.size >= 5 && p[0].toInt() == 3 && p[1].toInt() == 1 && p[2].toInt() == 1 -> le(p[3], p[4])
            else -> return null
        }
        return when {
            mask == 0x0008 -> AncMode.OFF
            (mask and 0x0300) != 0 -> AncMode.TRANSPARENCY
            (mask and 0x08F2) != 0 -> AncMode.ANC
            else -> null
        }
    }
}

/** RFCOMM akışından parçalı gelen OPO çerçevelerini birleştirir (LEB128 uzunluk dahil). */
class FrameAssembler {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray, n: Int): List<Opo.Frame> {
        buf += data.copyOf(n)
        val out = mutableListOf<Opo.Frame>()
        while (true) {
            val start = buf.indexOfFirst { it == 0xAA.toByte() }
            if (start < 0) { buf = ByteArray(0); break }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 2) break

            var pos = 1
            var v = 0
            var shift = 0
            var complete = false
            while (pos < buf.size && pos < 4) {
                val b = buf[pos].toInt() and 0xFF
                pos++
                v = v or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) { complete = true; break }
                shift += 7
            }
            if (!complete) {
                if (pos >= 4) { buf = buf.copyOfRange(1, buf.size); continue } else break
            }
            val totalLen = if (pos > 2) v + 1 else v
            if (totalLen < 7) { buf = buf.copyOfRange(1, buf.size); continue }
            val size = pos + totalLen
            if (buf.size < size) break

            val f = buf.copyOfRange(0, size)
            buf = buf.copyOfRange(size, buf.size)
            val h = pos
            val cmd = (f[h + 2].toInt() and 0xFF) or ((f[h + 3].toInt() and 0xFF) shl 8)
            val seq = f[h + 4].toInt() and 0xFF
            val payLen = (f[h + 5].toInt() and 0xFF) or ((f[h + 6].toInt() and 0xFF) shl 8)
            val ps = h + 7
            out += Opo.Frame(cmd, seq, f.copyOfRange(ps, minOf(f.size, ps + payLen)))
        }
        if (buf.size > 4096) buf = ByteArray(0)
        return out
    }
}
