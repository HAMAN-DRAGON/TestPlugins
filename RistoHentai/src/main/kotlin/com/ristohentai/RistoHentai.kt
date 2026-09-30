package com.ristohentai

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class RistoHentai : MainAPI() {
    override var mainUrl = "https://ristohentai.com"
    override var name = "RistoHentai"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/series/" to "قائمة الهنتاي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data
        else "$mainUrl/series/?offset=$page"

        val document = app.get(url).document
        val home = document.select("div.MovieItem").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = a.attr("abs:href")
        if (href.isBlank()) return null

        val title = a.attr("title").ifBlank {
            this.selectFirst("h3, h2")?.text()
        }?.trim() ?: a.text().trim()

        if (title.isBlank()) return null

        val style = this.selectFirst("div.poster, .poster")?.attr("style") ?: ""
        val poster = Regex("""url\(["']?(.*?)["']?\)""").find(style)?.groupValues?.getOrNull(1)

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("\( mainUrl/?s= \){query.replace(" ", "+")}").document
        return document.select("div.MovieItem").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("|").trim()

        val poster = document.selectFirst("img[src*='wp-content']")?.attr("abs:src")
            ?: document.selectFirst("div.poster, .poster")?.attr("style")?.let {
                Regex("""url\(["']?(.*?)["']?\)""").find(it)?.groupValues?.getOrNull(1)
            }

        val description = document.selectFirst("p")?.text()?.trim()

        val episodes = document.select("a[href]").mapNotNull { a ->
            val text = a.text().trim()
            val href = a.attr("abs:href")
            if (!href.contains("ristohentai.com")) return@mapNotNull null

            val epNum = Regex("""الحلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""حلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            newEpisode(href) {
                this.name = text.ifBlank { "الحلقة $epNum" }
                this.episode = epNum
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }

        val finalEpisodes = episodes.ifEmpty {
            listOf(
                newEpisode(url) {
                    this.name = "الحلقة 1"
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = if (data.contains("/watch")) data
        else data.trimEnd('/') + "/watch/"

        val document = app.get(watchUrl).document
        val servers = document.select("li[data-watch]")
        var found = false

        servers.forEach { li ->
            val embedUrl = li.attr("data-watch").trim()
            if (embedUrl.isBlank()) return@forEach

            val serverName = li.ownText().trim().ifBlank { li.text().trim() }.ifBlank { "Server" }

            loadExtractor(embedUrl, mainUrl, subtitleCallback) { link ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = serverName,
                        url = link.url
                    ) {
                        this.referer = link.referer ?: mainUrl
                        this.quality = link.quality
                        this.type = link.type
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
                found = true
            }
        }

        return found
    }
}
