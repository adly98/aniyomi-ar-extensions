package eu.kanade.tachiyomi.animeextension.ar.xsanime

import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class XsAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "XS Anime"

    override val baseUrl = "https://ww.xsanime.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.block-post"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/anime_list/page/$page", headers)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("a").attr("title")
        thumbnail_url = element.selectFirst("img")!!.attr("data-img")
    }

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "#episodes a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        return if ("/movie/" in url) {
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "مشاهدة"
            }.let(::listOf)
        } else {
            document.select(episodeListSelector()).map(::episodeFromElement)
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.attr("title")
        episode_number = name.filter { it.isDigit() }.toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListSelector(): String = "div.servList li"
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoElements = document.select(videoListSelector()).toList()
            .map {
                Pair(
                    it.attr("data-embed").trim(),
                    it.select("span.server").text().trim().lowercase(),
                )
            }.distinct()
        return videoElements.parallelCatchingFlatMapBlocking { extractVideos(it.first, it.second) }
    }

    private val multiServers by lazy { MultiServers(client, headers) }
    private val okRuExtractor by lazy { OkruExtractor(client) }
    private val dooDExtractor by lazy { DoodExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client) }

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
                mixDropExtractor.videosFromUrl(url, customQuality?.let { "$it " } ?: "")
            }

            "mp4" in server -> {
                mp4uploadExtractor.videosFromUrl(url, headers)
            }

            "ok.ru" in url -> {
                okRuExtractor.videosFromUrl(url)
            }

            "voe" in server -> {
                voeExtractor.videosFromUrl(url)
            }

            "dood" in server -> {
                dooDExtractor.videoFromUrl(url, "Dood: ${customQuality ?: "Mirror"}")?.let(::listOf)
                    ?: emptyList()
            }

            else -> emptyList()
        }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime =
        latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query", headers)
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val categoryFilter = filterList.find { it is CategoryFilter } as CategoryFilter
            val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
            val url = baseUrl.toHttpUrl().newBuilder()
            if (categoryFilter.state != 0) {
                url.addPathSegment(categoryFilter.toUriPart())
            } else if (genreFilter.state != 0) {
                url.addPathSegment("anime_genre")
                url.addPathSegment(genreFilter.toUriPart())
            } else {
                throw Exception("من فضلك اختر قسم او تصنيف")
            }
            url.addPathSegment("page")
            url.addPathSegment(page.toString())
            GET(url.toString(), headers)
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.posterWrapper div.poster").attr("style")
            .substringAfter("url(").substringBefore(")")
        anime.title = document.select("li.item-current.item-archive").text()
        anime.genre = document.select("div.singleInfoCon ul:contains(التصنيف) a")
            .joinToString(", ") { it.text() }
        anime.description = document.select("div.singleInfo div.story p").text()
        document.select("div.singleInfoCon ul:contains(عدد الحلقات)").text().filter { it.isDigit() }
            .toIntOrNull().also { episodesNum ->
                val episodesCount = document.select("#episodes a").size + 1
                when {
                    episodesCount == episodesNum || episodesNum == null -> anime.status = SAnime.COMPLETED
                    episodesCount < episodesNum -> anime.status = SAnime.ONGOING
                    else -> anime.status = SAnime.UNKNOWN
                }
            }
        return anime
    }

    // =============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("يمكنك تصفح اقسام الموقع اذا كان البحث فارغ"),
        CategoryFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("التصنيفات تعمل اذا كان 'اقسام الموقع' على 'اختر' فقط"),
        GenreFilter(),
    )

    private class CategoryFilter : PairFilter(
        "الاقسام",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام", "movies_list"),
            Pair("مسلسلات", "anime_list"),
        ),
    )

    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "أكشن",
            "مغامرات",
            "خيال-علمي",
            "رومانسي",
            "كوميدي",
            "دراما",
            "لعبة",
            "مدرسي",
            "نفسي",
            "خارق-للطبيعة",
            "غموض",
            "شونين",
        ).sortedArray(),
    )

    open class SingleFilter(displayName: String, private val vals: Array<String>) :
        AnimeFilter.Select<String>(displayName, vals) {
        fun toUriPart() = vals[state]
    }

    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        val url = element.select("a").attr("href")
            .replace("episode", "anime").split("-").dropLast(2).joinToString("-")
        setUrlWithoutDomain(url)
        title = element.select("a").attr("title")
        thumbnail_url = element.selectFirst("img")!!.attr("data-img")
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/episode/page/$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    // =============================== Settings ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred Quality"
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
        screen.addPreference(qualityPref)
    }
}
