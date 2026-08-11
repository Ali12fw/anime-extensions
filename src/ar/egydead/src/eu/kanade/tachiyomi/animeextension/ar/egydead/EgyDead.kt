package eu.kanade.tachiyomi.animeextension.ar.egydead

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.autoUnpacker
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest

class EgyDead :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Egy Dead"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://egydead.space"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ================================== popular ==================================

    override fun popularAnimeSelector(): String = "div.pin-posts-list li.movieItem"

    override fun popularAnimeNextPageSelector(): String = "div.whatever"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    // ================================== episodes ==================================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.attr("href"))
            episode.name = element.attr("title")
            return episode
        }
        fun addEpisodes(res: Response, final: Boolean = false) {
            val document = res.useAsJsoup()
            val url = res.request.url.toString()
            if (final) {
                document.select(episodeListSelector()).forEach {
                    val episode = episodeFromElement(it)
                    val season = document.select("div.infoBox div.singleTitle").text()
                    val seasonTxt = season.substringAfter("الموسم ").substringBefore(" ")
                    episode.name = if (season.contains("موسم")) "الموسم $seasonTxt ${episode.name}" else episode.name
                    episodes.add(episode)
                }
            } else if (url.contains("assembly")) {
                document.select("div.salery-list li.movieItem a").forEach {
                    episodes.add(episodeExtract(it))
                }
            } else if (url.contains("serie") || url.contains("season")) {
                if (document.select("div.seasons-list li.movieItem a").isEmpty()) {
                    document.select(episodeListSelector()).forEach {
                        episodes.add(episodeFromElement(it))
                    }
                } else {
                    document.select("div.seasons-list li.movieItem a").forEach {
                        addEpisodes(client.newCall(GET(it.attr("href"))).execute(), true)
                    }
                }
            } else if (url.contains("episode")) {
                document.selectFirst("#breadcrumbs li a[itemprop=url]")?.let {
                    addEpisodes(client.newCall(GET(it.attr("href"))).execute())
                }
            } else {
                val episode = SEpisode.create()
                episode.name = "مشاهدة"
                episode.setUrlWithoutDomain(url)
                episodes.add(episode)
            }
            // document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
        }
        addEpisodes(response)
        return episodes
    }

    override fun episodeListSelector() = "div.EpsList li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.select("a").text()
        episode.episode_number = element.select("a").text().filter { it.isDigit() }.toFloat()
        return episode
    }

    // ================================== video urls ==================================
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val originalUrl = baseUrl.toHttpUrl().resolve(episode.url)
            ?: return emptyList()
        println("EgyDead: episode slug=${episodeSlug(originalUrl)}")
        val primingHeaders = headers.newBuilder()
            .set("Referer", originalUrl.toString())
            .build()
        println("EgyDead: priming requested URL=$originalUrl")
        val resolvedOriginalUrl = runCatching {
            val primingResponse = client.newCall(GET(originalUrl, primingHeaders)).await()
            primingResponse.use { it.request.url }
        }.getOrElse {
            println("EgyDead: initial episode GET failed: ${it.message}")
            originalUrl
        }
        println("EgyDead: priming final URL=$resolvedOriginalUrl")

        val watchUrl = resolvedOriginalUrl.newBuilder()
            .setQueryParameter("view", "watch")
            .build()
        val playbackHeaders = headers.newBuilder()
            .set("Referer", resolvedOriginalUrl.toString())
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val requestBody = FormBody.Builder().add("View", "1").build()

        println("EgyDead: watch POST requested URL=$watchUrl")
        var response = runCatching {
            client.newCall(POST(watchUrl.toString(), playbackHeaders, requestBody)).await()
        }.getOrNull()
        println("EgyDead: watch POST final URL=${response?.request?.url ?: "request failed"}")
        println("EgyDead: watch POST final method=${response?.request?.method ?: "request failed"}")
        println("EgyDead: watch POST HTTP status=${response?.code ?: "request failed"}")

        var candidates = response?.use { parseServerCandidates(it.useAsJsoup(), watchUrl) }.orEmpty()
        if (response?.isSuccessful != true || candidates.isEmpty()) {
            response = runCatching {
                client.newCall(GET(watchUrl, playbackHeaders)).await()
            }.getOrNull()
            println("EgyDead: watch GET fallback HTTP status=${response?.code ?: "request failed"}")
            candidates = response?.use { parseServerCandidates(it.useAsJsoup(), watchUrl) }.orEmpty()
        }

        println("EgyDead: server candidate count=${candidates.size}")
        println("EgyDead: detected host domains=${candidates.map { it.url.host }.distinct().joinToString()}")
        val videos = candidates.parallelCatchingFlatMap { candidate ->
            extractVideos(candidate, playbackHeaders).also { extracted ->
                println(
                    "EgyDead: watch server extractor result " +
                        "name=${safeServerName(candidate.name)} count=${extracted.size}",
                )
            }
        }.distinctBy { it.videoUrl to it.quality }
        println("EgyDead: final Video count=${videos.size}")
        return videos
    }

    private fun parseServerCandidates(document: Document, watchUrl: HttpUrl): List<ServerCandidate> {
        val dataLinkElements = document.select("[data-link]")
        println("EgyDead: raw data-link count=${dataLinkElements.size}")
        val candidates = dataLinkElements.mapNotNull { element ->
            val rawUrl = element.attr("data-link")
            val resolvedUrl = rawUrl
                .takeIf(String::isNotBlank)
                ?.let(watchUrl::resolve)
            val visibleName = sequenceOf(
                element.attr("data-name"),
                element.attr("data-server"),
                element.ownText(),
                element.text(),
                nearestVisibleParentName(element),
            ).filterNotNull().firstOrNull(String::isNotBlank)
            val serverName = safeServerName(
                visibleName ?: resolvedUrl?.host?.let(::knownServerName).orEmpty(),
            )
            val safeClass = element.className()
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(80)
                .ifBlank { "none" }

            println(
                "EgyDead: data-link tag=${element.tagName()} class=$safeClass " +
                    "name=$serverName host=${resolvedUrl?.host ?: "invalid"}",
            )
            if (resolvedUrl == null) {
                return@mapNotNull null
            }
            if (
                resolvedUrl.scheme != "http" &&
                resolvedUrl.scheme != "https"
            ) {
                return@mapNotNull null
            }
            if (
                TRAILER_HOSTS.any { trailerHost ->
                    resolvedUrl.host == trailerHost || resolvedUrl.host.endsWith(".$trailerHost")
                }
            ) {
                return@mapNotNull null
            }

            ServerCandidate(
                name = serverName,
                url = resolvedUrl,
            )
        }
            .distinctBy { candidate -> candidate.name.lowercase() to candidate.url.toString() }

        println("EgyDead: watch server count=${candidates.size}")
        candidates.forEach { candidate ->
            println(
                "EgyDead: watch server name=${safeServerName(candidate.name)} " +
                    "host=${candidate.url.host}",
            )
        }
        return candidates
    }

    private fun nearestVisibleParentName(element: Element): String? = generateSequence(element.parent()) {
        it.parent()
    }
        .map { parent -> parent.ownText().ifBlank(parent::text) }
        .firstOrNull(String::isNotBlank)

    private fun knownServerName(host: String): String? = when {
        hostMatches(host, "streamruby.com") || hostMatches(host, "stmruby.com") -> "StreamRuby"
        hostMatches(host, "hgcloud.to") -> "StreamHG"
        hostMatches(host, "bysekoze.com") -> "Byse"
        hostMatches(host, "mixdrop.top") -> "Mixdrop"
        hostMatches(host, "dsvplay.com") -> "DoodStream"
        hostMatches(host, "voe.sx") -> "Voe"
        else -> null
    }

    private fun hostMatches(host: String, expectedHost: String): Boolean = host == expectedHost || host.endsWith(".$expectedHost")

    private suspend fun extractVideos(candidate: ServerCandidate, playbackHeaders: Headers): List<Video> {
        val serverName = safeServerName(candidate.name)
        val serverKey = serverName.lowercase().filter(Char::isLetterOrDigit)
        val url = candidate.url
        val host = url.host
        val urlString = url.toString()
        println("EgyDead: extracting host=$host")

        val route = when {
            serverKey == "mixdrop" ||
                MIXDROP_HOSTS.any { host == it || host.endsWith(".$it") } -> "mixdrop"
            serverKey == "voe" ||
                host == VOE_HOST || host.endsWith(".$VOE_HOST") -> "voe"
            serverKey == "doodstream" ||
                DOOD_REGEX.containsMatchIn(urlString) ||
                host == DSVPLAY_HOST ||
                host.endsWith(".$DSVPLAY_HOST") -> "dood"
            serverKey == "streamruby" || serverKey == "earnvids" -> "packedhls"
            serverKey == "streamix" -> "streamix"
            serverKey == "byse" -> "filemoon"
            serverKey in GENERIC_EMBEDDED_SERVER_NAMES -> "generic"
            urlString.contains("ahvsh") -> "streamhide"
            STREAMWISH_REGEX.containsMatchIn(urlString) -> "streamwish"
            urlString.contains("fanakishtuna") -> "fanakishtuna"
            urlString.contains("uqload") -> "uqload"
            else -> null
        }

        if (route == null) {
            println("EgyDead: extractor route=unsupported")
            println("EgyDead: unsupported candidate host=$host")
            println("EgyDead: extracted video count=0")
            return emptyList()
        }

        println("EgyDead: extractor route=$route")
        val videos = try {
            when (route) {
                "mixdrop" -> MixDropExtractor(client, playbackHeaders).videoFromUrl(
                    urlString,
                    referer = playbackHeaders["Referer"].orEmpty(),
                )
                "voe" -> extractVoeVideos(url, playbackHeaders)
                "dood" -> extractDoodVideos(url, playbackHeaders)
                "packedhls" -> extractPackedHlsVideos(url, playbackHeaders, serverName)
                "streamix" -> extractStreamixVideos(url, playbackHeaders, serverName)
                "filemoon" -> extractByseVideos(url, playbackHeaders, serverName)
                "generic" -> extractGenericEmbeddedVideos(url, playbackHeaders)
                "streamhide" -> {
                    val request = client.newCall(GET(urlString, playbackHeaders)).awaitSuccess().useAsJsoup()
                    val script = request.selectFirst("script:containsData(sources)")?.data()
                    val streamLink = script?.let {
                        Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(it)?.groupValues?.getOrNull(1)
                    }
                    val quality = script?.let {
                        Regex("'qualityLabels'\\s*:\\s*\\{\\s*\".*?\"\\s*:\\s*\"(.*?)\"").find(it)?.groupValues?.getOrNull(1)
                    } ?: "Mirror"
                    streamLink?.let { listOf(Video(it, "StreamHide: $quality", it, playbackHeaders)) }.orEmpty()
                }
                "streamwish" -> streamWishExtractor.videosFromUrl(urlString)
                "fanakishtuna" -> {
                    val request = client.newCall(GET(urlString, playbackHeaders)).awaitSuccess().useAsJsoup()
                    val data = request.selectFirst("script:containsData(sources)")?.data()
                    val streamLink = data?.let {
                        Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(it)?.groupValues?.getOrNull(1)
                    }
                    streamLink?.let { listOf(Video(it, "Mirror: High Quality", it, playbackHeaders)) }.orEmpty()
                }
                "uqload" -> {
                    val newURL = urlString.replace("https://uqload.co/", "https://www.uqload.co/")
                    val request = client.newCall(GET(newURL, playbackHeaders)).awaitSuccess().useAsJsoup()
                    val data = request.selectFirst("script:containsData(sources)")?.data()
                    val streamLink = data?.substringAfter("sources: [\"", "")?.substringBefore("\"]", "")
                        ?.takeIf(String::isNotBlank)
                    streamLink?.let { listOf(Video(it, "Uqload: Mirror", it, playbackHeaders)) }.orEmpty()
                }
                else -> emptyList()
            }
        } catch (exception: Exception) {
            val safeMessage = exception.message
                ?.replace(Regex("""https?://\S+"""), "<redacted-url>")
                ?: "no message"
            println("EgyDead: extractor failed host=$host error=${exception.javaClass.simpleName}: $safeMessage")
            emptyList()
        }

        val acceptedVideos = videos.mapNotNull { validateVideo(it, route, serverName) }
        if (route == "dood") {
            acceptedVideos.forEach { video ->
                video.videoUrl?.toHttpUrlOrNull()?.let { mediaUrl ->
                    println("EgyDead: accepted Dood media host=${mediaUrl.host}")
                }
            }
        }
        acceptedVideos.forEach { video ->
            logAcceptedVideoDiagnostics(serverName, video)
        }
        println("EgyDead: extracted video count=${acceptedVideos.size}")
        return acceptedVideos
    }

    private suspend fun extractDoodVideos(
        candidateUrl: HttpUrl,
        playbackHeaders: Headers,
    ): List<Video> {
        println(
            "EgyDead: server=DoodStream candidate host=${candidateUrl.host} " +
                "fingerprint=${urlFingerprint(candidateUrl)}",
        )
        val wrapperResponse = client.newCall(GET(candidateUrl, playbackHeaders)).awaitSuccess()
        val finalWrapperUrl = wrapperResponse.request.url
        val wrapperDocument = wrapperResponse.useAsJsoup()
        println("EgyDead: Dood wrapper final host=${finalWrapperUrl.host}")
        println(
            "EgyDead: Dood exact player host=${finalWrapperUrl.host} " +
                "fingerprint=${urlFingerprint(finalWrapperUrl)}",
        )

        val expectedEpisodeToken = EPISODE_TOKEN_REGEX
            .find(playbackHeaders["Referer"].orEmpty())
            ?.value
        if (
            expectedEpisodeToken != null &&
            !wrapperDocument.title().contains(expectedEpisodeToken, ignoreCase = true)
        ) {
            println("EgyDead: Dood wrapper episode correlation failed")
            return emptyList()
        }

        val doodClient = client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestBuilder = request.newBuilder()
                playbackHeaders.names().forEach { headerName ->
                    if (request.header(headerName) == null) {
                        playbackHeaders[headerName]?.let { headerValue ->
                            requestBuilder.header(headerName, headerValue)
                        }
                    }
                }
                if (request.header("Referer") == null) {
                    requestBuilder.header("Referer", finalWrapperUrl.toString())
                }
                chain.proceed(requestBuilder.build())
            }
            .build()
        val extractedVideo = DoodExtractor(doodClient).videoFromUrl(finalWrapperUrl.toString())
            ?: return emptyList()
        val mediaHeaders = (extractedVideo.headers ?: Headers.EMPTY).newBuilder()
            .set("Referer", finalWrapperUrl.toString())
            .set("Origin", "${finalWrapperUrl.scheme}://${finalWrapperUrl.host}")
            .apply {
                playbackHeaders["User-Agent"]?.let { set("User-Agent", it) }
            }
            .build()
        return listOf(
            Video(
                url = extractedVideo.url,
                quality = extractedVideo.quality,
                videoUrl = extractedVideo.videoUrl,
                headers = mediaHeaders,
                subtitleTracks = extractedVideo.subtitleTracks,
                audioTracks = extractedVideo.audioTracks,
            ),
        )
    }

    private suspend fun extractVoeVideos(
        candidateUrl: HttpUrl,
        playbackHeaders: Headers,
    ): List<Video> {
        val candidateResponse = client.newCall(GET(candidateUrl, playbackHeaders)).await()
        val finalCandidateUrl: HttpUrl
        val successful: Boolean
        candidateResponse.use { response ->
            finalCandidateUrl = response.request.url
            successful = response.isSuccessful
            val redirectLocationHost = generateSequence(response.priorResponse) { it.priorResponse }
                .mapNotNull { redirectResponse ->
                    redirectResponse.header("Location")
                        ?.let(redirectResponse.request.url::resolve)
                        ?.host
                }
                .firstOrNull()
                ?: response.header("Location")
                    ?.let(response.request.url::resolve)
                    ?.host
                ?: "none"
            println("EgyDead: Voe candidate HTTP status=${response.code}")
            println("EgyDead: Voe redirect Location host=$redirectLocationHost")
        }
        println("EgyDead: Voe final candidate host=${finalCandidateUrl.host}")
        if (!successful) {
            return emptyList()
        }

        val extractor = VoeExtractor(client, playbackHeaders)
        val firstAttempt = extractor.videosFromUrl(finalCandidateUrl.toString())
        return if (firstAttempt.isNotEmpty()) {
            firstAttempt
        } else {
            println("EgyDead: Voe extraction retry=1")
            extractor.videosFromUrl(finalCandidateUrl.toString())
        }
    }

    private suspend fun extractStreamixVideos(
        candidateUrl: HttpUrl,
        playbackHeaders: Headers,
        serverName: String,
    ): List<Video> {
        val candidateResponse = client.newCall(GET(candidateUrl, playbackHeaders)).await()
        val finalCandidateUrl: HttpUrl
        val candidateDocument: Document
        candidateResponse.use { response ->
            println("EgyDead: Streamix candidate HTTP status=${response.code}")
            println("EgyDead: Streamix final candidate host=${response.request.url.host}")
            if (!response.isSuccessful) {
                return emptyList()
            }
            finalCandidateUrl = response.request.url
            candidateDocument = response.useAsJsoup()
        }

        val expectedEpisodeToken = EPISODE_TOKEN_REGEX
            .find(playbackHeaders["Referer"].orEmpty())
            ?.value
        if (
            expectedEpisodeToken != null &&
            !candidateDocument.title().contains(expectedEpisodeToken, ignoreCase = true)
        ) {
            println("EgyDead: Streamix episode correlation failed")
            return emptyList()
        }

        val fileCode = finalCandidateUrl.pathSegments.lastOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val apiUrl = finalCandidateUrl.resolve("/api/stream")
            ?: return emptyList()
        val serverHeaders = playbackHeaders.newBuilder()
            .set("Referer", finalCandidateUrl.toString())
            .set("Origin", "${finalCandidateUrl.scheme}://${finalCandidateUrl.host}")
            .removeAll("X-Requested-With")
            .build()
        val requestBody = JSONObject()
            .put("filecode", fileCode)
            .put("device", "android")
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val apiResponse = client.newCall(POST(apiUrl.toString(), serverHeaders, requestBody)).await()
        val mediaUrl = apiResponse.use { response ->
            println("EgyDead: Streamix API HTTP status=${response.code}")
            if (!response.isSuccessful) {
                return emptyList()
            }
            JSONObject(response.body.string())
                .optString("streaming_url")
                .let(::normalizeEmbeddedMediaUrl)
        } ?: return emptyList()
        if (!isValidatedHls(mediaUrl, serverHeaders)) {
            println("EgyDead: Streamix media validation failed")
            return emptyList()
        }

        return playlistUtils.extractFromHls(
            playlistUrl = mediaUrl.toString(),
            referer = finalCandidateUrl.toString(),
            masterHeaders = serverHeaders,
            videoHeaders = serverHeaders,
            videoNameGen = { quality -> "$serverName - $quality" },
        )
    }

    private suspend fun extractByseVideos(
        candidateUrl: HttpUrl,
        playbackHeaders: Headers,
        serverName: String,
    ): List<Video> {
        val candidateResponse = client.newCall(GET(candidateUrl, playbackHeaders)).await()
        val finalCandidateUrl: HttpUrl
        val successful: Boolean
        candidateResponse.use { response ->
            finalCandidateUrl = response.request.url
            successful = response.isSuccessful
            println("EgyDead: Byse candidate HTTP status=${response.code}")
            println("EgyDead: Byse final candidate host=${finalCandidateUrl.host}")
        }
        if (!successful) {
            return emptyList()
        }

        return filemoonExtractor.videosFromUrl(
            url = finalCandidateUrl.toString(),
            prefix = "$serverName - ",
            headers = playbackHeaders,
            referer = playbackHeaders["Referer"],
        )
    }

    private suspend fun logAcceptedVideoDiagnostics(serverName: String, video: Video) {
        val mediaUrl = video.videoUrl?.toHttpUrlOrNull()
            ?: return
        val suppliedHeaders = video.headers ?: Headers.EMPTY
        val headerNames = suppliedHeaders.names()
            .sorted()
            .joinToString()
            .ifBlank { "none" }
        val validationHeaders = suppliedHeaders.newBuilder()
            .set("Range", "bytes=0-15")
            .build()

        try {
            client.newCall(GET(mediaUrl, validationHeaders)).await().use { response ->
                val contentType = response.header("Content-Type")
                    .orEmpty()
                    .substringBefore(';')
                    .trim()
                    .replace(Regex("""[^A-Za-z0-9.+/_-]"""), "")
                    .take(80)
                    .ifBlank { "unknown" }
                val mediaType = when {
                    mediaUrl.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
                        contentType.contains("mpegurl", ignoreCase = true) -> "HLS"
                    mediaUrl.encodedPath.endsWith(".mp4", ignoreCase = true) ||
                        contentType.contains("mp4", ignoreCase = true) ||
                        contentType.startsWith("video/", ignoreCase = true) -> "MP4"
                    else -> "unknown"
                }
                val byteRanges = response.code == 206 ||
                    response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true ||
                    response.header("Content-Range")?.startsWith("bytes", ignoreCase = true) == true
                println(
                    "EgyDead: accepted video diagnostics server=${safeServerName(serverName)} " +
                        "mediaType=$mediaType headers=$headerNames byteRanges=$byteRanges " +
                        "status=${response.code} contentType=$contentType",
                )
            }
        } catch (exception: Exception) {
            println(
                "EgyDead: accepted video diagnostics server=${safeServerName(serverName)} " +
                    "mediaType=unknown headers=$headerNames byteRanges=unknown " +
                    "status=request-failed contentType=unknown error=${exception.javaClass.simpleName}",
            )
        }
    }

    private fun episodeSlug(url: HttpUrl): String = url.encodedPath
        .trim('/')
        .substringAfterLast('/')
        .replace(Regex("""[^A-Za-z0-9_-]"""), "")
        .take(80)
        .ifBlank { "unknown" }

    private fun urlFingerprint(url: HttpUrl): String = MessageDigest
        .getInstance("SHA-256")
        .digest(url.toString().toByteArray(Charsets.UTF_8))
        .take(FINGERPRINT_BYTES)
        .joinToString("") { byte -> "%02x".format(byte) }

    private suspend fun extractGenericEmbeddedVideos(
        serverUrl: HttpUrl,
        playbackHeaders: Headers,
    ): List<Video> {
        val serverResponse = client.newCall(GET(serverUrl, playbackHeaders)).awaitSuccess()
        val finalServerUrl = serverResponse.request.url
        val document = serverResponse.useAsJsoup()
        val mediaHeaders = playbackHeaders.newBuilder()
            .set("Referer", finalServerUrl.toString())
            .build()
        val mediaUrls = document.select("script")
            .asSequence()
            .map(Element::data)
            .flatMap { script ->
                sequenceOf(
                    script,
                    runCatching { Unpacker.unpack(script) }.getOrDefault(""),
                )
            }
            .map(::decodeScriptEscapes)
            .flatMap { script -> EMBEDDED_MEDIA_URL_REGEX.findAll(script).map(MatchResult::value) }
            .mapNotNull(::normalizeEmbeddedMediaUrl)
            .distinctBy(HttpUrl::toString)
            .toList()

        return mediaUrls.flatMap { mediaUrl ->
            when {
                mediaUrl.encodedPath.endsWith(".m3u8", ignoreCase = true) -> {
                    if (!isValidatedHls(mediaUrl, mediaHeaders)) {
                        emptyList()
                    } else {
                        playlistUtils.extractFromHls(
                            playlistUrl = mediaUrl.toString(),
                            referer = finalServerUrl.toString(),
                            masterHeaders = mediaHeaders,
                            videoHeaders = mediaHeaders,
                        )
                    }
                }
                mediaUrl.encodedPath.endsWith(".mp4", ignoreCase = true) -> {
                    if (!isValidatedMp4(mediaUrl, mediaHeaders)) {
                        emptyList()
                    } else {
                        listOf(Video(mediaUrl.toString(), "Video", mediaUrl.toString(), mediaHeaders))
                    }
                }
                else -> emptyList()
            }
        }
    }

    private suspend fun extractPackedHlsVideos(
        serverUrl: HttpUrl,
        playbackHeaders: Headers,
        serverName: String,
    ): List<Video> {
        val response = client.newCall(GET(serverUrl, playbackHeaders)).awaitSuccess()
        val finalServerUrl = response.request.url
        val document = response.useAsJsoup()
        val mediaHeaders = headers.newBuilder()
            .set("Referer", finalServerUrl.toString())
            .set("Origin", "${finalServerUrl.scheme}://${finalServerUrl.host}")
            .build()
        val packedScript = document.select("script")
            .firstOrNull { it.html().contains("eval(function(p,a,c,k,e,d)") }
            ?.html()
            ?: return emptyList()
        val unpackedScript = autoUnpacker(packedScript)
            ?: return emptyList()
        val mediaUrls = PACKED_HLS_SOURCE_REGEX.findAll(unpackedScript)
            .mapNotNull { match ->
                UrlUtils.fixUrl(match.groupValues[1], finalServerUrl.toString())
            }
            .distinct()
            .toList()

        return mediaUrls.parallelCatchingFlatMap { mediaUrl ->
            playlistUtils.extractFromHls(
                playlistUrl = mediaUrl,
                referer = finalServerUrl.toString(),
                masterHeaders = mediaHeaders,
                videoHeaders = mediaHeaders,
                videoNameGen = { quality -> "$serverName - $quality" },
            )
        }
    }

    private suspend fun isValidatedHls(mediaUrl: HttpUrl, mediaHeaders: Headers): Boolean = runCatching {
        client.newCall(GET(mediaUrl, mediaHeaders)).await().use { response ->
            if (!response.isSuccessful) {
                false
            } else {
                val body = response.body.string().trimStart()
                body.startsWith("#EXTM3U")
            }
        }
    }.getOrDefault(false)

    private suspend fun isValidatedMp4(mediaUrl: HttpUrl, mediaHeaders: Headers): Boolean = runCatching {
        val validationHeaders = mediaHeaders.newBuilder()
            .set("Range", "bytes=0-15")
            .build()
        client.newCall(GET(mediaUrl, validationHeaders)).await().use { response ->
            if (!response.isSuccessful) {
                false
            } else {
                val contentType = response.header("Content-Type")
                    .orEmpty()
                    .substringBefore(';')
                    .trim()
                    .lowercase()
                val prefix = response.body.byteStream().use { stream ->
                    val buffer = ByteArray(16)
                    buffer.copyOf(stream.read(buffer).coerceAtLeast(0))
                }
                val mp4Marker = prefix
                    .drop(4)
                    .take(4)
                    .map(Byte::toInt)
                    .map(Int::toChar)
                    .joinToString("")
                contentType.startsWith("video/") ||
                    contentType == "application/mp4" ||
                    (contentType == "application/octet-stream" && mp4Marker in setOf("ftyp", "styp"))
            }
        }
    }.getOrDefault(false)

    private fun decodeScriptEscapes(script: String): String = script
        .replace("\\/", "/")
        .replace("\\u002f", "/", ignoreCase = true)
        .replace("\\x2f", "/", ignoreCase = true)
        .replace("\\u003a", ":", ignoreCase = true)
        .replace("\\x3a", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\x26", "&", ignoreCase = true)
        .replace("&amp;", "&")

    private fun normalizeEmbeddedMediaUrl(rawUrl: String): HttpUrl? {
        val value = rawUrl
            .trim()
            .trimEnd('"', '\'', ')', ']', '}', ',', ';')
        if (
            value.isBlank() ||
            value.startsWith("/https:", ignoreCase = true) ||
            value.startsWith("javascript:", ignoreCase = true) ||
            value.startsWith("data:", ignoreCase = true) ||
            value.startsWith("file:", ignoreCase = true)
        ) {
            return null
        }
        val normalized = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value
            else -> return null
        }
        val mediaUrl = normalized.toHttpUrlOrNull()
            ?: return null
        return mediaUrl.takeIf {
            it.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
                it.encodedPath.endsWith(".mp4", ignoreCase = true)
        }
    }

    private fun safeServerName(name: String): String = name
        .replace(URL_LOG_REGEX, "<redacted-url>")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(80)
        .ifBlank { "Unknown" }

    private data class ServerCandidate(
        val name: String,
        val url: HttpUrl,
    )

    private fun validateVideo(video: Video, route: String, serverName: String): Video? {
        val rawUrl = video.videoUrl.orEmpty()
        val malformedReason = when {
            rawUrl.isBlank() -> "blank URL"
            rawUrl.startsWith("/https:", ignoreCase = true) -> "relative /https URL"
            rawUrl.contains("MDCore", ignoreCase = true) -> "contains MDCore"
            rawUrl.contains("Core.wurl", ignoreCase = true) -> "contains Core.wurl"
            rawUrl.startsWith("javascript:", ignoreCase = true) -> "javascript scheme"
            rawUrl.startsWith("data:", ignoreCase = true) -> "data scheme"
            rawUrl.startsWith("file:", ignoreCase = true) -> "file scheme"
            else -> null
        }
        if (malformedReason != null) {
            println("EgyDead: rejected malformed video route=$route reason=$malformedReason")
            return null
        }

        val parsedUrl = rawUrl.toHttpUrlOrNull()
        if (parsedUrl == null || (parsedUrl.scheme != "http" && parsedUrl.scheme != "https")) {
            println("EgyDead: rejected malformed video route=$route reason=invalid HTTP/HTTPS URL")
            return null
        }

        val quality = routeQuality(serverName, route, video.quality)
        val acceptedVideo = Video(
            url = video.url,
            quality = quality,
            videoUrl = rawUrl,
            headers = video.headers,
            subtitleTracks = video.subtitleTracks,
            audioTracks = video.audioTracks,
        )
        val safeQuality = quality
            .replace(URL_LOG_REGEX, "<redacted-url>")
            .replace('\r', ' ')
            .replace('\n', ' ')
        println("EgyDead: accepted video route=$route host=${parsedUrl.host} quality=$safeQuality")
        return acceptedVideo
    }

    private fun routeQuality(serverName: String, route: String, quality: String): String {
        val routeLabel = ROUTE_LABELS[route] ?: route
        val serverLabel = serverName.ifBlank { routeLabel }
        var detail = quality.trim()
        listOf(serverLabel, routeLabel).distinct().forEach { existingLabel ->
            detail = when {
                detail.equals(existingLabel, ignoreCase = true) -> ""
                detail.startsWith("$existingLabel -", ignoreCase = true) ->
                    detail.substring(existingLabel.length + 2).trim()
                detail.startsWith("$existingLabel:", ignoreCase = true) ->
                    detail.substring(existingLabel.length + 1).trim()
                else -> detail
            }
        }
        return "$serverLabel - ${detail.ifBlank { "Mirror" }}"
    }

    override fun videoListSelector() = listOf(
        "ul.serversList li",
        "ul.servers-list li",
        "div.serversList li",
        "div.servers-list li",
    ).joinToString(", ")

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ================================== search ==================================

    override fun searchAnimeNextPageSelector(): String = "div.pagination-two a:contains(›)"

    override fun searchAnimeSelector(): String = "div.catHolder li.movieItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=$query"
        } else {
            val url = baseUrl
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].query
                            val catUrl = "$baseUrl/$catQ/?page=$page/"
                            return GET(catUrl, headers)
                        }
                    }

                    else -> {}
                }
            }
            return GET(url, headers)
        }
        return GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    override fun getFilterList() = AnimeFilterList(
        CategoryList(categoriesName),
    )

    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)

    private data class CatUnit(val name: String, val query: String)

    private val categoriesName = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getCategoryList() = listOf(
        CatUnit("اختر القسم", ""),
        CatUnit("افلام اجنبى", "category/افلام-اجنبي"),
        CatUnit("افلام اسلام الجيزاوى", "category/ترجمات-اسلام-الجيزاوي"),
        CatUnit("افلام انمى", "category/افلام-كرتون"),
        CatUnit("افلام تركيه", "category/افلام-تركية"),
        CatUnit("افلام اسيويه", "category/افلام-اسيوية"),
        CatUnit("افلام مدبلجة", "category/افلام-اجنبية-مدبلجة"),
        CatUnit("سلاسل افلام", "assembly"),
        CatUnit("مسلسلات اجنبية", "series-category/مسلسلات-اجنبي"),
        CatUnit("مسلسلات انمى", "series-category/مسلسلات-انمي"),
        CatUnit("مسلسلات تركية", "series-category/مسلسلات-تركية"),
        CatUnit("مسلسلات اسيوىة", "series-category/مسلسلات-اسيوية"),
        CatUnit("مسلسلات لاتينية", "series-category/مسلسلات-لاتينية"),
        CatUnit("المسلسلات الكاملة", "serie"),
        CatUnit("المواسم الكاملة", "season"),
    )

    // ================================== details ==================================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.single-thumbnail img").attr("src")
        anime.title = document.select("div.infoBox div.singleTitle").text()
        anime.author = document.select("div.LeftBox li:contains(البلد) a").text()
        anime.artist = document.select("div.LeftBox li:contains(القسم) a").text()
        anime.genre = document.select("div.LeftBox li:contains(النوع) a, div.LeftBox li:contains(اللغه) a, div.LeftBox li:contains(السنه) a").joinToString(", ") { it.text() }
        anime.description = document.select("div.infoBox div.extra-content p").text()
        anime.status = if (anime.title.contains("كامل") || anime.title.contains("فيلم")) SAnime.COMPLETED else SAnime.ONGOING
        return anime
    }

    // ================================== latest ==================================

    override fun latestUpdatesSelector(): String = "section.main-section li.movieItem"

    override fun latestUpdatesNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    // ================================== preferences ==================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "DoodStream", "Uqload")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "Dood", "Uqload")
            setDefaultValue("1080")
            summary = "%s"
        }
        screen.addPreference(videoQualityPref)
    }

    // like|kharabnahk
    companion object {
        private val DOOD_REGEX = Regex("(do*d(?:stream)?\\.(?:com?|watch|to|s[ho]|cx|la|w[sf]|pm|re|yt|stream))/[de]/([0-9a-zA-Z]+)|ds2play")
        private val EPISODE_TOKEN_REGEX = Regex("""s\d{1,3}e\d{1,4}""", RegexOption.IGNORE_CASE)
        private val STREAMWISH_REGEX = Regex("ajmidyad|alhayabambi|atabknh[ks]|https://.*\\.sbs/e/")
        private val MIXDROP_HOSTS = setOf("mixdrop.top", "mixdrop.co", "mixdrop.to", "mixdrop.ag", "mixdrop.club", "mixdrop.ch", "mixdrop.sx", "mixdrop.bz")
        private const val VOE_HOST = "voe.sx"
        private const val DSVPLAY_HOST = "dsvplay.com"
        private val TRAILER_HOSTS = setOf("youtube.com", "www.youtube.com", "youtu.be", "youtube-nocookie.com")
        private val GENERIC_EMBEDDED_SERVER_NAMES = setOf("streamhg")
        private val ROUTE_LABELS = mapOf(
            "mixdrop" to "MixDrop",
            "voe" to "VOE",
            "dood" to "Dood",
            "packedhls" to "HLS",
            "streamix" to "Streamix",
            "filemoon" to "Byse",
            "streamwish" to "StreamWish",
            "streamhide" to "StreamHide",
            "fanakishtuna" to "Fanakishtuna",
            "uqload" to "Uqload",
        )
        private val EMBEDDED_MEDIA_URL_REGEX = Regex(
            """(?:(?:https?:)?//)[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val PACKED_HLS_SOURCE_REGEX = Regex(""""((?:https?:/)?/[^"]*m3u8[^"]*)"""")
        private val URL_LOG_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val FINGERPRINT_BYTES = 6
    }
}
