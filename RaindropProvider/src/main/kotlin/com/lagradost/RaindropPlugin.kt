package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RaindropPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RaindropProvider())
    }

    override fun openSettings(context: Context): Any {
        RaindropSettings.show(context)
        return Unit
    }
}
