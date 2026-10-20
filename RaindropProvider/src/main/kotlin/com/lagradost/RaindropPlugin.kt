package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RaindropPlugin : Plugin() {
    override fun load(context: Context) {
        val provider = RaindropProvider()
        registerMainAPI(provider)
        // Fetch the tag list in the background so the tag shelves exist when the home page opens.
        provider.warmUp()
        openSettings = { ctx -> RaindropSettings.show(ctx) }
    }
}
