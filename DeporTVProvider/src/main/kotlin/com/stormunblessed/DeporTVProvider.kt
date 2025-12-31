package com.lagradost.cloudstream3.movieproviders

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.stormunblessed.StreamedInfo
import org.mozilla.javascript.Context
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Calendar


class DeporTVProvider : MainAPI() {
    override var mainUrl = ""
    val mainUrls: List<String> = listOf("https://rusticotv.top", "https://futbollibre-tv.su")
    override var name = "DeporTV"
    override var lang = "mx"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Live
    )
    val streamedInfo: StreamedInfo = StreamedInfo()
    val defaultPoster = "https://new.tvpublica.com.ar/wp-content/uploads/2021/05/DeporTVOK.jpg"

    override val mainPage = mainPageOf(
        "es/agenda/" to "Agenda",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        streamedInfo.init()
        val agendaData = mainUrls.map {
            val url = it
            val document = app.get(
                url
                    .replaceFirst("https://rusticotv.top", "https://rusticotv.top/agenda.html")
                    .replaceFirst("https://futbollibre-tv.su", "https://futbollibre-tv.su/es/agenda/")
            ).document
            document.select(".menu > li")
                .mapNotNull { it.toEventData(url) }
        }.flatten();

        val mergedEvents: List<EventData> = agendaData
            .groupBy { it.title.substringAfter(":").trim() }
            .map { (title, events) ->
                EventData(
                    title = title,
                    hour = events.first().hour,
                    urls = events.flatMap { it.urls }.distinct(),
                    poster = events.first().poster
                )
            }.sortedBy { it.hour.substringBefore(":").toIntOrNull() }

        val live = mergedEvents.filter { isEventLive(it.hour) }
        val items = ArrayList<HomePageList>()
        items.add(
            HomePageList(
                name = "En Vivo",
                list = live.map { it.toSearchResult() },
                isHorizontalImages = true
            )
        )
        items.add(
            HomePageList(
                name = request.name,
                list = mergedEvents.map { it.toSearchResult() },
                isHorizontalImages = true
            )
        )
        return newHomePageResponse(
            list = items,
            hasNext = false
        )
    }

    private fun Element.toEventData(mainUrl: String): EventData? {
        val titleElement = this.selectFirst("a")
        val matchTitle = titleElement?.ownText() ?: ""
        if (matchTitle.startsWith("Zapping Sports"))
            return null
        val hour = titleElement?.selectFirst("span")?.text() ?: "00:00"
        val hourLocal = transformHourToLocal(hour)
        val urls = this.select("ul li").mapNotNull {
            it.selectFirst("a")?.attr("href")?.replaceFirst("^/".toRegex(), "$mainUrl/")
        }
        val posterUrl = streamedInfo.searchPosterByTitle(matchTitle) ?: defaultPoster
        return EventData(matchTitle, hourLocal, urls, posterUrl)
    }

    private fun EventData.toSearchResult(): SearchResponse {
        val title = "${this.hour} ${this.title}"
        val posterUrl = this.poster
        return newLiveSearchResponse(
            title,
            this.toJson(),
            TvType.Live
        ) {
            this.posterUrl = posterUrl
        }
    }


