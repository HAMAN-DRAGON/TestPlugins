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
        "$mainUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d9%87%d9%86%d8%aa%d8%a7%d9%8a/" to "قائمة الهنتاي",
        "$mainUrl/tag/%d8%a8%d8%af%d9%88%d9%86-%d8%ad%d8%ac%d8%a8/" to "بدون حجب",
        "$mainUrl/" to "الأحدث"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val document = app.get(url).document
        val home = document.select("article.thumb-block, .thumb-block, article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank()) return null
        var title = a.attr("title").trim()
        if (title.isBlank()) title = this.selectFirst("h2, h3, .title")?.text()?.trim().orEmpty()
        if (title.isBlank()) title = a.text().trim()
        if (title.isBlank()) return null
        val img = this.selectFirst("img")
        var poster: String? = null
        if (img != null) {
            poster = img.attr("abs:src")
            if (poster.isBlank()) poster = img.attr("abs:data-src")
            if (poster.isBlank()) poster = null
        }
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.thumb-block, .thumb-block, article").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        var title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) title = document.title().substringBefore("|").substringBefore("–").trim()

        val poster = document.selectFirst("img.wp-post-image, .poster img, article img")?.attr("abs:src")
        val description = document.selectFirst(".entry-content p, article p")?.text()?.trim()

        // إن كانت صفحة حلقة مفردة، حاول صفحة التصنيف "جميع حلقات"
        val seriesLink = document.select("a").firstOrNull { a ->
            val t = a.text()
            t.contains("جميع حلقات") || t.contains("كل الحلقات")
        }?.attr("abs:href")

        val episodeDoc = if (!seriesLink.isNullOrBlank()) {
            try { app.get(seriesLink).document } catch (e: Exception) { document }
        } else document

        val episodes = episodeDoc.select("article.thumb-block a, .thumb-block a, a[href]").mapNotNull { a ->
            val href = fixUrl(a.attr("href"))
            val text = a.text().trim().ifBlank { a.attr("title").trim() }
            if (href.isBlank()) return@mapNotNull null
            if (!href.contains("hentaixplanet.com")) return@mapNotNull null
            val epNum = Regex("""حلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""الحلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""حلقة\s*(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
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

    private fun cleanUrl(url: String): String {
        var u = url.trim()
        if (u.contains("mega.nz")) u = u.removeSuffix(".html")
        return u
    }

    private fun hostName(url: String): String {
        return when {
            url.contains("mega.nz") -> "MEGA"
            url.contains("dood") -> "DoodStream"
            url.contains("streamtape") -> "Streamtape"
            url.contains("voe.sx") -> "VOE"
            url.contains("rubyvid") -> "RubyVid"
            url.contains("playmogo") -> "PlayMogo"
            url.contains("upn.") -> "UPN"
            url.contains("savemavo") -> "SaveMavo"
            url.contains("hglamioz") -> "Hglamioz"
            else -> "Server"
        }
    }

    private fun priority(url: String): Int {
        return when {
            url.contains("mega.nz") -> 10
            url.contains("dood") -> 9
            url.contains("streamtape") -> 8
            url.contains("voe.sx") -> 7
            url.contains("rubyvid") -> 6
            url.contains("playmogo") -> 5
            url.contains("upn.") -> 4
            url.contains("savemavo") -> 2
            url.contains("hglamioz") -> 1
            else -> 0
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
        val candidates = linkedSetOf<String>()

        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            var u = iframe.attr("abs:src").trim()
            if (u.isBlank()) u = iframe.attr("abs:data-src").trim()
            if (u.isNotBlank()) candidates.add(cleanUrl(u))
        }

        document.select("[data-src], [data-url], [data-embed], [data-watch]").forEach { el ->
            listOf("data-src", "data-url", "data-embed", "data-watch").forEach { attr ->
                var v = el.attr(attr).trim()
                if (v.startsWith("//")) v = "https:$v"
                if (v.startsWith("http")) candidates.add(cleanUrl(v))
            }
        }

        val sorted = candidates.sortedByDescending { priority(it) }

        for (embedUrl in sorted) {
            if (embedUrl.contains("youtube") || embedUrl.contains("facebook")) continue
            val name = hostName(embedUrl)

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
