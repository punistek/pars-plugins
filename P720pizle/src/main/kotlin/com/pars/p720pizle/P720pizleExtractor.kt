package com.pars.p720pizle

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "bePlayer" (bepeak temasi, CryptoJS AES sifreli player) sablonunu
 * kullanan embed sunucular icin otomatik uretilmis extractor.
 *
 * Sayfada soyle bir cagri bulunuyor:
 *   bePlayer('PAROLA', '{"ct":"...","iv":"...","s":"..."}');
 *
 * Bu, CryptoJS.AES.decrypt(sifreliMetin, parola, {format:...}) demektir:
 *   1) key = OpenSSL EVP_BytesToKey(parola, salt, 32 bayt) [MD5 tabanli]
 *   2) iv  = dogrudan verilen 'iv' alani (hex)
 *   3) ciphertext = base64'ten cozulmus 'ct' alani
 *   4) AES-256-CBC decrypt + PKCS7 unpad -> UTF8 JSON
 * Cozulen JSON icindeki "video_location" alani gercek video linkidir.
 */
class P720pizleExtractor : ExtractorApi() {
    override val name="720pizle Extractor"
    override val mainUrl="https://hotstream.club"
    override val requiresReferer=true

    override suspend fun getUrl(url:String,referer:String?):List<ExtractorLink>? {
        val pageReferer = referer ?: "https://720izle.com/"

        val response = try {
            app.get(url, referer = pageReferer, headers = mapOf("User-Agent" to USER_AGENT))
        } catch (e: Throwable) {
            Log.e(TAG, "FETCH_ERROR url=$url error=$e")
            return null
        }
        val html = response.text

        // ONEMLI: /list/{token} gibi video linkleri, embed sayfasini cekerken
        // sunucunun verdigi oturum cerezine (orn. PHPSESSID) bagli olabiliyor.
        // Bu cerezi yakalayip video linkine EKLEMEZSEK sunucu "gecersiz oturum"
        // diyerek 404/Video Not Found donuyor - cerezsiz istek, sifre cozme
        // dogru olsa bile calismiyor.
        val sessionCookie = try {
            response.cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        } catch (e: Throwable) { "" }

        val match = BEPLAYER_RX.find(html) ?: run {
            Log.i(TAG, "NO_BEPLAYER_CALL url=$url")
            return null
        }

        val hash = match.groupValues[1]
        val rawSet = match.groupValues[2].replace("\\/", "/")

        val decryptedJson = try {
            decryptCryptoJsAes(rawSet, hash)
        } catch (e: Throwable) {
            Log.e(TAG, "DECRYPT_ERROR url=$url error=$e")
            return null
        }

        val videoLocation = try {
            JSONObject(decryptedJson).optString("video_location").ifBlank { null }
        } catch (e: Throwable) {
            Log.e(TAG, "JSON_PARSE_ERROR error=$e")
            null
        } ?: return null

        val isM3u8 = videoLocation.contains(".m3u8", ignoreCase = true)
        Log.i(TAG, "VIDEO_LOCATION url=$videoLocation cookie=${sessionCookie.isNotBlank()}")

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = videoLocation,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                if (sessionCookie.isNotBlank()) {
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Cookie" to sessionCookie
                    )
                }
            }
        )
    }

    private fun decryptCryptoJsAes(setJson: String, password: String): String {
        val obj = JSONObject(setJson)
        val ciphertext = android.util.Base64.decode(obj.getString("ct"), android.util.Base64.DEFAULT)
        val iv = hexToBytes(obj.getString("iv"))
        val salt = hexToBytes(obj.getString("s"))
        val key = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt, 32)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        var d = ByteArray(0)
        val result = mutableListOf<Byte>()
        while (result.size < keyLen) {
            md5.reset(); md5.update(d); md5.update(password); md5.update(salt)
            d = md5.digest()
            result.addAll(d.toList())
        }
        return result.take(keyLen).toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = ((Character.digit(clean[idx], 16) shl 4) + Character.digit(clean[idx + 1], 16)).toByte()
        }
        return out
    }

    companion object {
        private const val TAG = "P720pizle_EXTRACT"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private val BEPLAYER_RX = Regex(
            """bePlayer\(\s*'([^']*)'\s*,\s*'((?:\\.|[^'\\])*)'""",
            RegexOption.IGNORE_CASE
        )
    }
}
