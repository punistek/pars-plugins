package com.pars.filmmakinesi

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.nio.charset.StandardCharsets

class CloseLoadExtractor : ExtractorApi() {

    override val name = "FilmMakinesi Close"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "https://filmmakinesi.to/"

        val response = try {
            app.get(
                url,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "FETCH_ERROR url=$url error=$e")
            return
        }

        val html = response.text
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        emitSubtitles(html, subtitleCallback)

        val links = LinkedHashSet<String>()

        // 1) Sitenin mevcut obfuscation decoder mantığını önce dene.
        runCatching {
            val sourceVariable = findSourceVariable(html) ?: return@runCatching
            val assignment = findDecoderAssignment(html, sourceVariable) ?: return@runCatching
            val functionBody = extractFunctionBody(html, assignment.functionName) ?: return@runCatching
            val operations = parseOperations(functionBody)
            if (assignment.parts.isEmpty() || operations.isEmpty()) return@runCatching

            var result = assignment.parts.joinToString("")
            for (operation in operations) {
                result = when (operation) {
                    is DecodeOperation.Base64Decode -> decodeBase64(result)
                    is DecodeOperation.Reverse -> result.reversed()
                    is DecodeOperation.Caesar -> caesar(result, operation.shift)
                    is DecodeOperation.XorUnmix -> xorUnmix(
                        value = result,
                        initialAcc = operation.initialAcc,
                        step = operation.step
                    )
                }
            }

            normalizeDecodedUrl(result)
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let(links::add)
        }

        // 2) JSON-LD VideoObject içindeki contentUrl. Analizde CloseLoad bunu veriyor.
        Regex(
            """(?is)[\"']contentUrl[\"']\s*:\s*[\"']([^\"']+)[\"']"""
        ).findAll(html).forEach { links += cleanUrl(it.groupValues[1]) }

