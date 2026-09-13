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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

class RaindropProvider : MainAPI() {
    override var mainUrl = "https://api.raindrop.io"
    override var name = "Raindrop Videos"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    private val mapper = jacksonObjectMapper()

    private val raindropToken: String
        get() = com.lagradost.cloudstream3.CloudStreamApp.context
            ?.let { raindropPrefs(it).getString(RAINDROP_TOKEN_KEY, "") }
            ?: ""

    private fun authHeaders() = mapOf("Authorization" to "Bearer $raindropToken")

    private val vxSemaphore = Semaphore(3)
    private val vxDelayMs = 300L
    private val vxCache = mutableMapOf<String, VxTweet?>()
    private val vxCacheMutex = Mutex()

    private val twSemaphore = Semaphore(3)
    private val twDelayMs = 300L
    private val twCache = mutableMapOf<String, VxTweet?>()
    private val twCacheMutex = Mutex()

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

    private suspend fun fetchVxTweet(username: String, tweetId: String): VxTweet? {
        vxCacheMutex.withLock {
            if (vxCache.containsKey(tweetId)) return vxCache[tweetId]
        }
        val result = vxSemaphore.withPermit {
            val fetched = try {
                val json = app.get(
                    "https://api.vxtwitter.com/$username/status/$tweetId",
                    headers = mapOf("User-Agent" to "Mozilla/5.0")
                ).text
                mapper.readValue<VxTweet>(json)
            } catch (e: Exception) {
                null
            }
            delay(vxDelayMs)
            fetched
        }
        vxCacheMutex.withLock { vxCache[tweetId] = result }
        return result
    }

