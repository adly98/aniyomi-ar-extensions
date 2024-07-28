package eu.kanade.tachiyomi.lib.mixdropextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class MixDropExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromUrl(
        url: String,
        quality: String = "",
    ): List<Video> {
        val host = url.split("/")[2].let {
            if (it.endsWith(".club")) it.replace(".club", ".co") else it
        }
        val rUrl = "https://$host/"
        val newH = headers.newBuilder().add("Referer", rUrl).add("Origin", rUrl.dropLast(1)).build()
        var html = client.newCall(GET(url, newH)).execute().body.string()
        val r = Regex("""location\s*=\s*["']([^'"]+)""").find(html)
        var newUrl = url
        if (r != null) {
            newUrl = "https://${host}${r.groupValues[1]}"
            html = client.newCall(GET(newUrl, newH)).execute().body.string()
        }
        if ("(p,a,c,k,e,d)" in html) {
            html = html.let(Unpacker::unpack)
        }
        val urlMatch = Regex("""(?:vsr|wurl|surl)[^=]*=\s*"([^"]+)""").find(html)
        if (urlMatch != null) {
            val sUrl = urlMatch.groupValues[1].let {
                if (it.startsWith("//")) it.replace("//", "https://") else it
            }
            val videoH = headers.newBuilder().set("Referer", newUrl).build()
            return Video(
                sUrl,
                "MixDrop${if (quality.isNotBlank()) ": $quality" else ""}",
                sUrl,
                headers = videoH,
            ).let(::listOf)
        }
        return emptyList()
    }
}
