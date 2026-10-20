package com.lagradost

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    // ---------- Tweet cache settings ----------

    // Resolved tweets (thumbnail, video URL, text) are stored on disk so a tile only needs the
    // network the first time it is ever shown.
    private val tweetCachePrefsName = "raindrop_tweet_cache"
    // Tweets with both a thumbnail and a video URL practically never change.
    private val tweetCacheFullTtlMs = 7L * 24L * 60L * 60L * 1000L
    // Incomplete results (no video, no thumbnail) are retried sooner, e.g. after adding X cookies.
    private val tweetCachePartialTtlMs = 60L * 60L * 1000L
    // Total failures (deleted tweet, rate limit) are only remembered for this session, briefly.
    private val tweetMissTtlMs = 10L * 60L * 1000L
    private val tweetCacheMaxEntries = 1500

    // Each single request to vxtwitter / FxTwitter / X gives up after this many seconds, so a
    // hung connection can't hold one of the lookup slots for 30+ seconds.
    private val lookupTimeoutSec = 8L

    // A tile waits at most this long for its tweet lookup. A slow lookup keeps running in the
    // background and fills the cache, so the tile shows its thumbnail on the next load.
    private val tileFetchTimeoutMs = 10_000L

    // While diagnosing slow thumbnails: shows a short toast when a shelf has tiles without a
    // thumbnail, with the most recent failure reason. Set to false to silence it.
    private val debugToasts = true

    // Ignore unknown fields: vxtwitter returns many fields we don't declare.
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val raindropToken: String
        get() = com.lagradost.cloudstream3.CloudStreamApp.context
            ?.let { raindropPrefs(it).getString(RAINDROP_TOKEN_KEY, "") }
            ?: ""

    private fun authHeaders() = mapOf("Authorization" to "Bearer $raindropToken")

    // Home opens ~100 tiles at once, so these limits decide how fast thumbnails appear.
    // If vxtwitter starts answering HTTP 429, lower the permits or raise the delay.
    private val vxSemaphore = Semaphore(6)
    private val vxDelayMs = 100L

    private val twSemaphore = Semaphore(4)
    private val twDelayMs = 150L

    // In-memory layer in front of the disk cache: tweetId -> (time stored, tweet).
    private val tweetMemCache = ConcurrentHashMap<String, Pair<Long, VxTweet>>()
    // tweetId -> time of the last complete failure.
    private val tweetMissCache = ConcurrentHashMap<String, Long>()

    // Lookups run in their own scope so a tile that stops waiting doesn't cancel them.
    // Also means two tiles asking for the same tweet share one request.
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<VxTweet?>>()

    // Every failure reason that gets recorded is also counted, for the debug toast.
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var lastFailure: String? = null
        set(value) {
            field = value
            if (value != null) failureCounts.getOrPut(value) { AtomicInteger() }.incrementAndGet()
        }

    private fun debugToast(message: String) {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

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

    // ---------- Tweet cache (memory + disk) ----------

    private fun tweetCachePrefs() =
        com.lagradost.cloudstream3.CloudStreamApp.context
            ?.getSharedPreferences(tweetCachePrefsName, Context.MODE_PRIVATE)

    private fun isFullTweet(t: VxTweet): Boolean {
        val m = t.media_extended?.firstOrNull()
        return !m?.url.isNullOrBlank() && !m?.thumbnail_url.isNullOrBlank()
    }

    private fun cacheTtlFor(t: VxTweet): Long =
        if (isFullTweet(t)) tweetCacheFullTtlMs else tweetCachePartialTtlMs

    // Disk entries are stored as "<epochMs>|<json>" so the timestamp can be read without parsing the JSON.
    private fun readCachedTweet(tweetId: String): VxTweet? {
        val now = System.currentTimeMillis()

        tweetMemCache[tweetId]?.let { (ts, tweet) ->
            if (now - ts < cacheTtlFor(tweet)) return tweet
            tweetMemCache.remove(tweetId)
        }

        val raw = tweetCachePrefs()?.getString(tweetId, null) ?: return null
        val parts = raw.split("|", limit = 2)
        val ts = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val json = parts.getOrNull(1) ?: return null
        val tweet = try {
            mapper.readValue<VxTweet>(json)
        } catch (e: Exception) {
            return null
        }
        if (now - ts >= cacheTtlFor(tweet)) return null

        tweetMemCache[tweetId] = ts to tweet
        return tweet
    }

    private fun writeCachedTweet(tweetId: String, tweet: VxTweet) {
        val now = System.currentTimeMillis()
        tweetMemCache[tweetId] = now to tweet
        try {
            tweetCachePrefs()?.edit()
                ?.putString(tweetId, "$now|${mapper.writeValueAsString(tweet)}")
                ?.apply()
        } catch (e: Exception) {
            // best effort
        }
    }

    // Drops expired entries and, if the cache is still too big, the oldest ones.
    private fun pruneTweetCache() {
        val prefs = tweetCachePrefs() ?: return
        val now = System.currentTimeMillis()
        val remove = mutableListOf<String>()
        val live = mutableListOf<Pair<String, Long>>()

        prefs.all.forEach { (key, value) ->
            val ts = (value as? String)?.substringBefore('|')?.toLongOrNull()
            if (ts == null || now - ts > tweetCacheFullTtlMs) remove.add(key) else live.add(key to ts)
        }
        if (live.size > tweetCacheMaxEntries) {
            live.sortedBy { it.second }
                .take(live.size - tweetCacheMaxEntries)
                .forEach { remove.add(it.first) }
        }
        if (remove.isNotEmpty()) {
            val editor = prefs.edit()
            remove.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    // ---------- Tweet fetching ----------

    // Looks like a normal phone browser; a bare "Mozilla/5.0" is the kind of thing bot filters block.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json,text/plain,*/*",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // After an HTTP 403/429 a source is skipped for a while, instead of sending ~100 more
    // requests it will refuse (each of which also makes the tiles wait).
    private val blockedBackoffMs = 10L * 60L * 1000L

    @Volatile
    private var vxBlockedUntil = 0L

    @Volatile
    private var fxBlockedUntil = 0L

    private suspend fun fetchVxTweet(username: String, tweetId: String): VxTweet? =
        vxSemaphore.withPermit {
            if (System.currentTimeMillis() < vxBlockedUntil) return@withPermit null
            val fetched = try {
                val vxUrl = "https://api.vxtwitter.com/$username/status/$tweetId"
                var res = app.get(vxUrl, headers = browserHeaders, timeout = lookupTimeoutSec)
                if (res.code == 429) {
                    // Rate limited: back off once and try again.
                    delay(1500)
                    res = app.get(vxUrl, headers = browserHeaders, timeout = lookupTimeoutSec)
                }
                if (res.code == 200) {
                    mapper.readValue<VxTweet>(res.text)
                } else {
                    lastFailure = "vxtwitter HTTP ${res.code}"
                    if (res.code == 403 || res.code == 429) {
                        vxBlockedUntil = System.currentTimeMillis() + blockedBackoffMs
                    }
                    null
                }
            } catch (e: Exception) {
                lastFailure = "vxtwitter ${e.javaClass.simpleName}"
                null
            }
            delay(vxDelayMs)
            fetched
        }

    // FxTwitter has its own JSON shape: tweet.text, tweet.media.videos[], tweet.media.photos[].
    private suspend fun fetchFxTweet(username: String, tweetId: String): VxTweet? =
        vxSemaphore.withPermit {
            if (System.currentTimeMillis() < fxBlockedUntil) return@withPermit null
            val fetched = try {
                val res = app.get(
                    "https://api.fxtwitter.com/$username/status/$tweetId",
                    headers = browserHeaders,
                    timeout = lookupTimeoutSec
                )
                if (res.code != 200) {
                    lastFailure = "fxtwitter HTTP ${res.code}"
                    if (res.code == 403 || res.code == 429) {
                        fxBlockedUntil = System.currentTimeMillis() + blockedBackoffMs
                    }
                    null
                } else {
                    val tweet = mapper.readTree(res.text).path("tweet")
                    val text = tweet.path("text").asText(null)?.takeIf { it.isNotBlank() }
                    val media = tweet.path("media")
                    val list = mutableListOf<VxMedia>()
                    for (v in media.path("videos")) {
                        list.add(
                            VxMedia(
                                type = if (v.path("type").asText() == "gif") "gif" else "video",
                                url = v.path("url").asText(null)?.takeIf { it.isNotBlank() },
                                thumbnail_url = v.path("thumbnail_url").asText(null)
                                    ?.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                    if (list.isEmpty()) {
                        for (p in media.path("photos")) {
                            val purl = p.path("url").asText(null)?.takeIf { it.isNotBlank() }
                            if (purl != null) list.add(VxMedia("photo", null, purl))
                        }
                    }
                    if (list.isEmpty() && text == null) null
                    else VxTweet(list.ifEmpty { null }, null, text)
                }
            } catch (e: Exception) {
                lastFailure = "fxtwitter ${e.javaClass.simpleName}"
                null
            }
            delay(vxDelayMs)
            fetched
        }

    private suspend fun fetchAuthenticatedTweet(tweetId: String): VxTweet? {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return null
        val prefs = raindropPrefs(ctx)
        val authToken = prefs.getString(TWITTER_AUTH_TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val ct0 = prefs.getString(TWITTER_CT0_KEY, null)?.takeIf { it.isNotBlank() }
            ?: return null

        return twSemaphore.withPermit {
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

                val res = app.get(url, headers = headers, timeout = lookupTimeoutSec)
                if (res.code != 200) lastFailure = "X API HTTP ${res.code}"
                val json = res.text
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
                lastFailure = "X API ${e.javaClass.simpleName}"
                null
            }
            delay(twDelayMs)
            fetched
        }
    }

    private fun isCompleteTweet(t: VxTweet?): Boolean =
        t != null && isFullTweet(t) && !t.text.isNullOrBlank()

    // Fills the gaps of `a` with whatever `b` has (first media item and text).
    private fun mergeTweets(a: VxTweet?, b: VxTweet?): VxTweet? {
        if (a == null) return b
        if (b == null) return a
        val am = a.media_extended?.firstOrNull()
        val bm = b.media_extended?.firstOrNull()
        val media = VxMedia(
            type = am?.type ?: bm?.type,
            url = am?.url?.takeIf { it.isNotBlank() } ?: bm?.url,
            thumbnail_url = am?.thumbnail_url?.takeIf { it.isNotBlank() } ?: bm?.thumbnail_url
        )
        val mediaList = if (media.url == null && media.thumbnail_url == null) null else listOf(media)
        val text = a.text?.takeIf { it.isNotBlank() } ?: b.text
        if (mediaList == null && text == null) return null
        return VxTweet(media_extended = mediaList, mediaURLs = a.mediaURLs ?: b.mediaURLs, text = text)
    }

    // The actual network work. Sources in order: vxtwitter, FxTwitter, then the authenticated
    // X API. Each one is only asked for what the previous ones couldn't provide.
    private suspend fun fetchTweetMediaFromNetwork(username: String, tweetId: String): VxTweet? {
        var result = fetchVxTweet(username, tweetId)
        if (isCompleteTweet(result)) return result

        result = mergeTweets(result, fetchFxTweet(username, tweetId))
        if (isCompleteTweet(result)) return result

        return mergeTweets(result, fetchAuthenticatedTweet(tweetId))
    }

    // Cache-first: memory, then disk, then network. Only real results are stored.
    private suspend fun fetchTweetMedia(tweetUrl: String): VxTweet? {
        val match = tweetRegex.find(tweetUrl) ?: return null
        val (username, tweetId) = match.destructured

        readCachedTweet(tweetId)?.let { return it }

        val lastMiss = tweetMissCache[tweetId]
        if (lastMiss != null && System.currentTimeMillis() - lastMiss < tweetMissTtlMs) return null

        // Lazy so a duplicate created by a race is simply never started.
        val job = inFlight.getOrPut(tweetId) {
            fetchScope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = fetchTweetMediaFromNetwork(username, tweetId)
                    if (result != null) {
                        tweetMissCache.remove(tweetId)
                        writeCachedTweet(tweetId, result)
                    } else {
                        tweetMissCache[tweetId] = System.currentTimeMillis()
                    }
                    result
                } finally {
                    inFlight.remove(tweetId)
                }
            }
        }
        job.start()
        return job.await()
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
        // Don't let one slow tweet hold up the whole shelf. If it times out, the lookup keeps
        // running in the background and the result is cached for the next load.
        val tweet = withTimeoutOrNull(tileFetchTimeoutMs) { fetchTweetMedia(link) }
        if (tweet == null) {
            val id = tweetIdOf(link)
            if (id != null && inFlight.containsKey(id)) {
                lastFailure = "lookups slower than ${tileFetchTimeoutMs / 1000}s"
            }
        }
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
        val responses = map { async { it.toSearchResponse(this@RaindropProvider) } }.awaitAll()
        if (debugToasts) {
            val missing = responses.count { it.posterUrl == null }
            if (missing > 0) {
                val reasons = failureCounts.entries
                    .sortedByDescending { it.value.get() }
                    .take(3)
                    .joinToString(", ") { "${it.key} x${it.value.get()}" }
                failureCounts.clear()
                debugToast(
                    "Raindrop: $missing/${responses.size} tiles without thumbnail " +
                            if (reasons.isNotEmpty()) "[$reasons]"
                            else "[no errors: lookups answered but had no thumbnail]"
                )
            }
        }
        responses
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
            try {
                pruneTweetCache()
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
