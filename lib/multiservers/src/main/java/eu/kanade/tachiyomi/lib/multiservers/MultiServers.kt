package eu.kanade.tachiyomi.lib.multiservers

import eu.kanade.tachiyomi.lib.multiservers.dto.IframeResponse
import eu.kanade.tachiyomi.lib.multiservers.dto.LeechResponse
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import kotlin.math.abs

class MultiServers(private val client: OkHttpClient, private val headers: Headers) {
    private val json = Json { ignoreUnknownKeys = true }
    fun extractedUrls(url: String): List<Provider> {
        val type = if ("/iframe/" in url) "mirror" else "leech"
        val newHeaders = headers.newBuilder()
            .add("X-Inertia", "true")
            .add("X-Inertia-Partial-Component", "files/$type/video")
            .add("X-Inertia-Partial-Data", "streams")
            .add("X-Inertia-Version", "933f5361ce18c71b82fa342f88de9634")
            .build()
        val iframe = client.newCall(GET(url, newHeaders)).execute().body.string()
        val urls = mutableListOf<Provider>()
        if (type == "mirror") {
            val resolved = json.decodeFromString<IframeResponse>(iframe)
            resolved.props.streams.data.forEach {
                val quality = it.resolution.substringAfter("x").let(::stnQuality)
                val size = it.size.let(::convertSize)
                it.mirrors.forEach { mirror ->
                    val link = if (mirror.link.startsWith("/")) "https:${mirror.link}" else mirror.link
                    urls += Provider(link, mirror.driver, quality, size)
                }
            }
        } else {
            val resolved = json.decodeFromString<LeechResponse>(iframe)
            resolved.props.streams.data.forEach {
                val size = it.size.let(::convertSize)
                urls += Provider(it.file, "Leech", it.label.substringBefore(" "), size)
            }
        }
        return urls
    }

    data class Provider(val url: String, val name: String, val quality: String, val size: String)
    private fun stnQuality(quality: String): String {
        val intQuality = quality.toInt()
        val standardQualities = listOf(144, 240, 360, 480, 720, 1080)
        val result =  standardQualities.minByOrNull { abs(it - intQuality) } ?: quality
        return "${result}p"
    }
    private fun  convertSize(bits: Long): String {
        val bytes = bits / 8
        return when {
            bytes >= 1 shl 30 -> "%.2f GB".format(bytes / (1 shl 30).toDouble())
            bytes >= 1 shl 20 -> "%.2f MB".format(bytes / (1 shl 20).toDouble())
            bytes >= 1 shl 10 -> "%.2f KB".format(bytes / (1 shl 10).toDouble())
            else -> "$bytes bytes"
        }
    }
}
