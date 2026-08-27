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
        referer: String?
    ): List<ExtractorLink>? {
        val pageReferer = referer ?: "https://filmmakinesi.to/"

        val html = try {
            app.get(
                url,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            ).text
        } catch (e: Throwable) {
            Log.e(TAG, "FETCH_ERROR url=$url error=$e")
            return null
        }

        return try {
            val sourceVariable = findSourceVariable(html)
                ?: run {
                    Log.e(TAG, "SOURCE_VARIABLE_NOT_FOUND")
                    return null
                }

            val assignment = findDecoderAssignment(html, sourceVariable)
                ?: run {
                    Log.e(TAG, "DECODER_ASSIGNMENT_NOT_FOUND variable=$sourceVariable")
                    return null
                }

            val functionBody = extractFunctionBody(html, assignment.functionName)
                ?: run {
                    Log.e(TAG, "DECODER_FUNCTION_NOT_FOUND name=${assignment.functionName}")
                    return null
                }

            val operations = parseOperations(functionBody)

            Log.i(
                TAG,
                "DECODER_FOUND variable=$sourceVariable " +
                    "function=${assignment.functionName} parts=${assignment.parts.size} " +
                    "ops=${operations.joinToString(" -> ")}"
            )

            if (assignment.parts.isEmpty() || operations.isEmpty()) {
                Log.e(TAG, "DECODER_EMPTY parts=${assignment.parts.size} ops=${operations.size}")
                return null
            }

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

            val streamUrl = normalizeDecodedUrl(result)

            Log.i(TAG, "DECODE_RESULT url=$streamUrl")

            if (!streamUrl.startsWith("http://") && !streamUrl.startsWith("https://")) {
                Log.e(TAG, "DECODE_RESULT_INVALID value=${streamUrl.take(500)}")
                return null
            }

            val lower = streamUrl.lowercase()
            val looksLikeHls =
                ".m3u8" in lower ||
                "master.txt" in lower ||
                "playlist.txt" in lower ||
                "index.txt" in lower ||
                "/hls/" in lower

            if (!looksLikeHls) {
                Log.w(TAG, "DECODE_RESULT_UNUSUAL url=$streamUrl")
            }

            val streamHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url,
                "Origin" to mainUrl,
                "Accept" to "*/*"
            )

            listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = streamHeaders
                    quality = Qualities.Unknown.value
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "DECODE_ERROR url=$url error=$e", e)
            null
        }
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
