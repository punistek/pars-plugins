package com.pars.filmmakinesi

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.utils.getAndUnpack

/**
 * FilmMakinesi CloseLoad sayfasındaki gerçek kaynak çözümünü yapar.
 *
 * İki yöntemi destekler:
 * 1) FilmMakinesi'nin güncel özel şifreleyicisi:
 *    function xxxx(parts) { ... key + pin + shuffle + xor ... }
 *    var stream = xxxx(["parça1", "parça2", ...]);
 *
 * 2) Eski/HDFilmCehennemi tarzı packed source çözümü fallback olarak.
 */
internal object FilmmakinesiPackedSource {

    private data class CustomConfig(
        val key: String,
        val pin: String,
        val parts: List<String>,
        val hashMul: Int = 31,
        val hashMod: Int = 251,
        val stateMod: Int = 256,
        val shiftMod: Int = 13,
        val shiftAdd: Int = 3,
        val seedMod: Int = 65521,
        val shuffleMul: Int = 75,
        val shuffleAdd: Int = 74,
        val shuffleMod: Int = 65537
    )

    private data class DecOp(val name: String, val rotShift: Int = 0)

    fun decryptFromPage(html: String): String? {
        return runCatching {
            decryptFilmMakinesiCustom(html)
                ?: decryptHdfStyleFromPage(html)
        }.onFailure {
            Log.e("FM-PACKED", "decryptFromPage failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * Güncel FilmMakinesi CloseLoad gerçek algoritması.
     *
     * Örnek yapı:
     * function f6vp(rh0a) {
     *   var yz200 = rh0a.join('');
     *   var jit = "...";
     *   var kni = "bvbv";
     *   ...
     * }
     * var lh1 = f6vp(["...", "..."]);
     * sources: [{file: lh1, type:"hls"}]
     *
     * Değişken/fonksiyon isimleri rastgele olabildiği için isimlere bağlı değiliz.
     */
    private fun decryptFilmMakinesiCustom(html: String): String? {
        val invocationRegex = Regex(
            """var\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\(\s*\[((?:"[^"]*"\s*,?\s*)+)]\s*\)\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        val invocations = invocationRegex.findAll(html).toList()

        for (inv in invocations) {
            val resultVar = inv.groupValues[1]
            val functionName = inv.groupValues[2]
            val rawParts = inv.groupValues[3]

            // Bu değişken JW source olarak gerçekten kullanılıyor mu?
            val usedAsSource = Regex(
                """(?:file|src)\s*:\s*${Regex.escape(resultVar)}\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(html)

            if (!usedAsSource) continue

            val functionStart = Regex(
                """function\s+${Regex.escape(functionName)}\s*\(\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*\)\s*\{""",
                RegexOption.IGNORE_CASE
            ).find(html)?.range?.first ?: continue

            val functionBody = extractFunctionBody(html, functionStart) ?: continue

            // Gerçek FilmMakinesi decoder'ını diğer sıradan fonksiyonlardan ayır.
            if (!functionBody.contains(".join('')") &&
                !functionBody.contains(".join(\"\")")) continue
            if (!functionBody.contains("charCodeAt")) continue
            if (!functionBody.contains("atob(")) continue
            if (!functionBody.contains("split('').reverse().join('')") &&
                !functionBody.contains("split(\"\").reverse().join(\"\")")) continue

            val stringVars = Regex(
                """var\s+[A-Za-z_$][A-Za-z0-9_$]*\s*=\s*"([^"]+)"\s*;"""
            ).findAll(functionBody).map { it.groupValues[1] }.toList()

            if (stringVars.size < 2) continue

            val key = stringVars[0]
            val pin = stringVars[1]

            val parts = Regex(""""([^"]*)"""")
                .findAll(rawParts)
                .map { it.groupValues[1] }
                .toList()

            if (parts.isEmpty()) continue

            val cfg = CustomConfig(
                key = key,
                pin = pin,
                parts = parts,
                hashMul = Regex("""\*\s*(\d+)\s*\+\s*[A-Za-z_$][A-Za-z0-9_$]*\)\s*%\s*(\d+)""")
                    .find(functionBody)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 31,
                hashMod = Regex("""\*\s*\d+\s*\+\s*[A-Za-z_$][A-Za-z0-9_$]*\)\s*%\s*(\d+)""")
                    .find(functionBody)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 251,
                shiftMod = Regex("""%\s*(\d+)\)\s*\+\s*(\d+)""")
                    .find(functionBody)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 13,
                shiftAdd = Regex("""%\s*\d+\)\s*\+\s*(\d+)""")
                    .find(functionBody)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 3,
                seedMod = Regex("""%\s*(65521|[0-9]{4,6})\)\s*\+\s*1""")
                    .find(functionBody)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 65521,
                shuffleMul = Regex("""=\s*\([^;]*\*\s*(\d+)\s*\+\s*(\d+)\)\s*%\s*(65537|[0-9]{4,6})""")
                    .find(functionBody)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 75,
                shuffleAdd = Regex("""=\s*\([^;]*\*\s*\d+\s*\+\s*(\d+)\)\s*%\s*(65537|[0-9]{4,6})""")
                    .find(functionBody)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 74,
                shuffleMod = Regex("""=\s*\([^;]*\*\s*\d+\s*\+\s*\d+\)\s*%\s*(\d+)""")
                    .find(functionBody)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 65537
            )

            val decoded = decodeCustom(cfg)
                ?.replace("\\/", "/")
                ?.trim()
                ?.trim('"', '\'', ' ')

            val url = extractHlsUrl(decoded.orEmpty())
            if (!url.isNullOrBlank()) {
                Log.d(
                    "FM-PACKED",
                    "CUSTOM_OK fn=$functionName var=$resultVar pin=$pin url=$url"
                )
                return url
            }

            Log.d(
                "FM-PACKED",
                "CUSTOM_DECODE_NO_HLS fn=$functionName decoded=${decoded?.take(220)}"
            )
        }

        return null
    }

    private fun extractFunctionBody(text: String, functionStart: Int): String? {
        val braceStart = text.indexOf('{', functionStart)
        if (braceStart < 0) return null

        var depth = 0
        var quote: Char? = null
        var escape = false

        for (i in braceStart until text.length) {
            val c = text[i]

            if (quote != null) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == quote) {
                    quote = null
                }
                continue
            }

            if (c == '\'' || c == '"' || c == '`') {
                quote = c
                continue
            }

            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(braceStart + 1, i)
                    }
                }
            }
        }

