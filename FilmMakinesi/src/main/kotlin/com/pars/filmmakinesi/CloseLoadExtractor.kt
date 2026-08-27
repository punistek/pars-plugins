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
        val pageHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to pageReferer)

        val html = try {
            app.get(url, headers = pageHeaders).text
        } catch (e: Throwable) {
            Log.e(TAG, "CLOSE_FETCH_ERROR url=$url error=$e")
            return null
        }

        Log.i(
            TAG,
            "CLOSE_HTML url=$url len=${html.length} preview=" +
                html.substring(0, minOf(1000, html.length)).replace("\n", " ")
        )

        val decoded = decode(html)
        val candidates = LinkedHashSet<String>()

        collectCandidates(decoded, candidates)
        collectCandidates(decode(decoded), candidates)

        Log.i(TAG, "CLOSE_CANDIDATES_RAW count=${candidates.size} values=$candidates")

        val ordered = candidates
            .asSequence()
            .map(::clean)
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .sortedBy(::score)
            .toList()

        Log.i(TAG, "CLOSE_CANDIDATES_ORDERED count=${ordered.size} values=$ordered")

        for (stream in ordered) {
            val streamHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url,
                "Origin" to mainUrl,
                "Accept" to "*/*"
            )

            val works = isWorkingHls(stream, streamHeaders)
            Log.i(TAG, "CLOSE_CHECK stream=$stream works=$works")

            if (!works) continue

            return listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = stream,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = streamHeaders
                    quality = Qualities.Unknown.value
                }
            )
        }

        Log.i(TAG, "CLOSE_NO_WORKING_STREAM_FOUND")
        return null
    }

    private suspend fun isWorkingHls(stream: String, headers: Map<String, String>): Boolean {
        return try {
            val response = app.get(stream, headers = headers)
            val body = response.text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            Log.i(
                TAG,
                "CLOSE_STREAM_CHECK url=$stream status=${response.code} " +
                    "bodyPreview=" + body.substring(0, minOf(120, body.length))
            )
            body.startsWith("#EXTM3U", ignoreCase = true)
        } catch (e: Throwable) {
            Log.e(TAG, "CLOSE_STREAM_CHECK_ERROR url=$stream error=$e")
            false
        }
    }

    private fun collectCandidates(text: String, out: LinkedHashSet<String>) {
        Regex(
            """https?://[^\s\"'<>\\]+?(?:\.m3u8|master\.txt|playlist\.txt|index\.txt)[^\s\"'<>\\]*""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { out.add(clean(it.value)) }

        Regex("""(?i)(?:file|src|source)\s*[:=]\s*[\"']([^\"']+)[\"']""")
            .findAll(text).forEach {
                val value = clean(it.groupValues[1])
                val lower = value.lowercase()
                if (value.startsWith("http") &&
                    (".m3u8" in lower || "master.txt" in lower ||
                     "playlist.txt" in lower || "index.txt" in lower)
                ) out.add(value)
            }
    }

    private fun score(value: String): Int {
        val v = value.lowercase()
        return when {
            "master.m3u8" in v -> 0
            "master.txt" in v -> 1
            "1080" in v && ".m3u8" in v -> 2
            "720" in v && ".m3u8" in v -> 3
            "playlist.m3u8" in v -> 4
            "playlist.txt" in v -> 5
            ".m3u8" in v -> 6
            "index.txt" in v -> 7
            else -> 20
        }
    }

    private fun decode(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003D", "=")
        .replace("&amp;", "&")
        .replace("%3A", ":", ignoreCase = true)
        .replace("%2F", "/", ignoreCase = true)
        .replace("%3F", "?", ignoreCase = true)
        .replace("%26", "&", ignoreCase = true)
        .replace("%3D", "=", ignoreCase = true)

    private fun clean(value: String): String = decode(value)
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
