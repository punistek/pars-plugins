package turkspor.shared

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.json.JSONObject

class DomainResolver(private val preferences: SharedPreferences, val spec: SourceSpec): CatalogueController {
    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        const val MANIFEST = "https://raw.githubusercontent.com/punistek/pars-plugins/main/domains.json"
    }
    private val mutex = Mutex()
    @Volatile private var cached: SiteSnapshot? = null
    override val currentUrl get() = cached?.url ?: preferences.getString("lastGood",spec.roots.first()) ?: spec.roots.first()
    override val checkedAt get() = cached?.checkedAt ?: preferences.getLong("checkedAt",0)
    override suspend fun resolve(force: Boolean): SiteSnapshot = mutex.withLock {
        cached?.takeIf { !force && System.currentTimeMillis()-it.checkedAt < 60_000 }?.let { return@withLock it }
        val tried = mutableSetOf<String>()
        val candidates = linkedSetOf<String>()
        suspend fun verify(root: String): SiteSnapshot? {
            if (!tried.add(root)) return null
            try {
                val response = app.get(root+spec.catalogPath,headers=mapOf("User-Agent" to UA),timeout=10)
                val final = Channels.site(spec,response.url) ?: return null
                if (response.code != 200 || spec.markers.none { Jsoup.parse(response.text).title().contains(it,true) }) return null
                var channels = Channels.read(spec,response.text,final)
                if (spec.mode == SourceMode.ROYAL) Channels.externalCatalog(response.text)?.let { url ->
                    try {
                        val data=app.get(url,referer=final,headers=mapOf("User-Agent" to UA),timeout=8)
                        if (data.code==200) channels=(channels+Channels.read(spec,data.text,final)).distinctBy { it.id }
                    } catch(e: CancellationException) { throw e } catch (_: Exception) { }
                }
                if (channels.isEmpty()) return null
                val next = Channels.links(spec,response.text,final).firstOrNull { it != final }
                if (next != null) preferences.edit().putString("announced",next).apply()
                return SiteSnapshot(final,channels,System.currentTimeMillis()).also {
                    cached=it;preferences.edit().putString("lastGood",final).putLong("checkedAt",it.checkedAt).apply()
                }
            } catch(e: CancellationException) { throw e } catch (_: Exception) { return null }
        }
        Channels.site(spec,preferences.getString("manual","").orEmpty())?.let { verify(it)?.let { result -> return@withLock result } }
        if (!force) verify(currentUrl)?.let { return@withLock it }
        try {
            val response=app.get(MANIFEST,timeout=8)
            if (response.code==200) {
                val json=JSONObject(response.text)
                if (json.optInt("schemaVersion")==1) {
                    val urls=json.optJSONObject("sources")?.optJSONObject(spec.key)?.optJSONArray("candidates")
                    if(urls!=null) for(i in 0 until minOf(urls.length(),5)) Channels.site(spec,urls.optString(i))?.let(candidates::add)
                }
            }
        } catch(e: CancellationException) { throw e } catch (_: Exception) { }
        Channels.site(spec,preferences.getString("announced","").orEmpty())?.let(candidates::add)
        candidates.addAll(spec.roots);candidates.add(currentUrl)
        for(candidate in candidates.take(8)) verify(candidate)?.let { return@withLock it }
        for(candidate in Channels.nextDomains(spec,currentUrl)) verify(candidate)?.let { return@withLock it }
        throw ErrorLoadingException("${spec.name} kanal listesine ulaşılamadı. Eklenti ayarlarından Domaini yenile'yi deneyin.")
    }
    override suspend fun setManual(value: String): SiteSnapshot {
        if(value.isBlank()) { preferences.edit().remove("manual").apply();return resolve(true) }
        val url=Channels.site(spec,value) ?: throw ErrorLoadingException("Bu kaynak ailesinden geçerli bir HTTPS adresi girin.")
        val old=preferences.getString("manual","").orEmpty()
        preferences.edit().putString("manual",url).apply()
        try {
            val result=resolve(true)
            if(result.url!=url) throw ErrorLoadingException("Girilen adres doğrulanamadı; çalışan kaynak korundu.")
            return result
        } catch(e: Exception) { preferences.edit().putString("manual",old).apply();throw e }
    }
}
