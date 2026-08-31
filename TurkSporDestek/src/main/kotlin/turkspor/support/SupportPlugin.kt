package turkspor.support

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SupportPlugin: Plugin() {
    override fun load(context: Context) {
        // Opt-in only: installing this extension never opens a browser or creates a fake stream.
        openSettings={ ctx -> MaterialAlertDialogBuilder(ctx)
            .setTitle("TurkSpor'a destek ol")
            .setMessage("Bir kahveyle destek ol. ☕\n\nmade by Wiojelt with love")
            .setPositiveButton("Bir kahve bırak") { _,_ -> open(ctx,"https://buymeacoffee.com/wiojelt") }
            .setNeutralButton("Wiojelt ↗") { _,_ -> open(ctx,"https://twitter.com/wiojelt") }
            .setNegativeButton("Kapat",null).show()
        }
    }
    private fun open(context: Context,url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
