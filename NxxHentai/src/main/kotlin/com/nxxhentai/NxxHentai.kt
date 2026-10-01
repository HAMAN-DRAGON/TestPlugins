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
        if (title.isBlank()) {
            title = this.selectFirst("h2, h3, .title, .entry-title")?.text()?.trim().orEmpty()
        }
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

    private suspend fun resolveLink(url: String): String? {
        return try {
            val res = app.get(url, allowRedirects = true)
            val finalUrl = res.url
            if (finalUrl != url && finalUrl.startsWith("http") && !finalUrl.contains("nxxhentai.net/links/")) {
                return finalUrl
            }
            val doc = res.document
            val iframe = doc.selectFirst("iframe[src], iframe[data-src]")
            if (iframe != null) {
                var src = iframe.attr("abs:src")
                if (src.isBlank()) src = iframe.attr("abs:data-src")
                if (src.isNotBlank()) return src
            }
            val a = doc.selectFirst("a[href*=http]")
            if (a != null) {
                val href = a.attr("abs:href")
                if (href.isNotBlank() && !href.contains("nxxhentai.net/links/")) return href
            }
            null
        } catch (_: Exception) {
            null
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
        val candidates = linkedSetOf<Pair<String, String>>() // name to url

        // 1) روابط التحميل /links/
        document.select("a[href*=/links/]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            val label = a.text().trim().ifBlank { "Download $index" }
            if (href.isNotBlank()) {
                val resolved = resolveLink(href)
                if (!resolved.isNullOrBlank()) {
                    candidates.add(label to resolved)
                } else {
                    candidates.add(label to href)
                }
            }
        }

        // 2) أزرار/عناصر السيرفرات
        document.select(
            "[data-src], [data-url], [data-embed], [data-link], [data-video], [data-player], " +
                ".server, .servers li, .player-server, button[data-id], a[data-embed]"
        ).forEach { el ->
            val label = el.text().trim().take(30).ifBlank { "Server $index" }
            listOf("data-src", "data-url", "data-embed", "data-link", "data-video", "data-player", "href").forEach { attr ->
                var v = el.attr(attr).trim()
                if (v.startsWith("//")) v = "https:$v"
                if (v.startsWith("http")) {
                    candidates.add(label to v)
                }
            }
        }

        // 3) iframes
        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            var u = iframe.attr("abs:src").trim()
            if (u.isBlank()) u = iframe.attr("abs:data-src").trim()
            if (u.isNotBlank()) candidates.add("Iframe $index" to u)
        }

        // 4) من مصدر الصفحة
        val html = document.html()
        Regex("""https?://[^\s"'<>]+""").findAll(html).forEach { m ->
            val u = m.value
            if (
                u.contains("embed", true) ||
                u.contains("/e/") ||
                u.contains("streamhg", true) ||
                u.contains("streamtape") ||
                u.contains("voe.sx") ||
                u.contains("mega.nz") ||
                u.contains("upn.") ||
                u.contains("player", true)
            ) {
                if (!u.contains("oembed") && !u.contains("wp-json") && !u.contains("cloudflare")) {
                    candidates.add("Embed $index" to u)
                }
            }
        }

        for ((label, embedUrl) in candidates) {
            if (embedUrl.contains("youtube") || embedUrl.contains("facebook")) continue
            if (embedUrl.contains("nxxhentai.net/links/")) continue

            val name = label.ifBlank { "Server $index" }
            index++

            val ok = loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            if (ok) {
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
