package eu.kanade.tachiyomi.lib.vidbomextractor

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidBomExtractor(private val client: OkHttpClient) {
    
    private val videoRegex by lazy { Regex("""https?://[^\"]+\.(?:m3u8|mp4)""") }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, headers: Headers? = null, src: String = ""): List<Video> {
        val request = if (headers != null) GET(url, headers) else GET(url)
        val document = client.newCall(request).execute().asJsoup()
        val script = document.selectFirst("script:containsData(eval)")
            ?.data()
            ?.let(JsUnpacker::unpackAndCombine)
            ?: return emptyList()
        
        return videoRegex.find(script)?.value?.let{
            when {
                "v.mp4" in it -> {
                    val quality = "${src}: " + script.substringAfter("label:\"").substringBefore("\"")
                    Video(it, quality, it).let(::listOf)
                }
                else -> {
                    playlistUtils.extractFromHls(it, url, videoNameGen = { quality -> "${src}: $quality" })
                }
            }
        } ?: emptyList()
    }
}
