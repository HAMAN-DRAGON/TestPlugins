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
        "$mainUrl/" to "الأحدث"
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
        if (title.isNullOrBlank()) return null
        val style = this.selectFirst("div.poster, .poster")?.attr("style") ?: ""
        val poster = Regex("url\\((['\"]?)(.*?)\\1\\)").find(style)?.groupValues?.getOrNull(2)
            ?: Regex("url\\(&quot;(.*?)&quot;\\)").find(style)?.groupValues?.getOrNull(1)
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.MovieItem").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            if (href.isBlank()) return@mapNotNull null
            var title = el.selectFirst("div.title h4, h4")?.text()?.trim().orEmpty()
            if (title.isBlank()) title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val style = el.selectFirst("div.poster, .poster")?.attr("style") ?: ""
            val poster = Regex("url\\((['\"]?)(.*?)\\1\\)").find(style)?.groupValues?.getOrNull(2)
            newAnimeSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        var title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) title = document.title().substringBefore("|").trim()

        val posterStyle = document.selectFirst("div.poster, .poster")?.attr("style") ?: ""
        val poster = document.selectFirst("img[src*=wp-content]")?.attr("abs:src")
            ?: Regex("url\\((['\"]?)(.*?)\\1\\)").find(posterStyle)?.groupValues?.getOrNull(2)

        val description = document.selectFirst("p")?.text()?.trim()

        val episodes = document.select(".EpisodesList a, ul.EpisodesList a").mapNotNull { a ->
            val text = a.text().trim()
            val href = fixUrl(a.attr("href"))
            if (href.isBlank()) return@mapNotNull null
            val epNum = Regex("""الحلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""حلقة\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""^(\d+)$""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
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
        if (u.contains("myvidplay") || u.contains("dood")) u = u.replace("/d/", "/e/")
        return u
    }

    private fun hostName(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains("voe.sx") -> "VOE"
            u.contains("streamtape") -> "Streamtape"
            u.contains("myvidplay") || u.contains("dood") -> "DoodStream"
            u.contains("abyss") -> "Abyss"
            u.contains("rubyvid") -> "RubyVid"
            u.contains("playmogo") -> "PlayMogo"
            u.contains("ristohentai.site") -> "RistoPlayer"
            u.contains("hglamioz") -> "Hglamioz"
            u.contains("mega.nz") -> "MEGA"
            else -> "Server"
        }
    }

    private fun priority(url: String): Int {
        val u = url.lowercase()
        return when {
            u.contains("voe.sx") -> 10
            u.contains("streamtape") -> 9
            u.contains("myvidplay") || u.contains("dood") -> 8
            u.contains("abyss") -> 6
            u.contains("rubyvid") -> 5
            u.contains("playmogo") -> 4
            u.contains("ristohentai.site") -> 2
            u.contains("hglamioz") -> 0
            u.contains("mega.nz") -> 0
            else -> 1
        }
    }

    private fun isSupportedHost(url: String): Boolean {
        val u = url.lowercase()
        if (u.contains("mega.nz")) return false
        if (u.contains("hglamioz")) return false
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = when {
            data.contains("/watch") -> data
            else -> data.trimEnd('/') + "/watch/"
        }

        var document = app.get(watchUrl).document
        var servers = document.select("li[data-watch]")

        // احتياطي: الصفحة بدون /watch/
        if (servers.isEmpty() && !data.contains("/watch")) {
            document = app.get(data).document
            servers = document.select("li[data-watch]")
        }

        var found = false
        val sorted = servers.sortedByDescending { priority(it.attr("data-watch")) }

        for (li in sorted) {
            val raw = li.attr("data-watch").trim()
            if (raw.isBlank()) continue
            if (!isSupportedHost(raw)) continue

            val embedUrl = cleanUrl(raw)
            val name = hostName(embedUrl)

            try {
                if (loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)) {
                    found = true
                    continue
                }
            } catch (_: Exception) {
            }

            // إبقاء المصادر القوية ظاهرة حتى لو فشل المستخرج مؤقتًا
            if (embedUrl.contains("voe.sx") ||
                embedUrl.contains("streamtape") ||
                embedUrl.contains("myvidplay") ||
                embedUrl.contains("dood") ||
                embedUrl.contains("abyss")
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
