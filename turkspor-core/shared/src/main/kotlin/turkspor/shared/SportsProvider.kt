package turkspor.shared

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

class SportsProvider(private val spec: SourceSpec, private val domains: DomainResolver, private val artwork: ChannelArtwork): MainAPI() {
    override var mainUrl=spec.roots.first()
    override var name="${spec.name} • TurkSpor"
    override var lang="tr"
    override val supportedTypes=setOf(TvType.Live)
    override val hasMainPage=true
    override val hasDownloadSupport=false
    override val mainPage=mainPageOf("all" to "Spor Kanalları")
    private fun Channel.stable()="${spec.roots.first()}turkspor?id=${URLEncoder.encode(id,"UTF-8")}" 
    private fun Channel.result()=newLiveSearchResponse(Branding.forChannel(this).title,stable(),TvType.Live,false) { posterUrl=artwork.poster(this@result) }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        turkspor.common.ChannelRules.refresh()
        val items=domains.resolve().channels;artwork.prepare(items)
        return newHomePageResponse(turkspor.common.ChannelGroups.sections(items) { it.title }.map { (category,group) -> HomePageList(category,group.map { it.result() },true) },false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        turkspor.common.ChannelRules.refresh()
        turkspor.common.ChannelRules.refresh()
        val term=query.lowercase(Locale.forLanguageTag("tr"))
        val items=domains.resolve().channels.filter { turkspor.common.ChannelRules.visible(it.title) }.filter { turkspor.common.ChannelRules.visible(it.title) }.filter { Branding.forChannel(it).title.lowercase(Locale.forLanguageTag("tr")).contains(term) }
        artwork.prepare(items);return items.map { it.result() }
    }
    private suspend fun current(data: String): Channel {
        val id=Channels.param(data,"id") ?: throw ErrorLoadingException("Kanal kimliği eksik.")
        return domains.resolve().channels.filter { turkspor.common.ChannelRules.visible(it.title) }.filter { turkspor.common.ChannelRules.visible(it.title) }.firstOrNull { it.id==id } ?: throw ErrorLoadingException("Kanal güncel listede yok; kaynağı yenileyin.")
    }
    override suspend fun load(url: String): LoadResponse {
        val channel=current(url);artwork.prepare(listOf(channel))
        return newLiveStreamLoadResponse(Branding.forChannel(channel).title,url,channel.stable()) {
            posterUrl=artwork.poster(channel)
            plot = turkspor.common.ChannelGroups.NOTICE
        }
    }
    private val ua=mapOf("User-Agent" to DomainResolver.UA)
    private fun origin(url: String)=URI(url).let { "${it.scheme}://${it.authority}/" }
    private suspend fun resolvePlayer(player: String, site: String): List<Playback> {
        if(spec.mode==SourceMode.INTER) return listOf(Playback(player,site))
        val page=app.get(player,referer=site,headers=ua,timeout=12)
        if(page.code!=200) return emptyList()
        return when(spec.mode) {
            SourceMode.WORDPRESS -> {
                val embed=PlayerParser.wordpressEmbed(page.text,page.url) ?: return emptyList()
                val html=app.get(embed,referer=site,headers=ua,timeout=12)
                if(html.code!=200) return emptyList()
                val id=Channels.param(html.url,"id") ?: return emptyList()
                if((id.toLongOrNull() ?: 0)>10000) {
                    val endpoint=Regex("""fetch\(['"](https://[^'"]+/cinema)['"]""").find(html.text)?.groupValues?.get(1) ?: return emptyList()
                    val data=app.post(endpoint,referer=site,headers=ua,json=mapOf("AppId" to "5000","AppVer" to "1","VpcVer" to "1.0.12","Language" to "en","Token" to "","VideoId" to id),timeout=12)
                    PlayerParser.apiStream(data.text)?.let { listOf(Playback(it,origin(embed))) } ?: emptyList()
                } else {
                    val session=app.get("${origin(embed)}t?id=${URLEncoder.encode(id,"UTF-8")}",referer=embed,headers=ua,timeout=10)
                    if(session.code!=200) return emptyList()
                    PlayerParser.wordpressStream(html.text,html.url,session.text)?.let { listOf(Playback(it,origin(embed))) } ?: emptyList()
                }
            }
            SourceMode.ROYAL -> {
                val endpoint=PlayerParser.domainEndpoint(page.text) ?: return emptyList()
                val data=app.get(endpoint,referer=origin(page.url),headers=ua,timeout=10)
                PlayerParser.royalStream(data.text,page.url)?.let { listOf(Playback(it,origin(page.url))) } ?: emptyList()
            }
            SourceMode.BEYAZ -> {
                val result=mutableListOf<Playback>()
                for(url in PlayerParser.nextStreams(page.text,page.url).take(3)) {
                    if(URI(url).path=="/api/embed") {
                        try {
                            val embedded=app.get(url,referer=site,headers=ua,timeout=12)
                            if(embedded.code==200) PlayerParser.embeddedHls(embedded.text,embedded.url)?.let { result.add(Playback(it,site)) }
                        } catch(e: CancellationException) { throw e } catch (_: Exception) { }
                    } else result.add(Playback(url,site))
                }
                result
            }
            SourceMode.INTER -> emptyList()
        }
    }
    override suspend fun loadLinks(data: String,isCasting: Boolean,subtitleCallback: (SubtitleFile)->Unit,callback: (ExtractorLink)->Unit): Boolean {
        val channel=current(data);val seen=mutableSetOf<String>();var found=false
        val links=mutableListOf<ExtractorLink>()
        var status=""
        for((index,player) in channel.players.take(4).withIndex()) {
            try {
                for(stream in resolvePlayer(player,domains.currentUrl)) {
                    if(!seen.add(stream.url)) continue
                    val requestHeaders=ua+mapOf("Origin" to stream.referer.trimEnd('/'))
                    val playlist=app.get(stream.url,referer=stream.referer,headers=requestHeaders,timeout=12)
                    status=playlist.code.toString()
                    if(playlist.code!=200 || !playlist.text.trimStart().startsWith("#EXTM3U")) continue
                    val label=Branding.forChannel(channel).title+if(channel.players.size>1) " • Kaynak ${index+1}" else ""
                    links.addAll(turkspor.common.HlsQuality.links(name,label,playlist.url,playlist.text,stream.referer,requestHeaders));found=true
                }
            } catch(e: CancellationException) { throw e } catch (_: Exception) { }
        }
        if(!found) throw ErrorLoadingException("Kanalın yayın adresi alınamadı veya yayın erişilemiyor${if(status.isNotEmpty()) " ($status)" else ""}. Maç saatinde veya WARP ile tekrar deneyin.")
        turkspor.common.HlsQuality.sorted(links).forEach(callback)
        return found
    }
}