    private suspend fun fetchAuthenticatedTweet(tweetId: String): VxTweet? {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return null
        val prefs = raindropPrefs(ctx)
        val authToken = prefs.getString(TWITTER_AUTH_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val ct0 = prefs.getString(TWITTER_CT0_KEY, null)?.takeIf { it.isNotBlank() }
            ?: return null

        twCacheMutex.withLock {
            if (twCache.containsKey(tweetId)) return twCache[tweetId]
        }

        val result = twSemaphore.withPermit {
            val fetched = try {
                val queryId = "2ICDjqPd81tulZcYrtpTuQ"
                val variables = "{\"tweetId\":\"$tweetId\",\"withCommunity\":false," +
                        "\"includePromotedContent\":false,\"withVoice\":false}"
                val features = "{\"creator_subscriptions_tweet_preview_api_enabled\":true," +
                        "\"tweetypie_unmention_optimization_enabled\":true," +
                        "\"responsive_web_edit_tweet_api_enabled\":true," +
                        "\"graphql_is_translatable_rweb_tweet_is_translatable_enabled\":true," +
                        "\"view_counts_everywhere_api_enabled\":true," +
                        "\"longform_notetweets_consumption_enabled\":true," +
                        "\"responsive_web_twitter_article_tweet_consumption_enabled\":true," +
                        "\"tweet_awards_web_tipping_enabled\":false," +
                        "\"freedom_of_speech_not_reach_fetch_enabled\":true," +
                        "\"standardized_nudges_misinfo\":true," +
                        "\"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled\":true," +
                        "\"longform_notetweets_rich_text_read_enabled\":true," +
                        "\"longform_notetweets_inline_media_enabled\":true," +
                        "\"responsive_web_graphql_exclude_directive_enabled\":true," +
                        "\"verified_phone_label_enabled\":false," +
                        "\"responsive_web_media_download_video_enabled\":true," +
                        "\"responsive_web_graphql_skip_user_profile_image_extensions_enabled\":false," +
                        "\"responsive_web_graphql_timeline_navigation_enabled\":true}"

                val url = "https://x.com/i/api/graphql/$queryId/TweetResultByRestId" +
                        "?variables=" + URLEncoder.encode(variables, "UTF-8") +
                        "&features=" + URLEncoder.encode(features, "UTF-8")

                val headers = mapOf(
                    "authorization" to ("Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6" +
                            "I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"),
                    "cookie" to "auth_token=$authToken; ct0=$ct0",
                    "x-csrf-token" to ct0,
                    "x-twitter-auth-type" to "OAuth2Session",
                    "x-twitter-active-user" to "yes",
                    "x-twitter-client-language" to "en",
                    "User-Agent" to "Mozilla/5.0"
                )

                val json = app.get(url, headers = headers).text
                val root = mapper.readTree(json)
                var tweetNode = root.path("data").path("tweetResult").path("result")
                if (tweetNode.path("__typename").asText() == "TweetWithVisibilityResults") {
                    tweetNode = tweetNode.path("tweet")
                }
                val mediaList = tweetNode.path("legacy").path("extended_entities").path("media")

                if (!mediaList.isArray || mediaList.isEmpty) {
                    null
                } else {
                    val mediaExtended = mediaList.mapNotNull { media ->
                        val type = media.path("type").asText()
                        val thumb = media.path("media_url_https").asText(null)
                        if (type != "video" && type != "animated_gif") {
                            return@mapNotNull if (thumb != null) VxMedia("photo", null, thumb) else null
                        }
                        val variants = media.path("video_info").path("variants")
                        val bestUrl = variants
                            .filter { it.path("content_type").asText() == "video/mp4" }
                            .maxByOrNull { it.path("bitrate").asInt(0) }
                            ?.path("url")?.asText()
                        VxMedia(
                            type = if (type == "animated_gif") "gif" else "video",
                            url = bestUrl,
                            thumbnail_url = thumb
                        )
                    }
                    if (mediaExtended.isEmpty()) null else VxTweet(mediaExtended, null)
                }
            } catch (e: Exception) {
                null
            }
            delay(twDelayMs)
            fetched
        }

        twCacheMutex.withLock { twCache[tweetId] = result }
        return result
    }

    private suspend fun fetchTweetMedia(tweetUrl: String): VxTweet? {
        val match = Regex("(?:x|twitter)\\.com/([^/]+)/status/(\\d+)").find(tweetUrl) ?: return null
        val (username, tweetId) = match.destructured

        val vx = fetchVxTweet(username, tweetId)
        val vxMedia = vx?.media_extended?.firstOrNull()
        val hasThumb = !vxMedia?.thumbnail_url.isNullOrBlank()
        val hasVideo = !vxMedia?.url.isNullOrBlank()
        if (hasThumb && hasVideo) return vx

        val auth = fetchAuthenticatedTweet(tweetId)
        val authMedia = auth?.media_extended?.firstOrNull()

        val merged = VxMedia(
            type = vxMedia?.type ?: authMedia?.type,
            url = vxMedia?.url?.takeIf { it.isNotBlank() } ?: authMedia?.url,
            thumbnail_url = vxMedia?.thumbnail_url?.takeIf { it.isNotBlank() } ?: authMedia?.thumbnail_url
        )
        if (merged.url == null && merged.thumbnail_url == null) return null
        return VxTweet(media_extended = listOf(merged), mediaURLs = vx?.mediaURLs)
    }

    private suspend fun RaindropItem.toSearchResponse(provider: MainAPI): SearchResponse {
        val poster = fetchTweetMedia(link)?.media_extended?.firstOrNull()?.thumbnail_url
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
    val topTags = fetchTopTags(topN = 4)

    val lists = coroutineScope {
        val recentDeferred = async { HomePageList("Recently Added", fetchShelf(null, 6)) }
        val randomDeferred = async { HomePageList("Random Picks", fetchRandomShelf(6)) }

        val tagDeferreds = topTags.map { tagName ->
            async {
                val items = fetchShelf("#$tagName", 4)
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

        val poster = fetchTweetMedia(url)?.media_extended?.firstOrNull()?.thumbnail_url
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
        val media = fetchTweetMedia(data)?.media_extended?.firstOrNull { !it.url.isNullOrBlank() }
            ?: return false
        val videoUrl = media.url ?: return false

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
