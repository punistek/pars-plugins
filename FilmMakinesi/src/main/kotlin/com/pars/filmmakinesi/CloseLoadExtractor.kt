package com.pars.filmmakinesi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CloseLoadExtractor : ExtractorApi() {

    override val name = "FilmMakinesi Close"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val pageReferer = referer ?: "https://filmmakinesi.to/"

        val html = try {
            app.get(
                url,
                referer = pageReferer,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (e: Throwable) {
            Log.e(TAG, "CLOSE_FETCH_ERROR url=$url error=$e")
            return null
        }

        Log.i(TAG, "CLOSE_HTML_FULL_LEN url=$url len=${html.length}")

        // AJAX/API cagrisi olabilecek anahtar kelimeleri ara vee
        // etrafindaki metni logla. Boylece JS'in nereden veri
        // cektigini gorebiliriz.
        val keywords = listOf(
            "ajax", "fetch(", "xhr.open", "XMLHttpRequest",
            "\$.get", "\$.post", "getSource", "get_source",
            "load_data", "loadSource", "player.setup",
            "sources:", "file:", "playerInstance", "jwplayer"
        )

        keywords.forEach { keyword ->
            var searchFrom = 0
            var found = 0
            while (found < 3) {
                val idx = html.indexOf(keyword, searchFrom, ignoreCase = true)
                if (idx == -1) break
                val start = maxOf(0, idx - 60)
                val end = minOf(html.length, idx + 200)
                val context = html.substring(start, end).replace("\n", " ")
                Log.i(TAG, "CLOSE_KEYWORD keyword=$keyword context=$context")
                searchFrom = idx + keyword.length
                found++
            }
        }

        // Onceki regex denemesi de kalsin, karsilastirma icin.
        val candidates = LinkedHashSet<String>()
        Regex(
            """https?://[^\s\"'<>\\]+?(?:\.m3u8|master\.txt|playlist\.txt|index\.txt)[^\s\"'<>\\]*""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { candidates.add(clean(it.value)) }

        Log.i(TAG, "CLOSE_M3U8_CANDIDATES count=${candidates.size} values=$candidates")

        return null
    }

    private fun clean(value: String): String = value
        .replace("\\/", "/")
        .replace("\\", "")
        .trim()
        .trim('"', '\'', ')', ']', '}')

    companion object {
        private const val TAG = "FM_CLOSE"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
