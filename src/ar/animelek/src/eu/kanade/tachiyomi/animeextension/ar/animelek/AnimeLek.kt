package eu.kanade.tachiyomi.animeextension.ar.animelek

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.animelek.extractors.SharedExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeLek : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeLek"

    override val baseUrl = "https://animelek.me"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.slider-episode-container div.episodes-card-container"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val ahref = element.selectFirst("h3 a")!!
            setUrlWithoutDomain(ahref.attr("href"))
            title = ahref.text()
            thumbnail_url = element.selectFirst("img")?.attr("src")
        }
    }

    override fun popularAnimeNextPageSelector() = null

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.ep-card-anime-title-detail h3 a"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val text = element.text()
            name = text
            val epNum = text.filter { it.isDigit() }
            episode_number = when {
                epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
                else -> 1F
            }
        }
    }

    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    // ============================ Video Links =============================
    override fun videoListSelector() = "ul#episode-servers li.watch a"

    override fun videoListParse(response: Response) = videosFromElement(response.asJsoup())
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        document.select(videoListSelector()).parallelCatchingFlatMapBlocking {
            extractVideos(it.attr("data-ep-url"), it.text())
        }
    }

    private val multiServers by lazy { MultiServers(client, headers) }
    private val okRuExtractor by lazy { OkruExtractor(client) }
    private val dooDExtractor by lazy { DoodExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client, headers) }

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
            "ok.ru" in url -> {
                okRuExtractor.videosFromUrl(url)
            }
            "vadbam" in server -> {
                val newH = headers.newBuilder().add("Referer", baseUrl).build()
                vidBomExtractor.videosFromUrl(url, newH)
            }
            "dood" in server -> {
                dooDExtractor.videoFromUrl(url, "Dood: ${customQuality ?: "Mirror"}")?.let(::listOf)
                    ?: emptyList()
            }
            "mp4" in server -> {
                mp4uploadExtractor.videosFromUrl(url, headers)
            }
            "upstream" in server || "lulustream" in server || "streamwish" in server || "vidhide" in server -> {
                streamWishExtractor.videosFromUrl(url, server)
            }
            "mixdrop" in server -> {
                mixDropExtractor.videosFromUrl(url, customQuality?.let { "$it " } ?: "")
            }
            "streamtape" in server -> {
                StreamTapeExtractor(client).videoFromUrl(url)?.let(::listOf)
            }
            "krakenfiles" in server -> {
                val req = client.newCall(GET(url)).execute().asJsoup()
                val source = req.select("source").attr("src")
                Video(source, "Kraken: ${customQuality ?: "Mirror"}", source).let(::listOf)
            }
            else -> emptyList()
        }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    // TODO: Add search filters
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "li.page-item a[rel=next]"

    override fun searchAnimeSelector() = "div.anime-list-content div.anime-card-container"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/search/?s=$query&page=$page")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val infos = document.selectFirst("div.anime-container-infos")!!
            val datas = document.selectFirst("div.anime-container-data")!!
            thumbnail_url = infos.selectFirst("img")!!.attr("src")
            title = datas.selectFirst("h1")!!.text()
            genre = datas.select("ul li > a").joinToString { it.text() }
            status = infos.selectFirst("div.full-list-info:contains(حالة الأنمي) a")?.text()?.let {
                when {
                    it.contains("يعرض الان") -> SAnime.ONGOING
                    it.contains("مكتمل", true) -> SAnime.COMPLETED
                    else -> null
                }
            } ?: SAnime.UNKNOWN

            artist = document.selectFirst("div:contains(المخرج) > span.info")?.text()

            description = buildString {
                append(datas.selectFirst("p.anime-story")!!.text() + "\n\n")

                infos.select("div.full-list-info").forEach {
                    append(it.text() + "\n")
                }
            }
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = searchAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episode/?page=$page")

    override fun latestUpdatesSelector() = "div.episodes-list-content div.episodes-card-container"

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
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

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape")
    }
}
