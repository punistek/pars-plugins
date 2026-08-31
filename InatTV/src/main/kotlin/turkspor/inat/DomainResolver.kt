package turkspor.inat

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class SiteSnapshot(val url: String, val channels: List<SportsChannel>, val config: PlayerConfig, val checkedAt: Long)

class DomainResolver(private val preferences: SharedPreferences) {
    companion object {
        const val GATEWAY = "https://www.inatgiris.com/"
        const val BOOTSTRAP = "https://www.inattvizle487.top/"
        const val MANIFEST = "https://raw.githubusercontent.com/punistek/pars-plugins/main/domains.json"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
    }
    private val mutex = Mutex()
    @Volatile private var cached: SiteSnapshot? = null
    val currentUrl: String get() = cached?.url ?: preferences.getString("lastGood", BOOTSTRAP) ?: BOOTSTRAP
    val checkedAt: Long get() = cached?.checkedAt ?: preferences.getLong("checkedAt", 0)

    suspend fun resolve(force: Boolean = false): SiteSnapshot = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { !force && now - it.checkedAt < 60_000 }?.let { return@withLock it }
        val tried = mutableSetOf<String>()
        val candidates = linkedSetOf<String>()
        fun snapshot(html: String, url: String): SiteSnapshot? {
            val finalUrl = SportsParser.siteUrl(url) ?: return null
            val items = SportsParser.channels(html, finalUrl).takeIf { it.isNotEmpty() } ?: return null
            val config = SportsParser.config(html, finalUrl) ?: return null
            return SiteSnapshot(finalUrl, items, config, System.currentTimeMillis()).also {
                cached = it
                preferences.edit().putString("lastGood", it.url).putLong("checkedAt", it.checkedAt).apply()
            }
        }
        suspend fun verify(url: String): SiteSnapshot? {
            if (!tried.add(url)) return null
            return try {
                val response = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 8)
                if (response.code == 200) snapshot(response.text, response.url) else null
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        }
        SportsParser.siteUrl(preferences.getString("manual", "").orEmpty())?.let {
            verify(it)?.let { result -> return@withLock result }
        }
        if (!force) verify(currentUrl)?.let { return@withLock it }
        // A stable landing page follows numeric domain changes without guessing.
        try {
            val response = app.get(GATEWAY, headers = mapOf("User-Agent" to UA), timeout = 8)
            if (response.code == 200) {
                snapshot(response.text, response.url)?.let { return@withLock it }
                candidates.addAll(SportsParser.gatewayTargets(response.text, response.url).take(3))
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try {
            val response = app.get(MANIFEST, timeout = 8)
            if (response.code == 200) {
                val root = JSONObject(response.text)
                if (root.optInt("schemaVersion") == 1) {
                    val urls = root.optJSONObject("sources")?.optJSONObject("inattv")?.optJSONArray("candidates")
                    if (urls != null) for (i in 0 until minOf(urls.length(), 3))
                        SportsParser.siteUrl(urls.optString(i))?.let(candidates::add)
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        candidates.add(currentUrl)
        candidates.add(BOOTSTRAP)
        for (candidate in candidates.take(6)) verify(candidate)?.let { return@withLock it }
        // Last resort only: at most three adjacent numbers, requiring real channels and player config.
        for (candidate in SportsParser.nextDomains(currentUrl)) verify(candidate)?.let { return@withLock it }
        throw ErrorLoadingException("İnat TV kaynağına ulaşılamadı. Eklenti ayarlarından Domaini yenile'yi deneyin.")
    }
    suspend fun setManual(value: String): SiteSnapshot {
        if (value.isBlank()) {
            preferences.edit().remove("manual").apply()
            return resolve(true)
        }
        val url = SportsParser.siteUrl(value) ?: throw ErrorLoadingException("Geçerli bir HTTPS inattvizle adresi girin.")
        val old = preferences.getString("manual", "").orEmpty()
        preferences.edit().putString("manual", url).apply()
        try {
            val result = resolve(true)
            if (result.url != url) throw ErrorLoadingException("Girilen adres doğrulanamadı; çalışan adres korundu.")
            return result
        } catch (e: Exception) {
            preferences.edit().putString("manual", old).apply()
            throw e
        }
    }
}
