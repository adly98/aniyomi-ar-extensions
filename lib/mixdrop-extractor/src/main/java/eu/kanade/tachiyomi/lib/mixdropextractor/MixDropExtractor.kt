package eu.kanade.tachiyomi.lib.mixdropextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.net.URLDecoder

class MixDropExtractor(private val client: OkHttpClient) {

    private val videoRegex by lazy { Regex("""//[^"]+\.(?:m3u8|mp4)[^"]*""") }

    fun videoFromUrl(
        url: String,
        suffix: String = "",
        externalSubs: List<Track> = emptyList()
    ): List<Video> {
        val headers = Headers.headersOf(
            "Referer",
            url,
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36",
        )
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val unpacked = doc.selectFirst("script:containsData(eval):containsData(MDCore)")
            ?.data()
            ?.let { "eval" + it.substringAfter("eval") }
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()

        val videoUrl = videoRegex.find(unpacked)?.value ?: return emptyList()
        val fullUrl = "https:" + videoUrl
        val subs = unpacked.substringAfter("Core.remotesub=\"").substringBefore('"')
            .takeIf(String::isNotBlank)
            ?.let { listOf(Track(URLDecoder.decode(it, "utf-8"), "sub")) }
            ?: emptyList()

        val quality = buildString {
            append("MixDrop")
            if (suffix.isNotBlank()) append(": $suffix")
        }

        return listOf(Video(fullUrl, quality, fullUrl, headers = headers, subtitleTracks = subs + externalSubs))
    }

    fun videosFromUrl(
        url: String,
        suffix: String = "",
        externalSubs: List<Track> = emptyList()
    ) = videoFromUrl(url, suffix, externalSubs)
}
