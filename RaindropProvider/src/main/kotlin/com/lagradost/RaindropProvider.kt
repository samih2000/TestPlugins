package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * Cloudstream provider that reads your own Raindrop.io bookmarks (filtered to
 * type=video) and resolves the actual playable video from the original link
 * at watch-time.
 *
 * SETUP:
 * 1. Go to https://app.raindrop.io/settings/integrations
 * 2. Create a new (private) app.
 * 3. Click into it and generate a "Test token" — paste it below into RAINDROP_TOKEN.
 *    (A test token is enough for personal use; no OAuth flow needed.)
 */
class RaindropProvider : MainAPI() {
    override var mainUrl = "https://api.raindrop.io"
    override var name = "Raindrop Videos"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    // ---- put your Raindrop "test token" here ----
    private val raindropToken = "6431f39f-a72a-41c9-b1a8-712b68484c5f"

    private fun authHeaders() = mapOf("Authorization" to "Bearer $raindropToken")

    // ---------- Raindrop API response shapes ----------
    data class RaindropItem(
        @JsonProperty("_id") val id: Long,
        val title: String?,
        val excerpt: String?,
        val link: String,
        val cover: String?,
        val type: String?,
        val tags: List<String>?
    )

    data class RaindropListResponse(
        val items: List<RaindropItem>,
        val count: Int
    )

    // ---------- Home page: all bookmarks tagged type:video ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val perPage = 50
        val url = "$mainUrl/rest/v1/raindrops/0" +
                "?search=" + URLEncoder.encode("type:video", "UTF-8") +
                "&page=${page - 1}&perpage=$perPage&sort=-created"

        val res = app.get(url, headers = authHeaders()).parsedSafe<RaindropListResponse>()
            ?: return newHomePageResponse(emptyList(), hasNext = false)

        val items = res.items.map { it.toSearchResponse(this) }
        val hasNext = (page - 1) * perPage + items.size < res.count

        return newHomePageResponse(
            list = listOf(HomePageList("Video bookmarks", items)),
            hasNext = hasNext
        )
    }

    private fun RaindropItem.toSearchResponse(provider: MainAPI): SearchResponse {
        return provider.newMovieSearchResponse(
            title ?: link,
            link, // this becomes the `url` argument passed into load()
            TvType.Movie
        ) {
            this.posterUrl = cover
        }
    }

    // ---------- Search ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode("$query type:video", "UTF-8")
        val url = "$mainUrl/rest/v1/raindrops/0?search=$q&perpage=50"
        val res = app.get(url, headers = authHeaders()).parsedSafe<RaindropListResponse>()
            ?: return emptyList()
        return res.items.map { it.toSearchResponse(this) }
    }

    // ---------- Load: detail page ----------
    override suspend fun load(url: String): LoadResponse {
        val q = URLEncoder.encode(url, "UTF-8")
        val res = app.get("$mainUrl/rest/v1/raindrops/0?search=$q", headers = authHeaders())
            .parsedSafe<RaindropListResponse>()
        val item = res?.items?.firstOrNull()

        return newMovieLoadResponse(
            item?.title ?: url,
            url,
            TvType.Movie,
            url // passed straight through as `data` to loadLinks()
        ) {
            this.posterUrl = item?.cover
            this.plot = item?.excerpt
            this.tags = item?.tags
        }
    }

    // ---------- loadLinks: resolve the actual playable video ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // `data` is the original bookmarked link, e.g. https://x.com/user/status/12345
        val tweetId = Regex("status/(\\d+)").find(data)?.groupValues?.get(1) ?: return false

        // X's public syndication endpoint (same one used to render embedded tweet
        // previews on third-party sites) returns the tweet JSON including video
        // variant URLs, with no login required for public tweets.
        //
        // NOTE: X periodically changes the required `token` query param algorithm.
        // If this stops returning data, search "twitter syndication token algorithm"
        // for the current formula and swap it in below.
        val syndicationUrl = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=1"
        val json = app.get(
            syndicationUrl,
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        ).text

        val mp4Urls = Regex("\"url\":\"(https:[^\"]+\\.mp4[^\"]*)\"")
            .findAll(json)
            .map { it.groupValues[1].replace("\\/", "/") }
            .toList()

        if (mp4Urls.isNotEmpty()) {
            // mp4 variant URLs embed resolution like /vid/720x1280/xyz.mp4 — pick the tallest
            val best = mp4Urls.maxByOrNull {
                Regex("/vid/\\d+x(\\d+)/").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            } ?: mp4Urls.first()

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = best
                ) {
                    this.referer = "https://x.com/"
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.VIDEO
                }
            )
            return true
        }

        // Fallback: some tweets (esp. longer/live video) only expose an HLS playlist
        val m3u8Url = Regex("\"url\":\"(https:[^\"]+\\.m3u8[^\"]*)\"")
            .find(json)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return false

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3u8Url
            ) {
                this.referer = "https://x.com/"
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.M3U8
            }
        )
        return true
    }
}

