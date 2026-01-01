package com.lagradost.cloudstream3.movieproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
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
import android.util.Base64
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.stormunblessed.StreamedInfo
import org.mozilla.javascript.Context
import java.net.URL
import java.util.Calendar


data class La14HDMatchInfo(
    val category: String,
    val link: String,
    val title: String,
    val time: String,
    val status: String,
    val language: String,
    val date: String
)

enum class SiteKey {
    RUSTICO,
    FUTBOLLIBRE,
    LA14HD,
}

data class Site(
    val key: SiteKey,
    val mainUrl: String,
    val agendaUrl: String
)

class DeporTVProvider : MainAPI() {
    override var mainUrl = ""

    val sites: List<Site> =
        listOf(
            Site(SiteKey.RUSTICO, "https://rusticotv.top", "https://rusticotv.top/agenda.html"),
            Site(
                SiteKey.FUTBOLLIBRE,
                "https://futbollibre-tv.su",
                "https://futbollibre-tv.su/es/agenda/"
            ),
            Site(
                SiteKey.LA14HD,
                "https://la14hd.com",
                "https://la14hd.com/eventos/json/agenda123.json"
            ),
        )
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
        val agendaData = sites.amap {
            val url = it.agendaUrl
            val res = app.get(url)
            var events: List<EventData> = emptyList()
            if (it.key.equals(SiteKey.LA14HD)) {
                events = AppUtils.tryParseJson<List<La14HDMatchInfo>>(res.text)
                    ?.map {
                        EventData(
                            it.title,
                            transformHourToLocal(it.time, "GMT-5"),
                            listOf(it.link),
                            ""
                        )
                    } ?: emptyList()
            } else {
                events = res.document.select(".menu > li")
                    .mapNotNull { it.rusticoToEventData(url) }
            }
            events
        }.flatten();

