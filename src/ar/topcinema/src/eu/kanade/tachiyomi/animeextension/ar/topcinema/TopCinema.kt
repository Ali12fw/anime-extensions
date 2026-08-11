package eu.kanade.tachiyomi.animeextension.ar.topcinema

import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.mixdropextractor.MixDropExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.jsunpacker.JsUnpacker
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.useAsJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.security.MessageDigest

class TopCinema : ParsedAnimeHttpSource() {

    override val name = "Top Cinema"

    override val baseUrl = "https://topcinemaa.cc"

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(listingUrl(TOP_MOVIES_PATH, page), headers)

    override fun popularAnimeSelector() = "div.Small--Box:not(.Season)"

    override fun popularAnimeFromElement(element: Element) = animeFromCard(element)

    override fun popularAnimeNextPageSelector() = ".page-numbers a:contains(»)"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val movieDocument = response.asJsoup()
        val movies = movieDocument.select(popularAnimeSelector()).map(::animeFromCard)
        val page = response.request.url.pageNumber()
        val seriesDocument = fetchListingDocument(
            path = TOP_SERIES_PATH,
            page = page,
            referer = response.request.url,
            label = "popular series",
        )
        val popularSeries = seriesDocument
            ?.select(popularAnimeSelector())
            ?.map(::animeFromCard)
            .orEmpty()
        val merged = mergeContent(movies, popularSeries)
        println(
            "TopCinema: popular movies=${movies.size} series=${popularSeries.size} " +
                "returned=${merged.size}",
        )
        return AnimesPage(
            merged,
            movieDocument.hasNextPage() || seriesDocument?.hasNextPage() == true,
        )
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/recent/"
        } else {
            "$baseUrl/recenT/page/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = animeFromCard(element)

    override fun latestUpdatesNextPageSelector() = ".page-numbers a:contains(»)"

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val recent = parseContentCards(
            document = document,
            contentType = ContentType.ALL,
            referer = response.request.url,
        )
        val page = response.request.url.pageNumber()
        val movieDocument = runCatching {
            val movieUrl = if (page == 1) {
                "$baseUrl/movies/"
            } else {
                "$baseUrl/movies/page/$page/"
            }
            client.newCall(GET(movieUrl, headers)).execute().use { movieResponse ->
                if (!movieResponse.isSuccessful) {
                    throw IllegalStateException("movies HTTP ${movieResponse.code}")
                }
                Jsoup.parse(movieResponse.body.string(), movieResponse.request.url.toString())
            }
        }.onFailure { error ->
            println(
                "TopCinema: latest movies request failed " +
                    "error=${error.javaClass.simpleName}",
            )
        }.getOrNull()
        val movies = buildList {
            addAll(recent.filterNot { it.isSeriesUrl() })
            movieDocument?.select(popularAnimeSelector())?.mapTo(this, ::animeFromCard)
        }.distinctBy(::canonicalContentKey)
        val series = recent.filter { it.isSeriesUrl() }
        val merged = mergeContent(movies, series)
        println(
            "TopCinema: latest raw=${document.select(latestUpdatesSelector()).size} " +
                "movies=${movies.size} series=${series.size} returned=${merged.size}",
        )
        return AnimesPage(
            merged,
            document.hasNextPage() || movieDocument?.hasNextPage() == true,
        )
    }

    // ================================ Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val contentFilter = filters.filterIsInstance<ContentTypeFilter>().firstOrNull()
        val contentType = contentFilter?.contentType() ?: ContentType.ALL
        if (query.isBlank()) {
            val primaryRoute = contentType.browseRoutes.first()
            val url = listingUrl(primaryRoute.path, page).toHttpUrl().newBuilder()
                .addQueryParameter(CONTENT_TYPE_QUERY, contentType.name.lowercase())
                .addQueryParameter(BROWSE_FILTER_QUERY, "true")
                .addQueryParameter(BROWSE_PAGE_QUERY, page.toString())
                .build()
            println("TopCinema: browse-filter entered")
            println("TopCinema: queryBlank=true")
            println("TopCinema: selected=${contentType.name.lowercase()}")
            println("TopCinema: page=$page")
            println("TopCinema: request route=${primaryRoute.path}")
            return GET(url, headers)
        }
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("search/")
            .addQueryParameter("query", query)
            .addQueryParameter("type", contentType.siteValue)
            .addQueryParameter(CONTENT_TYPE_QUERY, contentType.name.lowercase())
        if (page > 1) {
            urlBuilder.addQueryParameter("offset", page.toString())
        }
        val url = urlBuilder.build()
        println("TopCinema: search query length=${query.length}")
        println("TopCinema: selected filter=${contentType.name.lowercase()}")
        println("TopCinema: filter index=${contentFilter?.state ?: 0}")
        println("TopCinema: request method=GET")
        println("TopCinema: request path=${url.encodedPath}")
        println("TopCinema: requested type value=${contentType.siteValue}")
        println("TopCinema: page=$page")
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = animeFromCard(element)

    override fun searchAnimeNextPageSelector() = ""

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.queryParameter(BROWSE_FILTER_QUERY) == "true") {
            return browseFilterParse(response)
        }
        val responseBody = response.body.string()
        val document = Jsoup.parse(responseBody, response.request.url.toString())
        val contentType = ContentType.fromRequestValue(
            response.request.url.queryParameter(CONTENT_TYPE_QUERY),
        )
        val page = response.request.url.queryParameter("offset")?.toIntOrNull() ?: 1
        val genericCardCount = document.select(searchAnimeSelector()).size
        val parsed = parseContentCards(
            document = document,
            contentType = contentType,
            referer = response.request.url,
        )
        val nextOffset = document.nextSearchOffset(page)
        println("TopCinema: HTTP status=${response.code}")
        println("TopCinema: response length=${responseBody.length}")
        println("TopCinema: generic card count=$genericCardCount")
        println("TopCinema: filtered card count=${parsed.size}")
        println("TopCinema: parsed count=${parsed.size}")
        println("TopCinema: discarded count=${(genericCardCount - parsed.size).coerceAtLeast(0)}")
        println("TopCinema: returned count=${parsed.size}")
        println("TopCinema: page=$page")
        println("TopCinema: next offset=${nextOffset ?: 0}")
        return AnimesPage(parsed, nextOffset != null)
    }

    private fun browseFilterParse(response: Response): AnimesPage {
        val contentType = ContentType.fromRequestValue(
            response.request.url.queryParameter(CONTENT_TYPE_QUERY),
        )
        val page = response.request.url.queryParameter(BROWSE_PAGE_QUERY)?.toIntOrNull() ?: 1
        val routes = contentType.browseRoutes
        val responseBody = response.body.string()
        val primaryRoute = routes.first()
        val primaryDocument = Jsoup.parse(responseBody, response.request.url.toString())
        println("TopCinema: browse-filter entered")
        println("TopCinema: queryBlank=true")
        println("TopCinema: selected=${contentType.name.lowercase()}")
        println("TopCinema: page=$page")
        println("TopCinema: request route=${primaryRoute.path}")
        println("TopCinema: HTTP status=${response.code}")

        val routeDocuments = buildList {
            add(BrowseDocument(primaryRoute, primaryDocument))
            routes.drop(1).forEach { route ->
                fetchBrowseDocument(
                    route = route,
                    page = page,
                    referer = response.request.url,
                )?.let(::add)
            }
        }
        val rawCardCount = routeDocuments.sumOf { (_, document) ->
            document.select(searchAnimeSelector()).size
        }
        val accepted = routeDocuments.flatMap { (route, document) ->
            parseBrowseCards(
                document = document,
                route = route,
                referer = response.request.url,
            )
        }
        val returned = accepted.distinctBy(::canonicalContentKey)
        val hasNextPage = routeDocuments.any { (_, document) -> document.hasNextPage() }
        println("TopCinema: raw card count=$rawCardCount")
        println("TopCinema: accepted count=${accepted.size}")
        println("TopCinema: returned count=${returned.size}")
        println("TopCinema: hasNextPage=$hasNextPage")
        return AnimesPage(returned, hasNextPage)
    }

    private fun parseBrowseCards(
        document: Document,
        route: BrowseRoute,
        referer: HttpUrl,
    ): List<SAnime> = when (route.kind) {
        ContentKind.MOVIE,
        ContentKind.ANIME_MOVIE,
        -> document.select(searchAnimeSelector())
            .mapNotNull { card -> runCatching { animeFromCard(card) }.getOrNull() }
            .filterNot { it.isSeriesUrl() }
        ContentKind.SERIES,
        ContentKind.ANIME_SERIES,
        -> parseContentCards(
            document = document,
            contentType = ContentType.ALL,
            referer = referer,
        ).filter { it.isSeriesUrl() }
    }

    private fun fetchBrowseDocument(
        route: BrowseRoute,
        page: Int,
        referer: HttpUrl,
    ): BrowseDocument? {
        val requestHeaders = headers.newBuilder()
            .set("Referer", referer.toString())
            .build()
        return runCatching {
            client.newCall(GET(listingUrl(route.path, page), requestHeaders)).execute().use { browseResponse ->
                println("TopCinema: request route=${route.path}")
                println("TopCinema: HTTP status=${browseResponse.code}")
                if (!browseResponse.isSuccessful) {
                    return null
                }
                BrowseDocument(
                    route = route,
                    document = Jsoup.parse(
                        browseResponse.body.string(),
                        browseResponse.request.url.toString(),
                    ),
                )
            }
        }.onFailure { error ->
            println(
                "TopCinema: browse route failed=${route.path} " +
                    "error=${error.javaClass.simpleName}: ${safeError(error.message)}",
            )
        }.getOrNull()
    }

    override fun getFilterList() = AnimeFilterList(ContentTypeFilter())

    private class ContentTypeFilter :
        AnimeFilter.Select<String>(
            "نوع المحتوى",
            arrayOf("الكل", "الأفلام", "المسلسلات", "الأنمي"),
        ) {
        fun contentType() = ContentType.entries[state]
    }

    private enum class ContentType(val siteValue: String) {
        ALL("all"),
        MOVIES("movies"),
        SERIES("series"),
        ANIME("all"),
        ;

        companion object {
            fun fromRequestValue(value: String?) = entries.firstOrNull {
                it.name.equals(value, ignoreCase = true)
            } ?: ALL
        }

        val browseRoutes: List<BrowseRoute>
            get() = when (this) {
                ALL -> listOf(
                    BrowseRoute(TOP_MOVIES_PATH, ContentKind.MOVIE),
                    BrowseRoute(TOP_SERIES_PATH, ContentKind.SERIES),
                )
                MOVIES -> listOf(
                    BrowseRoute(FOREIGN_MOVIES_PATH, ContentKind.MOVIE),
                    BrowseRoute(ASIAN_MOVIES_PATH, ContentKind.MOVIE),
                )
                SERIES -> listOf(
                    BrowseRoute(FOREIGN_SERIES_PATH, ContentKind.SERIES),
                    BrowseRoute(ASIAN_SERIES_PATH, ContentKind.SERIES),
                )
                ANIME -> listOf(
                    BrowseRoute(ANIME_SERIES_PATH, ContentKind.ANIME_SERIES),
                    BrowseRoute(ANIME_MOVIES_PATH, ContentKind.ANIME_MOVIE),
                )
            }
    }

    private enum class ContentKind {
        MOVIE,
        SERIES,
        ANIME_MOVIE,
        ANIME_SERIES,
    }

    private data class ResolvedContent(
        val anime: SAnime,
        val kind: ContentKind,
    )

    private data class BrowseRoute(
        val path: String,
        val kind: ContentKind,
    )

    private data class BrowseDocument(
        val route: BrowseRoute,
        val document: Document,
    )

    private fun animeFromCard(element: Element): SAnime {
        val anchor = element.selectFirst("a[href]")
            ?: throw IllegalArgumentException("Top Cinema card has no link")
        return SAnime.create().apply {
            title = element.selectFirst("h3")?.text()
                .orEmpty()
                .trim()
                .ifBlank { anchor.attr("title").trim() }
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty().trim() }
            thumbnail_url = anchor.selectFirst("img")?.imageUrl()
            setUrlWithoutDomain(anchor.attr("abs:href"))
        }
    }

    private fun parseContentCards(
        document: Document,
        contentType: ContentType,
        referer: HttpUrl,
    ): List<SAnime> {
        val parentCache = mutableMapOf<String, ResolvedContent?>()
        val detailCache = mutableMapOf<String, ResolvedContent?>()
        val parsed = document.select(popularAnimeSelector()).mapNotNull { card ->
            val anchor = card.selectFirst("a[href]") ?: return@mapNotNull null
            val absoluteUrl = anchor.attr("abs:href")
            val contentUrl = absoluteUrl.toHttpUrlOrNull()
                ?: return@mapNotNull null
            val isParentSeries = contentUrl.pathSegments
                .firstOrNull()
                ?.equals("series", ignoreCase = true)
                ?: false
            val isEpisode = !isParentSeries && card.selectFirst("div.number") != null

            val resolved = when {
                isEpisode && contentType != ContentType.MOVIES -> {
                    val cacheKey = card.selectFirst("img")
                        ?.imageUrl()
                        ?.toHttpUrlOrNull()
                        ?.encodedPath
                        ?.takeIf(String::isNotBlank)
                        ?: absoluteUrl
                    parentCache.getOrPut(cacheKey) {
                        resolveParentSeries(card, referer)
                    }
                }
                isEpisode -> null
                isParentSeries && contentType == ContentType.ALL -> {
                    ResolvedContent(animeFromCard(card), ContentKind.SERIES)
                }
                isParentSeries -> detailCache.getOrPut(contentUrl.encodedPath) {
                    resolveCardContent(
                        card = card,
                        contentUrl = contentUrl,
                        referer = referer,
                        defaultKind = ContentKind.SERIES,
                    )
                }
                contentType == ContentType.ALL -> {
                    ResolvedContent(animeFromCard(card), ContentKind.MOVIE)
                }
                else -> detailCache.getOrPut(contentUrl.encodedPath) {
                    resolveCardContent(
                        card = card,
                        contentUrl = contentUrl,
                        referer = referer,
                        defaultKind = ContentKind.MOVIE,
                    )
                }
            }
            resolved?.takeIf { contentType.accepts(it.kind) }?.anime
        }
        return parsed.distinctBy(::canonicalContentKey)
    }

    private fun resolveParentSeries(card: Element, referer: HttpUrl): ResolvedContent? {
        val episodeUrl = card.selectFirst("a[href]")
            ?.attr("abs:href")
            ?.toHttpUrlOrNull()
            ?: return null
        val document = fetchContentDocument(episodeUrl, referer, "parent lookup")
            ?: return null
        val parent = document.selectFirst("#mpbreadcrumbs a[href*=/series/]")
            ?: return null
        val parentUrl = parent.attr("abs:href").toHttpUrlOrNull()
            ?: return null
        val anime = SAnime.create().apply {
            title = parent.selectFirst("span")?.text()
                .orEmpty()
                .trim()
                .ifBlank { parent.text().trim() }
            thumbnail_url = card.selectFirst("img")?.imageUrl()
            setUrlWithoutDomain(parentUrl.toString())
        }
        return ResolvedContent(
            anime = anime,
            kind = document.contentKind(ContentKind.SERIES),
        )
    }

    private fun resolveCardContent(
        card: Element,
        contentUrl: HttpUrl,
        referer: HttpUrl,
        defaultKind: ContentKind,
    ): ResolvedContent? {
        val document = fetchContentDocument(contentUrl, referer, "taxonomy lookup")
            ?: return null
        return ResolvedContent(
            anime = animeFromCard(card),
            kind = document.contentKind(defaultKind),
        )
    }

    private fun fetchContentDocument(
        url: HttpUrl,
        referer: HttpUrl,
        label: String,
    ): Document? {
        val requestHeaders = headers.newBuilder()
            .set("Referer", referer.toString())
            .build()
        return runCatching {
            client.newCall(GET(url, requestHeaders)).execute().use { contentResponse ->
                println("TopCinema: $label HTTP status=${contentResponse.code}")
                if (!contentResponse.isSuccessful) {
                    return null
                }
                Jsoup.parse(contentResponse.body.string(), contentResponse.request.url.toString())
            }
        }.onFailure { error ->
            println(
                "TopCinema: $label failed error=${error.javaClass.simpleName}",
            )
        }.getOrNull()
    }

    private fun Document.contentKind(defaultKind: ContentKind): ContentKind {
        val isAnime = select("#mpbreadcrumbs a[href*=/category/]")
            .mapNotNull { it.attr("abs:href").toHttpUrlOrNull() }
            .any { categoryUrl ->
                categoryUrl.pathSegments.any { segment ->
                    segment == ANIME_SERIES_SLUG || segment == ANIME_MOVIES_SLUG
                }
            }
        return when {
            isAnime && defaultKind == ContentKind.SERIES -> ContentKind.ANIME_SERIES
            isAnime -> ContentKind.ANIME_MOVIE
            else -> defaultKind
        }
    }

    private fun ContentType.accepts(kind: ContentKind): Boolean = when (this) {
        ContentType.ALL -> true
        ContentType.MOVIES -> kind == ContentKind.MOVIE
        ContentType.SERIES -> kind == ContentKind.SERIES
        ContentType.ANIME -> kind in setOf(
            ContentKind.ANIME_MOVIE,
            ContentKind.ANIME_SERIES,
        )
    }

    private fun mergeContent(movies: List<SAnime>, series: List<SAnime>): List<SAnime> {
        if (series.isEmpty()) return movies.distinctBy(::canonicalContentKey)

        val merged = mutableListOf<SAnime>()
        var seriesIndex = 0
        movies.forEachIndexed { movieIndex, movie ->
            if (movieIndex % 5 == 0 && seriesIndex < series.size) {
                merged += series[seriesIndex++]
            }
            merged += movie
        }
        while (seriesIndex < series.size) {
            merged += series[seriesIndex++]
        }
        return merged.distinctBy(::canonicalContentKey)
    }

    private fun canonicalContentKey(anime: SAnime): String {
        val url = baseUrl.toHttpUrl().resolve(anime.url)
            ?: return anime.url.trimEnd('/').lowercase()
        return "${url.host.lowercase()}${url.encodedPath.trimEnd('/').lowercase()}"
    }

    private fun SAnime.isSeriesUrl(): Boolean = baseUrl.toHttpUrl()
        .resolve(url)
        ?.pathSegments
        ?.firstOrNull()
        ?.equals("series", ignoreCase = true)
        ?: false

    private fun HttpUrl.pageNumber(): Int {
        val pageIndex = pathSegments.indexOf("page")
        return pathSegments.getOrNull(pageIndex + 1)?.toIntOrNull() ?: 1
    }

    private fun listingUrl(path: String, page: Int): String = if (page == 1) {
        "$baseUrl$path"
    } else {
        "$baseUrl${path}page/$page/"
    }

    private fun fetchListingDocument(
        path: String,
        page: Int,
        referer: HttpUrl,
        label: String,
    ): Document? {
        val requestHeaders = headers.newBuilder()
            .set("Referer", referer.toString())
            .build()
        return runCatching {
            client.newCall(GET(listingUrl(path, page), requestHeaders)).execute().use { listingResponse ->
                println("TopCinema: $label HTTP status=${listingResponse.code}")
                if (!listingResponse.isSuccessful) {
                    return null
                }
                Jsoup.parse(listingResponse.body.string(), listingResponse.request.url.toString())
            }
        }.onFailure { error ->
            println(
                "TopCinema: $label failed error=${error.javaClass.simpleName}",
            )
        }.getOrNull()
    }

    private fun Document.hasNextPage(): Boolean = select(
        "a.next.page-numbers, .page-numbers a",
    ).any { link ->
        link.hasClass("next") || link.text().contains("»")
    }

    private fun Document.nextSearchOffset(currentPage: Int): Int? = select(
        ".page-numbers[href], a.next[href]",
    ).mapNotNull { link ->
        link.attr("abs:href")
            .toHttpUrlOrNull()
            ?.queryParameter("offset")
            ?.toIntOrNull()
    }.filter { it > currentPage }
        .minOrNull()

    private fun Element.imageUrl(): String? = attr("abs:data-src")
        .ifBlank { attr("abs:data-lazy-src") }
        .ifBlank { attr("abs:src") }
        .takeIf(String::isNotBlank)

    // =============================== Details ==============================

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1.post-title")?.text().orEmpty().trim()
        thumbnail_url = document.selectFirst("div.left div.image img")?.imageUrl()
        description = document.selectFirst("div.story")?.text().orEmpty().trim()
        genre = document.select(".RightTaxContent li")
            .firstOrNull { it.text().contains("نوع") }
            ?.let { genreElement ->
                genreElement.select("a").joinToString { it.text().trim() }
                    .ifBlank { genreElement.text().substringAfter(":", "").trim() }
            }
        author = document.select(".RightTaxContent li")
            .firstOrNull { it.text().contains("المخرج") }
            ?.select("a")
            ?.joinToString { it.text().trim() }
            ?.takeIf(String::isNotBlank)
        status = when {
            title.contains("فيلم") -> SAnime.COMPLETED
            document.text().contains("والاخيرة") ||
                document.text().contains("والأخيرة") ||
                title.contains("كامل") -> SAnime.COMPLETED
            title.contains("مسلسل") || title.contains("انمي") -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "section.allepcont a[href]"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val currentUrl = response.request.url
        println("TopCinema: episode-list requested URL=${currentUrl.safeRequestUrl()}")
        println("TopCinema: parent HTTP status=${response.code}")
        println("TopCinema: parent final host=${currentUrl.host}")
        val document = response.asJsoup()
        val defaultKind = if (currentUrl.isSeriesPath()) {
            ContentKind.SERIES
        } else {
            ContentKind.MOVIE
        }
        val classification = document.contentKind(defaultKind).diagnosticName
        println("TopCinema: classification=$classification")
        val currentEpisodes = parseEpisodeEntries(
            document = document,
            selector = episodeListSelector(),
            diagnosticLabel = "direct",
        )
        val seasonAnchors = document.select("div.Small--Box.Season a[href]")
        val relatedSeasons = seasonAnchors
            .mapIndexedNotNull { index, anchor ->
                val seasonUrl = anchor.attr("abs:href").toHttpUrlOrNull()
                    ?: return@mapIndexedNotNull null
                SeasonPage(
                    number = seasonNumber(anchor.selectFirst(".number")?.text().orEmpty())
                        ?: seasonNumber(anchor.text())
                        ?: seasonNumber(anchor.parent()?.text().orEmpty())
                        ?: (seasonAnchors.size - index),
                    url = seasonUrl,
                )
            }
            .distinctBy { it.url.encodedPath }
            .sortedByDescending(SeasonPage::number)
        val parentListUrls = document.episodeListUrls()
        println("TopCinema: direct episode selector count=${document.select(episodeListSelector()).size}")
        println("TopCinema: season selector count=${seasonAnchors.size}")
        println("TopCinema: season URL count=${relatedSeasons.size}")
        println("TopCinema: list URL count=${parentListUrls.size}")

        val parsedEpisodes = when {
            relatedSeasons.isNotEmpty() -> relatedSeasons.parallelCatchingFlatMapBlocking { season ->
                try {
                    val seasonDocument = if (season.url.encodedPath == currentUrl.encodedPath) {
                        document
                    } else {
                        fetchEpisodeDocument(season.url, currentUrl, "season")
                            ?: return@parallelCatchingFlatMapBlocking emptyList()
                    }
                    parseSeasonPage(season, seasonDocument)
                } catch (error: Exception) {
                    println(
                        "TopCinema: episode failure stage=season " +
                            "error=${error.javaClass.simpleName}: ${safeError(error.message)}",
                    )
                    emptyList()
                }
            }
            currentEpisodes.isNotEmpty() -> {
                val season = seasonNumber(document.selectFirst("h1.post-title")?.text().orEmpty()) ?: 1
                currentEpisodes.toParsedEpisodes(season)
            }
            else -> {
                if (parentListUrls.isNotEmpty()) {
                    parentListUrls.parallelCatchingFlatMapBlocking { listUrl ->
                        parseEpisodeListPages(
                            listUrl = listUrl,
                            seasonNumber = seasonNumber(
                                document.selectFirst("h1.post-title")?.text().orEmpty(),
                            ) ?: 1,
                            referer = currentUrl,
                        )
                    }
                } else if (defaultKind == ContentKind.SERIES) {
                    println("TopCinema: episode failure stage=parent error=no verified episode source")
                    emptyList()
                } else {
                    return listOf(
                        SEpisode.create().apply {
                            name = "مشاهدة"
                            episode_number = 1F
                            setUrlWithoutDomain(currentUrl.toString())
                        },
                    )
                }
            }
        }

        return parsedEpisodes
            .distinctBy { it.episode.url }
            .sortedWith(
                compareByDescending<ParsedEpisode> { it.seasonNumber }
                    .thenByDescending { it.episodeNumber },
            )
            .map(ParsedEpisode::episode)
            .also {
                println("TopCinema: parsed episode count=${parsedEpisodes.size}")
                println("TopCinema: returned episode count=${it.size}")
            }
    }

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private fun parseSeasonPage(
        season: SeasonPage,
        seasonDocument: Document,
    ): List<ParsedEpisode> {
        val resolvedSeasonNumber = season.number.takeIf { it > 0 }
            ?: seasonNumber(seasonDocument.selectFirst("h1.post-title")?.text().orEmpty())
            ?: 1
        val directEntries = parseEpisodeEntries(
            document = seasonDocument,
            selector = episodeListSelector(),
            diagnosticLabel = "direct",
        )
        val listUrls = seasonDocument.episodeListUrls()
        println("TopCinema: list URL count=${listUrls.size}")
        val listEpisodes = listUrls.parallelCatchingFlatMapBlocking { listUrl ->
            parseEpisodeListPages(
                listUrl = listUrl,
                seasonNumber = resolvedSeasonNumber,
                referer = season.url,
            )
        }
        return (
            directEntries.toParsedEpisodes(resolvedSeasonNumber) +
                listEpisodes
            ).distinctBy { it.episode.url }
    }

    private fun parseEpisodeListPages(
        listUrl: HttpUrl,
        seasonNumber: Int,
        referer: HttpUrl,
    ): List<ParsedEpisode> {
        val firstDocument = fetchEpisodeDocument(listUrl, referer, "episode list")
            ?: return emptyList()
        val resolvedListUrl = firstDocument.location().toHttpUrlOrNull() ?: listUrl
        val paginationUrls = firstDocument.select(".page-numbers[href]")
            .mapNotNull { it.attr("abs:href").toHttpUrlOrNull() }
        val pageCount = paginationUrls
            .mapNotNull {
                it.queryParameter("page")?.toIntOrNull()
            }
            .maxOrNull()
            ?: 1
        val firstEntries = parseEpisodeEntries(
            document = firstDocument,
            selector = FULL_EPISODE_LIST_SELECTOR,
            diagnosticLabel = "season/list",
        )
        println("TopCinema: next-page count=${(pageCount - 1).coerceAtLeast(0)}")
        val remainingEntries = (2..pageCount).toList()
            .parallelCatchingFlatMapBlocking { page ->
                try {
                    val pageUrl = resolvedListUrl.newBuilder()
                        .setQueryParameter("page", page.toString())
                        .build()
                    val pageDocument = fetchEpisodeDocument(
                        pageUrl,
                        resolvedListUrl,
                        "episode list page",
                    ) ?: return@parallelCatchingFlatMapBlocking emptyList()
                    parseEpisodeEntries(
                        document = pageDocument,
                        selector = FULL_EPISODE_LIST_SELECTOR,
                        diagnosticLabel = "season/list",
                    )
                } catch (error: Exception) {
                    println(
                        "TopCinema: episode failure stage=list-page " +
                            "error=${error.javaClass.simpleName}: ${safeError(error.message)}",
                    )
                    emptyList()
                }
            }
        println(
            "TopCinema: season=$seasonNumber episode list pages=$pageCount " +
                "entries=${firstEntries.size + remainingEntries.size}",
        )
        return (firstEntries + remainingEntries).toParsedEpisodes(seasonNumber)
    }

    private fun parseEpisodeEntries(
        document: Document,
        selector: String,
        diagnosticLabel: String,
    ): List<EpisodeEntry> {
        val selected = document.select(selector)
        println("TopCinema: $diagnosticLabel episode selector count=${selected.size}")
        return selected
            .mapNotNull { episode ->
                val episodeUrl = episode.attr("abs:href").toHttpUrlOrNull()
                    ?: return@mapNotNull null
                if (!episode.isVerifiedEpisodeLink(episodeUrl, document)) {
                    return@mapNotNull null
                }
                val title = episode.attr("title")
                    .trim()
                    .ifBlank { episode.selectFirst("h2, h3")?.text().orEmpty().trim() }
                    .ifBlank { episode.text().replace(Regex("""\s+"""), " ").trim() }
                val number = episodeNumber(episode.select(".number, .epnum").text())
                    ?: episodeNumber(title)
                    ?: episodeNumber(episodeUrl.toString())
                EpisodeEntry(
                    title = title,
                    number = number,
                    url = episodeUrl,
                )
            }
            .distinctBy { "${it.url.host}${it.url.encodedPath}" }
            .also { println("TopCinema: parsed episode count=${it.size}") }
    }

    private fun Element.isVerifiedEpisodeLink(
        episodeUrl: HttpUrl,
        document: Document,
    ): Boolean {
        val documentUrl = document.location().toHttpUrlOrNull()
        if (documentUrl != null && episodeUrl.host != documentUrl.host) {
            return false
        }
        if (
            episodeUrl.isSeriesPath() ||
            episodeUrl.encodedPath.endsWith("/list/") ||
            episodeUrl.encodedPath.endsWith("/watch/") ||
            episodeUrl.pathSegments.firstOrNull() == "category"
        ) {
            return false
        }
        val card = closest("div.Small--Box")
        val episodeText = listOf(
            attr("title"),
            select(".number, .epnum").text(),
            card?.select(".number, .epnum")?.text().orEmpty(),
            selectFirst("h2, h3")?.text().orEmpty(),
            text(),
        ).joinToString(" ")
        return episodeText.contains("حلقة") || episodeText.contains("الحلقة")
    }

    private fun List<EpisodeEntry>.toParsedEpisodes(seasonNumber: Int): List<ParsedEpisode> {
        val ordered = sortedWith(
            compareByDescending<EpisodeEntry> { it.number ?: Float.NEGATIVE_INFINITY },
        )
        return ordered.mapIndexed { index, entry ->
            val resolvedEpisodeNumber = entry.number ?: (ordered.size - index).toFloat()
            val episodeLabel = resolvedEpisodeNumber.toEpisodeLabel()
            val name = if (entry.number != null) {
                "الموسم $seasonNumber - الحلقة $episodeLabel"
            } else {
                "الموسم $seasonNumber - ${entry.title}"
            }
            ParsedEpisode(
                seasonNumber = seasonNumber,
                episodeNumber = resolvedEpisodeNumber,
                episode = SEpisode.create().apply {
                    this.name = name
                    episode_number = resolvedEpisodeNumber
                    scanlator = "الموسم $seasonNumber"
                    setUrlWithoutDomain(entry.url.toString())
                },
            )
        }
    }

    private fun fetchEpisodeDocument(
        url: HttpUrl,
        referer: HttpUrl,
        label: String,
    ): Document? {
        val requestHeaders = headers.newBuilder()
            .set("Referer", referer.toString())
            .build()
        return runCatching {
            client.newCall(GET(url, requestHeaders)).execute().use { episodeResponse ->
                println("TopCinema: $label HTTP status=${episodeResponse.code}")
                if (!episodeResponse.isSuccessful) {
                    return null
                }
                Jsoup.parse(episodeResponse.body.string(), episodeResponse.request.url.toString())
            }
        }.onFailure { error ->
            println(
                "TopCinema: $label failed error=${error.javaClass.simpleName}",
            )
        }.getOrNull()
    }

    private fun Document.episodeListUrls(): List<HttpUrl> = select(
        "a.watch[href], a[href*=/list/]",
    )
        .mapNotNull { it.attr("abs:href").toHttpUrlOrNull() }
        .filter { url ->
            url.pathSegments.lastOrNull(String::isNotBlank) == "list"
        }
        .distinctBy { "${it.host}${it.encodedPath}" }

    private fun seasonNumber(text: String): Int? {
        val normalizedDigits = text.normalizeArabicDigits()
        Regex("""الموسم\s*(\d+)""").find(normalizedDigits)?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }
        val normalized = normalizedDigits
            .replace('أ', 'ا')
            .replace('إ', 'ا')
        return SEASON_WORDS.entries.firstOrNull { (word, _) ->
            normalized.contains("الموسم $word")
        }?.value
    }

    private fun episodeNumber(text: String): Float? = Regex(
        """الحلقة\s*(\d+(?:[.,]\d+)?)""",
    ).find(text.normalizeArabicDigits())
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(',', '.')
        ?.toFloatOrNull()

    private fun String.normalizeArabicDigits(): String = map { character ->
        when (character) {
            '٠' -> '0'
            '١' -> '1'
            '٢' -> '2'
            '٣' -> '3'
            '٤' -> '4'
            '٥' -> '5'
            '٦' -> '6'
            '٧' -> '7'
            '٨' -> '8'
            '٩' -> '9'
            else -> character
        }
    }.joinToString("")

    private fun Float.toEpisodeLabel(): String = if (this % 1F == 0F) {
        toInt().toString()
    } else {
        toString().trimEnd('0').trimEnd('.')
    }

    private data class SeasonPage(
        val number: Int,
        val url: HttpUrl,
    )

    private data class EpisodeEntry(
        val title: String,
        val number: Float?,
        val url: HttpUrl,
    )

    private data class ParsedEpisode(
        val seasonNumber: Int,
        val episodeNumber: Float,
        val episode: SEpisode,
    )

    // ================================ Video ===============================

    override fun videoListSelector() = "li.server--item"

    override fun videoListParse(response: Response): List<Video> {
        val detailDocument = response.asJsoup()
        val detailUrl = response.request.url
        val watchUrl = detailDocument.selectFirst("a.watch[href]")
            ?.attr("abs:href")
            ?.toHttpUrlOrNull()
            ?: detailUrl.takeIf { it.encodedPath.endsWith("/watch/") }
            ?: return emptyList()
        val watchHeaders = headers.newBuilder()
            .set("Referer", detailUrl.toString())
            .build()

        val watchResponse = client.newCall(GET(watchUrl, watchHeaders)).execute()
        val watchDocument: Document
        val finalWatchUrl: HttpUrl
        watchResponse.use {
            println("TopCinema: watch HTTP status=${it.code}")
            finalWatchUrl = it.request.url
            watchDocument = Jsoup.parse(it.body.string(), finalWatchUrl.toString())
        }

        val ajaxUrl = watchDocument.select("script")
            .asSequence()
            .map(Element::data)
            .mapNotNull { script -> AJAX_BASE_REGEX.find(script)?.groupValues?.getOrNull(1) }
            .mapNotNull { finalWatchUrl.resolve(it) }
            .firstOrNull()
            ?.resolve("Single/Server.php")
            ?: return emptyList()

        val serverElements = watchDocument.select(videoListSelector())
            .filter { it.attr("data-id").isNotBlank() && it.attr("data-server").isNotBlank() }
        println("TopCinema: watch server count=${serverElements.size}")

        val serverHeaders = headers.newBuilder()
            .set("Referer", finalWatchUrl.toString())
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val videos = serverElements.parallelCatchingFlatMapBlocking { element ->
            extractServer(
                ServerSlot(
                    name = element.selectFirst("span")?.text()
                        ?.trim()
                        .takeUnless(String?::isNullOrBlank)
                        ?: element.text().trim(),
                    id = element.attr("data-id"),
                    index = element.attr("data-server"),
                ),
                ajaxUrl,
                finalWatchUrl,
                serverHeaders,
            )
        }

        return videos
            .mapNotNull(::validateVideo)
            .distinctBy { "${it.videoUrl}|${it.quality}" }
            .also { println("TopCinema: final Video count=${it.size}") }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    private suspend fun extractServer(
        slot: ServerSlot,
        ajaxUrl: HttpUrl,
        watchUrl: HttpUrl,
        serverHeaders: Headers,
    ): List<Video> {
        val body = FormBody.Builder()
            .add("id", slot.id)
            .add("i", slot.index)
            .build()
        val candidate = client.newCall(POST(ajaxUrl.toString(), serverHeaders, body)).await().use { response ->
            println(
                "TopCinema: server name=${safeLabel(slot.name)} AJAX status=${response.code}",
            )
            if (!response.isSuccessful) {
                return emptyList()
            }
            val document = Jsoup.parse(response.body.string(), ajaxUrl.toString())
            val url = document.selectFirst("iframe[src]")
                ?.attr("abs:src")
                ?.toHttpUrlOrNull()
                ?: return emptyList()
            ServerCandidate(slot.name, url, watchUrl)
        }

        println(
            "TopCinema: server name=${safeLabel(candidate.name)} " +
                "host=${candidate.url.host} fingerprint=${fingerprint(candidate.url)}",
        )

        val exactCandidate = if (candidate.normalizedName in WRAPPER_SERVER_NAMES) {
            resolveNestedPlayer(candidate)
                ?: return emptyList()
        } else {
            candidate
        }
        val route = routeFor(exactCandidate)
        println(
            "TopCinema: extractor name=${safeLabel(candidate.name)} " +
                "route=$route host=${exactCandidate.url.host}",
        )

        val extracted = try {
            when (route) {
                "packed-media" -> extractPackedMedia(exactCandidate)
                "dood" -> extractDood(exactCandidate)
                "vidhide" -> VidHideExtractor(client, exactCandidate.playerHeaders())
                    .videosFromUrl(exactCandidate.url.toString()) { quality ->
                        "${exactCandidate.name} - $quality"
                    }
                "streamtape" -> extractStreamTape(exactCandidate)
                "filemoon" -> FilemoonExtractor(client).videosFromUrl(
                    url = exactCandidate.url.toString(),
                    prefix = "${exactCandidate.name} - ",
                    headers = exactCandidate.playerHeaders(),
                    referer = exactCandidate.referer.toString(),
                )
                "mixdrop" -> MixDropExtractor(client, exactCandidate.playerHeaders()).videosFromUrl(
                    url = exactCandidate.url.toString(),
                    prefix = "${exactCandidate.name} - ",
                    referer = exactCandidate.referer.toString(),
                )
                else -> emptyList()
            }
        } catch (exception: Exception) {
            println(
                "TopCinema: extractor failed name=${safeLabel(candidate.name)} " +
                    "error=${exception.javaClass.simpleName}: ${safeError(exception.message)}",
            )
            emptyList()
        }

        return extracted
            .map { it.withServerLabel(candidate.name) }
            .also {
                println(
                    "TopCinema: server extractor result name=${safeLabel(candidate.name)} " +
                        "count=${it.size}",
                )
            }
    }

    private suspend fun resolveNestedPlayer(candidate: ServerCandidate): ServerCandidate? {
        val response = client.newCall(GET(candidate.url, candidate.playerHeaders())).await()
        val finalUrl: HttpUrl
        val document: Document
        response.use {
            println(
                "TopCinema: wrapper name=${safeLabel(candidate.name)} status=${it.code} " +
                    "finalHost=${it.request.url.host}",
            )
            if (!it.isSuccessful) {
                return null
            }
            finalUrl = it.request.url
            document = Jsoup.parse(it.body.string(), finalUrl.toString())
        }
        val nestedUrl = document.selectFirst("iframe[src]")
            ?.attr("abs:src")
            ?.toHttpUrlOrNull()
            ?: return null
        println(
            "TopCinema: nested player name=${safeLabel(candidate.name)} " +
                "host=${nestedUrl.host} fingerprint=${fingerprint(nestedUrl)}",
        )
        return candidate.copy(url = nestedUrl, referer = finalUrl)
    }

    private fun routeFor(candidate: ServerCandidate): String = when (candidate.normalizedName) {
        "متعددالجودات", "updown", "lulustream", "streamwish" -> "packed-media"
        "doodstream" -> if (candidate.url.host == "d0o0d.com" ||
            candidate.url.host.contains("dood", ignoreCase = true)
        ) {
            "dood"
        } else {
            "unsupported"
        }
        "filelions" -> if (candidate.url.host.contains("earnvids", ignoreCase = true)) {
            "vidhide"
        } else {
            "unsupported"
        }
        "streamtape" -> "streamtape"
        "filemoon" -> "filemoon"
        "mixdrop" -> "mixdrop"
        else -> "unsupported"
    }

    private suspend fun extractPackedMedia(candidate: ServerCandidate): List<Video> {
        val response = client.newCall(GET(candidate.url, candidate.playerHeaders())).awaitSuccess()
        val finalUrl = response.request.url
        val document = response.useAsJsoup()
        val unpackedScripts = document.select("script")
            .asSequence()
            .map(Element::data)
            .filter(JsUnpacker::detect)
            .mapNotNull(JsUnpacker::unpackAndCombine)
            .joinToString("\n")
            .decodeScriptEscapes()
        val mediaUrls = PACKED_MEDIA_REGEX.findAll(unpackedScripts)
            .map(MatchResult::value)
            .mapNotNull { normalizeMediaUrl(it, finalUrl) }
            .distinctBy(HttpUrl::toString)
            .toList()
        val mediaHeaders = headers.newBuilder()
            .set("Referer", finalUrl.toString())
            .set("Origin", "${finalUrl.scheme}://${finalUrl.host}")
            .build()

        return mediaUrls.flatMap { mediaUrl ->
            when {
                mediaUrl.encodedPath.endsWith(".m3u8", ignoreCase = true) &&
                    isValidatedHls(mediaUrl, mediaHeaders) -> {
                    playlistUtils.extractFromHls(
                        playlistUrl = mediaUrl.toString(),
                        referer = finalUrl.toString(),
                        masterHeaders = mediaHeaders,
                        videoHeaders = mediaHeaders,
                        videoNameGen = { quality -> "${candidate.name} - $quality" },
                    )
                }
                mediaUrl.encodedPath.endsWith(".mp4", ignoreCase = true) &&
                    isValidatedMp4(mediaUrl, mediaHeaders) -> {
                    listOf(
                        Video(
                            mediaUrl.toString(),
                            "${candidate.name} - MP4",
                            mediaUrl.toString(),
                            mediaHeaders,
                        ),
                    )
                }
                else -> emptyList()
            }
        }
    }

    private suspend fun extractDood(candidate: ServerCandidate): List<Video> {
        val candidateHeaders = candidate.playerHeaders()
        val doodClient = client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestBuilder = request.newBuilder()
                candidateHeaders.names().forEach { name ->
                    if (request.header(name) == null) {
                        candidateHeaders[name]?.let { requestBuilder.header(name, it) }
                    }
                }
                chain.proceed(requestBuilder.build())
            }
            .build()
        return DoodExtractor(doodClient).videosFromUrl(
            candidate.url.toString(),
            quality = candidate.name,
        )
    }

    private suspend fun extractStreamTape(candidate: ServerCandidate): List<Video> {
        val response = client.newCall(GET(candidate.url, candidate.playerHeaders())).awaitSuccess()
        val finalUrl = response.request.url
        val document = response.useAsJsoup()
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?.substringAfter("$targetLine.innerHTML = '", "")
            ?: return emptyList()
        val rawUrl = "https:" +
            script.substringBefore("'") +
            script.substringAfter("+ ('xcd", "").substringBefore("'")
        val videoUrl = rawUrl.toHttpUrlOrNull()
            ?: return emptyList()
        val mediaHeaders = headers.newBuilder()
            .set("Referer", finalUrl.toString())
            .set("Origin", "${finalUrl.scheme}://${finalUrl.host}")
            .build()
        if (!isValidatedMp4(videoUrl, mediaHeaders)) {
            return emptyList()
        }
        return listOf(
            Video(
                videoUrl.toString(),
                "${candidate.name} - MP4",
                videoUrl.toString(),
                mediaHeaders,
            ),
        )
    }

    private fun ServerCandidate.playerHeaders(): Headers = headers.newBuilder()
        .set("Referer", referer.toString())
        .set("Origin", "${referer.scheme}://${referer.host}")
        .build()

    private fun Video.withServerLabel(serverName: String): Video {
        val currentQuality = quality.trim()
        val label = if (currentQuality.startsWith(serverName, ignoreCase = true)) {
            currentQuality
        } else {
            "$serverName - $currentQuality"
        }
        return Video(
            url = url,
            quality = label,
            videoUrl = videoUrl,
            headers = headers,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
        )
    }

    private fun validateVideo(video: Video): Video? {
        val rawUrl = video.videoUrl.orEmpty()
        if (
            rawUrl.isBlank() ||
            rawUrl.startsWith("/https:", ignoreCase = true) ||
            rawUrl.startsWith("javascript:", ignoreCase = true) ||
            rawUrl.startsWith("data:", ignoreCase = true) ||
            rawUrl.startsWith("file:", ignoreCase = true) ||
            rawUrl.contains("MDCore", ignoreCase = true) ||
            rawUrl.contains("Core.wurl", ignoreCase = true)
        ) {
            return null
        }
        return video.takeIf {
            rawUrl.toHttpUrlOrNull()?.scheme in setOf("http", "https")
        }
    }

    private suspend fun isValidatedHls(mediaUrl: HttpUrl, mediaHeaders: Headers): Boolean = runCatching {
        client.newCall(GET(mediaUrl, mediaHeaders)).await().use { response ->
            response.isSuccessful && response.body.string().trimStart().startsWith("#EXTM3U")
        }
    }.getOrDefault(false)

    private suspend fun isValidatedMp4(mediaUrl: HttpUrl, mediaHeaders: Headers): Boolean = runCatching {
        val validationHeaders = mediaHeaders.newBuilder()
            .set("Range", "bytes=0-15")
            .build()
        client.newCall(GET(mediaUrl, validationHeaders)).await().use { response ->
            if (!response.isSuccessful) {
                return@use false
            }
            val contentType = response.header("Content-Type")
                .orEmpty()
                .substringBefore(';')
                .trim()
                .lowercase()
            val prefix = response.body.byteStream().use { stream ->
                val buffer = ByteArray(16)
                val length = stream.read(buffer).coerceAtLeast(0)
                buffer.copyOf(length)
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
    }.getOrDefault(false)

    private fun normalizeMediaUrl(rawUrl: String, baseUrl: HttpUrl): HttpUrl? {
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
            else -> UrlUtils.fixUrl(value, baseUrl.toString()) ?: return null
        }
        return normalized.toHttpUrlOrNull()?.takeIf {
            it.scheme in setOf("http", "https") &&
                (
                    it.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
                        it.encodedPath.endsWith(".mp4", ignoreCase = true)
                    )
        }
    }

    private fun String.decodeScriptEscapes(): String = replace("\\/", "/")
        .replace("\\u002f", "/", ignoreCase = true)
        .replace("\\x2f", "/", ignoreCase = true)
        .replace("\\u003a", ":", ignoreCase = true)
        .replace("\\x3a", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\x26", "&", ignoreCase = true)
        .replace("&amp;", "&")

    private fun fingerprint(url: HttpUrl): String = MessageDigest
        .getInstance("SHA-256")
        .digest(url.toString().toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun safeLabel(value: String): String = value
        .replace(Regex("""https?://\S+"""), "<redacted-url>")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(80)
        .ifBlank { "Unknown" }

    private fun safeError(value: String?): String = value
        ?.replace(Regex("""https?://\S+"""), "<redacted-url>")
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.take(160)
        .orEmpty()
        .ifBlank { "no message" }

    private fun HttpUrl.safeRequestUrl(): String = "$scheme://$host$encodedPath"

    private fun HttpUrl.isSeriesPath(): Boolean = pathSegments
        .firstOrNull()
        ?.equals("series", ignoreCase = true)
        ?: false

    private val ContentKind.diagnosticName: String
        get() = when (this) {
            ContentKind.MOVIE -> "movie"
            ContentKind.SERIES -> "series"
            ContentKind.ANIME_MOVIE -> "anime-movie"
            ContentKind.ANIME_SERIES -> "anime-series"
        }

    private val ServerCandidate.normalizedName: String
        get() = name.lowercase().replace(Regex("""[\s_-]+"""), "")

    private data class ServerSlot(
        val name: String,
        val id: String,
        val index: String,
    )

    private data class ServerCandidate(
        val name: String,
        val url: HttpUrl,
        val referer: HttpUrl,
    )

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    companion object {
        private const val TOP_MOVIES_PATH = "/top-rating-imdb/"
        private const val TOP_SERIES_PATH = "/top-rating-imdb-series/"
        private const val CONTENT_TYPE_QUERY = "_topcinema_type"
        private const val BROWSE_FILTER_QUERY = "_topcinema_browse_filter"
        private const val BROWSE_PAGE_QUERY = "_topcinema_browse_page"
        private const val FOREIGN_MOVIES_PATH = "/category/افلام-اجنبي-8/"
        private const val ASIAN_MOVIES_PATH = "/category/افلام-اسيوي/"
        private const val FOREIGN_SERIES_PATH = "/category/مسلسلات-اجنبي/"
        private const val ASIAN_SERIES_PATH = "/category/مسلسلات-اسيوية/"
        private const val ANIME_MOVIES_PATH = "/category/افلام-انمي-2/"
        private const val ANIME_SERIES_PATH = "/category/مسلسلات-انمي/"
        private const val ANIME_MOVIES_SLUG = "افلام-انمي-2"
        private const val ANIME_SERIES_SLUG = "مسلسلات-انمي"
        private const val FULL_EPISODE_LIST_SELECTOR =
            ".Posts--List.SixInRow div.Small--Box:not(.Season) a[href], " +
                "div.Small--Box:not(.Season) a[href]"

        private val AJAX_BASE_REGEX = Regex(
            """(?:var\s+)?MyAjaxURL\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val PACKED_MEDIA_REGEX = Regex(
            """(?:https?:)?//[^\s"'\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'\\]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val WRAPPER_SERVER_NAMES = setOf(
            "doodstream",
            "filelions",
            "streamwish",
        )
        private val SEASON_WORDS = linkedMapOf(
            "الاول" to 1,
            "الثاني" to 2,
            "الثالث" to 3,
            "الرابع" to 4,
            "الخامس" to 5,
            "السادس" to 6,
            "السابع" to 7,
            "الثامن" to 8,
            "التاسع" to 9,
            "العاشر" to 10,
            "الحادي عشر" to 11,
            "الثاني عشر" to 12,
            "الثالث عشر" to 13,
            "الرابع عشر" to 14,
            "الخامس عشر" to 15,
        )
    }
}
