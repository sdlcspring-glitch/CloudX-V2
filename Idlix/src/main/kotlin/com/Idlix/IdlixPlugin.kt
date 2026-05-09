package com.idlix

import com.lagradost.cloudstream3.extractors.FileMoonIn
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class IdlixPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Idlix())
        registerExtractorAPI(FileMoonIn())
        registerExtractorAPI(Emturbovid())
        registerExtractorAPI(GodriveplayerNet())
        registerExtractorAPI(Gdriveplayerto())
        registerExtractorAPI(Gdplayerto())
        registerExtractorAPI(GodriveplayerCom())
        registerExtractorAPI(Shorticu())
        registerExtractorAPI(Jeniusplay())
    }
}
