package eu.kanade.tachiyomi.animeextension.ar.fastmovies

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FastMovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Fast Movies"

    override val baseUrl = "https://fastmovies.online"

    override val lang = "ar"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = ".item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a").attr("abs:href"))
            thumbnail_url = element.select("img").attr("src")
            title = element.select("img").attr("alt")
        }
    }

    override fun popularAnimeNextPageSelector(): String = "fill"

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode {
        val url = element.attr("abs:href")
        val season = url.split("/").dropLast(1).last()
        val title = element.select(".episode-title").text().split(" ")[1]
        val epNum = (title.toFloat() / 1000).toString().replace(".", "")
        return SEpisode.create().apply {
            name = "الموسم $season : الحلقة $title"
            this.episode_number = "$season.$epNum".toFloatOrNull() ?: 1f
            setUrlWithoutDomain(url)
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seasons = doc.select(seasonListSelector())
        return if (seasons.isEmpty()) {
            SEpisode.create().apply {
                name = "مشاهدة"
                setUrlWithoutDomain(doc.location())
            }.let(::listOf)
        } else {
            seasons.parallelCatchingFlatMapBlocking {
                val url = it.select("a").attr("abs:href")
                val sDoc = client.newCall(GET(url)).execute().asJsoup()
                sDoc.select(episodeListSelector()).map(::episodeFromElement)
            }.sortedByDescending { it.episode_number }
        }
    }

    override fun episodeListSelector(): String = ".list-group .episode-link"
    private fun seasonListSelector(): String = ".season-image-container"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val infoElement =
            document.selectFirst("script:containsData(animateText), script:containsData(typeWriter)")!!
                .data()
        return SAnime.create().apply {
            thumbnail_url = document.select(".card img").attr("src")
            description = when {
                "animateText" in infoElement -> infoElement.substringAfter("animateText('overview', '")
                    .substringBefore("',")

                else -> infoElement.substringAfter("const text = \"").substringBefore("\"")
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoListSelector(): String = ".button-group .btn-custom"
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        return doc.select(videoListSelector()).flatMap {
            val url = it.attr("href")
            if (".mp4" in url) {
                val quality = it.text().replace("(", "[").replace(")", "]")
                Video(url, quality, url).let(::listOf)
            } else {
                val vDoc = client.newCall(GET(url)).execute().asJsoup()
                val tracks = vDoc.select("track[label=\"Arabic\"]").map { track ->
                    Track(track.attr("abs:src"), "Arabic")
                }
                val playlist = vDoc.select("source").attr("src")
                playlistUtils.extractFromHls(playlist, subtitleList = tracks)
            }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = ".card"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder().add("query", query).build()
        return POST("$baseUrl/search", body = body, headers = headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a").attr("abs:href"))
            thumbnail_url = element.select("img").attr("src")
            title = element.select("h5").text()
        }
    }

    override fun searchAnimeNextPageSelector(): String = "fill"

    // =============================== Latest ===============================
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

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
