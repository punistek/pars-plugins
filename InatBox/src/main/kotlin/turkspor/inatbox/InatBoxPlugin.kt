package turkspor.inatbox

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import turkspor.shared.*

@CloudstreamPlugin
class InatBoxPlugin: Plugin() {
    override fun load(context: Context) {
        val catalogue=InatCatalogue(context.getSharedPreferences("turkspor_inatbox",Context.MODE_PRIVATE))
        registerMainAPI(InatBox(catalogue,ChannelArtwork(context,"inatbox")))
        openSettings={ ctx -> DomainSettings.show(ctx,catalogue,"İnat Box") }
    }
}
class InatBox(catalogue: InatCatalogue,artwork: ChannelArtwork): CatalogueProvider("İnat Box",catalogue,artwork) {
    override suspend fun loadLinks(data: String,isCasting: Boolean,subtitleCallback: (SubtitleFile)->Unit,callback: (ExtractorLink)->Unit): Boolean {
        val channel=current(data);var emitted=false;val links=mutableListOf<ExtractorLink>()
        val seen=mutableSetOf<String>()
        for((i,value) in channel.players.take(6).withIndex()) try {
            val row=InatData.mapper.readTree(value)
            var url=InatData.stream(row) ?: continue
            if(row.path("chType").asText().startsWith("tekli_regex_lb_sh_3") && !url.contains(".m3u8",true)) {
                val result=InatCatalogue.request(url,InatData.key(row))
                url=InatData.decode(result,InatData.key(row))?.let(InatData::stream) ?: continue
            }
            if(!seen.add(url)) continue
            val headers=InatData.headers(row)
            val playlist=app.get(url,headers=headers,referer=headers["Referer"],timeout=12)
            if(playlist.code!=200 || !playlist.text.trimStart().startsWith("#EXTM3U")) continue
            links.addAll(turkspor.common.HlsQuality.links(name,channel.title+if(channel.players.size>1) " • Kaynak ${i+1}" else "",playlist.url,playlist.text,headers["Referer"].orEmpty(),headers));emitted=true
        } catch(e: CancellationException) { throw e } catch (_: Exception) { }
        if(!emitted) throw ErrorLoadingException("İnat Box bu kanal için erişilebilir HLS yayını döndürmedi; başka kanal veya kaynak deneyin.")
        turkspor.common.HlsQuality.sorted(links).forEach(callback)
        return true
    }
}
