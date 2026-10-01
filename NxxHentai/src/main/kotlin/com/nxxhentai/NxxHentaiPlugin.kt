package com.nxxhentai

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NxxHentaiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NxxHentai())
    }
}