        val mergedEvents: List<EventData> = agendaData
            .groupBy { it.title.substringAfter(":").trim() }
            .amap { (title, events) ->
                val date = transformHourToDate(events.first().hour) ?: Date()
                val posterUrl =
                    streamedInfo.searchPosterByDateAndTitle(date, title) ?: defaultPoster
                EventData(
                    title = title,
                    hour = events.first().hour,
                    urls = events.flatMap { it.urls }.distinct(),
                    poster = posterUrl
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

    private fun Element.rusticoToEventData(mainUrl: String): EventData? {
        val titleElement = this.selectFirst("a")
        val matchTitle = titleElement?.ownText() ?: ""
        if (matchTitle.startsWith("Zapping Sports"))
            return null
        val hour = titleElement?.selectFirst("span")?.text() ?: "00:00"
        val hourLocal = transformHourToLocal(hour, "GMT+1")
        val urls = this.select("ul li").mapNotNull {
            it.selectFirst("a")?.attr("href")?.replaceFirst("^/".toRegex(), "$mainUrl/")
        }
        return EventData(matchTitle, hourLocal, urls, "")
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

    fun String.replaceLast(oldValue: String, newValue: String): String {
        val lastIndex = this.lastIndexOf(oldValue)
        return if (lastIndex == -1) {
            this // nothing to replace
        } else {
            this.substring(0, lastIndex) + newValue + this.substring(lastIndex + oldValue.length)
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
            val frame = if (it.contains("?r=")) {
                base64Decode(
                    it.substringAfter("?r=")
                ).replaceFirst(
                    "https://vivolibre.org/global1.php?stream=",
                    "https://streamtpcloud.com/global1.php?stream="
                )
                    .replaceFirst(
                        "https://librefutbolhd.su/embed/canales.php?stream=",
                        "https://futbollibrelibre.com/canales.php?stream="
                    )
            } else it
            if (frame.contains("canales.php?stream=")) {
                // https://futbollibrelibre.com/canales.php?stream=
                // https://la14hd.com/vivo/canales.php?stream=
                val source = URL(frame).host
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
                            "${source}[$name]",
                            "${source}[$name]",
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
            } else if (frame.startsWith("https://rojadirectatve.com")) {
                val url = frame.substringAfter("?get=")
                val source = URL(url).host
                val name = url.substringAfter("/repro/").substringBefore(".html")
                app.get(url).document.selectFirst("iframe")?.attr("src")
                    ?.replaceFirst("//", "https://")?.let {
                        val lastFrameUrl = it
                        app.get(
                            lastFrameUrl,
                            referer = "https://rojadirectatve.com/",
                            headers = mapOf("Sec-Fetch-Dest" to "iframe")
                        ).document.select("script")
                            .firstOrNull {
                                it.data().contains("eval(function(p,a,c,k,e,d)") && it.data()
                                    .contains("Clappr")
                            }?.let {
                                val script = getAndUnpack(it.data())
                                if (script.contains("src=")) {
                                    callback(
                                        newExtractorLink(
                                            "${source}[$name]",
                                            "${source}[$name]",
                                            script.substringAfter("src=\"")
                                                .substringBefore("\";")
                                        ) {
                                            this.quality = Qualities.Unknown.value
                                            this.referer = lastFrameUrl
                                        }
                                    )
                                }
                            }
                    }
            } else if (frame.startsWith("https://stgruber.world/cobo1.php?id=UNIVERSO")) {
                val source = URL(frame).host
                var result = app.get(
                    frame,
                    referer = "https://ww.futbollibre-tv.su/"
                ).document.select("script")
                    .first { it.data().contains("ConfiguracionCanales =") }?.data()
                    ?.substringAfter("ConfiguracionCanales = {")?.substringBefore("};")
                result = "{$result}"
                val json = result
                    .replace("url:", "\"url\":")
                    .replace("k1:", "\"k1\":")
                    .replace("k2:", "\"k2\":")
                    .replace(Regex(",\\s*\\}"), "}")
                var channels = AppUtils.tryParseJson<Map<String, StgruberChannelInfo>>(json)
                val channelId = frame.substringAfter("?id=")
                val channelinfo = channels?.get(channelId)
                if (channelinfo != null) {
                    if (channelinfo.url.contains("mpd")) {
                        val drmKidBytes = channelinfo.k1?.chunked(2)
                            ?.map { it.toInt(16).toByte() }
                            ?.toByteArray()
                        val drmKidBase64 = Base64.encodeToString(drmKidBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                        val drmKeyBytes = channelinfo.k2?.chunked(2)
                            ?.map { it.toInt(16).toByte() }
                            ?.toByteArray()
                        val drmKeyBase64 = Base64.encodeToString(drmKeyBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                        callback.invoke(
                            newDrmExtractorLink(
                                "${source}[$channelId]",
                                "${source}[$channelId]",
                                channelinfo.url,
                                ExtractorLinkType.DASH,
                                CLEARKEY_UUID
                            ){
                                this.quality = Qualities.Unknown.value
                                this.kid = drmKidBase64
                                this.key = drmKeyBase64
                            }
                        )
                    } else {
                        callback(
                            newExtractorLink(
                                "${source}[$channelId]",
                                "${source}[$channelId]",
                                channelinfo.url
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }
        }
        return true
    }
}

data class StgruberChannelInfo(
    val url: String,
    val k1: String?,
    val k2: String?
)


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

fun transformHourToDate(hourString: String): Date? {
    val inputFormat = SimpleDateFormat("HH:mm", Locale.US)
    inputFormat.timeZone = TimeZone.getDefault()
    val parsedTime = inputFormat.parse(hourString) ?: return null
    val calendarToday = Calendar.getInstance()
    val calendarParsed = Calendar.getInstance().apply { time = parsedTime }
    calendarToday.set(Calendar.HOUR_OF_DAY, calendarParsed.get(Calendar.HOUR_OF_DAY))
    calendarToday.set(Calendar.MINUTE, calendarParsed.get(Calendar.MINUTE))
    calendarToday.set(Calendar.SECOND, 0)
    calendarToday.set(Calendar.MILLISECOND, 0)
    return calendarToday.time
}

fun transformHourToLocal(hourString: String, timezoneId: String): String {
    val inputFormat = SimpleDateFormat("HH:mm", Locale.US)
    inputFormat.timeZone = TimeZone.getTimeZone(timezoneId)
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
    return now.timeInMillis in (startCal.timeInMillis - fiveMinInMiliseconds)..endCal.timeInMillis
}

