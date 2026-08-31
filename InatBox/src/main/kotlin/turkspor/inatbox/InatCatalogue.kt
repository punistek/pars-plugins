package turkspor.inatbox

import android.content.SharedPreferences
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.CancellationException
import turkspor.shared.*

class InatCatalogue(prefs: SharedPreferences): RemoteCatalogue(prefs,FALLBACK) {
    companion object {
        const val FALLBACK="https://static.staticsave.com/fast/ct.js"
        private val documents=listOf("https://raw.githubusercontent.com/mtlshash/cert/main/hash","https://cdn.jsdelivr.net/gh/mtlshash/cert@main/hash","https://api.github.com/repos/mtlshash/cert/contents/hash")
        val headers=mapOf("User-Agent" to "speedrestapi","Referer" to "https://speedrestapi.com/","X-Requested-With" to "com.bp.box")
        suspend fun request(url: String,key: String=InatData.KEY): String {
            val interceptor=okhttp3.Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent","speedrestapi").build()) }
            return if(url.contains("/SPR/") || java.net.URI(url).host=="sprspr.help") app.get(url,headers=headers,interceptor=interceptor,timeout=12).text
            else app.post(url,headers=headers,data=mapOf("1" to key,"0" to key),interceptor=interceptor,timeout=12).text
        }
    }
    private suspend fun discover(): String? {
        for(url in documents) try {
            val response=app.get(url,headers=mapOf("User-Agent" to DomainResolver.UA),timeout=8)
            if(response.code!=200) continue
            val body=if(url.contains("api.github.com")) InatData.githubDocument(response.text) ?: continue else response.text
            InatData.domain(body)?.let { return it }
        } catch(e: CancellationException) { throw e } catch (_: Exception) { }
        return null
    }
    override suspend fun download(manual: String?): SiteSnapshot {
        val candidates=if(manual!=null) listOf(manual) else listOfNotNull(discover(),currentUrl,FALLBACK).distinct()
        for(url in candidates) try {
            val response=app.get(url,headers=mapOf("User-Agent" to DomainResolver.UA),timeout=12)
            if(response.code!=200) continue
            val index=InatData.decode(response.text) ?: continue
            val categories=index.filter { InatData.fold(it.path("catName").asText())=="spor" && it.path("catType").asText().contains("tv") }
            val channels=categories.take(3).flatMap { category ->
                val endpoint=Channels.https(category.path("catUrl").asText()) ?: return@flatMap emptyList()
                InatData.decode(request(endpoint))?.let(InatData::channels).orEmpty()
            }.distinctBy { it.id }
            if(channels.isNotEmpty()) return SiteSnapshot(url,channels,System.currentTimeMillis())
        } catch(e: CancellationException) { throw e } catch (_: Exception) { }
        throw ErrorLoadingException("İnat Box spor kataloğu alınamadı. Ayarlarda Domaini yenile'yi deneyin.")
    }
}
