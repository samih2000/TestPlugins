package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class RaindropProvider : MainAPI() {
    override var mainUrl = "https://api.raindrop.io"
    override var name = "Raindrop Videos"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    private val raindropToken = "6431f39f-a72a-41c9-b1a8-712b68484c5f"

    private fun authHeaders() = mapOf("Authorization" to "Bearer $raindropToken")

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val perPage = 50
        val url = "$mainUrl/rest/v1/raindrops/0" +
                "?page=${page - 1}&perpage=$perPage&sort=-created"

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
            link,
            TvType.Movie
        ) {
            this.posterUrl = cover
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/rest/v1/raindrops/0?search=$q&perpage=50"
        val res = app.get(url, headers = authHeaders()).parsedSafe<RaindropListResponse>()
            ?: return emptyList()
        return res.items.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse {
        val q = URLEncoder.encode(url, "UTF-8")
        val res = app.get("$mainUrl/rest/v1/raindrops/0?search=$q", headers = authHeaders())
            .parsedSafe<RaindropListResponse>()
        val item = res?.items?.firstOrNull()

        return newMovieLoadResponse(
            item?.title ?: url,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = item?.cover
            this.plot = item?.excerpt
            this.tags = item?.tags
        }
    }

    data class VxMedia(val type: String?, val url: String?)
    data class VxTweet(val media_extended: List<VxMedia>?, val mediaURLs: List<String>?)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = Regex("(?:x|twitter)\\.com/([^/]+)/status/(\\d+)").find(data) ?: return false
        val (username, tweetId) = match.destructured

        val json = app.get(
            "https://api.vxtwitter.com/$username/status/$tweetId",
            headers = mapOf("User-Agent" to "Mozilla/5.0")
        ).text

        val tweet = tryParseJson<VxTweet>(json) ?: return false

        val videoUrl = tweet.media_extended
            ?.firstOrNull { it.type == "video" || it.type == "gif" }
            ?.url
            ?: tweet.mediaURLs?.firstOrNull { it.endsWith(".mp4") }
            ?: return false

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl
            ) {
                this.referer = "https://x.com/"
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.VIDEO
            }
        )
        return true
    }
}
