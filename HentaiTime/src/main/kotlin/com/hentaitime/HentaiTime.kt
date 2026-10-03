package com.hentaitime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class HentaiTime : MainAPI() {
    override var mainUrl = "https://hentai-time.com"
    override var name = "HentaiTime"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/categories/" to "الأنميات",
        "$mainUrl/tag/uncensored/" to "بدون حجب",
        "$mainUrl/" to "الأحدث"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data
        else request.data.trimEnd('/') + "/page/$page/"
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

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
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
            title = document.title().substringBefore("-").substringBefore("|").trim()
        }

        val poster = document.selectFirst("img.wp-post-image, .poster img, article img, video[poster]")
            ?.attr("abs:src")
            ?: document.selectFirst("video")?.attr("abs:poster")

        val description = document.selectFirst(".entry-content p, .video-description, article p")
            ?.text()?.trim()

        // صفحة تصنيف = قائمة حلقات
        if (url.contains("/category/")) {
            val episodes = document.select("article.thumb-block a, .thumb-block a").mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                var text = a.attr("title").trim()
                if (text.isBlank()) text = a.text().trim()
                if (href.isBlank()) return@mapNotNull null

                val epNum = Regex("""(\d+)""").findAll(text).lastOrNull()?.value?.toIntOrNull()
                    ?: Regex("""(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null

                newEpisode(href) {
                    this.name = text.ifBlank { "الحلقة $epNum" }
                    this.episode = epNum
                }
            }.distinctBy { it.episode }.sortedBy { it.episode }

            return newAnimeLoadResponse(title, url, TvType.NSFW) {
                this.posterUrl = poster
                this.plot = description
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }

        // صفحة حلقة مفردة — حاول ربطها بالتصنيف إن وُجد
        val categoryLink = document.select("a[href*=/category/]").firstOrNull()?.attr("abs:href")
        if (!categoryLink.isNullOrBlank()) {
            try {
                val catDoc = app.get(categoryLink).document
                val episodes = catDoc.select("article.thumb-block a, .thumb-block a").mapNotNull { a ->
                    val href = fixUrl(a.attr("href"))
                    var text = a.attr("title").trim()
                    if (text.isBlank()) text = a.text().trim()
                    if (href.isBlank()) return@mapNotNull null
                    val epNum = Regex("""(\d+)""").findAll(text).lastOrNull()?.value?.toIntOrNull()
                        ?: Regex("""(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: return@mapNotNull null
                    newEpisode(href) {
                        this.name = text.ifBlank { "الحلقة $epNum" }
                        this.episode = epNum
                    }
                }.distinctBy { it.episode }.sortedBy { it.episode }

                if (episodes.isNotEmpty()) {
                    val seriesTitle = catDoc.selectFirst("h1")?.text()?.trim() ?: title
                    return newAnimeLoadResponse(seriesTitle, categoryLink, TvType.NSFW) {
                        this.posterUrl = poster
                        this.plot = description
                        addEpisodes(DubStatus.Subbed, episodes)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // حلقة واحدة
        val epNum = Regex("""(\d+)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(
                DubStatus.Subbed,
                listOf(
                    newEpisode(url) {
                        this.name = "الحلقة $epNum"
                        this.episode = epNum
                    }
                )
            )
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

        // فيديو مباشر في الصفحة
        document.select("video[src], source[src]").forEach { el ->
            val u = el.attr("abs:src").trim()
            if (u.isNotBlank()) candidates.add(u)
        }

        document.select("iframe[src], iframe[data-src]").forEach { iframe ->
            var u = iframe.attr("abs:src").trim()
            if (u.isBlank()) u = iframe.attr("abs:data-src").trim()
            if (u.isNotBlank()) candidates.add(u)
        }

        // كل روابط mp4 / مضيفات من HTML
        val html = document.html()
        Regex("""https?://[^\s"'<>]+""").findAll(html).forEach { m ->
            val u = m.value
            if (u.contains(".mp4") ||
                u.contains("850005.xyz") ||
                u.contains("embed", true) ||
                u.contains("/e/") ||
                u.contains("dood", true) ||
                u.contains("streamtape") ||
                u.contains("voe.sx") ||
                u.contains("playmogo")
            ) {
                if (!u.contains("oembed") && !u.contains("wp-json") && !u.contains(".jpg") && !u.contains(".png")) {
                    candidates.add(u)
                }
            }
        }

        for (embedUrl in candidates) {
            if (embedUrl.contains("youtube") || embedUrl.contains("facebook")) continue

            // MP4 مباشر
            if (embedUrl.contains(".mp4") || embedUrl.contains("850005.xyz")) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Direct MP4",
                        url = embedUrl
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
                continue
            }

            if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) {
                found = true
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Server",
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
