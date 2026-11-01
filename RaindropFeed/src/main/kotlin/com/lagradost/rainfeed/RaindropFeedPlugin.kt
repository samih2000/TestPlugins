package com.lagradost.rainfeed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RaindropFeedPlugin : Plugin() {
    override fun load(context: Context) {
    val provider = RaindropFeedProvider()
    registerMainAPI(provider)
    provider.warmUp()
    openSettings = { ctx -> RaindropFeedSettings.show(ctx) }
    }
}
