package com.pars.filmmakinesi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmMakinesiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmMakinesi())

        // FilmMakinesi'nin kendi embed sunucuları.
        // Bunlar kayıtlı olmazsa FilmMakinesi.loadLinks() içindeki
        // loadExtractor(...) callback üretmez ve links=0 döner.
        registerExtractorAPI(CloseLoadExtractor())
        registerExtractorAPI(FilmMakinesiRapidExtractor())
    }
}
