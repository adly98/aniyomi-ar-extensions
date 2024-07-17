package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

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
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
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
import kotlin.math.abs

class Test : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Test"

    override val baseUrl = "https://w.tuktokcinema.com"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.Block--Item, div.Small--Box"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.select("a").attr("title").let { editTitle(it, true) }
            thumbnail_url = element.select("img").attr(if (element.ownerDocument()!!.location().contains("?s=")) "src" else "data-src")
            setUrlWithoutDomain(element.select("a").attr("href"))
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "section.allseasonss div.Block--Item"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        val seasonsDOM = document.select(episodeListSelector())
        return if (seasonsDOM.isNullOrEmpty()) {
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "مشاهدة"
            }.let(::listOf)
        } else {
            document.select(episodeListSelector()).reversed().flatMap { season ->
                val seasonNum = season.select("h3").text()
                var seasonDoc = document
                if (seasonNum != document.selectFirst("div#mpbreadcrumbs a span:contains(الموسم)")!!.text()) {
                    seasonDoc = client.newCall(GET(season.selectFirst("a")!!.attr("href"))).execute().asJsoup()
                }
                seasonDoc.select("section.allepcont a").map { episode ->
                    SEpisode.create().apply {
                        setUrlWithoutDomain(episode.attr("href"))
                        name = seasonNum + " : الحلقة " + episode.select("div.epnum").text().filter { it.isDigit() }
                    }
                }
            }
        }
    }
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            genre = document.select("div.catssection li a").joinToString(", ") { it.text() }
            title = document.select("h1.post-title").text().let(::editTitle)
            author = document.select("ul.RightTaxContent li:contains(دولة) a").text()
            description = document.select("div.story").text().trim()
            status = SAnime.COMPLETED
            thumbnail_url = document.select("div.left div.image img").attr("src")
        }
    }

    // ============================ Video Links =============================
    override fun videoListSelector(): String = "ul li.server--item"

    override fun videoListRequest(episode: SEpisode): Request {
        val docHeaders = headers.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
            add("Referer", "$baseUrl/")
        }.build()

        return GET("$baseUrl${episode.url}watch/", headers = docHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoElements = document.select(videoListSelector())
        return if (videoElements.isNullOrEmpty()) {
            val new = client.newCall(GET(response.request.url.toString() + "watch/")).execute()
            Video("http://", new.toString(), "http://").let(::listOf)
        } else {
            document.select(videoListSelector()).parallelCatchingFlatMapBlocking {
                val url = it.absUrl("data-link")
                val txt = it.text()
                extractVideos(url, txt)
            }
        }
    }

    private fun extractVideos(url: String, server: String, customQuality: String? = null): List<Video> {
        return when {
            "tuktuk" in url -> {
                val newH = headers.newBuilder()
                    .add("X-Inertia", "true")
                    .add("X-Inertia-Partial-Component", "files/mirror/video")
                    .add("X-Inertia-Partial-Data", "streams")
                    .add("X-Inertia-Version", "933f5361ce18c71b82fa342f88de9634")
                    .build()
                val iframe = client.newCall(GET(url, newH)).execute()
                val allUrls = mutableListOf<List<String>>()
                LINKS_REGEX.findAll(iframe.body.string()).forEach {
                    allUrls.add(mutableListOf("https:" + it.groupValues[2].replace("\\\\", ""), it.groupValues[1]))
                }
                allUrls.parallelCatchingFlatMapBlocking {
                    extractVideos(it[0], it[1])
                }
            }
            "ok.ru" in url -> {
                OkruExtractor(client).videosFromUrl(url)
            }
            "Vidbom" in server || "Vidshare" in server || "Govid" in server -> {
                val newH = headers.newBuilder().add("Referer", baseUrl).build()
                VidBomExtractor(client).videosFromUrl(url, newH)
            }
            "dood" in server -> {
                DoodExtractor(client).videoFromUrl(url, "Dood: ${customQuality ?: "Mirror"}")?.let(::listOf) ?: emptyList()
            }
            "mp4" in server -> {
                Mp4uploadExtractor(client).videosFromUrl(url, headers)
            }
            "Upstream" in server || "streamwish" in server || "vidhide" in server -> {
                StreamWishExtractor(client, headers).videosFromUrl(url, server)
            }
            "mixdrop" in server -> {
                MixDropExtractor(client).videosFromUrl(url, "", customQuality?.let { "[$it] " } ?: "")
            }
            else -> emptyList()
        }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val preferredQuality = preferences.getString("preferred_quality", "1080")!!.toInt()

        return sortedWith(
            compareBy { video ->
                val videoQuality = video.quality.filter { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE
                abs(preferredQuality - videoQuality)
            },
        )
    }

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/?s=$query&page=$page", headers)
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val sectionFilter = filterList.find { it is SectionFilter } as SectionFilter
            val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
            val url = baseUrl.toHttpUrl().newBuilder()
            if (sectionFilter.state != 0) {
                url.addPathSegment(sectionFilter.toUriPart())
            } else if (genreFilter.state != 0) {
                url.addPathSegment("genre")
                url.addPathSegment(genreFilter.toUriPart())
            } else {
                throw Exception("من فضلك اختر قسم او تصنيف")
            }
            url.addQueryParameter("page", page.toString())
            GET(url.toString(), headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("يمكنك تصفح اقسام الموقع اذا كان البحث فارغ"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("التصنيفات تعمل اذا كان 'اقسام الموقع' على 'اختر' فقط"),
        GenreFilter(),
    )

    private class SectionFilter : PairFilter(
        "اقسام الموقع",
        arrayOf(
            Pair("اختر", ""),
            Pair("كل الافلام", "category/movies-33/"),
            Pair("افلام اجنبى", "category/movies-33/افلام-اجنبي/"),
            Pair("افلام انمى", "category/anime-6/افلام-انمي/"),
            Pair("افلام تركيه", "category/movies-33/افلام-تركي/"),
            Pair("افلام اسيويه", "category/movies-33/افلام-اسيوي/"),
            Pair("افلام هنديه", "category/movies-33/افلام-هندى/"),
            Pair("كل المسسلسلات", "category/series-9/"),
            Pair("مسلسلات اجنبى", "category/series-9/مسلسلات-اجنبي/"),
            Pair("مسلسلات انمى", "category/anime-6/انمي-مترجم/"),
            Pair("مسلسلات تركى", "category/series-9/مسلسلات-تركي/"),
            Pair("مسلسلات اسيوى", "category/series-9/مسلسلات-أسيوي/"),
            Pair("مسلسلات هندى", "category/series-9/مسلسلات-هندي/"),
        ),
    )

    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "اكشن", "مغامرة", "كرتون", "فانتازيا", "خيال-علمي", "رومانسي", "كوميدي", "عائلي", "دراما", "اثارة", "غموض", "جريمة", "رعب", "وثائقي",
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
    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent/page/$page/", headers)

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Utilities ===============================
    private fun editTitle(title: String, details: Boolean = false): String {
        return if (Regex("(?:فيلم|عرض)\\s(.*\\s[0-9]+)\\s(.+?)\\s").containsMatchIn(title)) {
            val titleGroup = Regex("(?:فيلم|عرض)\\s(.*\\s[0-9]+)\\s(.+?)\\s").find(title)!!
            val movieName = titleGroup.groupValues[1]
            val type = titleGroup.groupValues[2]
            movieName + if (details) " ($type)" else ""
        } else if (Regex("(?:مسلسل|برنامج|انمي)\\s(.+)\\sالحلقة\\s(\\d+)").containsMatchIn(title)) {
            val titleGroup = Regex("(?:مسلسل|برنامج|انمي)\\s(.+)\\sالحلقة\\s(\\d+)").find(title)!!
            val seriesName = titleGroup.groupValues[1]
            val epNum = titleGroup.groupValues[2]
            if (details) {
                "$seriesName (ep:$epNum)"
            } else if (seriesName.contains("الموسم")) {
                seriesName.split("الموسم")[0].trim()
            } else {
                seriesName
            }
        } else {
            title
        }.trim()
    }

    // =============================== Settings ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "144p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "144")
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
    companion object {
        // private val MIRRORS_REGEX by lazy { Regex("""label":"(.*?p).*?size":(.*?),.*?mirrors":\[(.*?)]}""")}
        private val LINKS_REGEX by lazy { Regex("driver\":\\s*\"(.*?)\",\\n*\\s*\"link\":\\s*\"(.*?)\"") }
    }
}