        // 3) Açık medya URL'leri; HLS bazı sayfalarda .m3u8 yerine /master.txt ile bitiyor.
        Regex(
            """https?://[^\"'\\s<>]+?(?:\.m3u8|\.mpd|\.mp4|/master\.txt)(?:\?[^\"'\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { links += cleanUrl(it.value) }

        // 4) JWPlayer file/src/source/url alanları.
        Regex(
            """(?is)(?:file|src|source|url)\s*[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']"""
        ).findAll(html).forEach { match ->
            val value = cleanUrl(match.groupValues[1])
            if (looksLikeMedia(value)) links += value
        }

        // 5) DOM video/source/audio.
        response.document.select("video[src], source[src], audio[src]").forEach { element ->
            val value = cleanUrl(element.attr("src"))
            if (looksLikeMedia(value)) links += absoluteUrl(value)
        }

        val emitted = LinkedHashSet<String>()
        links.forEach { raw ->
            val stream = absoluteUrl(raw)
            if (!emitted.add(stream)) return@forEach

            val type = when {
                isHls(stream) -> ExtractorLinkType.M3U8
                stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = if (isHls(stream)) "$name HLS" else name,
                    url = stream,
                    type = type
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to url,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                }
            )
        }
    }

    private fun emitSubtitles(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val seen = LinkedHashSet<String>()

        Regex(
            """(?is)\{[^{}]*[\"']file[\"']\s*:\s*[\"']([^\"']+\.(?:vtt|srt)[^\"']*)[\"'][^{}]*[\"']label[\"']\s*:\s*[\"']([^\"']+)[\"'][^{}]*\}"""
        ).findAll(html).forEach { m ->
            val subUrl = absoluteUrl(cleanUrl(m.groupValues[1]))
            if (seen.add(subUrl)) subtitleCallback(SubtitleFile(m.groupValues[2], subUrl))
        }

        Regex(
            """(?is)\{[^{}]*[\"']label[\"']\s*:\s*[\"']([^\"']+)[\"'][^{}]*[\"']file[\"']\s*:\s*[\"']([^\"']+\.(?:vtt|srt)[^\"']*)[\"'][^{}]*\}"""
        ).findAll(html).forEach { m ->
            val subUrl = absoluteUrl(cleanUrl(m.groupValues[2]))
            if (seen.add(subUrl)) subtitleCallback(SubtitleFile(m.groupValues[1], subUrl))
        }
    }

    private fun cleanUrl(value: String): String = value
        .trim()
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .trim('"', '\'', ' ')

    private fun absoluteUrl(raw: String): String {
        val value = cleanUrl(raw)
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    private fun isHls(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/master.txt") ||
            lower.contains("/playlist.txt") ||
            lower.contains("/hls/")
    }

    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        return isHls(lower) || lower.contains(".mp4") || lower.contains(".mpd")
    }

    /**
     * JWPlayer tarafındaki:
     * sources: [{file: s_xxxxx, type: "hls"}]
     * değişkenini bulur.
     */
    private fun findSourceVariable(html: String): String? {
        return Regex(
            """(?is)sources\s*:\s*\[\s*\{\s*file\s*:\s*(s_[A-Za-z0-9_$]+)"""
        ).find(html)?.groupValues?.getOrNull(1)
    }

    /**
     * Örnek:
     * var s_xxx = dc_ABC(["parca1","parca2",...]);
     */
    private fun findDecoderAssignment(
        html: String,
        variable: String
    ): DecoderAssignment? {
        val variableEscaped = Regex.escape(variable)

        val assignmentRegex = Regex(
            """(?is)\b(?:var|let|const)\s+$variableEscaped\s*=\s*""" +
                """([A-Za-z_$][A-Za-z0-9_$]*)\s*\(\s*\[(.*?)\]\s*\)\s*;"""
        )

        val match = assignmentRegex.find(html) ?: return null

        val functionName = match.groupValues[1]
        val rawArray = match.groupValues[2]

        val parts = Regex(
            """(["'])((?:\\.|(?!\1).)*)\1"""
        ).findAll(rawArray).map { stringMatch ->
            unescapeJsString(stringMatch.groupValues[2])
        }.toList()

        return DecoderAssignment(
            functionName = functionName,
            parts = parts
        )
    }

    /**
     * İç içe function(c) { ... } bulunduğu için basit regex yerine
     * brace sayarak decoder function gövdesini eksiksiz çıkarır.
     */
    private fun extractFunctionBody(
        html: String,
        functionName: String
    ): String? {
        val headerRegex = Regex(
            """(?is)\bfunction\s+${Regex.escape(functionName)}\s*\([^)]*\)\s*\{"""
        )

        val header = headerRegex.find(html) ?: return null
        val openBrace = html.indexOf('{', header.range.first)
        if (openBrace < 0) return null

        var depth = 0
        var quote: Char? = null
        var escaped = false
        var lineComment = false
        var blockComment = false
        var i = openBrace

        while (i < html.length) {
            val c = html[i]
            val next = if (i + 1 < html.length) html[i + 1] else '\u0000'

            if (lineComment) {
                if (c == '\n') lineComment = false
                i++
                continue
            }

            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false
                    i += 2
                    continue
                }
                i++
                continue
            }

            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == quote) {
                    quote = null
                }
                i++
                continue
            }

            if (c == '/' && next == '/') {
                lineComment = true
                i += 2
                continue
            }

            if (c == '/' && next == '*') {
                blockComment = true
                i += 2
                continue
            }

            if (c == '"' || c == '\'' || c == '`') {
                quote = c
                i++
                continue
            }

            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return html.substring(openBrace + 1, i)
                    }
                }
            }

            i++
        }

        return null
    }

    /**
     * Sitedeki decoder her filmde farklı işlem sırası üretebiliyor.
     * Bu yüzden sabit algoritma kullanmıyoruz; function gövdesindeki
     * gerçek işlem sırasını pozisyonlarına göre çıkarıyoruz.
     */
    private fun parseOperations(body: String): List<DecodeOperation> {
        val positioned = mutableListOf<Pair<Int, DecodeOperation>>()

        Regex(
            """(?is)result\s*=\s*atob\s*\(\s*result\s*\)\s*;"""
        ).findAll(body).forEach {
            positioned += it.range.first to DecodeOperation.Base64Decode
        }

        Regex(
            """(?is)result\s*=\s*result\s*\.split\s*\(\s*['"]{2}\s*\)""" +
                """\s*\.reverse\s*\(\s*\)\s*\.join\s*\(\s*['"]{2}\s*\)\s*;"""
        ).findAll(body).forEach {
            positioned += it.range.first to DecodeOperation.Reverse
        }

        // result.replace(/[a-zA-Z]/g, ... + SHIFT) % 26 ...
        Regex(
            """(?is)result\s*=\s*result\s*\.replace\s*\(\s*/\[a-zA-Z\]/g\s*,""" +
                """.*?String\.fromCharCode\s*\(\s*\(\s*o\s*-\s*base\s*\+\s*(\d+)""" +
                """\s*\)\s*%\s*26\s*\+\s*base\s*\).*?\)\s*;"""
        ).findAll(body).forEach { match ->
            val shift = match.groupValues[1].toIntOrNull() ?: return@forEach
            positioned += match.range.first to DecodeOperation.Caesar(shift)
        }

        // Final byte unmix:
        // var acc = 69;
        // ...
        // acc = (acc + 20) % 256;
        // var plain = b ^ acc;
        // acc = (acc + b) % 256;
        val xorRegex = Regex(
            """(?is)var\s+acc\s*=\s*(\d+)\s*;""" +
                """.*?for\s*\(.*?\)\s*\{""" +
                """.*?acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\s*\)\s*%\s*256\s*;""" +
                """.*?(?:var|let|const)\s+\w+\s*=\s*b\s*\^\s*acc\s*;""" +
                """.*?acc\s*=\s*\(\s*acc\s*\+\s*b\s*\)\s*%\s*256\s*;"""
        )

        xorRegex.find(body)?.let { match ->
            val initialAcc = match.groupValues[1].toIntOrNull()
            val step = match.groupValues[2].toIntOrNull()
            if (initialAcc != null && step != null) {
                positioned += match.range.first to DecodeOperation.XorUnmix(
                    initialAcc = initialAcc,
                    step = step
                )
            }
        }

        return positioned
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun decodeBase64(value: String): String {
        val cleaned = value.trim()
        val bytes = Base64.decode(cleaned, Base64.DEFAULT)
        // JS atob byte-string üretir. UTF-8 kullanmak 0x80-0xFF byte'larını
        // bozacağı için ISO-8859-1 ile 1 byte = 1 char koruyoruz.
        return String(bytes, StandardCharsets.ISO_8859_1)
    }

    private fun caesar(value: String, shift: Int): String {
        val normalized = ((shift % 26) + 26) % 26

        return buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    in 'A'..'Z' -> {
                        append(
                            ('A'.code + ((c.code - 'A'.code + normalized) % 26)).toChar()
                        )
                    }

                    in 'a'..'z' -> {
                        append(
                            ('a'.code + ((c.code - 'a'.code + normalized) % 26)).toChar()
                        )
                    }

                    else -> append(c)
                }
            }
        }
    }

    private fun xorUnmix(
        value: String,
        initialAcc: Int,
        step: Int
    ): String {
        var acc = initialAcc and 0xFF

        return buildString(value.length) {
            value.forEach { c ->
                val b = c.code and 0xFF

                acc = (acc + step) and 0xFF
                val plain = b xor acc
                acc = (acc + b) and 0xFF

                append(plain.toChar())
            }
        }
    }

    private fun normalizeDecodedUrl(value: String): String {
        return value
            .trim()
            .trim('\u0000')
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
    }

    private fun unescapeJsString(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0

        while (i < value.length) {
            val c = value[i]

            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }

            val next = value[i + 1]

            when (next) {
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                '"' -> out.append('"')
                '\'' -> out.append('\'')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')

                'u' -> {
                    if (i + 5 < value.length) {
                        val hex = value.substring(i + 2, i + 6)
                        val decoded = hex.toIntOrNull(16)
                        if (decoded != null) {
                            out.append(decoded.toChar())
                            i += 6
                            continue
                        }
                    }
                    out.append('u')
                }

                else -> out.append(next)
            }

            i += 2
        }

        return out.toString()
    }

    private data class DecoderAssignment(
        val functionName: String,
        val parts: List<String>
    )

    private sealed class DecodeOperation {
        data object Base64Decode : DecodeOperation()
        data object Reverse : DecodeOperation()
        data class Caesar(val shift: Int) : DecodeOperation()
        data class XorUnmix(
            val initialAcc: Int,
            val step: Int
        ) : DecodeOperation()
    }

    companion object {
        private const val TAG = "FM_CLOSE"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
