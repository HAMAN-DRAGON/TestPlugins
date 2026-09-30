package com.hentaixplanet

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HentaiXPlanetPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HentaiXPlanet())
    }
}
