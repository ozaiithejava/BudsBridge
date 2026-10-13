package dev.ozaii.budsbridge

/** Desteklenen OnePlus / Nord kulaklık modelleri. Sıra önemli: özel olanlar önce, jenerik en sonda. */
data class BudsModel(
    val id: String,
    val displayName: String,
    val pattern: Regex,
    val hasAnc: Boolean = true,
) {
    /** HeyMelody kaynak adlarını eşleştirmek için sadeleştirilmiş anahtar (örn. "buds3pro"). */
    val key: String = displayName.lowercase().removePrefix("oneplus").filter { it.isLetterOrDigit() }
}

object DeviceRegistry {
    private fun m(id: String, name: String, rx: String, anc: Boolean = true) =
        BudsModel(id, name, Regex(rx, RegexOption.IGNORE_CASE), anc)

    val buds3 = m("buds3", "OnePlus Buds 3", """one\s*plus\s*buds\s*3\b""")

    val generic = m(
        "generic", "OnePlus Buds",
        """(one\s*plus|一加|oneplus).*(buds|耳机)|nord\s*buds|\bbuds\b"""
    )

    val all: List<BudsModel> = listOf(
        m("buds3pro", "OnePlus Buds 3 Pro", """buds\s*3\s*pro"""),
        buds3,
        m("budspro3", "OnePlus Buds Pro 3", """buds\s*pro\s*3"""),
        m("budspro2r", "OnePlus Buds Pro 2R", """buds\s*pro\s*2\s*r"""),
        m("budspro2", "OnePlus Buds Pro 2", """buds\s*pro\s*2"""),
        m("budspro", "OnePlus Buds Pro", """buds\s*pro\b"""),
        m("budsz2", "OnePlus Buds Z2", """buds\s*z\s*2"""),
        m("budsz", "OnePlus Buds Z", """buds\s*z\b""", anc = false),
        m("buds2r", "OnePlus Buds 2R", """buds\s*2\s*r"""),
        m("buds2", "OnePlus Buds 2", """buds\s*2\b"""),
        m("nordbuds3pro", "OnePlus Nord Buds 3 Pro", """nord\s*buds\s*3\s*pro"""),
        m("nordbuds3", "OnePlus Nord Buds 3", """nord\s*buds\s*3"""),
        m("nordbuds2r", "OnePlus Nord Buds 2R", """nord\s*buds\s*2\s*r"""),
        m("nordbuds2", "OnePlus Nord Buds 2", """nord\s*buds\s*2"""),
        m("nordbuds", "OnePlus Nord Buds", """nord\s*buds""", anc = false),
        m("buds", "OnePlus Buds", """one\s*plus\s*buds\b""", anc = false),
    )

    /** İsimden model bul. OnePlus ailesine uymuyorsa null. */
    fun match(name: String?): BudsModel? {
        if (name.isNullOrBlank()) return null
        all.firstOrNull { it.pattern.containsMatchIn(name) }?.let { return it }
        return generic.takeIf { it.pattern.containsMatchIn(name) }
    }

    fun byId(id: String?): BudsModel? =
        if (id == null) null else all.firstOrNull { it.id == id } ?: generic.takeIf { it.id == id }
}
