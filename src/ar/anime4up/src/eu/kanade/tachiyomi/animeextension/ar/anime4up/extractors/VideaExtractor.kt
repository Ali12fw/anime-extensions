package eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class VideaExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    fun videosFromUrl(url: String, prefix: String): List<Video> {
        val body = client.newCall(GET(url, headers)).execute().body.string()
        val nonce = NONCE_REGEX.find(body)?.groupValues?.getOrNull(1) ?: return emptyList()
        val parameterL = nonce.take(32)
        val parameterS = nonce.substring(32)
        val result = (0..31).joinToString("") { index ->
            val sourceIndex = index - (STUPID_KEY.indexOf(parameterL[index]) - 31)
            parameterS.getOrNull(sourceIndex)?.toString().orEmpty()
        }
        if (result.length < 32) return emptyList()

        val seed = randomString()
        val requestUrl = REQUEST_URL.toHttpUrl().newBuilder()
            .addQueryParameter("_s", seed)
            .addQueryParameter("_t", result.take(16))
            .addQueryParameter("v", url.toHttpUrl().queryParameter("v").orEmpty())
            .build()
        val requestHeaders = headers.newBuilder()
            .set("Referer", url)
            .set("Origin", "https://videa.hu")
            .build()
        val document = client.newCall(GET(requestUrl, requestHeaders)).execute().use { response ->
            val responseBody = response.body.string()
            when {
                responseBody.startsWith("<?xml") -> Jsoup.parse(responseBody)
                else -> {
                    val key = result.substring(16) + seed + response.headers["x-videa-xs"].orEmpty()
                    val decoded = Base64.decode(responseBody, Base64.DEFAULT)
                    Jsoup.parse(decryptXml(decoded, key))
                }
            }
        }

        return document.select("video_source").mapNotNull { source ->
            val name = source.attr("name")
            val hash = document.selectFirst("hash_value_$name")?.text()
                ?: return@mapNotNull null
            val mediaUrl = "https:${source.text()}?md5=$hash&expires=${source.attr("exp")}"
            Video(mediaUrl, "$prefix - $name", mediaUrl, requestHeaders)
        }
    }

    private fun decryptXml(xml: ByteArray, key: String): String {
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "RC4"))
        return cipher.doFinal(xml).toString(Charsets.UTF_8)
    }

    private fun randomString(length: Int = 8): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length).map { allowedChars.random() }.joinToString("")
    }

    companion object {
        private val NONCE_REGEX = Regex("""_xt\s*=\s*"([^"]+)"""")
        private const val REQUEST_URL = "https://videa.hu/player/xml?platform=desktop"
        private const val STUPID_KEY = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"
    }
}
