package com.hentaibalo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class HentaiBalo : MainAPI() {
    override var mainUrl = "https://hentaibalo.com"
    override var name = "HentaiBalo"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/series/" to "كل المسلسلات",
        "$mainUrl/%D9%87%D9%86%D8%AA%D8%A7%D9%8A-%D8%A8%D8%AF%D9%88%D9%86-%D8%AD%D8%AC%D8%A8/" to "بدون حجب",
        "$mainUrl/" to "الأحدث"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("/series/") && page > 1 -> "$mainUrl/series/?offset=$page"
            request.data == "$mainUrl/" && page > 1 -> "$mainUrl/?page=$page/"
            else -> request.data
        }
        val document = app.get(url).document
        val home = document.select("div.MovieItem").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank()) return null

        val title = this.selectFirst("div.title h4, h4")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null

        val style = this.selectFirst("div.poster, .poster")?.attr("style")
            ?: this.selectFirst("div.poster, .poster")?.attr("data-style")
            ?: ""
        val poster = Regex("url\\((['\"]?)(.*?)\\1\\)").find(style)?.groupValues?.getOrNull(2)
            ?: Regex("url\\(&quot;(.*?)&quot;\\)").find(style)?.groupValues?.getOrNull(1)
            ?: Regex("url\\((https?://[^)]+)\\)").find(style)?.groupValues?.getOrNull(1)

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
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("|").trim()

        val posterStyle = document.selectFirst("div.poster, .poster")?.attr("style") ?: ""
        val poster = document.selectFirst("img[src*=wp-content]")?.attr("abs:src")
            ?: Regex("url\\((['\"]?)(.*?)\\1\\)").find(posterStyle)?.groupValues?.getOrNull(2)

        val description = document.selectFirst("p")?.text()?.trim()

        // حلقات المسلسل فقط — بدون روابط عشوائية
        val episodes = document.select(".EpisodesList a, ul.EpisodesList a").mapNotNull { a ->
            val text = a.text().trim()
            val href = fixUrl(a.attr("href"))
            if (href.isBlank()) return@mapNotNull null

            val epNum = Regex("""الحلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""حلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""^(\d+)$""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            newEpisode(href) {
                this.name = "الحلقة $epNum"
                this.episode = epNum
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }

        val finalEpisodes = if (episodes.isNotEmpty()) episodes else listOf(
            newEpisode(url) {
                this.name = "الحلقة 1"
                this.episode = 1
            }
        )

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, finalEpisodes)
        }
    }

    private fun cleanEmbedUrl(url: String): String {
        var u = url.trim()
        // تنظيف روابط MEGA الشائعة على الموقع
        if (u.contains("mega.nz")) {
            u = u.removeSuffix(".html")
            if (u.contains("/embed/")) {
                u = u.replace("/embed/", "/file/")
            }
        }
        return u
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

        // رتّب السيرفرات: MEGA أولاً ثم الباقي
        val sorted = servers.sortedByDescending { li ->
            val u = li.attr("data-watch")
            when {
                u.contains("mega.nz") -> 3
                u.contains("savemavo") -> 2
                u.contains("streamtape") || u.contains("voe.sx") -> 1
                else -> 0
            }
        }

        for (li in sorted) {
            val raw = li.attr("data-watch").trim()
            if (raw.isBlank()) continue
            val embedUrl = cleanEmbedUrl(raw)

            if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) {
                found = true
            }
        }
        return found
    }
}
