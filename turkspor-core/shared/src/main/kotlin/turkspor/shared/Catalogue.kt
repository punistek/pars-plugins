package turkspor.shared

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.Locale

interface CatalogueController {
    val currentUrl: String
    val checkedAt: Long
    suspend fun resolve(force: Boolean = false): SiteSnapshot
    suspend fun setManual(value: String): SiteSnapshot
}

abstract class RemoteCatalogue(private val prefs: SharedPreferences, private val fallback: String): CatalogueController {
    private val mutex=Mutex()
    @Volatile private var cache: SiteSnapshot?=null
    override val currentUrl get()=cache?.url ?: prefs.getString("lastGood",fallback) ?: fallback
    override val checkedAt get()=cache?.checkedAt ?: prefs.getLong("checkedAt",0)
    protected abstract suspend fun download(manual: String?): SiteSnapshot
    override suspend fun resolve(force: Boolean): SiteSnapshot = mutex.withLock {
        cache?.takeIf { !force && System.currentTimeMillis()-it.checkedAt<60_000 }?.let { return@withLock it }
        val manual=prefs.getString("manual","").orEmpty().takeIf { it.isNotBlank() }
        turkspor.common.ChannelRules.refresh()
        val next=download(manual)
        if(next.channels.isEmpty()) throw ErrorLoadingException("Kaynakta sabit spor kanalı bulunamadı; önceki adres korundu.")
        cache=next
        prefs.edit().putString("lastGood",next.url).putLong("checkedAt",next.checkedAt).apply()
        next
    }
    override suspend fun setManual(value: String): SiteSnapshot {
        val url=value.trim()
        if(url.isNotEmpty() && Channels.https(url)==null) throw ErrorLoadingException("Geçerli bir HTTPS katalog/liste adresi girin.")
        val old=prefs.getString("manual","").orEmpty()
        prefs.edit().putString("manual",url).apply()
        try { return resolve(true) }
        catch(e: Exception) { prefs.edit().putString("manual",old).apply();throw e }
    }
}

abstract class CatalogueProvider(private val providerTitle: String, protected val catalogue: CatalogueController, private val artwork: ChannelArtwork): MainAPI() {
    override var mainUrl="https://github.com/punistek/pars-plugins/"
    override var name="$providerTitle • TurkSpor"
    override var lang="tr"
    override val supportedTypes=setOf(TvType.Live)
    override val hasMainPage=true
    override val hasDownloadSupport=false
    override val getMainPageTimeoutMs=90_000L
    override val mainPage=mainPageOf("all" to "Spor Kanalları")
    protected fun stable(channel: Channel)="${mainUrl}channel?id=${URLEncoder.encode(channel.id,"UTF-8")}"
    private fun Channel.result()=newLiveSearchResponse(title,stable(this),TvType.Live,false) { posterUrl=artwork.poster(this@result) }
    override suspend fun getMainPage(page: Int,request: MainPageRequest): HomePageResponse {
        turkspor.common.ChannelRules.refresh()
        val channels=catalogue.resolve().channels;artwork.prepare(channels)
        return newHomePageResponse(turkspor.common.ChannelGroups.sections(channels) { it.title }.map { (category,group) -> HomePageList(category,group.map { it.result() },true) },false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        turkspor.common.ChannelRules.refresh()
        turkspor.common.ChannelRules.refresh()
        val term=query.lowercase(Locale.forLanguageTag("tr"))
        val channels=catalogue.resolve().channels.filter { turkspor.common.ChannelRules.visible(it.title) && it.title.lowercase(Locale.forLanguageTag("tr")).contains(term) }
        artwork.prepare(channels);return channels.map { it.result() }
    }
    protected suspend fun current(data: String): Channel {
        val id=Channels.param(data,"id") ?: throw ErrorLoadingException("Kanal kimliği bulunamadı.")
        return catalogue.resolve().channels.firstOrNull { it.id==id && turkspor.common.ChannelRules.visible(it.title) } ?: throw ErrorLoadingException("Kanal güncel listede yok; eklenti ayarlarından listeyi yenileyin.")
    }
    override suspend fun load(url: String): LoadResponse {
        val channel=current(url);artwork.prepare(listOf(channel))
        return newLiveStreamLoadResponse(channel.title,url,stable(channel)) {
            posterUrl=artwork.poster(channel)
            plot = turkspor.common.ChannelGroups.NOTICE
        }
    }
}
