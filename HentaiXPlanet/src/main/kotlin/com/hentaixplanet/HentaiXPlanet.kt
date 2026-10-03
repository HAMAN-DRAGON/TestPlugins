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
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d9%87%d9%86%d8%aa%d8%a7%d9%8a/" to "قائمة الهنتاي",
        "$mainUrl/tag/%d8%a8%d8%af%d9%88%d9%86-%d8%ad%d8%ac%d8%a8/" to "بدون حجب",
        "$mainUrl/" to "الأحدث"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val document = app.get(url).document
        val home = document.select("article.thumb-block, .thumb-block").mapNotNull { it.toSearchResult() }
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

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.thumb-block, .thumb-block").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        var title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = document.title().substringBefore("|").substringBefore("–").trim()
        }

        var poster: String? = document.selectFirst("img.wp-post-image, .poster img, article img")
            ?.attr("abs:src")
        if (poster.isNullOrBlank()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        }

        var description: String? = document.selectFirst(".entry-content p, article p")?.text()?.trim()
        if (description.isNullOrBlank()) {
            description = document.selectFirst("meta[property=og:description]")?.attr("content")
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun hostName(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains("playmogo") || u.contains("dood") || u.contains("myvidplay") -> "DoodStream"
            u.contains("rubyvid") -> "RubyVid"
            u.contains("upn.") -> "UPN"
            u.contains("streamtape") -> "Streamtape"
            u.contains("voe.sx") -> "VOE"
            u.contains("mixdrop") -> "MixDrop"
            u.contains("savemavo") -> "SaveMavo"
            u.contains("hglamioz") -> "Hglamioz"
            else -> "Server"
        }
    }

    private fun priority(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("playmogo") || u.contains("dood") || u.contains("myvidplay") -> 10
            u.contains("rubyvid") -> 9
            u.contains("streamtape") -> 8
            u.contains("voe.sx") -> 7
            u.contains("mixdrop") -> 6
            u.contains("upn.") -> 5
            u.contains("savemavo") -> 2
            u.contains("hglamioz") -> 1
            else -> 3
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

        document.select("iframe").forEach { iframe ->
            var u = iframe.attr("abs:src").trim()
            if (u.isBlank()) u = iframe.attr("src").trim()
            if (u.isBlank()) u = iframe.attr("abs:data-src").trim()
            if (u.startsWith("//")) u = "https:$u"
            if (u.startsWith("http")) candidates.add(u)
        }

        val html = document.html()
        Regex("""https?://[^\s"'<>]+""").findAll(html).forEach { m ->
            val u = m.value
            val low = u.lowercase()
            if (low.contains("playmogo") ||
                low.contains("dood") ||
                low.contains("myvidplay") ||
                low.contains("rubyvid") ||
                low.contains("streamtape") ||
                low.contains("voe.sx") ||
                low.contains("mixdrop") ||
                low.contains("upn.") ||
                low.contains("savemavo") ||
                low.contains("hglamioz") ||
                low.contains("/e/") ||
                low.contains("embed")
            ) {
                if (!low.contains("oembed") &&
                    !low.contains("wp-json") &&
                    !low.contains("facebook") &&
                    !low.contains("youtube")
                ) {
                    candidates.add(u)
                }
            }
        }

        val sorted = candidates.sortedByDescending { priority(it) }

        for (embedUrl in sorted) {
            if (embedUrl.contains("mega.nz")) continue

            var finalUrl = embedUrl
            if (finalUrl.contains("playmogo") || finalUrl.contains("dood") || finalUrl.contains("myvidplay")) {
                finalUrl = finalUrl.replace("/d/", "/e/")
            }

            val name = hostName(finalUrl)

            try {
                if (loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            } catch (_: Exception) {
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = name,
                    url = finalUrl
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }
}
