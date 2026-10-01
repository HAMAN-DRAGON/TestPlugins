package com.nxxhentai

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class NxxHentai : MainAPI() {
    override var mainUrl = "https://nxxhentai.net"
    override var name = "NxxHentai"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/anime/" to "قائمة الهنتاي",
        "$mainUrl/ecchi/" to "إيتشي",
        "$mainUrl/movies/" to "أفلام"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data
        else request.data.trimEnd('/') + "/page/$page/"

        val document = app.get(url).document
        val home = document.select("article, .anime-card, .post, .item, .card, h3 a[href*=/anime/]")
            .mapNotNull { el ->
                if (el.tagName() == "a") el.toSearchFromAnchor()
                else el.toSearchResult()
            }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchFromAnchor(): SearchResponse? {
        val href = fixUrl(this.attr("href"))
        if (!href.contains("/anime/") || href.contains("/page/")) return null
        val title = this.text().trim().ifBlank { this.attr("title").trim() }
        if (title.isBlank()) return null
        return newAnimeSearchResponse(title, href, TvType.NSFW)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href*=/anime/]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank() || href.contains("/page/")) return null

        var title = a.attr("title").trim()
        if (title.isBlank()) title = a.text().trim()
        if (title.isBlank()) title = this.selectFirst("h2, h3, .title, .entry-title")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        var poster: String? = null
        if (img != null) {
            poster = img.attr("abs:src")
            if (poster.isBlank()) poster = img.attr("abs:data-src")
            if (poster.isBlank()) poster = img.attr("abs:data-lazy-src")
            if (poster.isBlank()) poster = null
        }

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article, .anime-card, .post, .item, h3 a[href*=/anime/]")
            .mapNotNull { el ->
                if (el.tagName() == "a") el.toSearchFromAnchor()
                else el.toSearchResult()
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        var title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = document.title().substringBefore("-").substringBefore("|").trim()
        }

        val poster = document.selectFirst("img.wp-post-image, .poster img, .thumb img, article img")
            ?.attr("abs:src")

        val description = document.selectFirst(".entry-content p, .description p, .synopsis, article p")
            ?.text()?.trim()

        // حلقات من روابط /episodes/
        val episodes = document.select("a[href*=/episodes/]").mapNotNull { a ->
            val href = fixUrl(a.attr("href"))
            val text = a.text().trim().ifBlank { a.attr("title").trim() }
            if (href.isBlank()) return@mapNotNull null

            val epNum = Regex("""الحلقة\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(text)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""الحلقة\s*0*(\d+)""", RegexOption.IGNORE_CASE).find(href)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""0*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false
        var index = 1
        val links = linkedSetOf<String>()

        // iframes
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            var u = iframe.attr("abs:src").trim()
            if (u.isBlank()) u = iframe.attr("abs:data-src").trim()
            if (u.isNotBlank()) links.add(u)
        }

        // data attributes شائعة للسيرفرات
        document.select("[data-src], [data-url], [data-embed], [data-link], [data-video]").forEach { el ->
            listOf("data-src", "data-url", "data-embed", "data-link", "data-video").forEach { attr ->
                val v = el.attr(attr).trim()
                if (v.startsWith("http")) links.add(v)
            }
        }

        // روابط من مصدر الصفحة
        val html = document.html()
        Regex("""https?://[^\s"'<>]+""").findAll(html).forEach { m ->
            val u = m.value
            if (u.contains("embed", true) ||
                u.contains("/e/") ||
                u.contains("streamhg", true) ||
                u.contains("streamtape") ||
                u.contains("voe.sx") ||
                u.contains("mega.nz") ||
                u.contains("player", true)
            ) {
                if (!u.contains("oembed") && !u.contains("wp-json") && !u.contains("cloudflare")) {
                    links.add(u)
                }
            }
        }

        for (embedUrl in links) {
            if (embedUrl.contains("youtube") || embedUrl.contains("facebook")) continue

            val name = "Server $index"
            index++

            if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) {
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
