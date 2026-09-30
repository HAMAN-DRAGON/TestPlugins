package com.ristohentai

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class RistoHentai : MainAPI() {
    override var mainUrl = "https://ristohentai.com"
    override var name = "RistoHentai"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/series/" to "Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/series/?offset=$page"
        val document = app.get(url).document
        val home = document.select("div.MovieItem").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank() || !href.contains("/series/")) return null

        val title = this.selectFirst("div.title h4, h4")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null

        val style = this.selectFirst("div.poster, .poster")?.attr("style") ?: ""
        val poster = Regex("url\\((['\"]?)(.*?)\\1\\)").find(style)?.groupValues?.getOrNull(2)
            ?: Regex("url\\(&quot;(.*?)&quot;\\)").find(style)?.groupValues?.getOrNull(1)

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.MovieItem").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            if (href.isBlank()) return@mapNotNull null

            val title = el.selectFirst("div.title h4, h4")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: a.text().trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val style = el.selectFirst("div.poster, .poster")?.attr("style") ?: ""
            val poster = Regex("url\\((['\"]?)(.*?)\\1\\)").find(style)?.groupValues?.getOrNull(2)

            newAnimeSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("|").trim()

        val posterStyle = document.selectFirst("div.poster, .poster")?.attr("style") ?: ""
        val poster = document.selectFirst("img[src*=wp-content]")?.attr("abs:src")
            ?: Regex("url\\((['\"]?)(.*?)\\1\\)").find(posterStyle)?.groupValues?.getOrNull(2)

        val description = document.selectFirst("p")?.text()?.trim()

        val episodes = document.select("a[href]").mapNotNull { a ->
            val text = a.text().trim()
            val href = fixUrl(a.attr("href"))
            if (!href.contains("ristohentai.com")) return@mapNotNull null

            val epNum = Regex("(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            if (!text.contains("حلق") && !text.contains("episode", true)) return@mapNotNull null

            newEpisode(href) {
                this.name = text.ifBlank { "Episode $epNum" }
                this.episode = epNum
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }

        val finalEpisodes = if (episodes.isNotEmpty()) episodes else listOf(
            newEpisode(url) {
                this.name = "Episode 1"
                this.episode = 1
            }
        )

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
        val watchUrl = if (data.contains("/watch")) data else data.trimEnd('/') + "/watch/"
        val document = app.get(watchUrl).document
        val servers = document.select("li[data-watch]")
        var found = false

        for (li in servers) {
            val embedUrl = li.attr("data-watch").trim()
            if (embedUrl.isBlank()) continue
            if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }
}
