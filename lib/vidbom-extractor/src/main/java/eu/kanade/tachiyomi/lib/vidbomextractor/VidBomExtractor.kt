package eu.kanade.tachiyomi.lib.vidbomextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class VidBomExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers? = null): List<Video> {
        val request = if (headers != null) GET(url, headers) else GET(url)
        val doc = client.newCall(request).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources)")!!
        val data = script.data().substringAfter("sources: [").substringBefore("],")

        return data.split("file:\"").drop(1).map { source ->
            val src = source.substringBefore("\"")

            val quality = when {
                "v.mp4" in src -> {
                    "${if("go" in url) "Govid" else "Vidbom"}: " + source.substringAfter("label:\"").substringBefore("\"")
                }
                else -> {
                    val m3u8 = client.newCall(GET(src)).execute().body.string()
                        .substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                    "Vidshare: $m3u8"
                }
            }
            Video(src, quality, src)
        }
    }
}
