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
                if (el.tagName() == "a") el.toSearchFromAnchor() else el.toSearchResult()
            }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchFromAnchor(): SearchResponse? {
        val href = fixUrl(this.attr("href"))
        if (!href.contains("/anime/") || href.contains("/page/")) return null
        var title = this.text().trim()
        if (title.isBlank()) title = this.attr("title").trim()
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
                if (el.tagName() == "a") el.toSearchFromAnchor() else el.toSearchResult()
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
            var text = a.text().trim()
            if (text.isBlank()) text = a.attr("title").trim()
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

        val finalEpisodes = if (episodes.isNotEmpty()) {
            episodes
        } else {
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

    private fun normalizeEmbed(url: String): String {
        var u = url.trim()
        if (u.contains("dood", true) || u.contains("playmogo", true) || u.contains("myvidplay", true)) {
            u = u.replace("/d/", "/e/")
        }
        return u
    }

    private fun hostName(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains("mixdrop") -> "MixDrop"
            u.contains("dood") || u.contains("myvidplay") || u.contains("playmogo") -> "DoodStream"
            u.contains("streamtape") -> "Streamtape"
            u.contains("voe.sx") -> "VOE"
            u.contains("streamhg") || u.contains("sssrr") || u.contains("hgcloud") -> "StreamHG"
            u.contains("upn.") -> "UPN"
            u.contains("player.nxxhentai") -> "NxxPlayer"
            else -> "Server"
        }
    }

    private fun priority(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("mixdrop") -> 10
            u.contains("dood") || u.contains("myvidplay") || u.contains("playmogo") -> 9
            u.contains("streamtape") -> 8
            u.contains("voe.sx") -> 7
            u.contains("streamhg") || u.contains("sssrr") -> 6
            u.contains("upn.") -> 5
            else -> 3
        }
    }

    private fun isUsefulHost(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("youtube") || u.contains("facebook")) return false
        if (u.contains("nxxhentai.net/links/")) return false
        if (u.contains("nxxhentai.net/episodes/")) return false
        if (u.contains("mega.nz")) return false
        if (u.matches(Regex("""https?://[^/]*dood[^/]*/?"""))) return false
        return true
    }

    private suspend fun resolveRedirect(url: String): String? {
        return try {
            val res = app.get(url, allowRedirects = true, referer = mainUrl)
            val finalUrl = res.url
            if (finalUrl.startsWith("http") && isUsefulHost(finalUrl)) {
                return finalUrl
            }
            val doc = res.document
            val iframe = doc.selectFirst("iframe[src], iframe[data-src]")
            if (iframe != null) {
                var src = iframe.attr("abs:src")
                if (src.isBlank()) src = iframe.attr("abs:data-src")
                if (src.isNotBlank() && isUsefulHost(src)) return src
            }
            val html = doc.html()
            Regex(
                """https?://(?:[\w.-]*dood[\w.-]*|myvidplay\.com|playmogo\.com|mixdrop[\w.-]*|streamtape\.com|voe\.sx|streamhg[\w.-]*|[\w.-]*sssrr\.org)/[^\s"'<>]+""",
                RegexOption.IGNORE_CASE
            ).find(html)?.value
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
        val candidates = linkedSetOf<String>()

        // روابط التحميل /links/
        document.select("a[href*=/links/]").forEach { a ->
            val href = fixUrl(a.attr("href"))
            if (href.isBlank()) return@forEach
            val resolved = resolveRedirect(href)
            if (!resolved.isNullOrBlank()) {
                candidates.add(normalizeEmbed(resolved))
            }
        }

        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            var u = iframe.attr("abs:src").trim()
            if (u.isBlank()) u = iframe.attr("abs:data-src").trim()
            if (u.isNotBlank()) candidates.add(normalizeEmbed(u))
        }

        document.select("[data-embed], [data-watch], [data-url], [data-link]").forEach { el ->
            listOf("data-embed", "data-watch", "data-url", "data-link").forEach { attr ->
                var v = el.attr(attr).trim()
                if (v.startsWith("//")) v = "https:$v"
                if (v.startsWith("http") && !v.contains("wp-content/uploads")) {
                    candidates.add(normalizeEmbed(v))
                }
            }
        }

        val html = document.html()
        Regex(
            """https?://(?:[\w.-]*dood[\w.-]*|myvidplay\.com|playmogo\.com|mixdrop[\w.-]*|streamtape\.com|voe\.sx|streamhg[\w.-]*|player\.nxxhentai\.net)/[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            candidates.add(normalizeEmbed(m.value))
        }

        val sorted = candidates.sortedByDescending { priority(it) }

        for (embedUrl in sorted) {
            if (!isUsefulHost(embedUrl)) continue
            val name = hostName(embedUrl)

            try {
                if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            } catch (_: Exception) {
            }

            // إظهار المصادر المعروفة حتى لو فشل المستخرج
            if (embedUrl.contains("mixdrop", true) ||
                embedUrl.contains("dood", true) ||
                embedUrl.contains("myvidplay", true) ||
                embedUrl.contains("playmogo", true) ||
                embedUrl.contains("streamtape", true) ||
                embedUrl.contains("voe.sx", true) ||
                embedUrl.contains("/e/")
            ) {
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
