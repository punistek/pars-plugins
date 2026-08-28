package com.pars.p720pizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class P720pizlePlugin:Plugin(){ override fun load(context:Context){ registerMainAPI(P720pizle()); registerExtractorAPI(P720pizleExtractor()) } }
