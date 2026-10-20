package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // ---------- Shelf layout ----------

    // Each shelf starts with one page of 16 tiles. CloudStream loads the next page when the
    // row is swiped to its end or the expanded grid is scrolled to the bottom, up to
    // shelfMaxPages pages (4 x 16 = 64 tiles).
    private val shelfRowSize = 16
    private val shelfMaxPages = 4
    private val recentShelfSize = 8
    private val tagShelfCount = 6
    private val tagMinCount = 5 // tags with fewer bookmarks make thin shelves, so they're skipped
    private val tagPoolMax = 100
    private val tagPoolTtlMs = 60L * 60L * 1000L
    private val tagRotationMs = 6L * 60L * 60L * 1000L

    private val tagPoolKey = "tag_pool_json"
    private val tagPoolTsKey = "tag_pool_ts"
    private val tagSelectionKey = "tag_selection_json"
    private val tagSelectionBucketKey = "tag_selection_bucket"

    // Ignore unknown fields: vxtwitter returns many fields we don't declare.
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

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

    data class RaindropTag(
        @JsonProperty("_id") val id: String,
        val count: Int
    )

    data class RaindropTagsResponse(val items: List<RaindropTag>)

    data class VxMedia(val type: String?, val url: String?, val thumbnail_url: String?)
    data class VxTweet(
        val media_extended: List<VxMedia>?,
        val mediaURLs: List<String>?,
        val text: String? = null
    )

    // ---------- Link handling (inspired by Piko's "Clear tracking params" / "Handle custom links") ----------

    // Accepts x.com, twitter.com and the common embed-fixer domains.
    private val tweetRegex =
        Regex("(?:x|twitter|vxtwitter|fxtwitter|fixupx|fixvx)\\.com/([^/?#]+)/status/(\\d+)")

    private fun tweetIdOf(url: String): String? =
        tweetRegex.find(url)?.groupValues?.get(2)

    // https://vxtwitter.com/user/status/123?s=20&t=abc  ->  https://x.com/user/status/123
    private fun normalizeTweetUrl(url: String): String {
        val m = tweetRegex.find(url) ?: return url
        return "https://x.com/${m.groupValues[1]}/status/${m.groupValues[2]}"
    }

    // ---------- Tweet fetching ----------

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
                val legacy = tweetNode.path("legacy")
                val text = legacy.path("full_text").asText(null)?.takeIf { it.isNotBlank() }
                val mediaList = legacy.path("extended_entities").path("media")

                val mediaExtended = if (!mediaList.isArray || mediaList.isEmpty) {
                    null
                } else {
                    mediaList.mapNotNull { media ->
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
                    }.ifEmpty { null }
                }

                if (mediaExtended == null && text == null) null
                else VxTweet(mediaExtended, null, text)
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
        val match = tweetRegex.find(tweetUrl) ?: return null
        val (username, tweetId) = match.destructured

        val vx = fetchVxTweet(username, tweetId)
        val vxMedia = vx?.media_extended?.firstOrNull()
        val hasThumb = !vxMedia?.thumbnail_url.isNullOrBlank()
        val hasVideo = !vxMedia?.url.isNullOrBlank()
        val hasText = !vx?.text.isNullOrBlank()
        if (hasThumb && hasVideo && hasText) return vx

        // Fall back to the authenticated X API for anything vxtwitter couldn't provide.
        val auth = fetchAuthenticatedTweet(tweetId)
        val authMedia = auth?.media_extended?.firstOrNull()

        val merged = VxMedia(
            type = vxMedia?.type ?: authMedia?.type,
            url = vxMedia?.url?.takeIf { it.isNotBlank() } ?: authMedia?.url,
            thumbnail_url = vxMedia?.thumbnail_url?.takeIf { it.isNotBlank() } ?: authMedia?.thumbnail_url
        )
        val text = vx?.text?.takeIf { it.isNotBlank() } ?: auth?.text
        val mediaList = if (merged.url == null && merged.thumbnail_url == null) null else listOf(merged)

        if (mediaList == null && text == null) return null
        return VxTweet(media_extended = mediaList, mediaURLs = vx?.mediaURLs, text = text)
    }

    // ---------- Titles ----------

    // Junk titles Raindrop saves when X is login-walled / age-restricted.
    private val badTitle = Regex(
        "age[- ]?restricted|^\\s*(x|twitter|post|x\\s*\\(formerly twitter\\))\\s*$",
        RegexOption.IGNORE_CASE
    )

    private fun cleanTitle(raw: String?, maxLen: Int = 80): String? {
        if (raw.isNullOrBlank() || badTitle.containsMatchIn(raw)) return null
        val cleaned = raw
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isBlank()) return null
        return if (cleaned.length > maxLen) cleaned.take(maxLen - 1).trimEnd() + "…" else cleaned
    }

    private fun fallbackTitle(url: String): String {
        val m = tweetRegex.find(url)
        return m?.let { "@${it.groupValues[1]}" } ?: url
    }

    // Raindrop title -> tweet text -> @username
    private fun pickTitle(raindropTitle: String?, tweetText: String?, url: String): String =
        cleanTitle(raindropTitle) ?: cleanTitle(tweetText) ?: fallbackTitle(url)

    // For tweet links, Raindrop's cover is usually a screenshot of X's error/login page
    // ("Not found", "Read more on X"), so only use it for non-tweet links.
    private fun posterFor(link: String, tweet: VxTweet?, cover: String?): String? {
        val thumb = tweet?.media_extended?.firstOrNull()?.thumbnail_url?.takeIf { it.isNotBlank() }
        if (thumb != null) return thumb
        return if (tweetRegex.containsMatchIn(link)) null else cover?.takeIf { it.isNotBlank() }
    }

    private suspend fun RaindropItem.toSearchResponse(provider: MainAPI): SearchResponse {
        val tweet = fetchTweetMedia(link)
        val poster = posterFor(link, tweet, cover)

        return provider.newMovieSearchResponse(
            pickTitle(title, tweet?.text, link),
            link,
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }

    private suspend fun List<RaindropItem>.toSearchResponses(): List<SearchResponse> = coroutineScope {
        map { async { it.toSearchResponse(this@RaindropProvider) } }.awaitAll()
    }

    // ---------- Raindrop shelves ----------

    // pageIndex is Raindrop's own 0-based page number.
    private suspend fun fetchShelf(
        searchQuery: String?,
        perPage: Int,
        pageIndex: Int = 0
    ): List<SearchResponse> {
        val q = searchQuery?.let { "&search=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val res = app.get(
            "$mainUrl/rest/v1/raindrops/0?sort=-created&perpage=$perPage&page=$pageIndex$q",
            headers = authHeaders()
        ).parsedSafe<RaindropListResponse>()

        return res?.items?.toSearchResponses() ?: emptyList()
    }

    // Random Picks: one shuffled pool of (row size x max pages) bookmarks, built when the home page
    // loads. Expanding the shelf reads the next slice of the SAME pool, so page 2 never repeats page 1.
    private var randomPool: List<RaindropItem> = emptyList()
    private val randomPoolMutex = Mutex()

    private suspend fun buildRandomPool(): List<RaindropItem> = coroutineScope {
        val pageSize = 50 // Raindrop's maximum per request
        val total = app.get("$mainUrl/rest/v1/raindrops/0?perpage=1", headers = authHeaders())
            .parsedSafe<RaindropListResponse>()?.count ?: 0
        if (total == 0) return@coroutineScope emptyList()

        val totalPages = ((total - 1) / pageSize) + 1
        // Two distinct random pages: more variety, and the last partial page still gets picked sometimes.
        val pages = (0 until totalPages).shuffled().take(2)

        pages.map { p ->
            async {
                app.get(
                    "$mainUrl/rest/v1/raindrops/0?perpage=$pageSize&page=$p",
                    headers = authHeaders()
                ).parsedSafe<RaindropListResponse>()?.items ?: emptyList()
            }
        }.awaitAll()
            .flatten()
            .distinctBy { it.id }
            .shuffled()
            .take(shelfRowSize * shelfMaxPages)
    }

    private suspend fun fetchRandomShelf(page: Int): List<SearchResponse> {
        val pool = randomPoolMutex.withLock {
            if (page <= 1 || randomPool.isEmpty()) randomPool = buildRandomPool()
            randomPool
        }
        return pool.drop((page - 1) * shelfRowSize).take(shelfRowSize).toSearchResponses()
    }

    // ---------- Tag pool ----------
    // The tag list is kept in SharedPreferences because `mainPage` is read synchronously
    // (it has to know the shelf names before any request is made).

    private fun readTagPool(): List<String> {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return emptyList()
        val json = raindropPrefs(ctx).getString(tagPoolKey, null) ?: return emptyList()
        return try {
            mapper.readValue<List<String>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun refreshTagPoolIfStale() {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return
        if (raindropToken.isBlank()) return
        val prefs = raindropPrefs(ctx)
        val age = System.currentTimeMillis() - prefs.getLong(tagPoolTsKey, 0L)
        if (readTagPool().isNotEmpty() && age < tagPoolTtlMs) return

        val res = try {
            app.get("$mainUrl/rest/v1/tags/0", headers = authHeaders())
                .parsedSafe<RaindropTagsResponse>()
        } catch (e: Exception) {
            null
        } ?: return

        val names = res.items
            .filter { it.count >= tagMinCount && it.id.isNotBlank() }
            .sortedByDescending { it.count }
            .map { it.id }
            .take(tagPoolMax)
        if (names.isEmpty()) return

        prefs.edit()
            .putString(tagPoolKey, mapper.writeValueAsString(names))
            .putLong(tagPoolTsKey, System.currentTimeMillis())
            .apply()
    }

    /** Called by the plugin at app start so the tag shelves exist by the time the home page opens. */
    fun warmUp() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                refreshTagPoolIfStale()
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    // Index of the current 6-hour window in local time (flips at 00:00, 06:00, 12:00 and 18:00).
    private fun currentTagBucket(): Long {
        val now = System.currentTimeMillis()
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / tagRotationMs
    }

    // Which tag shelves to show right now: a random pick from the tag pool that stays the same
    // for the whole 6-hour window, then changes. The next pick avoids repeating the last one
    // whenever the pool is big enough.
    private fun selectedTags(): List<String> {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return emptyList()
        val pool = readTagPool()
        if (pool.isEmpty()) return emptyList()

        val prefs = raindropPrefs(ctx)
        val bucket = currentTagBucket()

        val previous: List<String> = try {
            prefs.getString(tagSelectionKey, null)
                ?.let { mapper.readValue<List<String>>(it) }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // Same window as the stored pick: keep the same shelves.
        if (prefs.getLong(tagSelectionBucketKey, -1L) == bucket) {
            val stillValid = previous.filter { it in pool }
            if (stillValid.size >= minOf(tagShelfCount, pool.size)) return stillValid
        }

        val fresh = pool.filter { it !in previous }
        val candidates = if (fresh.size >= tagShelfCount) fresh else pool
        val picked = candidates.shuffled(kotlin.random.Random(bucket)).take(tagShelfCount)

        prefs.edit()
            .putString(tagSelectionKey, mapper.writeValueAsString(picked))
            .putLong(tagSelectionBucketKey, bucket)
            .apply()
        return picked
    }

    private fun tagQuery(tag: String): String =
        if (tag.any { it.isWhitespace() }) "#\"$tag\"" else "#$tag"

    // ---------- Main page ----------

    // One request per shelf, so CloudStream can paginate (expand) each shelf on its own.
    // `data` -> `name` order is the CloudStream convention for mainPageOf.
    override val mainPage: List<MainPageData>
        get() {
            val shelves = mutableListOf(
                "recent" to "Recently Added",
                "random" to "Random Picks"
            )
            selectedTags().forEach { tag -> shelves.add("tag:$tag" to tag) }
            return mainPageOf(*shelves.toTypedArray())
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        return when {
            data == "recent" -> {
                refreshTagPoolIfStale()
                newHomePageResponse(request.name, fetchShelf(null, recentShelfSize), false)
            }

            data == "random" -> {
                val items = fetchRandomShelf(page)
                newHomePageResponse(
                    request.name,
                    items,
                    page < shelfMaxPages && items.size >= shelfRowSize
                )
            }

            data.startsWith("tag:") -> {
                val items = fetchShelf(tagQuery(data.removePrefix("tag:")), shelfRowSize, page - 1)
                newHomePageResponse(
                    request.name,
                    items,
                    page < shelfMaxPages && items.size >= shelfRowSize
                )
            }

            else -> newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return fetchShelf(query, 50)
    }

    private suspend fun findRaindropItem(url: String): RaindropItem? {
        suspend fun query(term: String): RaindropItem? {
            val q = URLEncoder.encode(term, "UTF-8")
            return app.get("$mainUrl/rest/v1/raindrops/0?search=$q", headers = authHeaders())
                .parsedSafe<RaindropListResponse>()
                ?.items
                ?.firstOrNull()
        }

        // Search by tweet ID first so tracking params / alternate domains don't break the match,
        // then fall back to the full URL.
        val id = tweetIdOf(url)
        if (id != null) {
            val byId = query(id)
            if (byId != null && tweetIdOf(byId.link) == id) return byId
        }
        return query(url)
    }

    override suspend fun load(url: String): LoadResponse {
        val item = findRaindropItem(url)
        val tweet = fetchTweetMedia(url)

        val poster = posterFor(url, tweet, item?.cover)

        return newMovieLoadResponse(
            pickTitle(item?.title, tweet?.text, url),
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
