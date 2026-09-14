package com.lagradost

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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

class TwitterAccountsProvider : MainAPI() {
    override var mainUrl = "https://x.com"
    override var name = "Twitter Accounts"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Others)

    private val mapper = jacksonObjectMapper()
    private val semaphore = Semaphore(3)
    private val delayMs = 300L

    private fun authCookies(): Pair<String, String>? {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context ?: return null
        val prefs = twAccPrefs(ctx)
        val authToken = prefs.getString(TWACC_AUTH_TOKEN_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        val ct0 = prefs.getString(TWACC_CT0_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
        return authToken to ct0
    }

    private fun authHeaders(ct0: String, authToken: String) = mapOf(
        "authorization" to ("Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6" +
                "I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"),
        "cookie" to "auth_token=$authToken; ct0=$ct0",
        "x-csrf-token" to ct0,
        "x-twitter-auth-type" to "OAuth2Session",
        "x-twitter-active-user" to "yes",
        "x-twitter-client-language" to "en",
        "User-Agent" to "Mozilla/5.0"
    )

    private suspend fun resolveUserId(username: String): Pair<String?, String> {
        val (authToken, ct0) = authCookies() ?: return null to "No cookies configured"

        val queryId = "KybxDj9RrADIITXlGG8kpw"
        val variables = "{\"screen_name\":\"$username\",\"withSafetyModeUserFields\":true}"
        val url = "https://x.com/i/api/graphql/$queryId/UserByScreenName" +
                "?variables=" + URLEncoder.encode(variables, "UTF-8")

        return try {
            val json = app.get(url, headers = authHeaders(ct0, authToken)).text
            val root = mapper.readTree(json)
            val id = root.path("data").path("user").path("result").path("rest_id").asText(null)
            if (id != null) id to "" else null to "Parsed OK but no rest_id. Raw: ${json.take(4000)}"
        } catch (e: Exception) {
            null to "Request failed: ${e.message}"
        }
    }

    private suspend fun fetchUserMedia(username: String, userId: String, perPage: Int): Pair<List<SearchResponse>, String> {
        val (authToken, ct0) = authCookies() ?: return emptyList<SearchResponse>() to "No cookies configured"

        val queryId = "atLYUUmER14HCLFnNUKJgA"
        val variables = "{\"userId\":\"$userId\",\"count\":$perPage,\"includePromotedContent\":false," +
                "\"withClientEventToken\":false,\"withBirdwatchNotes\":false,\"withVoice\":true}"
        val url = "https://x.com/i/api/graphql/$queryId/UserMedia" +
                "?variables=" + URLEncoder.encode(variables, "UTF-8")

        return semaphore.withPermit {
            val out = try {
                val json = app.get(url, headers = authHeaders(ct0, authToken)).text
                val root = mapper.readTree(json)
                val instructions = root.path("data").path("user").path("result")
                    .path("timeline_v2").path("timeline").path("instructions")

                if (instructions.isMissingNode || !instructions.isArray) {
                    emptyList<SearchResponse>() to "No instructions array found. Raw: ${json.take(4000)}"
                } else {
                    val entries = instructions.flatMap { instr ->
                        if (instr.path("type").asText() == "TimelineAddEntries") instr.path("entries").toList()
                        else emptyList()
                    }

                    val results = entries.mapNotNull { entry ->
                        var node = entry.path("content").path("itemContent")
                            .path("tweet_results").path("result")
                        if (node.path("__typename").asText() == "TweetWithVisibilityResults") {
                            node = node.path("tweet")
                        }
                        val legacy = node.path("legacy")
                        val tweetId = node.path("rest_id").asText(null) ?: return@mapNotNull null
                        val media = legacy.path("extended_entities").path("media").firstOrNull()
                            ?: return@mapNotNull null
                        val thumb = media.path("media_url_https").asText(null)
                        val tweetUrl = "https://x.com/$username/status/$tweetId"

                        newMovieSearchResponse(legacy.path("full_text").asText(tweetUrl), tweetUrl, TvType.Movie) {
                            this.posterUrl = thumb
                        }
                    }

                    if (results.isEmpty())
                        results to "Got ${entries.size} entries but 0 had media. Raw: ${json.take(4000)}"
                    else
                        results to ""
                }
            } catch (e: Exception) {
                emptyList<SearchResponse>() to "Request failed: ${e.message}"
            }
            delay(delayMs)
            out
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val ctx = com.lagradost.cloudstream3.CloudStreamApp.context
        val usernames = ctx?.let { twAccUsernames(it) } ?: emptyList()

        if (usernames.isEmpty()) {
            return newHomePageResponse(
                list = listOf(HomePageList("Debug", listOf(
                    newMovieSearchResponse(
                        "No usernames configured in settings", "https://x.com/debug/no-usernames", TvType.Movie
                    )
                ))),
                hasNext = false
            )
        }

        val lists = coroutineScope {
            usernames.map { username ->
                async {
                    val (userId, userIdDebug) = resolveUserId(username)
                    if (userId == null) {
                        return@async HomePageList(
                            "$username — FAILED",
                            listOf(newMovieSearchResponse(
                                userIdDebug.take(300), "https://x.com/debug/$username", TvType.Movie
                            ))
                        )
                    }

                    val (items, mediaDebug) = fetchUserMedia(username, userId, 12)
                    if (items.isEmpty()) {
                        HomePageList(
                            "$username — got ID $userId, then FAILED",
                            listOf(newMovieSearchResponse(
                                mediaDebug.take(300), "https://x.com/debug/$username", TvType.Movie
                            ))
                        )
                    } else {
                        HomePageList(username, items)
                    }
                }
            }.awaitAll()
        }

        return newHomePageResponse(list = lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/debug/")) {
            val debugUsername = url.substringAfterLast("/debug/").substringBefore("?")
            val (userId, userIdDebug) = resolveUserId(debugUsername)
            val fullDebugText = if (userId == null) {
                "url received: $url\nparsed username: $debugUsername\n\nuserId lookup failed:\n\n$userIdDebug"
            } else {
                val (_, mediaDebug) = fetchUserMedia(debugUsername, userId, 12)
                "url received: $url\nparsed username: $debugUsername\nuserId = $userId\n\nmedia fetch result:\n\n$mediaDebug"
            }
            return newMovieLoadResponse("Debug: $debugUsername", url, TvType.Movie, url) {
                this.plot = fullDebugText
            }
        }

        return newMovieLoadResponse(url, url, TvType.Movie, url) {
            this.plot = "NON-DEBUG PATH — url was: $url"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (authToken, ct0) = authCookies() ?: return false
        val tweetId = Regex("status/(\\d+)").find(data)?.groupValues?.get(1) ?: return false

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

        val videoUrl = try {
            val json = app.get(url, headers = authHeaders(ct0, authToken)).text
            val root = mapper.readTree(json)
            var tweetNode = root.path("data").path("tweetResult").path("result")
            if (tweetNode.path("__typename").asText() == "TweetWithVisibilityResults") {
                tweetNode = tweetNode.path("tweet")
            }
            val media = tweetNode.path("legacy").path("extended_entities").path("media").firstOrNull()
                ?: return false
            val variants = media.path("video_info").path("variants")
            variants.filter { it.path("content_type").asText() == "video/mp4" }
                .maxByOrNull { it.path("bitrate").asInt(0) }
                ?.path("url")?.asText()
        } catch (e: Exception) {
            null
        } ?: return false

        callback.invoke(
            newExtractorLink(source = this.name, name = this.name, url = videoUrl) {
                this.referer = "https://x.com/"
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.VIDEO
            }
        )
        return true
    }
}
