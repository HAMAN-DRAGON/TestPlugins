package com.hentaixplanet

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class HentaiXPlanet : MainAPI() {
    override var mainUrl = "https://hentaixplanet.com"
    override var name = "HentaiXPlanet"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/%D9%82%D8%A7%D8%A6%D9%85%D8%A9-%D8%A7%D9%84%D9%87%D9%86%D8%AA%D8%A7%D9%8A/" to "قائمة الهنتاي",
        "$mainUrl/tag/%D8%A8%D8%AF%D9%88%D9%86-%D8%AD%D8%AC%D8%A8/" to "بدون حجب",
        "$mainUrl/" to "الأحدث"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data
        else request.data.trimEnd('/') + "/page/$page/"

        val document = app.get(url).document
        val home = document.select("div.thumb-block").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank()) return null

        val title = a.attr("title").ifBlank {
            this.selectFirst(".title, h2, h3, .entry-title")?.text()
        }?.trim() ?: a.text().trim()

        if (title.isBlank()) return null

        val poster = this.selectFirst("img")?.let {
            it.attr("abs:src").ifBlank { it.attr("abs:data-src") }
                .ifBlank { it.attr("abs:data-lazy-src") }
        }

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.thumb-block").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore("–").substringBefore("|").trim()

        val poster = document.selectFirst("img.wp-post-image, .post-thumbnail img, article img")
            ?.attr("abs:src")

        val description = document.selectFirst(".entry-content p, .post-content p, article p")
            ?.text()?.trim()

        // إذا كانت صفحة تصنيف (series) نجلب الحلقات
        val episodes = if (url.contains("/category/")) {
            document.select("div.thumb-block").mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))
                val text = a.attr("title").ifBlank { a.text() }.trim()
                if (href.isBlank() || href.contains("/category/")) return@mapNotNull null

                val epNum = Regex("""حلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(href) {
                    this.name = text.ifBlank { "الحلقة $epNum" }
                    this.episode = epNum
                    this.posterUrl = el.selectFirst("img")?.attr("abs:src")
                }
            }.distinctBy { it.episode }.sortedBy { it.episode }
        } else {
            emptyList()
        }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false

        // السيرفرات داخل iframe
        val iframes = document.select("iframe[src], iframe[data-src]")
        var index = 1
        for (iframe in iframes) {
            val embedUrl = iframe.attr("abs:src").ifBlank {
                iframe.attr("abs:data-src")
            }.trim()
            if (embedUrl.isBlank()) continue
            if (embedUrl.contains("youtube") || embedUrl.contains("facebook")) continue

            val name = "Server $index"
            index++

            val extracted = loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            if (extracted) {
                found = true
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = name,
                        url = embedUrl
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        return found
    }
}
