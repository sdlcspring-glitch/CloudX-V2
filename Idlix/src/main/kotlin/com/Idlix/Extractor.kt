package com.idlix

import com.idlix.Idlix.ResponseSource
import com.idlix.Idlix.Tracks
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.extractors.Gdriveplayer
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import java.net.URLDecoder

class GodriveplayerNet : Gdriveplayer() {
    override val mainUrl = "https://godriveplayer.net"
}

open class GdriveplayerBase : ExtractorApi() {
    override var name = "Gdriveplayer"
    override var mainUrl = "https://gdriveplayer.to"
    override val requiresReferer = true

    protected open fun buildDirectUrl(url: String): String? {
        val target = Regex("""[?&]link=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.trim()
            .orEmpty()
        if (target.isBlank()) return null
        return if (target.contains("?")) "$target&res=default" else "$target?&res=default"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val directUrl = buildDirectUrl(url) ?: document.selectFirst("video")?.attr("src")
        if (!directUrl.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    name,
                    name,
                    directUrl,
                    ExtractorLinkType.VIDEO
                )
            )
        }
    }
}

class Gdriveplayerto : GdriveplayerBase() {
    override var mainUrl = "https://gdriveplayer.to"
}

class Gdplayerto : GdriveplayerBase() {
    override var name = "GDPlayer"
    override var mainUrl = "https://gdplayer.to"
}

class GodriveplayerCom : ExtractorApi() {
    override var name = "Godriveplayer"
    override var mainUrl = "https://godriveplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val serverLinks = document.select("ul.list-server-items li.linkserver[data-video]")
            .mapNotNull { item ->
                val serverUrl = item.attr("data-video").trim()
                if (serverUrl.isBlank()) null else serverUrl
            }
            .distinct()

        val orderedServers = buildList {
            fun addIfPresent(match: String) {
                serverLinks.firstOrNull { it.contains(match, true) }?.let { add(it) }
            }

            addIfPresent("short.icu")
            addIfPresent("gdriveplayer.to")
            addIfPresent("gdplayer.to")
            addIfPresent("filemoon")
            addIfPresent("emturbovid")
            addIfPresent("godriveplayer.net")
            serverLinks.forEach { if (it !in this) add(it) }
        }

        for (serverUrl in orderedServers) {
            try {
                loadExtractor(serverUrl, url, subtitleCallback, callback)
                loadExtractor(serverUrl, serverUrl, subtitleCallback, callback)
            } catch (_: Exception) {
                continue
            }
        }
    }
}

class Shorticu : StreamWishExtractor() {
    override val name = "Shorticu"
    override val mainUrl = "https://short.icu"
}

class Jeniusplay : ExtractorApi() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        val hash = url.split("/").last().substringAfter("data=")

        val m3uLink = app.post(
            url = "$mainUrl/player/index.php?data=$hash&do=getVideo",
            data = mapOf("hash" to hash, "r" to "$referer"),
            referer = referer,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsed<ResponseSource>().videoSource

        callback.invoke(
            newExtractorLink(
                name,
                name,
                url = m3uLink,
                ExtractorLinkType.M3U8
            )
        )

        document.select("script").map { script ->
            if (script.data().contains("eval(function(p,a,c,k,e,d)")) {
                val subData =
                    getAndUnpack(script.data()).substringAfter("\"tracks\":[").substringBefore("],")
                AppUtils.tryParseJson<List<Tracks>>("[$subData]")?.map { subtitle ->
                    subtitleCallback.invoke(
                        SubtitleFile(
                            getLanguage(subtitle.label ?: ""),
                            subtitle.file
                        )
                    )
                }
            }
        }
    }


    private fun getLanguage(str: String): String {
        return when {
            str.contains("indonesia", true) || str
                .contains("bahasa", true) -> "Indonesian"
            else -> str
        }
    }
}
