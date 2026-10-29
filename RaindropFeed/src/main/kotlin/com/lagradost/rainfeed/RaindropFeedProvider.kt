package com.lagradost.rainfeed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * v2: nothing is looked up on X from the phone. The sync job (tools/xsync) stores everything
 * the plugin needs inside each Raindrop bookmark:
 *   cover   -> thumbnail (tile poster)
 *   note    -> direct video URL(s), one per line
 *   excerpt -> tweet text
 *   tags    -> account name + "xsync" (+ "priority" for priority accounts)
 * so tiles and playback are a single Raindrop request each.
 *
 * Home page: "Priority Latest", "Latest", then 5 random priority accounts and 5 random
 * other accounts. The random picks stay the same for 6 hours, then change.
 */
class RaindropFeedProvider : MainAPI() {
    override var mainUrl = "https://api.raindrop.io"
    override var name = "Raindrop Feed"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    private val pageSize = 16
    private val maxPages = 6
    private val syncTag = "xsync"
    private val priorityTag = "priority"

    private val priorityShelfCount = 5
    private val otherShelfCount = 5
    private val rotationMs = 6L * 60L * 60L * 1000L

    private val token: String
        get() = CloudStreamApp.context
            ?.let { feedPrefs(it).getString(FEED_TOKEN_KEY, "") }
            ?: ""

    private fun authHeaders() = mapOf("Authorization" to "Bearer $token")

    data class FeedItem(
        @JsonProperty("_id") val id: Long,
        val title: String?,
        val excerpt: String?,
        val note: String?,
        val link: String,
        val cover: String?,
        val tags: List<String>?
    )

    data class FeedResponse(val items: List<FeedItem>, val count: Int)

    private val tweetIdRegex = Regex("status/(\\d+)")

    // Raindrop search: "#tag" filters by tag, a bare word searches text.
    private suspend fun fetch(search: String, page: Int, perPage: Int): List<FeedItem> {
        val q = URLEncoder.encode(search, "UTF-8")
        return app.get(
            "$mainUrl/rest/v1/raindrops/0?sort=-created&perpage=$perPage&page=$page&search=$q",
            headers = authHeaders()
        ).parsedSafe<FeedResponse>()?.items ?: emptyList()
    }

    private fun FeedItem.toTile(): SearchResponse =
        newMovieSearchResponse(
            title?.takeIf { it.isNotBlank() } ?: link,
            link,
            TvType.Movie
        ) {
            this.posterUrl = cover?.takeIf { it.isNotBlank() }
        }

    // ---------- Main page ----------

    // Same picks for the whole 6-hour window (local time), a new random set after that.
    private fun pick(list: List<String>, count: Int, salt: Long): List<String> {
        if (list.size <= count) return list
        val now = System.currentTimeMillis()
        val bucket = (now + java.util.TimeZone.getDefault().getOffset(now)) / rotationMs
        return list.shuffled(kotlin.random.Random(bucket + salt)).take(count)
    }

    override val mainPage: List<MainPageData>
        get() {
            val shelves = mutableListOf(
                "priority" to "Priority Latest",
                "all" to "Latest"
            )
            val ctx = CloudStreamApp.context
            if (ctx != null) {
                pick(feedPriority(ctx), priorityShelfCount, 1L)
                    .forEach { shelves.add("acct:$it" to it) }
                pick(feedAccounts(ctx), otherShelfCount, 2L)
                    .forEach { shelves.add("acct:$it" to it) }
            }
            return mainPageOf(*shelves.toTypedArray())
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val search = when {
            data == "priority" -> "#$priorityTag"
            // "#xsync" too, so older hand-saved bookmarks that share the account tag stay out.
            data.startsWith("acct:") -> "#$syncTag #${data.removePrefix("acct:")}"
            else -> "#$syncTag"
        }
        val items = fetch(search, page - 1, pageSize)
        return newHomePageResponse(
            request.name,
            items.map { it.toTile() },
            page < maxPages && items.size >= pageSize
        )
    }

    // Typing an account name (with or without @) lists that account's videos;
    // anything else is a text search over the synced videos.
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().removePrefix("@")
        val ctx = CloudStreamApp.context
        val isAccount = ctx != null &&
            (feedPriority(ctx) + feedAccounts(ctx)).any { it.equals(q, ignoreCase = true) }
        val search = if (isAccount) "#$syncTag #$q" else "$q #$syncTag"
        return fetch(search, 0, 50).map { it.toTile() }
    }

    // ---------- Details + playback ----------

    private suspend fun findItem(url: String): FeedItem? {
        val id = tweetIdRegex.find(url)?.groupValues?.get(1)
        if (id != null) {
            val hit = fetch(id, 0, 5).firstOrNull { it.link.contains(id) }
            if (hit != null) return hit
        }
        return fetch(url, 0, 1).firstOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val item = findItem(url) ?: throw ErrorLoadingException("Bookmark not found in Raindrop")
        return newMovieLoadResponse(
            item.title?.takeIf { it.isNotBlank() } ?: item.link,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = item.cover?.takeIf { it.isNotBlank() }
            this.plot = item.excerpt
            this.tags = item.tags?.filter { it != syncTag && it != priorityTag }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = findItem(data)?.note
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.startsWith("http") }
            .orEmpty()
        if (urls.isEmpty()) return false

        urls.forEachIndexed { i, videoUrl ->
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = if (urls.size > 1) "${this.name} ${i + 1}" else this.name,
                    url = videoUrl
                ) {
                    this.referer = "https://x.com/"
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.VIDEO
                }
            )
        }
        return true
    }
}
