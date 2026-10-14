package org.opencell.core.voice

/** A codec this build runs: its id, and whether it is still experimental (OpenVoxNB spec V3). */
data class CodecInfo(val id: Int, val experimental: Boolean = false) {
    val label: String get() = CodecId.label(id)

    /** How the setting and the "Call with…" menu name it: "OpenVoxNB 1200 (experimental)". */
    val choiceLabel: String get() = if (experimental) "$label (experimental)" else label
}

/** The user's choice of codecs (codec-negotiation spec §7.1): Automatic, or only one. */
sealed interface CodecPreference {
    data object Automatic : CodecPreference

    data class Only(val codecId: Int) : CodecPreference
}

/** What a phone offers and accepts, and what it answers with (codec-negotiation spec N3, N4, §7). */
object CodecChoice {
    /**
     * The `codec_caps` this phone offers when it dials and accepts when it answers:
     * [preference] within [supported]. Automatic is every supported codec that isn't
     * experimental (V3), or all of them if every one is; Only a codec this build lacks
     * reads as Automatic. It never adds a codec and never reorders the ranking (N4).
     */
    fun allowedCaps(preference: CodecPreference, supported: List<CodecInfo>): Int {
        if (preference is CodecPreference.Only && supported.any { it.id == preference.codecId }) {
            return CodecId.bit(preference.codecId)
        }
        val stable = supported.filterNot { it.experimental }.ifEmpty { supported }
        return CodecId.caps(stable.map { it.id })
    }

    /** The codec to answer with: the best in the caller's [offered] caps that [allowed] has, or [CodecId.NONE]. */
    fun answer(offered: Int, allowed: Int): Int = CodecId.best(offered and allowed)
}
