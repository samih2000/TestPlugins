package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class RaindropProvider : MainAPI() {
    override var mainUrl = "https://api.raindrop.io"
    override var name = "Raindrop Videos"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    private val raindropToken = "6431f39f-a72a-41c9-b1a8-712b68484c5f"
    private val mapper = jacksonObjectMapper()
    private val vxSemaphore = Semaphore(5)

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

    data class RaindropTag(val _id: String, val count: Int)
    data class RaindropTagsResponse(val items: List<RaindropTag>)

    data class VxMedia(val type: String?, val url: String?, val thumbnail_url: String?)
    data class VxTweet(val media_extended: List<VxMedia>?, val mediaURLs: List<String>?)

    private suspend fun fetchVxTweet(tweetUrl: String): VxTweet? {
    val match = Regex("(?:x|twitter)\\.com/([^/]+)/status/(\\d+)").find(tweetUrl) ?: return null
    val (username, tweetId) = match.destructured
    return vxSemaphore.withPermit {
        try {
            val json = app.get(
                "https://api.vxtwitter.com/$username/status/$tweetId",
                headers = mapOf("User-Agent" to "Mozilla/5.0")
            ).text
            mapper.readValue<VxTweet>(json)
        } catch (e: Exception) {
            null
        }
    }
}

    private suspend fun RaindropItem.toSearchResponse(provider: MainAPI): SearchResponse {
        val poster = cover?.takeIf { it.isNotBlank() }
            ?: fetchVxTweet(link)?.media_extended?.firstOrNull()?.thumbnail_url

        return provider.newMovieSearchResponse(
            title ?: link,
            link,
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }

    private suspend fun fetchShelf(searchQuery: String?, perPage: Int): List<SearchResponse> = coroutineScope {
        val q = searchQuery?.let { "&search=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val res = app.get(
            "$mainUrl/rest/v1/raindrops/0?sort=-created&perpage=$perPage$q",
            headers = authHeaders()
        ).parsedSafe<RaindropListResponse>()

        res?.items?.map { async { it.toSearchResponse(this@RaindropProvider) } }?.awaitAll()
            ?: emptyList()
    }

    private suspend fun fetchRandomShelf(perPage: Int = 12): List<SearchResponse> = coroutineScope {
        val countRes = app.get("$mainUrl/rest/v1/raindrops/0?perpage=1", headers = authHeaders())
            .parsedSafe<RaindropListResponse>()
        val total = countRes?.count ?: 0
        if (total == 0) return@coroutineScope emptyList()

        val pageSize = 50
        val totalPages = ((total - 1) / pageSize) + 1
        val randomPage = (0 until totalPages).random()

        val res = app.get(
            "$mainUrl/rest/v1/raindrops/0?perpage=$pageSize&page=$randomPage",
            headers = authHeaders()
        ).parsedSafe<RaindropListResponse>()

        res?.items?.shuffled()?.take(perPage)
            ?.map { async { it.toSearchResponse(this@RaindropProvider) } }?.awaitAll()
            ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val tagsRes = app.get("$mainUrl/rest/v1/tags/0", headers = authHeaders())
            .parsedSafe<RaindropTagsResponse>()
        val topTags = tagsRes?.items?.sortedByDescending { it.count }?.take(10) ?: emptyList()

        val lists = coroutineScope {
            val recentDeferred = async { HomePageList("Recently Added", fetchShelf(null, 15)) }
            val randomDeferred = async { HomePageList("Random Picks", fetchRandomShelf(12)) }

            val tagDeferreds = topTags.map { tag ->
                async {
                    val items = fetchShelf("tag:\"${tag._id}\"", 10)
                    if (items.isEmpty()) null else HomePageList(tag._id, items)
                }
            }

            listOf(recentDeferred.await(), randomDeferred.await()) + tagDeferreds.awaitAll().filterNotNull()
        }

        return newHomePageResponse(list = lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchShelf(query, 50)
    }

    override suspend fun load(url: String): LoadResponse {
        val q = URLEncoder.encode(url, "UTF-8")
        val res = app.get("$mainUrl/rest/v1/raindrops/0?search=$q", headers = authHeaders())
            .parsedSafe<RaindropListResponse>()
        val item = res?.items?.firstOrNull()

        val poster = item?.cover?.takeIf { it.isNotBlank() }
            ?: fetchVxTweet(url)?.media_extended?.firstOrNull()?.thumbnail_url

        return newMovieLoadResponse(
            item?.title ?: url,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = item?.excerpt
            this.tags = item?.tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tweet = fetchVxTweet(data) ?: return false

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

