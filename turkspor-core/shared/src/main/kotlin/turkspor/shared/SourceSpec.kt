package turkspor.shared

enum class SourceMode { WORDPRESS, ROYAL, INTER, BEYAZ }
data class SourceSpec(val key: String, val name: String, val roots: List<String>, val host: Regex, val markers: List<String>, val mode: SourceMode, val catalogPath: String = "") {
    companion object {
        val all = listOf(
            SourceSpec("mackeyfi", "Maçkeyfi / Canlımaçlar", listOf("https://www.mackeyfi559.sbs/", "https://www.canlimaclar559.sbs/"), Regex("(www\\.)?(mackeyfi|canlimaclar)[0-9]+\\.sbs"), listOf("Maçkeyfi", "Canlımaclar"), SourceMode.WORDPRESS),
            SourceSpec("zbahistv", "Zbahis / Baywin TV", listOf("https://zbahistv65.com/", "https://99baywintv.live/"), Regex("(www\\.)?(zbahistv[0-9]+\\.com|[0-9]+baywintv\\.live)"), listOf("Zbahis", "Baywin"), SourceMode.ROYAL),
            SourceSpec("intersportv", "İntersportv", listOf("https://intersportv1.live/"), Regex("(www\\.)?intersportv[0-9]+\\.live"), listOf("İntersportv", "Intersportv"), SourceMode.INTER),
            SourceSpec("beyazelma", "BeyazElma", listOf("https://beyazelma78.com/"), Regex("(www\\.)?beyazelma[0-9]+\\.com"), listOf("BeyazElma"), SourceMode.BEYAZ, "kanallar")
        ).associateBy { it.key }
    }
}
data class Channel(val id: String, val title: String, val players: List<String>, val logo: String = "")
data class SiteSnapshot(val url: String, val channels: List<Channel>, val checkedAt: Long)
data class Playback(val url: String, val referer: String, val label: String = "")
