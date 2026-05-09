package com.idlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.extractors.helper.AesHelper
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Idlix : MainAPI() {
    override var mainUrl = "https://idlixku.co"
    private var directUrl = mainUrl
    override var name = "Idlix"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured",
        "$mainUrl/trending/page/?get=movies" to "Trending Movies",
        "$mainUrl/trending/page/?get=tv" to "Trending TV Series",
        "$mainUrl/movie/page/" to "Movie Terbaru",
        "$mainUrl/tv/page/" to "TV Series Terbaru",
        "$mainUrl/network/amazon/page/" to "Amazon Prime",
        "$mainUrl/network/apple-tv/page/" to "Apple TV+ Series",
        "$mainUrl/network/disney/page/" to "Disney+ Series",
        "$mainUrl/network/HBO/page/" to "HBO Series",
        "$mainUrl/network/netflix/page/" to "Netflix Series",
    )

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun getPagedUrl(data: String, page: Int): String {
        val base = data.substringBefore("?")
        val query = data.substringAfter("?", "")
        return buildString {
            append(base)
            append(page)
            append("/")
            if (query.isNotBlank()) {
                append("?")
                append(query)
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val nonPaged = request.name == "Featured" && page <= 1
        val req = if (nonPaged) {
            app.get(request.data)
        } else {
            app.get(getPagedUrl(request.data, page))
        }
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        val home = (if (nonPaged) {
            document.select("div.items.featured article")
        } else {
            document.select("div.items.full article, div#archive-content article")
        }).mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperLink(uri: String): String {
        return when {
            uri.contains("/episode/") -> {
                var title = uri.substringAfter("$mainUrl/episode/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.getOrNull(1) ?: title
                "$mainUrl/tv/$title"
            }

            uri.contains("/season/") -> {
                var title = uri.substringAfter("$mainUrl/season/")
                title = Regex("(.+?)-season").find(title)?.groupValues?.getOrNull(1) ?: title
                "$mainUrl/tv/$title"
            }

            else -> {
                uri
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("h3 > a") ?: return null
        val title = anchor.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
        val href = getProperLink(anchor.attr("href"))
        val posterUrl = this.select("div.poster > img").attr("src")
        val quality = getQualityFromString(this.select("span.quality").text())
        val tvType = if (href.contains("/tv/")) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            this.quality = quality
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchQuery = URLEncoder.encode(query, "UTF-8")
        val req = app.get("$mainUrl/?s=$searchQuery")
        mainUrl = getBaseUrl(req.url)
        val document = req.document
        return document.select("div.result-item").mapNotNull {
            val anchor = it.selectFirst("div.title > a") ?: return@mapNotNull null
            val title = anchor.text().replace(Regex("\\(\\d{4}\\)"), "").trim()
            val href = getProperLink(anchor.attr("href"))
            val posterUrl = it.selectFirst("img")?.attr("src")
            val tvType = if (href.contains("/tv/")) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val request = app.get(url)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        val title =
            document.selectFirst("div.data > h1")?.text()?.replace(Regex("\\(\\d{4}\\)"), "")
                ?.trim().toString()
        val images = document.select("div.g-item")

        val poster = images
            .shuffled()
            .firstOrNull()
            ?.selectFirst("a")
            ?.attr("href")
            ?: document.select("div.poster > img").attr("src")
        val tags = document.select("div.sgeneros > a").map { it.text() }
        val year = Regex(",\\s?(\\d+)").find(
            document.select("span.date").text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val tvType = if (document.select("ul#section > li:nth-child(1)").text().contains("Episodes")
        ) TvType.TvSeries else TvType.Movie
         val description = if (tvType == TvType.Movie) 
            document.select("div.wp-content > p").text().trim() else 
            document.select("div.content > center > p:nth-child(3)").text().trim()
        val trailer = document.selectFirst("div.embed iframe")?.attr("src")
        val rating = document.selectFirst("span.dt_rating_vgs[itemprop=ratingValue]")
        ?.text()
        ?.toDoubleOrNull()
        val actors = document.select("div.persons > div[itemprop=actor]").map {
            Actor(it.select("meta[itemprop=name]").attr("content"), it.select("img").attr("src"))
        }
        val duration = document.selectFirst("div.extra span[itemprop=duration]")?.text()
                        ?.replace(Regex("\\D"), "")
                        ?.toIntOrNull() ?: 0
        val recommendations = document.select("#single_relacionados article").map {
            val recName = it.selectFirst("img")!!.attr("alt").replace(Regex("\\(\\d{4}\\)"), "")
            val recHref = it.selectFirst("a")!!.attr("href")
            val recPosterUrl = it.selectFirst("img")?.attr("src").toString()
            newMovieSearchResponse(recName,recHref,
                if (recHref.contains("/movie/")) TvType.Movie 
                    else TvType.TvSeries, false
            ) {
                this.posterUrl = recPosterUrl
            }
        }

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("ul.episodios > li").map {
                val href = it.select("a").attr("href")
                val name = fixTitle(it.select("div.episodiotitle > a").text().trim())
                val image = it.select("div.imagen > img").attr("src")
                val episode = it.select("div.numerando").text().replace(" ", "").split("-").last()
                    .toIntOrNull()
                val season = it.select("div.numerando").text().replace(" ", "").split("-").first()
                    .toIntOrNull()
                newEpisode(href)
                {
                        this.name=name
                        this.season=season
                        this.episode=episode
                        this.posterUrl=image
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.duration = duration
                this.tags = tags
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.duration = duration
                this.tags = tags
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                this.recommendations = recommendations
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val requestUrl = data.toMainDomainUrl()
        val request = app.get(requestUrl)
        directUrl = getBaseUrl(request.url)
        val document = request.document
        val scriptRegex = """window\.idlixNonce=['"]([a-f0-9]+)['"].*?window\.idlixTime=(\d+).*?""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val script = document.select("script:containsData(window.idlix)").toString()
        val match = scriptRegex.find(script)
        val idlixNonce = match?.groups?.get(1)?.value ?: ""
        val idlixTime = match?.groups?.get(2)?.value ?: ""

        document.select("ul#playeroptionsul > li").map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.amap { (id, nume, type) ->
            val json = app.post(
                url = "$directUrl/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax", "post" to id, "nume" to nume, "type" to type, "_n" to idlixNonce, "_p" to id, "_t" to idlixTime
                ),
                referer = requestUrl,
                headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<ResponseHash>() ?: return@amap
            val embedUrl = json.getEmbedUrl() ?: return@amap

            when {
                embedUrl.contains("godriveplayer.com/player.php", true) -> {
                    embedUrl.getGodriveServers().forEach { serverUrl ->
                        loadExtractor(serverUrl, embedUrl, subtitleCallback, callback)
                    }
                }
                !embedUrl.contains("youtube") -> {
                    loadExtractor(embedUrl, directUrl, subtitleCallback, callback)
                }
                else -> return@amap
            }

        }

        return true
    }

    private suspend fun String.getGodriveServers(): List<String> {
        val document = app.get(this, referer = directUrl).document
        val serverLinks = document.select("ul.list-server-items li.linkserver[data-video]")
            .mapNotNull { item ->
                item.attr("data-video")
                    .replace("&amp;", "&")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()

        return buildList {
            fun addIfPresent(match: String) {
                serverLinks.firstOrNull { it.contains(match, true) }?.let { add(it) }
            }

            addIfPresent("filemoon")
            addIfPresent("emturbovid")
            addIfPresent("short.icu")
            addIfPresent("gdriveplayer.to")
            addIfPresent("gdplayer.to")
            addIfPresent("godriveplayer.net")
            serverLinks.forEach { if (it !in this) add(it) }
        }
    }

    private fun String.toMainDomainUrl(): String {
        val uri = runCatching { URI(this) }.getOrNull() ?: return this
        val host = uri.host ?: return this
        if (host.equals(URI(mainUrl).host, true)) return this
        return when {
            host.endsWith("idlixku.com", true) || host.endsWith("idlixtv.com", true) -> {
                val query = uri.rawQuery?.let { "?$it" }.orEmpty()
                "$mainUrl${uri.rawPath.orEmpty()}$query"
            }
            else -> this
        }
    }

    private fun ResponseHash.getEmbedUrl(): String? {
        val key = key
        if (key.isNullOrBlank()) return embed_url.fixBloat().extractIframeSrc()

        val metrix = AppUtils.tryParseJson<AesData>(embed_url)?.m ?: return null
        val password = createKey(key, metrix).takeIf { it.isNotBlank() } ?: return null
        return AesHelper.cryptoAESHandler(embed_url, password.toByteArray(), false)
            ?.fixBloat()
            ?.extractIframeSrc()
    }

    private fun String.extractIframeSrc(): String {
        return Regex("""<iframe[^>]+src=['"]([^'"]+)""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?: this
    }

    private fun createKey(r: String, m: String): String {
        val rList = r.split("\\x").filter { it.isNotEmpty() }.toTypedArray()
        var n = ""
        var reversedM = m.split("").reversed().joinToString("")
        while (reversedM.length % 4 != 0) reversedM += "="
        val decodedBytes = try {
            base64Decode(reversedM)
        } catch (_: Exception) {
            return ""
        }
        val decodedM = String(decodedBytes.toCharArray())
        for (s in decodedM.split("|")) {
            try {
                val index = Integer.parseInt(s)
                if (index in rList.indices) {
                    n += "\\x" + rList[index]
                }
            } catch (_: Exception) {
            }
        }
        return n
    }

    private fun String.fixBloat(): String {
        return this.replace("\"", "").replace("\\", "")
    }

    data class ResponseSource(
        @JsonProperty("hls") val hls: Boolean,
        @JsonProperty("videoSource") val videoSource: String,
        @JsonProperty("securedLink") val securedLink: String?,
    )

    data class Tracks(
        @JsonProperty("kind") val kind: String?,
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String?,
    )

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null,
    )

    data class AesData(
        @JsonProperty("m") val m: String,
    )


}
