package eu.kanade.tachiyomi.animeextension.ar.anime4up

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.megamaxmultiserver.MegaMaxMultiServer
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidlandextractor.VidLandExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.VideaExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.autoUnpacker
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anime4Up :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anime4Up"

    override val baseUrl = "https://w1.anime4up.rest"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()
    private val latestJson = Json { ignoreUnknownKeys = true }

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        println("Anime4Up: popular page=$page")
        return GET(pagedUrl(ANIME_LIST_PATH, page), headers)
    }

    override fun popularAnimeSelector() = "div.anime-grid div.anime-card-container"

    override fun popularAnimeFromElement(element: Element) = animeFromCard(element)

    override fun popularAnimeNextPageSelector() = "a.next.page-numbers, .pagination a.next"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select(popularAnimeSelector())
        val parsed = cards.mapNotNull { card ->
            runCatching { animeFromCard(card) }
                .onFailure { println("Anime4Up: malformed popular card error=${it::class.java.simpleName}") }
                .getOrNull()
        }.distinctBy { it.url }
        val hasNextPage = document.selectFirst(popularAnimeNextPageSelector()) != null
        println("Anime4Up: popular HTTP status=${response.code}")
        println("Anime4Up: popular final host=${response.request.url.host}")
        println("Anime4Up: popular card count=${cards.size}")
        println("Anime4Up: popular parsed count=${parsed.size}")
        println("Anime4Up: popular hasNextPage=$hasNextPage")
        return AnimesPage(parsed, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl$LATEST_PATH".toHttpUrl().newBuilder().apply {
            if (page > 1) {
                addQueryParameter("wa_latest_episodes_ajax", "1")
                addQueryParameter("wa_latest_page", page.toString())
            }
        }.build()
        val latestHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl$LATEST_PATH")
            .apply {
                if (page > 1) set("X-Requested-With", "XMLHttpRequest")
            }
            .build()
        println("Anime4Up: latest page=$page")
        return GET(url, latestHeaders)
    }

    override fun latestUpdatesSelector() = "#wa-latest-episodes-grid div.anime-card-container"

    override fun latestUpdatesFromElement(element: Element) = animeFromCard(element)

    override fun latestUpdatesNextPageSelector() = "#wa-latest-loadmore-btn[data-next-page]"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val isAjax = response.request.url.queryParameter("wa_latest_episodes_ajax") == "1"
        val (cards, hasNextPage) = if (isAjax) {
            val data = latestJson.parseToJsonElement(response.body.string()).jsonObject["data"]
                ?.jsonObject
                ?: return AnimesPage(emptyList(), false)
            val html = data["html"]?.jsonPrimitive?.content.orEmpty()
            val document = Jsoup.parse(html, response.request.url.toString())
            document.select("div.anime-card-container") to
                (data["has_more"]?.jsonPrimitive?.booleanOrNull == true)
        } else {
            val document = response.asJsoup()
            document.select(latestUpdatesSelector()) to
                (document.selectFirst(latestUpdatesNextPageSelector()) != null)
        }
        val parsed = cards.mapNotNull { card ->
            runCatching { animeFromCard(card) }
                .onFailure { println("Anime4Up: malformed latest card error=${it::class.java.simpleName}") }
                .getOrNull()
        }.distinctBy { it.url }
        println("Anime4Up: latest HTTP status=${response.code}")
        println("Anime4Up: latest raw card count=${cards.size}")
        println("Anime4Up: latest parsed count=${parsed.size}")
        println("Anime4Up: latest hasNextPage=$hasNextPage")
        return AnimesPage(parsed, hasNextPage)
    }

    // =============================== Search ===============================
    override fun getFilterList() = Anime4UpFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                if (page > 1) addPathSegments("page/$page/")
                addQueryParameter("search_param", "animes")
                addQueryParameter("s", query)
            }.build()
            println("Anime4Up: search queryLength=${query.length}")
            println("Anime4Up: search page=$page")
            return GET(url, headers)
        }

        return with(Anime4UpFilters.getSearchParameters(filters)) {
            val path = when {
                genre.isNotBlank() -> "/anime-genre/$genre"
                type.isNotBlank() -> "/anime-type/$type"
                status.isNotBlank() -> "/anime-status/$status"
                else -> throw Exception("اختر فلتر")
            }
            println("Anime4Up: search queryLength=0")
            println("Anime4Up: search page=$page")
            GET(pagedUrl(path, page), headers)
        }
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select(searchAnimeSelector())
        val parsed = cards.mapNotNull { card ->
            runCatching { animeFromCard(card) }
                .onFailure { println("Anime4Up: malformed search card error=${it::class.java.simpleName}") }
                .getOrNull()
        }.distinctBy { it.url }
        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        println("Anime4Up: search HTTP status=${response.code}")
        println("Anime4Up: search raw card count=${cards.size}")
        println("Anime4Up: search parsed count=${parsed.size}")
        println("Anime4Up: search returned count=${parsed.size}")
        return AnimesPage(parsed, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst(".anime-thumbnail img, img.thumbnail")
            .let(::imageUrl)
        title = document.selectFirst("h1.anime-details-title, h1")?.text().orEmpty()
        genre = document.select(".anime-genres a").eachText().distinct().joinToString()

        description = buildString {
            document.select("div.anime-info").eachText().forEach {
                append("$it\n")
            }
            document.selectFirst(".anime-story")?.text()?.also {
                append("\n$it")
            }
        }.trim()

        document.selectFirst("div.anime-info:contains(حالة الأنمي)")?.text()?.also {
            status = when {
                it.contains("يعرض الان", true) -> SAnime.ONGOING
                it.contains("مكتمل", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val firstDocument = response.asJsoup()
        val finalUrl = response.request.url
        val maxPages = firstDocument.selectFirst("a.episodes-load-more[data-max-pages]")
            ?.attr("data-max-pages")
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1
        val documents = buildList {
            add(firstDocument)
            for (page in 2..maxPages) {
                val pageUrl = finalUrl.newBuilder()
                    .addPathSegments("page/$page/")
                    .build()
                val pageHeaders = headers.newBuilder()
                    .set("Referer", finalUrl.toString())
                    .build()
                runCatching {
                    client.newCall(GET(pageUrl, pageHeaders)).execute().use { pageResponse ->
                        println("Anime4Up: episode page=$page HTTP status=${pageResponse.code}")
                        pageResponse.asJsoup()
                    }
                }.onFailure {
                    println("Anime4Up: episode page=$page failed error=${it::class.java.simpleName}")
                }.getOrNull()?.let(::add)
            }
        }
        val episodes = documents.flatMap { document ->
            document.select(episodeListSelector()).mapNotNull { element ->
                runCatching { episodeFromElement(element) }.getOrNull()
            }
        }.distinctBy { episode ->
            episode.url.toHttpUrlOrNull()?.encodedPath?.trimEnd('/') ?: episode.url
        }
        println("Anime4Up: episode pages=$maxPages")
        println("Anime4Up: episode returned count=${episodes.size}")
        return episodes
    }

    override fun episodeListSelector() = "#episodesList .ep_num > a[href]"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text().trim().ifBlank { element.attr("aria-label").trim() }
        episode_number = EPISODE_NUMBER_REGEX.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0F
    }

    private fun animeFromCard(element: Element): SAnime {
        val anchor = element.selectFirst(
            ".anime-card-title a[href*='/anime/'], " +
                ".anime-card-poster a.overlay[href*='/anime/'], a[href*='/anime/']",
        )
            ?: throw IllegalArgumentException("Missing anime link")
        val image = element.selectFirst("img")
        val cardTitle = element.selectFirst(".anime-card-title")?.text().orEmpty()
        val title = cardTitle.ifBlank {
            image?.attr("alt").orEmpty().ifBlank { anchor.attr("aria-label") }
        }
        if (title.isBlank()) throw IllegalArgumentException("Missing anime title")

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = imageUrl(image)
            setUrlWithoutDomain(anchor.attr("href"))
        }
    }

    private fun imageUrl(image: Element?): String? = image?.let { element ->
        listOf("data-image", "data-src", "data-lazy-src", "src")
            .asSequence()
            .map { attribute -> element.absUrl(attribute).ifBlank { element.attr(attribute) } }
            .firstOrNull { value -> value.isNotBlank() && !value.startsWith("data:", true) }
    }

    private fun pagedUrl(path: String, page: Int): String {
        val normalized = path.trimEnd('/')
        return if (page > 1) "$baseUrl$normalized/page/$page/" else "$baseUrl$normalized/"
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val watchUrl = response.request.url
        val playbackHeaders = headers.newBuilder()
            .set("Referer", watchUrl.toString())
            .set("Origin", "${watchUrl.scheme}://${watchUrl.host}")
            .build()
        val candidates = document.select(videoListSelector()).mapNotNull { element ->
            val serverUrl = element.attr("data-watch")
                .takeIf(String::isNotBlank)
                ?.let(watchUrl::resolve)
                ?: return@mapNotNull null
            val label = element.ownText().ifBlank(element::text).trim()
            val quality = SERVER_QUALITY_REGEX.find(label)?.groupValues?.getOrNull(1).orEmpty()
            val name = label.substringBefore('[').trim().ifBlank { serverUrl.host }
            ServerCandidate(name, quality, serverUrl)
        }.distinctBy { candidate -> candidate.name.lowercase() to candidate.url.toString() }

        println("Anime4Up: watch HTTP status=${response.code}")
        println("Anime4Up: watch server count=${candidates.size}")
        candidates.forEach { candidate ->
            println("Anime4Up: server name=${safeLabel(candidate.name)}")
            println("Anime4Up: server host=${candidate.url.host}")
        }

        val videos = candidates.parallelCatchingFlatMapBlocking { candidate ->
            try {
                extractVideos(candidate, playbackHeaders, watchUrl)
            } catch (exception: Exception) {
                val safeMessage = exception.message
                    ?.replace(URL_LOG_REGEX, "<redacted-url>")
                    ?.replace('\n', ' ')
                    ?.take(160)
                    ?: "no message"
                println(
                    "Anime4Up: extractor failed host=${candidate.url.host} " +
                        "error=${exception.javaClass.simpleName}: $safeMessage",
                )
                println("Anime4Up: server result count=0")
                emptyList()
            }
        }.distinctBy { video -> video.videoUrl to video.quality }
        println("Anime4Up: final Video count=${videos.size}")
        return videos
    }

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val vidLandExtractor by lazy { VidLandExtractor(client) }

    private suspend fun extractVideos(
        candidate: ServerCandidate,
        playbackHeaders: Headers,
        watchUrl: HttpUrl,
    ): List<Video> {
        val host = candidate.url.host.lowercase()
        val route = when {
            hostMatches(host, "share4max.com") || hostMatches(host, "megamax.me") -> "megamax"
            host == watchUrl.host && candidate.url.encodedPath.startsWith("/Anime4up-", true) -> "internal"
            hostMatches(host, "videa.hu") -> "videa"
            hostMatches(host, "voe.sx") -> "voe"
            host.contains("uqload") -> "uqload"
            candidate.name.contains("DoodStream", true) || hostMatches(host, "playmogo.com") -> "dood"
            host.contains("mp4upload") -> "mp4upload"
            hostMatches(host, "rubyvidhub.com") || hostMatches(host, "streamruby.com") -> "streamruby"
            hostMatches(host, "mega.nz") -> "mega"
            else -> "unsupported"
        }
        println("Anime4Up: extractor route=$route")
        val extracted = when (route) {
            "megamax" -> extractMegaMax(candidate, playbackHeaders)
            "internal" -> extractEmbeddedMediaPage(candidate.url, playbackHeaders, candidate.name)
            "videa" -> VideaExtractor(client, playbackHeaders)
                .videosFromUrl(candidate.url.toString(), candidate.name)
            "voe" -> extractVoe(candidate.url, playbackHeaders, candidate.name)
            "uqload" -> uqloadExtractor.videosFromUrl(candidate.url.toString(), candidate.name)
            "dood" -> extractDood(candidate.url, playbackHeaders, candidate.name)
            "mp4upload" -> mp4uploadExtractor.videosFromUrl(
                candidate.url.toString(),
                playbackHeaders,
                prefix = "${candidate.name} - ",
            )
            "streamruby" -> extractEmbeddedMediaPage(candidate.url, playbackHeaders, candidate.name)
            "mega" -> {
                println("Anime4Up: unsupported candidate host=$host")
                emptyList()
            }
            else -> {
                println("Anime4Up: unsupported candidate host=$host")
                emptyList()
            }
        }
        val accepted = extracted.mapNotNull { video -> validateVideo(video, candidate, route) }
        println("Anime4Up: server result count=${accepted.size}")
        return accepted
    }

    private suspend fun extractMegaMax(candidate: ServerCandidate, playbackHeaders: Headers): List<Video> {
        val providers = MegaMaxMultiServer(client, playbackHeaders).extractUrls(candidate.url.toString())
        return providers.flatMap { provider ->
            val providerUrl = provider.url.toHttpUrlOrNull() ?: return@flatMap emptyList()
            val providerLabel = "${candidate.name}/${provider.name}"
            when {
                providerUrl.encodedPath.endsWith(".m3u8", true) -> playlistUtils.extractFromHls(
                    playlistUrl = providerUrl.toString(),
                    referer = candidate.url.toString(),
                    masterHeaders = playbackHeaders,
                    videoHeaders = playbackHeaders,
                    videoNameGen = { quality -> "$providerLabel - $quality" },
                )
                providerUrl.encodedPath.endsWith(".mp4", true) -> listOf(
                    Video(providerUrl.toString(), "$providerLabel - ${provider.quality}", providerUrl.toString(), playbackHeaders),
                )
                hostMatches(providerUrl.host, "mixdrop.co") || provider.name.contains("mixdrop", true) ->
                    MixDropExtractor(client).videosFromUrl(
                        providerUrl.toString(),
                        prefix = "$providerLabel - ",
                        referer = candidate.url.toString(),
                    )
                provider.name.contains("dood", true) -> DoodExtractor(client)
                    .videosFromUrl(providerUrl.toString(), providerLabel)
                provider.name.contains("lulu", true) || STREAMWISH_REGEX.containsMatchIn(providerUrl.toString()) ->
                    streamwishExtractor.videosFromUrl(providerUrl.toString(), providerLabel)
                provider.name.contains("earnvid", true) -> vidLandExtractor.videosFromUrl(providerUrl.toString())
                providerUrl.host.contains("mp4upload") -> mp4uploadExtractor.videosFromUrl(
                    providerUrl.toString(),
                    playbackHeaders,
                    prefix = "$providerLabel - ",
                )
                else -> emptyList()
            }
        }
    }

    private fun extractVoe(url: HttpUrl, playbackHeaders: Headers, serverName: String): List<Video> {
        val resolvedUrl = client.newCall(GET(url, playbackHeaders)).execute().use { response ->
            println("Anime4Up: Voe HTTP status=${response.code}")
            if (!response.isSuccessful) return emptyList()
            response.request.url
        }
        val serverHeaders = playbackHeaders.newBuilder()
            .set("Referer", resolvedUrl.toString())
            .set("Origin", "${resolvedUrl.scheme}://${resolvedUrl.host}")
            .build()
        return VoeExtractor(client, serverHeaders).videosFromUrl(resolvedUrl.toString(), "$serverName - ")
    }

    private fun extractDood(url: HttpUrl, playbackHeaders: Headers, serverName: String): List<Video> {
        val resolvedUrl = client.newCall(GET(url, playbackHeaders)).execute().use { response ->
            println("Anime4Up: Dood wrapper HTTP status=${response.code}")
            if (!response.isSuccessful) return emptyList()
            response.request.url
        }
        val doodClient = client.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val requestBuilder = request.newBuilder()
            playbackHeaders.names().forEach { name ->
                if (request.header(name) == null) {
                    playbackHeaders[name]?.let { value -> requestBuilder.header(name, value) }
                }
            }
            if (request.header("Referer") == null) {
                requestBuilder.header("Referer", resolvedUrl.toString())
            }
            chain.proceed(requestBuilder.build())
        }.build()
        val video = DoodExtractor(doodClient).videoFromUrl(resolvedUrl.toString(), serverName)
            ?: return emptyList()
        val mediaHeaders = (video.headers ?: Headers.EMPTY).newBuilder()
            .set("Referer", resolvedUrl.toString())
            .set("Origin", "${resolvedUrl.scheme}://${resolvedUrl.host}")
            .apply { playbackHeaders["User-Agent"]?.let { set("User-Agent", it) } }
            .build()
        return listOf(
            Video(
                video.url,
                video.quality,
                video.videoUrl,
                headers = mediaHeaders,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            ),
        )
    }

    private fun extractEmbeddedMediaPage(
        url: HttpUrl,
        playbackHeaders: Headers,
        serverName: String,
    ): List<Video> {
        val (document, finalUrl) = client.newCall(GET(url, playbackHeaders)).execute().use { response ->
            println("Anime4Up: embedded HTTP status=${response.code}")
            if (!response.isSuccessful) return emptyList()
            response.asJsoup() to response.request.url
        }
        val mediaHeaders = playbackHeaders.newBuilder()
            .set("Referer", finalUrl.toString())
            .set("Origin", "${finalUrl.scheme}://${finalUrl.host}")
            .build()
        val mediaUrls = document.select("script")
            .asSequence()
            .map(Element::data)
            .flatMap { script -> sequenceOf(script, autoUnpacker(script).orEmpty()) }
            .map(::decodeScriptEscapes)
            .flatMap { script -> MEDIA_URL_REGEX.findAll(script).map(MatchResult::value) }
            .mapNotNull(::normalizeMediaUrl)
            .distinctBy(HttpUrl::toString)
            .toList()
        return mediaUrls.flatMap { mediaUrl ->
            when {
                mediaUrl.encodedPath.endsWith(".m3u8", true) && isValidatedHls(mediaUrl, mediaHeaders) ->
                    playlistUtils.extractFromHls(
                        playlistUrl = mediaUrl.toString(),
                        referer = finalUrl.toString(),
                        masterHeaders = mediaHeaders,
                        videoHeaders = mediaHeaders,
                        videoNameGen = { quality -> "$serverName - $quality" },
                    )
                mediaUrl.encodedPath.endsWith(".mp4", true) && isValidatedMp4(mediaUrl, mediaHeaders) -> listOf(
                    Video(mediaUrl.toString(), "$serverName - MP4", mediaUrl.toString(), mediaHeaders),
                )
                else -> emptyList()
            }
        }
    }

    private fun isValidatedHls(url: HttpUrl, mediaHeaders: Headers): Boolean = runCatching {
        client.newCall(GET(url, mediaHeaders)).execute().use { response ->
            response.isSuccessful && response.body.string().trimStart().startsWith("#EXTM3U")
        }
    }.getOrDefault(false)

    private fun isValidatedMp4(url: HttpUrl, mediaHeaders: Headers): Boolean = runCatching {
        val validationHeaders = mediaHeaders.newBuilder().set("Range", "bytes=0-15").build()
        client.newCall(GET(url, validationHeaders)).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
            val prefix = response.body.byteStream().use { stream ->
                val buffer = ByteArray(16)
                buffer.copyOf(stream.read(buffer).coerceAtLeast(0))
            }
            val marker = prefix.drop(4).take(4).map(Byte::toInt).map(Int::toChar).joinToString("")
            contentType.startsWith("video/") || contentType == "application/mp4" || marker in setOf("ftyp", "styp")
        }
    }.getOrDefault(false)

    private fun decodeScriptEscapes(script: String): String = script
        .replace("\\/", "/")
        .replace("\\u002f", "/", true)
        .replace("\\x2f", "/", true)
        .replace("\\u003a", ":", true)
        .replace("\\x3a", ":", true)
        .replace("&amp;", "&")

    private fun normalizeMediaUrl(rawUrl: String): HttpUrl? {
        val value = rawUrl.trim().trimEnd('"', '\'', ')', ']', '}', ',', ';')
        if (
            value.isBlank() ||
            value.startsWith("/https:", true) ||
            value.startsWith("javascript:", true) ||
            value.startsWith("data:", true) ||
            value.startsWith("file:", true)
        ) {
            return null
        }
        val normalized = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            else -> return null
        }
        return normalized.toHttpUrlOrNull()?.takeIf { url ->
            url.encodedPath.endsWith(".m3u8", true) || url.encodedPath.endsWith(".mp4", true)
        }
    }

    private fun validateVideo(video: Video, candidate: ServerCandidate, route: String): Video? {
        val mediaUrl = video.videoUrl?.toHttpUrlOrNull() ?: return null
        if (mediaUrl.scheme !in setOf("http", "https")) return null
        val qualityDetail = video.quality.trim().ifBlank { candidate.quality.ifBlank { "Mirror" } }
        val quality = listOf("Anime4Up", safeLabel(candidate.name), candidate.quality, qualityDetail)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" - ")
        val mediaType = when {
            mediaUrl.encodedPath.endsWith(".m3u8", true) ||
                route in setOf("streamruby", "internal") ||
                (route == "voe" && !video.quality.contains("MP4", true)) -> "HLS"
            else -> "MP4"
        }
        val resolution = RESOLUTION_REGEX.find(quality)?.value ?: candidate.quality.ifBlank { "unknown" }
        println("Anime4Up: accepted media type=$mediaType")
        println("Anime4Up: accepted resolution=$resolution")
        return Video(
            video.url,
            quality,
            mediaUrl.toString(),
            headers = video.headers ?: headers,
            subtitleTracks = video.subtitleTracks,
            audioTracks = video.audioTracks,
        )
    }

    private fun safeLabel(value: String): String = value
        .replace(URL_LOG_REGEX, "<redacted-url>")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(80)
        .ifBlank { "Unknown" }

    private fun hostMatches(host: String, expected: String): Boolean = host == expected || host.endsWith(".$expected")

    private data class ServerCandidate(
        val name: String,
        val quality: String,
        val url: HttpUrl,
    )

    override fun videoListSelector() = "#episode-servers li[data-watch]"
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private const val ANIME_LIST_PATH = "/قائمة-الانمي/"
        private const val LATEST_PATH = "/home8/"
        private val EPISODE_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
        private val STREAMWISH_REGEX = Regex("((?:streamwish|anime7u|animezd|ajmidyad|khadhnayad|yadmalik|hayaatieadhab)\\.(?:com|to|sbs))/(?:e/|v/|f/)?([0-9a-zA-Z]+)")
        private val SERVER_QUALITY_REGEX = Regex("""\[([^]]+)]""")
        private val MEDIA_URL_REGEX = Regex(
            """https?://[^\"'\\\s]+?\.(?:m3u8|mp4)(?:\?[^\"'\\\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val RESOLUTION_REGEX = Regex("""(?:\d{3,4}x\d{3,4}|\d{3,4}p)""", RegexOption.IGNORE_CASE)
        private val URL_LOG_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "dood")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
