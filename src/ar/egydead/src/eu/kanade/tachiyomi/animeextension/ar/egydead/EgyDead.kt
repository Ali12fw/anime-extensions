package eu.kanade.tachiyomi.animeextension.ar.egydead

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val originalUrl = baseUrl.toHttpUrl().resolve(episode.url)
            ?: return emptyList()
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
        println("EgyDead: detected host domains=${candidates.map { it.host }.distinct().joinToString()}")
        val videos = candidates.parallelCatchingFlatMap {
            extractVideos(it.toString(), playbackHeaders)
        }
        println("EgyDead: final Video count=${videos.size}")
        return videos
    }

    private fun parseServerCandidates(document: Document, watchUrl: HttpUrl): List<HttpUrl> {
        val containerCandidates = document.select(videoListSelector()).flatMap { server ->
            buildList {
                add(server.attr("data-link"))
                addAll(server.select("[data-link]").map { it.attr("data-link") })
                addAll(server.select("button[data-link]").map { it.attr("data-link") })
                addAll(server.select("a[href]").map { it.attr("href") })
            }
        }
        val documentCandidates = document.select("[data-link], [data-video], [data-url], iframe[src]")
            .flatMap { element ->
                listOf("data-link", "data-video", "data-url", "href", "src")
                    .map { attribute -> element.attr(attribute) }
            }
        val matchingAnchorCandidates = document.select("a[href]")
            .map { it.attr("href") }
            .filter { href ->
                listOf("player", "embed", "download", "drive", "mp4", "stream")
                    .any { href.contains(it, ignoreCase = true) }
            }
        val candidates = (containerCandidates + documentCandidates + matchingAnchorCandidates)
            .mapNotNull { raw -> raw.takeIf(String::isNotBlank)?.let(watchUrl::resolve) }
            .filterNot { candidate ->
                TRAILER_HOSTS.any { trailerHost ->
                    candidate.host == trailerHost || candidate.host.endsWith(".$trailerHost")
                }
            }
            .distinctBy(HttpUrl::toString)

        println("EgyDead: watch document title=${document.title()}")
        println("EgyDead: watch document HTML length=${document.outerHtml().length}")
        println("EgyDead: watch document [data-link] count=${document.select("[data-link]").size}")
        println("EgyDead: watch document [data-video] count=${document.select("[data-video]").size}")
        println("EgyDead: watch document iframe[src] count=${document.select("iframe[src]").size}")
        println("EgyDead: watch document total candidate count=${candidates.size}")
        println("EgyDead: watch document detected candidate host domains=${candidates.map { it.host }.distinct().joinToString()}")
        return candidates
    }

    private suspend fun extractVideos(url: String, playbackHeaders: okhttp3.Headers): List<Video> = when {
        DOOD_REGEX.containsMatchIn(url) -> {
            DoodExtractor(client).videoFromUrl(url, "Dood mirror")?.let(::listOf)
        }

        url.contains("mdbekjwqa") -> {
            MixDropExtractor(client).videoFromUrl(url, referer = playbackHeaders["Referer"].orEmpty())
        }

        url.contains("ahvsh") -> {
            val request = client.newCall(GET(url, playbackHeaders)).awaitSuccess().useAsJsoup()
            val script = request.selectFirst("script:containsData(sources)")?.data()
            val streamLink = script?.let {
                Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(it)?.groupValues?.getOrNull(1)
            }
            val quality = script?.let {
                Regex("'qualityLabels'\\s*:\\s*\\{\\s*\".*?\"\\s*:\\s*\"(.*?)\"").find(it)?.groupValues?.getOrNull(1)
            } ?: "Mirror"
            streamLink?.let { listOf(Video(it, "StreamHide: $quality", it, playbackHeaders)) }.orEmpty()
        }

        STREAMWISH_REGEX.containsMatchIn(url) -> {
            streamWishExtractor.videosFromUrl(url)
        }

        url.contains("fanakishtuna") -> {
            val request = client.newCall(GET(url, playbackHeaders)).awaitSuccess().useAsJsoup()
            val data = request.selectFirst("script:containsData(sources)")?.data()
            val streamLink = data?.let {
                Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(it)?.groupValues?.getOrNull(1)
            }
            streamLink?.let { listOf(Video(it, "Mirror: High Quality", it, playbackHeaders)) }.orEmpty()
        }

        url.contains("uqload") -> {
            val newURL = url.replace("https://uqload.co/", "https://www.uqload.co/")
            val request = client.newCall(GET(newURL, playbackHeaders)).awaitSuccess().useAsJsoup()
            val data = request.selectFirst("script:containsData(sources)")?.data()
            val streamLink = data?.substringAfter("sources: [\"", "")?.substringBefore("\"]", "")
                ?.takeIf(String::isNotBlank)
            streamLink?.let { listOf(Video(it, "Uqload: Mirror", it, playbackHeaders)) }.orEmpty()
        }

        else -> null
    } ?: emptyList()

    override fun videoListSelector() = listOf(
        "ul.serversList li",
        "ul.servers-list li",
        "div.serversList li",
        "div.servers-list li",
        "ul.donwload-servers-list li",
        "ul.download-servers-list li",
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
        private val STREAMWISH_REGEX = Regex("ajmidyad|alhayabambi|atabknh[ks]|https://.*\\.sbs/e/")
        private val TRAILER_HOSTS = setOf("youtube.com", "www.youtube.com", "youtu.be", "youtube-nocookie.com")
    }
}
