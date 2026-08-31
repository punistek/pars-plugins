package turkspor.inat

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InatPlugin : Plugin() {
    override fun load(context: Context) {
        val resolver = DomainResolver(context.getSharedPreferences("turkspor_inat", Context.MODE_PRIVATE))
        registerMainAPI(InatTV(resolver, ChannelArtwork(context)))
        openSettings = { uiContext -> showSettings(uiContext, resolver) }
    }
    private fun showSettings(context: Context, resolver: DomainResolver) {
        turkspor.common.SourceSettings.show(context,"InatTV",{ resolver.currentUrl },{ resolver.checkedAt }) { manual ->
            (if(manual==null) resolver.resolve(true) else resolver.setManual(manual)).channels.size
        }
    }
}
