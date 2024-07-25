package eu.kanade.tachiyomi.animeextension.ar.anime4up

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.multiservers.MultiServers
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Anime4Up : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime4Up"

    override val baseUrl = "https://anime4up.cam"

    override val lang = "ar"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.anime-list-content div.anime-card-poster div.hover"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/anime-list-3/page/$page/", headers)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("img")!!.run {
            thumbnail_url = absUrl("src")
            title = attr("alt")
        }
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:contains(»)"

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() =
        "div.ehover6 > div.episodes-card-title > h3 > a, ul.all-episodes-list li > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = name.substringAfterLast(" ").toFloatOrNull() ?: 0F
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = document // Shortcut

        thumbnail_url = doc.selectFirst("img.thumbnail")!!.attr("src")
        title = doc.selectFirst("h1.anime-details-title")!!.text()
        // Genres + useful info
        genre = doc.select("ul.anime-genres > li > a, div.anime-info > a").eachText().joinToString()

        description = buildString {
            // Additional info
            doc.select("div.anime-info").eachText().forEach {
                append("$it\n")
            }
            // Description
            doc.selectFirst("p.anime-story")?.text()?.also {
                append("\n$it")
            }
        }

        doc.selectFirst("div.anime-info:contains(حالة الأنمي)")?.text()?.also {
            status = when {
                it.contains("يعرض الان", true) -> SAnime.ONGOING
                it.contains("مكتمل", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================ Video Links =============================
    @Serializable
    data class Qualities(
        val fhd: Map<String, String> = emptyMap(),
        val hd: Map<String, String> = emptyMap(),
        val sd: Map<String, String> = emptyMap(),
    )

    override fun videoListParse(response: Response): List<Video> {
        val base64 = response.asJsoup().selectFirst("input[name=wl]")
            ?.attr("value")
            ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
            ?: return emptyList()

        val parsedData = json.decodeFromString<Qualities>(base64)
        val streamLinks = with(parsedData) { fhd + hd + sd }
        return streamLinks.keys.parallelCatchingFlatMapBlocking {
            extractVideos(streamLinks[it]!!, it)
        }
    }

    private val multiServers by lazy { MultiServers(client, headers) }
    private val okRuExtractor by lazy { OkruExtractor(client) }
    private val dooDExtractor by lazy { DoodExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }

    private fun extractVideos(
        url: String,
        server: String,
        customQuality: String? = null,
    ): List<Video> {
        return when {
            "leech" in server -> {
                val newH = headers.newBuilder().add("Referer", url).build()
                return multiServers.extractedUrls(url).map {
                    Video(it.url, "${it.name}: ${it.quality}", it.url, headers = newH)
                }
            }

            "iframe" in url -> {
                return multiServers.extractedUrls(url).parallelCatchingFlatMapBlocking {
                    extractVideos(it.url, it.name, it.quality)
                }
            }

            "upstream" in server || "streamwish" in server || "vidhide" in server -> {
                streamWishExtractor.videosFromUrl(url, server.apply { first().uppercase() })
            }

            "vadbam" in server || "lulustream" in server -> {
                val newH = headers.newBuilder().add("Referer", baseUrl).build()
                vidBomExtractor.videosFromUrl(url, newH)
            }

            "mixdrop" in server -> {
                mixDropExtractor.videosFromUrl(url, "", customQuality?.let { "$it " } ?: "")
            }

            "mp4" in server -> {
                mp4uploadExtractor.videosFromUrl(url, headers)
            }

            "ok.ru" in url -> {
                okRuExtractor.videosFromUrl(url)
            }

            "dood" in server -> {
                dooDExtractor.videoFromUrl(url, "Dood: ${customQuality ?: "Mirror"}")?.let(::listOf)
                    ?: emptyList()
            }

            else -> emptyList()
        }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            return GET("$baseUrl/?search_param=animes&s=$query", headers)
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
            val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
            val statusFilter = filterList.find { it is StatusFilter } as StatusFilter
            val url = baseUrl.toHttpUrl().newBuilder()
            when {
                genreFilter.state != 0 -> url.addPathSegment("anime-genre/" + genreFilter.toUriPart())
                typeFilter.state != 0 -> url.addPathSegment("anime-type/" + typeFilter.toUriPart())
                statusFilter.state != 0 -> url.addPathSegment("anime-status/" + statusFilter.toUriPart())
                else -> throw Exception("من فضلك اختر فلتر")
            }
            GET(url.toString(), headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات تعمل فقط اذا كان واحد فقط هو المختار و الباقى على [اختر]"),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    private class TypeFilter : PairFilter(
        "النوع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("Movie", "movie-3"),
            Pair("ONA", "ona1"),
            Pair("OVA", "ova1"),
            Pair("Special", "special1"),
            Pair("TV", "tv2"),
        ),
    )

    private class StatusFilter : PairFilter(
        "الحالة",
        arrayOf(
            Pair("اختر", "none"),
            Pair("لم يعرض بعد", "%d9%84%d9%85-%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a8%d8%b9%d8%af"),
            Pair("مكتمل", "complete"),
            Pair("يعرض الان", "%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a7%d9%84%d8%a7%d9%86-1"),
        ),
    )

    private class GenreFilter : PairFilter(
        "التصنيف",
        arrayOf(
            Pair("اختر", "none"),
            Pair("أطفال", "%d8%a3%d8%b7%d9%81%d8%a7%d9%84"),
            Pair("أكشن", "%d8%a3%d9%83%d8%b4%d9%86"),
            Pair("إيتشي", "%d8%a5%d9%8a%d8%aa%d8%b4%d9%8a"),
            Pair("اثارة", "%d8%a7%d8%ab%d8%a7%d8%b1%d8%a9"),
            Pair("العاب", "%d8%a7%d9%84%d8%b9%d8%a7%d8%a8"),
            Pair("بوليسي", "%d8%a8%d9%88%d9%84%d9%8a%d8%b3%d9%8a"),
            Pair("تاريخي", "%d8%aa%d8%a7%d8%b1%d9%8a%d8%ae%d9%8a"),
            Pair("جنون", "%d8%ac%d9%86%d9%88%d9%86"),
            Pair("جوسي", "%d8%ac%d9%88%d8%b3%d9%8a"),
            Pair("حربي", "%d8%ad%d8%b1%d8%a8%d9%8a"),
            Pair("حريم", "%d8%ad%d8%b1%d9%8a%d9%85"),
            Pair("خارق للعادة", "%d8%ae%d8%a7%d8%b1%d9%82-%d9%84%d9%84%d8%b9%d8%a7%d8%af%d8%a9"),
            Pair("خيال علمي", "%d8%ae%d9%8a%d8%a7%d9%84-%d8%b9%d9%84%d9%85%d9%8a"),
            Pair("دراما", "%d8%af%d8%b1%d8%a7%d9%85%d8%a7"),
            Pair("رعب", "%d8%b1%d8%b9%d8%a8"),
            Pair("رومانسي", "%d8%b1%d9%88%d9%85%d8%a7%d9%86%d8%b3%d9%8a"),
            Pair("رياضي", "%d8%b1%d9%8a%d8%a7%d8%b6%d9%8a"),
            Pair("ساموراي", "%d8%b3%d8%a7%d9%85%d9%88%d8%b1%d8%a7%d9%8a"),
            Pair("سحر", "%d8%b3%d8%ad%d8%b1"),
            Pair("سينين", "%d8%b3%d9%8a%d9%86%d9%8a%d9%86"),
            Pair(
                "شريحة من الحياة",
                "%d8%b4%d8%b1%d9%8a%d8%ad%d8%a9-%d9%85%d9%86-%d8%a7%d9%84%d8%ad%d9%8a%d8%a7%d8%a9",
            ),
            Pair("شوجو", "%d8%b4%d9%88%d8%ac%d9%88"),
            Pair("شوجو اَي", "%d8%b4%d9%88%d8%ac%d9%88-%d8%a7%d9%8e%d9%8a"),
            Pair("شونين", "%d8%b4%d9%88%d9%86%d9%8a%d9%86"),
            Pair("شونين اي", "%d8%b4%d9%88%d9%86%d9%8a%d9%86-%d8%a7%d9%8a"),
            Pair("شياطين", "%d8%b4%d9%8a%d8%a7%d8%b7%d9%8a%d9%86"),
            Pair("غموض", "%d8%ba%d9%85%d9%88%d8%b6"),
            Pair("فضائي", "%d9%81%d8%b6%d8%a7%d8%a6%d9%8a"),
            Pair("فنتازيا", "%d9%81%d9%86%d8%aa%d8%a7%d8%b2%d9%8a%d8%a7"),
            Pair("فنون قتالية", "%d9%81%d9%86%d9%88%d9%86-%d9%82%d8%aa%d8%a7%d9%84%d9%8a%d8%a9"),
            Pair("قوى خارقة", "%d9%82%d9%88%d9%89-%d8%ae%d8%a7%d8%b1%d9%82%d8%a9"),
            Pair("كوميدي", "%d9%83%d9%88%d9%85%d9%8a%d8%af%d9%8a"),
            Pair(
                "محاكاة ساخرة",
                "%d9%85%d8%ad%d8%a7%d9%83%d8%a7%d8%a9-%d8%b3%d8%a7%d8%ae%d8%b1%d8%a9",
            ),
            Pair("مدرسي", "%d9%85%d8%af%d8%b1%d8%b3%d9%8a"),
            Pair("مصاصي دماء", "%d9%85%d8%b5%d8%a7%d8%b5%d9%8a-%d8%af%d9%85%d8%a7%d8%a1"),
            Pair("مغامرات", "%d9%85%d8%ba%d8%a7%d9%85%d8%b1%d8%a7%d8%aa"),
            Pair("موسيقي", "%d9%85%d9%88%d8%b3%d9%8a%d9%82%d9%8a"),
            Pair("ميكا", "%d9%85%d9%8a%d9%83%d8%a7"),
            Pair("نفسي", "%d9%86%d9%81%d8%b3%d9%8a"),
        ),
    )

    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =============================== Latest ===============================
    override fun latestUpdatesSelector(): String = "div.anime-list-content div.anime-card-container"

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/episode/page/$page/", headers)

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("img")!!.run {
            thumbnail_url = absUrl("src")
            title = attr("alt") + " (${element.select(".episodes-card-title h3").text()})"
        }
        setUrlWithoutDomain(element.select(".anime-card-details h3 a").attr("href"))
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Settings ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
