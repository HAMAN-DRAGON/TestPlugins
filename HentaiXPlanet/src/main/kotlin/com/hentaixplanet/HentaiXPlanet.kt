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

    private fun extractEpNum(text: String, href: String): Int? {
        return Regex("""حلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""الحلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""حلقة\s*(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""الحلقة[_-](\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        var title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) {
            title = document.title().substringBefore("|").substringBefore("–").trim()
        }

        val poster = document.selectFirst("img.wp-post-image, .poster img, article img")
            ?.attr("abs:src")

        val description = document.selectFirst(".entry-content p, article p")
            ?.text()?.trim()

        // رابط "جميع حلقات ..."
        val categoryLink = document.select("a[href*=/category/]").firstOrNull { a ->
            val t = a.text()
            t.contains("جميع حلقات") || t.contains("كل الحلقات")
        }?.attr("abs:href")
            ?: document.select("a[href*=/category/]").firstOrNull()?.attr("abs:href")

        // كلمة مفتاحية من العنوان لتصفية الحلقات غير المرتبطة
        val key = Regex("""[A-Za-z][A-Za-z0-9\-]{2,}""").findAll(title)
            .map { it.value.lowercase() }
            .filter { it !in listOf("the", "and", "مترجم", "عربي", "هنتاي", "انمي") }
            .toList()
            .take(3)

        if (!categoryLink.isNullOrBlank()) {
            try {
                val catDoc = app.get(categoryLink).document
                val episodes = catDoc.select("article.thumb-block a, .thumb-block a").mapNotNull { a ->
                    val href = fixUrl(a.attr("href"))
                    var text = a.attr("title").trim()
                    if (text.isBlank()) text = a.text().trim()
                    if (href.isBlank()) return@mapNotNull null

                    // استبعاد حلقات مسلسلات أخرى مختلطة في التصنيف
                    if (key.isNotEmpty()) {
                        val blob = (text + " " + href).lowercase()
                        if (key.none { blob.contains(it) }) return@mapNotNull null
                    }

                    val epNum = extractEpNum(text, href) ?: return@mapNotNull null
                    newEpisode(href) {
                        this.name = "الحلقة $epNum"
                        this.episode = epNum
                    }
                }.distinctBy { it.episode }.sortedBy { it.episode }

                if (episodes.isNotEmpty()) {
                    return newAnimeLoadResponse(title, url, TvType.NSFW) {
                        this.posterUrl = poster
                        this.plot = description
                        addEpisodes(DubStatus.Subbed, episodes)
                    }
                }
            } catch (_: Exception) {
            }
        }

        val epNum = extractEpNum(title, url) ?: 1
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
            u.contains("mega.nz") -> "MEGA"
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
            u.contains("mega.nz") -> 0
            else -> 3
        }
    }

    private fun normalizeEmbed(url: String): String {
        var u = url.trim()
        if (u.contains("playmogo", true) || u.contains("dood", true) || u.contains("myvidplay", true)) {
            u = u.replace("/d/", "/e/")
        }
        return u
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

        // كل مشغّلات السيرفرات (حتى المخفية display:none)
        document.select(
            ".server-player-item iframe, .video-multi-players-container iframe, " +
                ".responsive-player iframe, iframe[src], iframe[data-src]"
        ).forEach { iframe ->
            var u = iframe.attr("abs:src").trim()
            if (u.isBlank()) u = iframe.attr("abs:data-src").trim()
            if (u.isNotBlank()) candidates.add(normalizeEmbed(u))
        }

        document.select("[data-embed], [data-watch], [data-url]").forEach { el ->
            listOf("data-embed", "data-watch", "data-url").forEach { attr ->
                var v = el.attr(attr).trim()
                if (v.startsWith("//")) v = "https:$v"
                if (v.startsWith("http")) candidates.add(normalizeEmbed(v))
            }
        }

        val html = document.html()
        Regex(
            """https?://(?:playmogo\.com|[\w.-]*dood[\w.-]*|myvidplay\.com|rubyvidhub\.com|streamtape\.com|voe\.sx|mixdrop[\w.-]*|[\w.-]*upn\.[\w.]+|savemavo\.com|sv\d+\.savemavo\.com|hglamioz\.com)/[^\s"'<>]+""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            candidates.add(normalizeEmbed(m.value))
        }

        val sorted = candidates.sortedByDescending { priority(it) }

        for (embedUrl in sorted) {
            if (embedUrl.contains("youtube") || embedUrl.contains("facebook")) continue
            if (embedUrl.contains("mega.nz")) continue
            if (embedUrl.contains("oembed") || embedUrl.contains("wp-json")) continue

            val name = hostName(embedUrl)

            try {
                if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            } catch (_: Exception) {
            }

            // إظهار المصدر حتى لو فشل المستخرج (أفضل من "لا روابط")
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
        return found
    }
}
