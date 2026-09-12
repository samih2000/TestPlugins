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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

class RaindropProvider : MainAPI() {
    override var mainUrl = "https://api.raindrop.io"
    override var name = "Raindrop Videos"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    private val raindropToken: String
    get() = com.lagradost.cloudstream3.CloudStreamApp.context
        ?.let { raindropPrefs(it).getString(RAINDROP_TOKEN_KEY, "") }
        ?: ""
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
        val poster = fetchVxTweet(link)?.media_extended?.firstOrNull()?.thumbnail_url
            ?: cover?.takeIf { it.isNotBlank() }

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

    private suspend fun fetchTopTags(sampleSize: Int = 200, topN: Int = 10): List<String> {
        val res = app.get(
            "$mainUrl/rest/v1/raindrops/0?perpage=$sampleSize&sort=-created",
            headers = authHeaders()
        ).parsedSafe<RaindropListResponse>()

        return res?.items
            ?.flatMap { it.tags ?: emptyList() }
            ?.groupingBy { it }
            ?.eachCount()
            ?.entries
            ?.sortedByDescending { it.value }
            ?.take(topN)
            ?.map { it.key }
            ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val topTags = fetchTopTags()

        val lists = coroutineScope {
            val recentDeferred = async { HomePageList("Recently Added", fetchShelf(null, 15)) }
            val randomDeferred = async { HomePageList("Random Picks", fetchRandomShelf(12)) }

            val tagDeferreds = topTags.map { tagName ->
                async {
                    val items = fetchShelf("#$tagName", 6)
                    if (items.isEmpty()) null else HomePageList(tagName, items)
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

        val poster = fetchVxTweet(url)?.media_extended?.firstOrNull()?.thumbnail_url
            ?: item?.cover?.takeIf { it.isNotBlank() }

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
        val tweet = fetchVxTweet(data)

        val videoUrl = tweet?.media_extended
            ?.firstOrNull { it.type == "video" || it.type == "gif" }
            ?.url
            ?: tweet?.mediaURLs?.firstOrNull { it.endsWith(".mp4") }
            ?: fetchViaAuthenticatedTwitter(data)
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

    private suspend fun fetchViaAuthenticatedTwitter(tweetUrl: String): String? {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return null
        val prefs = raindropPrefs(ctx)
        val authToken = prefs.getString(TWITTER_AUTH_TOKEN_KEY, null)
        val ct0 = prefs.getString(TWITTER_CT0_KEY, null)
        if (authToken.isNullOrBlank() || ct0.isNullOrBlank()) return null

        val tweetId = Regex("status/(\\d+)").find(tweetUrl)?.groupValues?.get(1) ?: return null

        val variables = "{\"tweetId\":\"$tweetId\",\"includePromotedContent\":true," +
                "\"withBirdwatchNotes\":true,\"withVoice\":true,\"withCommunity\":true}"
        val features = "{\"creator_subscriptions_tweet_preview_api_enabled\":true," +
                "\"c9s_tweet_anatomy_moderator_badge_enabled\":true," +
                "\"responsive_web_graphql_exclude_directive_enabled\":true," +
                "\"verified_phone_label_enabled\":false," +
                "\"tweet_awards_web_tipping_enabled\":false," +
                "\"responsive_web_graphql_skip_user_profile_image_extensions_enabled\":false," +
                "\"responsive_web_graphql_timeline_navigation_enabled\":true," +
                "\"rweb_tipjar_consumption_enabled\":true," +
                "\"freedom_of_speech_not_reach_fetch_enabled\":true," +
                "\"standardized_nudges_misinfo\":true," +
                "\"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled\":true," +
                "\"rweb_video_timestamps_enabled\":true," +
                "\"longform_notetweets_rich_text_read_enabled\":true," +
                "\"longform_notetweets_inline_media_enabled\":true," +
                "\"responsive_web_enhance_cards_enabled\":false}"

        val url = "https://x.com/i/api/graphql/2ICDjqPd81tulZcYrtpTuQ/TweetResultByRestId" +
                "?variables=" + URLEncoder.encode(variables, "UTF-8") +
                "&features=" + URLEncoder.encode(features, "UTF-8")

        val json = try {
            app.get(
                url,
                headers = mapOf(
                    "Authorization" to ("Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6" +
                            "I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"),
                    "x-csrf-token" to ct0,
                    "Cookie" to "auth_token=$authToken; ct0=$ct0",
                    "User-Agent" to "Mozilla/5.0"
                )
            ).text
        } catch (e: Exception) {
            return null
        }

        val mp4Urls = Regex("\"url\":\"(https:[^\"]+\\.mp4[^\"]*)\"")
            .findAll(json)
            .map { it.groupValues[1].replace("\\/", "/") }
            .toList()

        return mp4Urls.maxByOrNull {
            Regex("/vid/\\d+x(\\d+)/").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
    }
}
