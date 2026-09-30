package com.ristohentai

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class RistoHentai : MainAPI() {
    override var mainUrl = "https://ristohentai.com"
    override var name = "RistoHentai"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/series/" to "كل المسلسلات",
        "$mainUrl/%D9%87%D9%86%D8%AA%D8%A7%D9%8A-%D8%A8%D8%AF%D9%88%D9%86-%D8%AD%D8%AC%D8%A8/" to "بدون حجب",
        "$mainUrl/" to "أحدث الحلقات"
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
        return document.select("div.MovieItem").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("div.PostTitle h1, .PostTitle")?.text()?.trim()
            ?: document.title().substringBefore("|").trim()

        val posterStyle = document.selectFirst("div.poster, .poster")?.attr("style") ?: ""
        val poster = document.selectFirst("img[src*=wp-content]")?.attr("abs:src")
            ?: Regex("url\\((['\"]?)(.*?)\\1\\)").find(posterStyle)?.groupValues?.getOrNull(2)

        val description = document.selectFirst(".story p, .Story p, p")?.text()?.trim()

        // حلقات من قائمة الحلقات فقط
        val episodes = document.select(".EpisodesList a, ul.EpisodesList a, .episodes a").mapNotNull { a ->
            val text = a.text().trim()
            val href = fixUrl(a.attr("href"))
            if (href.isBlank()) return@mapNotNull null

            val epNum = Regex("(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null

            newEpisode(href) {
                this.name = text.ifBlank { "الحلقة $epNum" }
                this.episode = epNum
            }
        }.distinctBy { it.episode }.sortedBy { it.episode }

        // إذا لم نجد قائمة حلقات (صفحة حلقة واحدة من البحث)
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
        val watchUrl = if (data.contains("/watch")) data else data.trimEnd('/') + "/watch/"
        val document = app.get(watchUrl).document
        val servers = document.select("li[data-watch]")
        var found = false

        for (li in servers) {
            val embedUrl = li.attr("data-watch").trim()
            if (embedUrl.isBlank()) continue

            val serverName = li.ownText().trim()
                .ifBlank { li.text().trim() }
                .replace(Regex("<.*?>"), "")
                .ifBlank { "Server" }

            // جرب المستخرجات المدمجة أولاً
            val extracted = loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            if (extracted) {
                found = true
            } else {
                // إن لم ينجح، أضف الرابط مباشرة كخيار
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = serverName,
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
