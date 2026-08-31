package turkspor.mackeyfi
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import turkspor.shared.*

@CloudstreamPlugin
class MacKeyfiPlugin : Plugin() {
    override fun load(context: Context) {
        val spec = SourceSpec.all.getValue("mackeyfi")
        val resolver = DomainResolver(context.getSharedPreferences("turkspor_mackeyfi", Context.MODE_PRIVATE), spec)
        registerMainAPI(SportsProvider(spec, resolver, ChannelArtwork(context, spec.key)))
        openSettings = { uiContext -> DomainSettings.show(uiContext, resolver) }
    }
}
