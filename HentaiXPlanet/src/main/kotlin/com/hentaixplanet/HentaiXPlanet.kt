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
        val home = document.select("article.thumb-block, article.loop-video")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank()) return null

        // تنظيف العنوان من &nbsp; والمسافات الزائدة
        var title = a.attr("title")
            .replace("\u00a0", " ")
            .replace("&nbsp;", " ")
            .trim()

        if (title.isBlank()) {
            title = this.selectFirst("header.entry-header span, .entry-header, .title, h2, h3, .entry-title")
                ?.text()
                ?.replace("\u00a0", " ")
                ?.trim()
                .orEmpty()
        }
        if (title.isBlank()) {
            title = a.text().replace("\u00a0", " ").trim()
        }
        if (title.isBlank()) return null

        // الصورة (الموقع يستخدم data-src بشكل أساسي)
        val img = this.selectFirst("img")
        var poster: String? = null
        if (img != null) {
            poster = img.attr("abs:data-src").takeIf { it.isNotBlank() }
                ?: img.attr("abs:src").takeIf { it.isNotBlank() }
                ?: img.attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
                ?: img.attr("abs:data-original").takeIf { it.isNotBlank() }
        }

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("\( mainUrl/?s= \){query.replace(" ", "+")}").document
        return document.select("article.thumb-block, article.loop-video")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        var title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.replace("\u00a0", " ")
            ?.trim()
            .orEmpty()

        if (title.isBlank()) {
            title = document.title()
                .substringBefore("–")
                .substringBefore("|")
                .substringBefore("-")
                .replace("\u00a0", " ")
                .trim()
        }

        val poster = document.selectFirst("img.wp-post-image, .post-thumbnail img, article img, .entry-content img")
            ?.let { img ->
                img.attr("abs:data-src").takeIf { it.isNotBlank() }
                    ?: img.attr("abs:src").takeIf { it.isNotBlank() }
                    ?: img.attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            }

        val description = document.selectFirst(".entry-content p, .post-content p, article .entry-content p")
            ?.text()
            ?.trim()

        // إذا كانت الصفحة تصنيف (series) → نجمع الحلقات
        val isCategory = url.contains("/category/") || 
                         document.select("article.thumb-block, article.loop-video").size > 3

        val episodes = if (isCategory) {
            document.select("article.thumb-block, article.loop-video").mapNotNull { el ->
                val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                val href = fixUrl(a.attr("href"))
                if (href.isBlank() || href.contains("/category/") || href.contains("/tag/")) return@mapNotNull null

                var text = a.attr("title")
                    .replace("\u00a0", " ")
                    .replace("&nbsp;", " ")
                    .trim()
                if (text.isBlank()) {
                    text = a.text().replace("\u00a0", " ").trim()
                }

                val epNum = Regex("""حلقة\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(?:ep|episode|حلقة)?\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null

                val epPoster = el.selectFirst("img")?.let { img ->
                    img.attr("abs:data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("abs:src").takeIf { it.isNotBlank() }
                }

                newEpisode(href) {
                    this.name = if (text.isNotBlank()) text else "الحلقة $epNum"
                    this.episode = epNum
                    this.posterUrl = epPoster
                }
            }.distinctBy { it.episode }.sortedBy { it.episode }
        } else {
            emptyList()
        }

        val finalEpisodes = if (episodes.isNotEmpty()) {
            episodes
        } else {
            // حلقة واحدة (صفحة الحلقة نفسها)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var found = false
        var index = 1

        // جمع كل الـ iframes (src و data-src)
        val iframes = document.select("iframe[src], iframe[data-src], iframe[data-lazy-src]")
        for (iframe in iframes) {
            var embedUrl = iframe.attr("abs:src").trim()
            if (embedUrl.isBlank()) embedUrl = iframe.attr("abs:data-src").trim()
            if (embedUrl.isBlank()) embedUrl = iframe.attr("abs:data-lazy-src").trim()
            if (embedUrl.isBlank()) continue

            // تجاهل يوتيوب وفيسبوك والإعلانات
            if (embedUrl.contains("youtube", true) ||
                embedUrl.contains("facebook", true) ||
                embedUrl.contains("doubleclick", true) ||
                embedUrl.contains("googlesyndication", true)
            ) continue

            val serverName = "Server $index"
            index++

            val extracted = loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            if (extracted) {
                found = true
            } else {
                // fallback مباشر
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
