package turkspor.beyazelma
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import turkspor.shared.*

@CloudstreamPlugin
class BeyazElmaPlugin : Plugin() {
    override fun load(context: Context) {
        val spec = SourceSpec.all.getValue("beyazelma")
        val resolver = DomainResolver(context.getSharedPreferences("turkspor_beyazelma", Context.MODE_PRIVATE), spec)
        registerMainAPI(SportsProvider(spec, resolver, ChannelArtwork(context, spec.key)))
        openSettings = { uiContext -> DomainSettings.show(uiContext, resolver) }
    }
}
