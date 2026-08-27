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
            app.get(url, referer = pageReferer, headers = mapOf("User-Agent" to USER_AGENT)).text
        } catch (e: Throwable) {
            Log.e(TAG, "CLOSE_FETCH_ERROR url=$url error=$e")
            return null
        }

        Log.i(TAG, "CLOSE_HTML_FULL_LEN url=$url len=${html.length}")
        val variables = findSourceVariables(html)
        Log.i(TAG, "CLOSE_SOURCE_VARIABLES count=${variables.size} values=$variables")
        variables.forEach { logVariableDefinitions(html, it) }
        logInlineScripts(html, variables)

        val directCandidates = LinkedHashSet<String>()
        Regex("""https?://[^\s\"'<>\\]+?(?:\.m3u8|master\.txt|playlist\.txt|index\.txt)[^\s\"'<>\\]*""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { directCandidates += it.value.replace("\\/", "/").replace("\\", "") }
        Log.i(TAG, "CLOSE_DIRECT_CANDIDATES count=${directCandidates.size} values=$directCandidates")
        return null
    }

    private fun findSourceVariables(html: String): LinkedHashSet<String> {
        val vars = LinkedHashSet<String>()
        Regex("""(?i)sources\s*:\s*\[\s*\{\s*file\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""")
            .findAll(html).forEach { vars += it.groupValues[1] }
        Regex("""(?i)\bfile\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""")
            .findAll(html).forEach {
                val v = it.groupValues[1]
                if (v.startsWith("s_")) vars += v
            }
        return vars
    }

    private fun logVariableDefinitions(html: String, variable: String) {
        Log.i(TAG, "VAR_TRACE_BEGIN variable=$variable htmlLen=${html.length}")
        var pos = 0
        var occurrence = 0
        while (occurrence < 20) {
            val idx = html.indexOf(variable, pos, ignoreCase = false)
            if (idx < 0) break
            val start = maxOf(0, idx - 500)
            val end = minOf(html.length, idx + variable.length + 900)
            val context = html.substring(start, end).replace("\r", " ").replace("\n", " ")
            Log.i(TAG, "VAR_OCCURRENCE variable=$variable n=$occurrence idx=$idx context=$context")
            occurrence++
            pos = idx + variable.length
        }

        val escaped = Regex.escape(variable)
        val patterns = listOf(
            Regex("""(?is)\b(?:var|let|const)\s+$escaped\s*=\s*(.+?);"""),
            Regex("""(?is)(?<![A-Za-z0-9_$])$escaped\s*=\s*(.+?);""")
        )
        var assignments = 0
        patterns.forEachIndexed { p, rx ->
            rx.findAll(html).forEach { m ->
                val rhs = m.groupValues.getOrNull(1)?.trim().orEmpty()
                Log.i(TAG, "VAR_ASSIGN variable=$variable pattern=$p rhs=${rhs.take(2500)}")
                assignments++
            }
        }

        listOf("atob(", "btoa(", "decodeURIComponent(", "String.fromCharCode", "eval(", "unescape(", "CryptoJS", "base64", "xor", "reverse(")
            .forEach { keyword ->
                var from = 0
                var count = 0
                while (count < 5) {
                    val idx = html.indexOf(keyword, from, ignoreCase = true)
                    if (idx < 0) break
                    val start = maxOf(0, idx - 350)
                    val end = minOf(html.length, idx + 900)
                    val context = html.substring(start, end).replace("\r", " ").replace("\n", " ")
                    Log.i(TAG, "DECODE_HINT variable=$variable keyword=$keyword context=$context")
                    from = idx + keyword.length
                    count++
                }
            }
        Log.i(TAG, "VAR_TRACE_DONE variable=$variable occurrences=$occurrence assignments=$assignments")
    }

    private fun logInlineScripts(html: String, variables: Set<String>) {
        Regex("""(?is)<script\b[^>]*>(.*?)</script>""").findAll(html).forEachIndexed { index, match ->
            val script = match.groupValues[1]
            if (variables.any { script.contains(it) }) {
                Log.i(TAG, "INLINE_SCRIPT_HIT index=$index len=${script.length} body=${script.take(12000)}")
            }
        }
    }

    companion object {
        private const val TAG = "FM_CLOSE"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
