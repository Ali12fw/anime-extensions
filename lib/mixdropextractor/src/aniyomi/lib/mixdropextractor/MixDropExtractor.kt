package aniyomi.lib.mixdropextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.autoUnpacker
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.URLDecoder

class MixDropExtractor(
    private val client: OkHttpClient,
    private val headers: Headers = Headers.EMPTY,
) {
    fun videoFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ): List<Video> {
        val requestUrl = url.replaceFirst("/f/", "/e/")
        val requestHeaders = headers.newBuilder()
            .set("Referer", referer)
            .set("User-Agent", headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .build()
        val (document, pageUrl) = client.newCall(GET(requestUrl, requestHeaders)).execute().use { response ->
            response.asJsoup() to response.request.url.toString()
        }
        val unpacked = document.select("script")
            .asSequence()
            .map { it.html() }
            .map { script -> autoUnpacker(script).orEmpty().ifBlank { script } }
            .firstOrNull(WURL_REGEX::containsMatchIn)
            ?: return emptyList()

        val rawVideoUrl = WURL_REGEX.find(unpacked)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        val videoUrl = normalizeVideoUrl(rawVideoUrl)
            ?: return emptyList()

        val subs = unpacked.substringAfter("Core.remotesub=\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.let { listOf(Track(URLDecoder.decode(it, "utf-8"), "sub")) }
            ?: emptyList()

        val quality = buildString {
            append("${prefix}MixDrop")
            if (lang.isNotBlank()) append("($lang)")
        }

        val videoHeaders = requestHeaders.newBuilder()
            .set("Referer", pageUrl)
            .set("Origin", pageUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }.orEmpty())
            .build()
        return listOf(Video(videoUrl, quality, videoUrl, headers = videoHeaders, subtitleTracks = subs + externalSubs))
    }

    fun videosFromUrl(
        url: String,
        lang: String = "",
        prefix: String = "",
        externalSubs: List<Track> = emptyList(),
        referer: String = DEFAULT_REFERER,
    ) = videoFromUrl(url, lang, prefix, externalSubs, referer)

    private fun normalizeVideoUrl(rawUrl: String): String? {
        val value = rawUrl.trim()
        if (
            value.isBlank() ||
            value.contains("MDCore", ignoreCase = true) ||
            value.contains("Core.wurl", ignoreCase = true) ||
            value.contains("javascript:", ignoreCase = true) ||
            value.contains("/https:", ignoreCase = true)
        ) {
            return null
        }

        val normalized = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value
            else -> return null
        }
        val parsedUrl = normalized.toHttpUrlOrNull()
            ?: return null
        return normalized.takeIf { parsedUrl.scheme == "http" || parsedUrl.scheme == "https" }
    }

    companion object {
        private val WURL_REGEX = Regex(
            """wurl.*?=.*?["'](.*?)["']\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}

private const val DEFAULT_REFERER = "https://mixdrop.co/"
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