//    override suspend fun search(query: String): List<SearchResponse> {
//        val document = app.get("${mainUrl}/?s=$query").document
//        val results =
//            document.select("div.container div.card__cover").mapNotNull { it.toSearchResult() }
//        return results
//    }

    override suspend fun load(data: String): LoadResponse? {
        val eventData = AppUtils.tryParseJson<EventData>(data)
        if (eventData == null)
            return null
        return newLiveStreamLoadResponse(eventData.title, data, data) {
            this.posterUrl = eventData.poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val eventData = AppUtils.tryParseJson<EventData>(data)
        if (eventData == null)
            return false
        eventData.urls.amap {
            val frame = base64Decode(
                it.substringAfter("?r=")
            )
                .replaceFirst("https://vivolibre.org/global1.php?stream=", "https://streamtpcloud.com/global1.php?stream=")
                .replaceFirst("https://librefutbolhd.su/embed/canales.php?stream=", "https://futbollibrelibre.com/canales.php?stream=")
            Log.d("qwerty", "loadLinks: $frame")
            if (frame.startsWith("https://futbollibrelibre.com")) {
                val name = frame.substringAfter("?stream=")
                val url =
                    if (name.startsWith("evento"))
                        frame.replace("/canales.php?", "/tv/canal.php?")
                    else frame
                val doc = app.get(url, referer = url).document
                val link =
                    doc.select("script").firstOrNull { it.data().contains("var playbackURL = ") }
                        ?.data()
                        ?.substringAfter("var playbackURL = \"")?.substringBefore("\";")
                if (link != null)
                    callback(
                        newExtractorLink(
                            "${this.name}[$name]",
                            "${this.name}[$name]",
                            link,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
            } else if (frame.contains("global1.php?")) {
//              https://streamvv33.lat/global1.php?channel=
//                https://streamtpcloud.com/global1.php?stream=
                val source = URL(frame).host
                val chanelNameParameter = frame.substringAfter("global1.php?").substringBefore("=")
                val name = frame.substringAfter(".php?$chanelNameParameter=")
                val doc = app.get(frame).document
                var result =
                    doc.select("script").firstOrNull { it.html().contains("playbackURL=") }?.let {
                        var result = ""
                        val scriptContent = it.data().substringBefore("var p2pConfig=")
                        val rhino = Context.enter()
                        rhino.setInterpretedMode(true)
                        val scope = rhino.initStandardObjects()
                        try {
                            scope.put(
                                "atob",
                                scope,
                                object : org.mozilla.javascript.BaseFunction() {
                                    override fun call(
                                        cx: org.mozilla.javascript.Context,
                                        scope: org.mozilla.javascript.Scriptable,
                                        thisObj: org.mozilla.javascript.Scriptable,
                                        args: Array<out Any>
                                    ): Any {
                                        val str = args[0] as String
                                        val decoded =
                                            android.util.Base64.decode(str, Base64.DEFAULT)
                                        return String(decoded, Charsets.UTF_8)
                                    }
                                })
                            rhino.evaluateString(scope, scriptContent, "playbackURL", 1, null)
                            result = scope.get("playbackURL", scope).toString()
                        } finally {
                            rhino.close()
                        }
                        result
                    }
                if (!result.isNullOrEmpty()) {
                    callback(
                        newExtractorLink(
                            "${source}[$name]",
                            "${source}[$name]",
                            result,
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
        return true
    }
}

data class EventData(
    val title: String,
    val hour: String,
    val urls: List<String>,
    val poster: String,
)

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source[${link.source}]",
                    "$source[${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

fun fixHostsLinks(url: String): String {
    return url
        .replaceFirst("https://hglink.to", "https://streamwish.to")
        .replaceFirst("https://swdyu.com", "https://streamwish.to")
        .replaceFirst("https://cybervynx.com", "https://streamwish.to")
        .replaceFirst("https://dumbalag.com", "https://streamwish.to")
        .replaceFirst("https://mivalyo.com", "https://vidhidepro.com")
        .replaceFirst("https://dinisglows.com", "https://vidhidepro.com")
        .replaceFirst("https://dhtpre.com", "https://vidhidepro.com")
        .replaceFirst("https://filemoon.link", "https://filemoon.sx")
        .replaceFirst("https://sblona.com", "https://watchsb.com")
        .replaceFirst("https://lulu.st", "https://lulustream.com")
        .replaceFirst("https://uqload.io", "https://uqload.com")
        .replaceFirst("https://do7go.com", "https://dood.la")
}

fun transformHourToLocal(hourString: String): String {
    val inputFormat = SimpleDateFormat("HH:mm", Locale.US)
    inputFormat.timeZone = TimeZone.getTimeZone("GMT+1")
    val date = inputFormat.parse(hourString)
    val outputFormat = SimpleDateFormat("HH:mm", Locale.US)
    outputFormat.timeZone = TimeZone.getDefault() // current mobile timezone
    return outputFormat.format(date)
}

fun isEventLive(startHour: String): Boolean {
    val fiveMinInMiliseconds = 600000
    val sdf = SimpleDateFormat("HH:mm", Locale.US)
    sdf.timeZone = TimeZone.getDefault() // interpret in local phone timezone
    val parsedDate = sdf.parse(startHour)
    val now = Calendar.getInstance()
    val startCal = Calendar.getInstance()
    startCal.time = parsedDate
    startCal.set(Calendar.YEAR, now.get(Calendar.YEAR))
    startCal.set(Calendar.MONTH, now.get(Calendar.MONTH))
    startCal.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
    val endCal = startCal.clone() as Calendar
    endCal.add(Calendar.HOUR_OF_DAY, 2)
    return now.timeInMillis in (startCal.timeInMillis- fiveMinInMiliseconds)..endCal.timeInMillis
}

