package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TwitterAccountsPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TwitterAccountsProvider())
        openSettings = { ctx -> TwitterAccountsSettings.show(ctx) }
    }
}
