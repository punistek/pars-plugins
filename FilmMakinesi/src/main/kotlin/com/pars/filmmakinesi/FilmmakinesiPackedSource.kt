package com.pars.filmmakinesi

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.utils.getAndUnpack

internal object FilmmakinesiPackedSource {

    private data class DecOp(val name: String, val rotShift: Int = 0)

    fun unpackAndDecrypt(script: String): String? {
        return try {
            val unpacked = getAndUnpack(script)
            decryptLocalUrl(unpacked)
        } catch (t: Throwable) {
            Log.e("FM-PACKED", "unpackAndDecrypt failed: ${t.message}")
            null
        }
    }

    /**
     * Mirrors the working HDFilmCehennemi repo's dynamic decoder:
     * - collect string parts
     * - infer atob/reverse/ROT operations in execution order
     * - infer modulo number + offset
     * - undo the final modulo mix
     *
     * It deliberately reads these values from the unpacked script instead of
     * hard-coding a FilmMakinesi CDN URL.
     */
    private fun decryptLocalUrl(unpackedScript: String): String? {
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

            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull()
                ?: return null
            val magicOffset = moduloMatch.groupValues[2].toIntOrNull()
                ?: return null

            val dcStart = unpackedScript.indexOf("function dc_")
            if (dcStart < 0) return null

            val d1xStart = unpackedScript.indexOf("function d1x", dcStart)
            val funcBody = if (d1xStart > dcStart) {
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
                val block = funcBody.substring(index, minOf(index + 300, funcBody.length))
                var shift = 13

                val m1 =
                    """charCodeAt\(0\)\s*\+\s*(\d+)"""
                        .toRegex()
                        .find(block)

                if (m1 != null) {
                    shift = m1.groupValues[1].toInt()
                } else {
                    val m2 =
                        """o\s*-\s*base\s*([+-])\s*(\d+)"""
                            .toRegex()
                            .find(block)

                    if (m2 != null) {
                        val sign = m2.groupValues[1]
                        val num = m2.groupValues[2].toInt()
                        shift = if (sign == "-") (26 - num) % 26 else num
                    }
                }

                operations += index to DecOp("rot", shift)
                index = funcBody.indexOf("replace", index + 1)
            }

            operations.sortBy { it.first }

            var result = parts.joinToString("")

            for ((_, op) in operations) {
                result = when (op.name) {
                    "reverse" -> result.reversed()

                    "atob" -> {
                        var padded = result
                        while (padded.length % 4 != 0) padded += "="
                        String(
                            Base64.decode(padded, Base64.NO_WRAP),
                            Charsets.ISO_8859_1
                        )
                    }

                    "rot" -> {
                        buildString {
                            for (c in result) {
                                when (c) {
                                    in 'a'..'z' -> {
                                        val shifted = c.code + op.rotShift
                                        append(
                                            if (shifted > 'z'.code)
                                                (shifted - 26).toChar()
                                            else shifted.toChar()
                                        )
                                    }

                                    in 'A'..'Z' -> {
                                        val shifted = c.code + op.rotShift
                                        append(
                                            if (shifted > 'Z'.code)
                                                (shifted - 26).toChar()
                                            else shifted.toChar()
                                        )
                                    }

                                    else -> append(c)
                                }
                            }
                        }
                    }

                    else -> result
                }
            }

            val unmix = StringBuilder()
            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decoded =
                    (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                unmix.append(decoded.toInt().toChar())
            }

            val decoded = unmix.toString()
                .replace("\\/", "/")
                .trim()
                .trim('"', '\'', ' ')

            // Keep only the actual absolute media URL if the decoder returned
            // surrounding text.
            Regex(
                """https?://[^"'\\\s<>]+?(?:\.m3u8|\.mpd|\.mp4|/master\.txt)(?:\?[^"'\\\s<>]*)?""",
                RegexOption.IGNORE_CASE
            ).find(decoded)?.value ?: decoded.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            }
        } catch (t: Throwable) {
            Log.e("FM-PACKED", "decryptLocalUrl failed: ${t.message}")
            null
        }
    }
}