        return null
    }

    private fun decodeCustom(cfg: CustomConfig): String? {
        return try {
            var value = cfg.parts.joinToString("")

            var hashState = 0
            var xorState = 0

            cfg.key.forEachIndexed { index, ch ->
                val code = ch.code
                hashState = (hashState * cfg.hashMul + code) % cfg.hashMod
                xorState = (xorState xor (code + index)) and 255
            }

            val xorSeed = (hashState + xorState) % cfg.stateMod
            val xorStep = (hashState % cfg.shiftMod) + cfg.shiftAdd
            var shuffleSeed =
                ((hashState * 256 + xorState) % cfg.seedMod) + 1

            // JS kodunda pin sondan başa uygulanıyor.
            for (index in cfg.pin.length - 1 downTo 0) {
                val op = cfg.pin[index]

                value = when (op) {
                    'b' -> base64DecodeLatin1(value) ?: return null
                    'v' -> value.reversed()
                    else -> {
                        val shift =
                            (26 - ((op.code - 64) % 26) + 26) % 26
                        rotateLetters(value, shift)
                    }
                }
            }

            val length = value.length
            if (length <= 0) return null

            val swaps = IntArray(length)

            for (i in length - 1 downTo 1) {
                shuffleSeed =
                    (shuffleSeed * cfg.shuffleMul + cfg.shuffleAdd) %
                        cfg.shuffleMod
                swaps[i] = shuffleSeed % (i + 1)
            }

            val chars = value.toCharArray()

            for (i in 1 until length) {
                val j = swaps[i]
                val tmp = chars[i]
                chars[i] = chars[j]
                chars[j] = tmp
            }

            var state = xorSeed
            val out = StringBuilder(length)

            for (ch in chars) {
                val code = ch.code and 255
                state = (state + xorStep) % 256
                out.append((code xor state).toChar())
                state = (state + code) % 256
            }

            out.toString()
        } catch (t: Throwable) {
            Log.e("FM-PACKED", "decodeCustom failed: ${t.message}")
            null
        }
    }

    private fun base64DecodeLatin1(raw: String): String? {
        return try {
            var value = raw
            while (value.length % 4 != 0) value += "="
            String(
                Base64.decode(value, Base64.DEFAULT),
                Charsets.ISO_8859_1
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun rotateLetters(value: String, shift: Int): String {
        return buildString(value.length) {
            value.forEach { c ->
                when (c) {
                    in 'a'..'z' ->
                        append(((c.code - 97 + shift) % 26 + 97).toChar())
                    in 'A'..'Z' ->
                        append(((c.code - 65 + shift) % 26 + 65).toChar())
                    else -> append(c)
                }
            }
        }
    }

    private fun extractHlsUrl(text: String): String? {
        if (text.isBlank()) return null

        return Regex(
            """https?://[^"'\\\s<>]+?(?:\.m3u8|/master\.txt|/txt/master\.txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).find(text)?.value
            ?: text.takeIf {
                val lower = it.lowercase()
                (it.startsWith("http://", true) ||
                    it.startsWith("https://", true)) &&
                    (
                        lower.contains(".m3u8") ||
                        lower.contains("/master.txt") ||
                        lower.contains("/txt/master.txt")
                    )
            }
    }

    // ------------------------------------------------------------
    // HDFilmCehennemi tarzı decoder fallback
    // ------------------------------------------------------------

    private fun decryptHdfStyleFromPage(html: String): String? {
        val scripts = Regex(
            """<script[^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html)

        for (match in scripts) {
            val script = match.groupValues[1]
            if (!script.contains("eval(function(p,a,c,k,e,d)", true)) continue

            val unpacked = runCatching { getAndUnpack(script) }.getOrNull()
                ?: continue

            val decoded = decryptHdfStyle(unpacked)
            val url = extractHlsUrl(decoded.orEmpty())

            if (!url.isNullOrBlank()) {
                Log.d("FM-PACKED", "HDF_FALLBACK_OK url=$url")
                return url
            }
        }

        return null
    }

    private fun decryptHdfStyle(unpackedScript: String): String? {
        return try {
            val partsMatch =
                """\(\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\)"""
                    .toRegex()
                    .find(unpackedScript)

            val parts = partsMatch
                ?.groupValues
                ?.get(1)
                ?.split(",")
                ?.map {
                    it.trim()
                        .trim('\'', '"')
                        .replace("\\/", "/")
                }
                ?: return null

            val moduloMatch =
                """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)"""
                    .toRegex()
                    .find(unpackedScript)

            val magicNum =
                moduloMatch?.groupValues?.get(1)?.toLongOrNull()
                    ?: 399756995L

            val magicOffset =
                moduloMatch?.groupValues?.get(2)?.toIntOrNull()
                    ?: 5

            val dcStart = unpackedScript.indexOf("function dc_")
            if (dcStart < 0) return null

            val d1xStart = unpackedScript.indexOf("function d1x", dcStart)
            val funcBody =
                if (d1xStart > dcStart) {
                    unpackedScript.substring(dcStart, d1xStart)
                } else {
                    unpackedScript.substring(dcStart)
                }

            val operations = mutableListOf<Pair<Int, DecOp>>()

            var index = funcBody.indexOf("atob(")
            while (index >= 0) {
                operations += index to DecOp("atob")
                index = funcBody.indexOf("atob(", index + 1)
            }

            index = funcBody.indexOf("reverse")
            while (index >= 0) {
                operations += index to DecOp("reverse")
                index = funcBody.indexOf("reverse", index + 1)
            }

            index = funcBody.indexOf("replace")
            while (index >= 0) {
                val block =
                    funcBody.substring(
                        index,
                        minOf(index + 300, funcBody.length)
                    )

                var shift = 13

                Regex("""charCodeAt\(0\)\s*\+\s*(\d+)""")
                    .find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let { shift = it }

                operations += index to DecOp("rot", shift)
                index = funcBody.indexOf("replace", index + 1)
            }

            operations.sortBy { it.first }

            var result = parts.joinToString("")

            operations.forEach { (_, op) ->
                result = when (op.name) {
                    "reverse" -> result.reversed()
                    "atob" -> base64DecodeLatin1(result) ?: return null
                    "rot" -> rotateLetters(result, op.rotShift)
                    else -> result
                }
            }

            val unmix = StringBuilder()

            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decoded =
                    (charCode -
                        (magicNum % (i + magicOffset)) +
                        256) % 256
                unmix.append(decoded.toInt().toChar())
            }

            unmix.toString()
        } catch (_: Throwable) {
            null
        }
    }
}
