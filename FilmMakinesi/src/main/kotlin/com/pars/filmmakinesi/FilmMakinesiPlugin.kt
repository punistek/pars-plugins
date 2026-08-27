package com.pars.filmmakinesi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmMakinesiPlugin : Plugin() {

    override fun load(context: android.content.Context) {
        registerMainAPI(FilmMakinesi())
    }
}
