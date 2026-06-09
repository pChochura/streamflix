package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.FilmanLoginServer
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

object FilmanCcProvider : Provider {

    override val name = "Filman.cc"
    override val baseUrl = "https://filman.cc"
    override val logo = "$baseUrl/favicon.ico"
    override val language = "pl"

    private var webViewResolver: WebViewResolver? = null
    private val loginServer = FilmanLoginServer()
    private val providerMutex = Mutex()
    private const val TAG = "FilmanCc"

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    private suspend fun getDocument(url: String, depth: Int = 0): Document {
        if (depth > 2) return Jsoup.parse("<html><body>Too many redirects/login attempts</body></html>")

        val resultDoc = try {
            val client = NetworkClient.default.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Referer", baseUrl)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseUrl = response.request.url.toString()
                val html = response.body?.string() ?: ""
                
                // Only trigger login when the server actually redirected us to /logowanie
                // (not just because the page has a "Zaloguj się" nav link)
                if (responseUrl.contains("/logowanie")) {
                    Log.d(TAG, "[Provider] Login redirect detected for $url")
                    triggerManualLogin(url, depth)
                } else if (!html.contains("cf-browser-verification") && !html.contains("Checking your browser") && !html.contains("Just a moment...")) {
                    Jsoup.parse(html).apply { setBaseUri(baseUrl) }
                } else {
                    launchWebViewBypass(url, depth)
                }
            } else {
                launchWebViewBypass(url, depth)
            }
        } catch (_: Exception) { 
            launchWebViewBypass(url, depth)
        }

        return resultDoc
    }

    private suspend fun launchWebViewBypass(url: String, depth: Int): Document {
        Log.d(TAG, "[Provider] Launching WebView Bypass for $url")
        val html = getResolver().get(url)
        Log.d(TAG, "[Provider] WebView Bypass finished for $url. HTML length: ${html.length}")
        
        if (html.contains("<body>Timeout</body>")) {
            Log.e(TAG, "[Provider] WebView Bypass TIMEOUT for $url")
        }
        
        if (html.contains("filman.cc/logowanie")) {
             return triggerManualLogin(url, depth)
        }
        
        return Jsoup.parse(html).apply { setBaseUri(baseUrl) }
    }

    private suspend fun triggerManualLogin(originalUrl: String, depth: Int): Document {
        Log.d(TAG, "[Provider] Launching QR Code Login Server")
        val credentials = loginServer.requestLogin()
        Log.d(TAG, "[Provider] Login server result: user=${credentials?.user}, launching TV WebView for Recaptcha")
        
        if (credentials != null) {
            val html = getResolver().get("$baseUrl/logowanie", forceVisible = true, credentials = credentials)
            Log.d(TAG, "[Provider] TV WebView finished, HTML length=${html.length}")
        }
        
        return getDocument(originalUrl, depth + 1)
    }

    override suspend fun getHome(): List<Category> = providerMutex.withLock {
        val doc = getDocument(baseUrl)
        val categories = mutableListOf<Category>()
        val processedContainers = mutableSetOf<org.jsoup.nodes.Element>()

        // Check if user is logged out to offer early login
        if (doc.selectFirst("a[href*=/logowanie], a:contains(Zaloguj)") != null) {
            categories.add(
                Category(
                    name = "Konto",
                    list = listOf(
                        Movie(
                            id = "login",
                            title = "Zaloguj się (Opcjonalnie)",
                            poster = logo
                        )
                    )
                )
            )
        }

        // 1. Featured Section
        val featuredContainer = doc.selectFirst("#featured, .featured, #slider, .slider, .owl-carousel, #home-slider")
        if (featuredContainer != null) {
            val featuredItems = parseItems(featuredContainer)
            if (featuredItems.isNotEmpty()) {
                categories.add(Category("Polecane", featuredItems))
                processedContainers.add(featuredContainer)
            }
        }

        // 2. Generic Header to List extraction
        val headers = doc.select("h1, h2, h3, h4, .title, .block-title")
        for (header in headers) {
            val title = header.text().trim()
            if (title.isBlank() || title.length > 50) continue

            var next = header.nextElementSibling()
            if (next == null) next = header.parent()?.nextElementSibling()

            var container: org.jsoup.nodes.Element? = null
            var count = 0
            while (next != null && count < 5) {
                if (next.id() == "item-list" || next.hasClass("item-list") || next.hasClass("row") || next.select(".movie-item, .film-item, .col-xs-6, .poster").isNotEmpty()) {
                    container = next
                    break
                }
                next = next.nextElementSibling()
                count++
            }

            if (container != null && !processedContainers.contains(container)) {
                val items = parseItems(container)
                if (items.isNotEmpty() && categories.none { it.name.equals(title, ignoreCase = true) }) {
                    categories.add(Category(title, items))
                    processedContainers.add(container)
                }
            }
        }

        // 3. Fallback if the generic logic didn't find specific categories
        if (categories.isEmpty() || categories.size == 1) {
            val moviesContainer = doc.select("#item-list, .item-list").firstOrNull()
            if (moviesContainer != null && !processedContainers.contains(moviesContainer)) {
                val movies = parseItems(moviesContainer).filterIsInstance<Movie>()
                if (movies.isNotEmpty() && categories.none { it.name.contains("Filmy", ignoreCase = true) }) {
                    categories.add(Category("Filmy na czasie", movies))
                    processedContainers.add(moviesContainer)
                }
            }

            val tvHeader = doc.select("h3").find { it.text().contains("SERIALE NA CZASIE", ignoreCase = true) }
            val tvContainer = tvHeader?.parent()?.select("div.row, div.item-list")?.find { it.select(".movie-item").isNotEmpty() }
                ?: doc.select("#item-list, .item-list").getOrNull(1)
            
            if (tvContainer != null && !processedContainers.contains(tvContainer)) {
                val tvShows = parseItems(tvContainer).filterIsInstance<TvShow>()
                if (tvShows.isNotEmpty() && categories.none { it.name.contains("Seriale", ignoreCase = true) }) {
                    categories.add(Category("Seriale na czasie", tvShows))
                    processedContainers.add(tvContainer)
                }
            }
        }

        return@withLock categories
    }

    private fun getMainContainer(doc: Document): org.jsoup.nodes.Element {
        val lists = doc.select("#item-list, .item-list, #results, .content-box")
        return if (lists.size > 1) {
            lists.maxByOrNull { it.select(".movie-item, .film-item, .col-xs-6, .poster").size } ?: doc
        } else {
            lists.firstOrNull() ?: doc
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre(id = "filmy", name = "Filmy"),
                Genre(id = "seriale", name = "Seriale")
            )
        }
        val url = "$baseUrl/search?phrase=${URLEncoder.encode(query, "UTF-8")}&page=$page"
        val doc = getDocument(url)
        val lists = doc.select("#item-list, .item-list, #results, .content-box")
        
        if (lists.isNotEmpty()) {
            val allItems = mutableListOf<AppAdapter.Item>()
            for (list in lists) {
                allItems.addAll(parseItems(list))
            }
            return allItems.distinctBy { 
                when (it) {
                    is Movie -> it.id
                    is TvShow -> it.id
                    else -> it.hashCode()
                }
            }
        }
        
        return parseItems(doc)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page == 1) "$baseUrl/filmy" else "$baseUrl/filmy?page=$page"
        val doc = getDocument(url)
        return parseItems(getMainContainer(doc)).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = if (page == 1) "$baseUrl/seriale" else "$baseUrl/seriale?page=$page"
        val doc = getDocument(url)
        return parseItems(getMainContainer(doc)).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        if (id == "login") {
            val credentials = loginServer.requestLogin()
            if (credentials != null) {
                getResolver().get("$baseUrl/logowanie", forceVisible = true, credentials = credentials)
                throw Exception("Zalogowano pomyślnie. Odśwież stronę główną.")
            } else {
                throw Exception("Logowanie anulowane.")
            }
        }
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        return getDocument(url).let { doc ->
            val title = doc.selectFirst("h1[itemprop=\"name\"]")?.text()?.replace(doc.selectFirst("h1 .flm-online-badge")?.text() ?: "", "")?.trim() ?: ""
            val overview = doc.selectFirst("#item-content p.description, p.description")?.text()?.trim()
            val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
                ?: doc.selectFirst("img.main-poster")?.let { it.attr("abs:data-src").ifBlank { it.attr("data-src").ifBlank { it.attr("abs:src").ifBlank { it.attr("src") } } } }
            
            val bannerStyle = doc.selectFirst("#item-headline")?.attr("style") ?: ""
            val banner = Regex("""url\(['"]?(.*?)['"]?\)""").find(bannerStyle)?.groupValues?.getOrNull(1)?.let { doc.absUrl(it) }

            var year: String? = null
            var rating: Double? = null
            var runtime: Int? = null

            doc.select(".flm-meta-item").forEach { item ->
                val icon = item.selectFirst(".flm-meta-icon")?.text() ?: ""
                val value = item.selectFirst(".flm-meta-value")?.text() ?: ""
                when {
                    icon.contains("📅") -> year = value.trim()
                    icon.contains("⭐") -> rating = value.trim().toDoubleOrNull()
                    icon.contains("⏱️") -> {
                        runtime = value.replace("min", "").trim().toIntOrNull()
                    }
                }
            }

            val genres = doc.select(".flm-genre-tags a.flm-genre-tag, .flm-genre-tags a[itemprop=\"genre\"]").map {
                Genre(
                    id = parsePathId(it.attr("href")),
                    name = it.text().trim()
                )
            }

            return@let Movie(
                id = id,
                title = title,
                overview = overview,
                released = year,
                runtime = runtime,
                rating = rating,
                poster = poster,
                banner = banner,
                genres = genres
            )
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        return getDocument(url).let { doc ->
            val title = doc.selectFirst("h1[itemprop=\"name\"]")?.text()?.replace(doc.selectFirst("h1 .flm-online-badge")?.text() ?: "", "")?.trim() ?: ""
            val overview = doc.selectFirst("#item-content p.description, p.description")?.text()?.trim()
            val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
                ?: doc.selectFirst("img.main-poster")?.let { it.attr("abs:data-src").ifBlank { it.attr("data-src").ifBlank { it.attr("abs:src").ifBlank { it.attr("src") } } } }
            
            val bannerStyle = doc.selectFirst("#item-headline")?.attr("style") ?: ""
            val banner = Regex("""url\(['"]?(.*?)['"]?\)""").find(bannerStyle)?.groupValues?.getOrNull(1)?.let { doc.absUrl(it) }

            var year: String? = null
            var rating: Double? = null

            doc.select(".flm-meta-item").forEach { item ->
                val icon = item.selectFirst(".flm-meta-icon")?.text() ?: ""
                val value = item.selectFirst(".flm-meta-value")?.text() ?: ""
                if (icon.contains("📅")) year = value.trim()
                if (icon.contains("⭐")) rating = value.trim().toDoubleOrNull()
            }

            val genres = doc.select(".flm-genre-tags a.flm-genre-tag, .flm-genre-tags a[itemprop=\"genre\"]").map {
                Genre(
                    id = parsePathId(it.attr("href")),
                    name = it.text().trim()
                )
            }

            val seasons = doc.select("#episode-list > li").mapIndexedNotNull { seasonIndex, seasonLi ->
                val seasonSpan = seasonLi.selectFirst("span") ?: return@mapIndexedNotNull null
                val seasonName = seasonSpan.text().trim()
                val seasonNumber = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull() ?: (seasonIndex + 1)
                
                val episodes = seasonLi.select("ul > li").mapNotNull { episodeLi ->
                    val anchor = episodeLi.selectFirst("a") ?: return@mapNotNull null
                    val epHref = anchor.attr("href")
                    val epText = anchor.text().trim()
                    
                    // Parse "[S01E05] Episode Name" format
                    val bracketMatch = Regex("""^\[S(\d+)E(\d+)\]\s*(.*)""", RegexOption.IGNORE_CASE).find(epText)
                    val epNumber: Int
                    val epTitle: String
                    
                    if (bracketMatch != null) {
                        epNumber = bracketMatch.groupValues[2].toIntOrNull() ?: 0
                        epTitle = bracketMatch.groupValues[3].trim().ifBlank { "Odcinek $epNumber" }
                    } else {
                        // Fallback: try to extract any episode number
                        epNumber = Regex("""[Ee](\d+)""").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: Regex("""\d+""").find(epText)?.value?.toIntOrNull()
                            ?: 0
                        epTitle = epText.replace(Regex("""^\[.*?\]\s*"""), "").trim().ifBlank { "Odcinek $epNumber" }
                    }
                    
                    Episode(
                        id = parsePathId(epHref),
                        number = epNumber,
                        title = epTitle,
                        poster = poster
                    )
                }.sortedBy { it.number }

                Season(
                    id = "$id|season|$seasonNumber",
                    number = seasonNumber,
                    title = seasonName,
                    poster = poster,
                    episodes = episodes
                )
            }.sortedBy { it.number }

            return@let TvShow(
                id = id,
                title = title,
                overview = overview,
                released = year,
                rating = rating,
                poster = poster,
                banner = banner,
                genres = genres,
                seasons = seasons
            )
        }
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("|season|")
        val showId = parts.getOrNull(0) ?: return emptyList()
        val seasonNumber = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        
        val tvShow = getTvShow(showId)
        return tvShow.seasons.find { it.number == seasonNumber }?.episodes.orEmpty()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = if (page == 1) "$baseUrl/$id" else "$baseUrl/$id?page=$page"
        val doc = getDocument(url)
        val name = doc.selectFirst("h1, h2")?.text()?.trim() ?: id.substringAfterLast("/")
        val shows = parseItems(getMainContainer(doc)).filterIsInstance<Show>()
        return Genre(
            id = id,
            name = name,
            shows = shows
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val url = if (page == 1) "$baseUrl/$id" else "$baseUrl/$id?page=$page"
        val doc = getDocument(url)
        val name = doc.selectFirst("h1, h2")?.text()?.trim() ?: id.substringAfterLast("/")
        val filmography = parseItems(getMainContainer(doc)).filterIsInstance<Show>()
        return People(
            id = id,
            name = name,
            filmography = filmography
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = if (id.startsWith("http")) id else "$baseUrl/$id"
        val doc = getDocument(url)
        
        val html = doc.outerHtml()
        val routeTokenRegex = """var routeToken\s*=\s*'([^']*)'""".toRegex()
        val matchResult = routeTokenRegex.find(html)
        val routeToken = matchResult?.groups?.get(1)?.value ?: ""
        if (routeToken.isBlank()) {
            Log.e(TAG, "Failed to extract routeToken")
            return emptyList()
        }

        val servers = mutableListOf<Video.Server>()
        val rows = doc.select("#link-list table#links tbody tr.version")
        
        rows.forEach { row ->
            val linkAnchor = row.selectFirst(".link-to-video a") ?: return@forEach
            val linkId = linkAnchor.attr("data-id").ifBlank { linkAnchor.attr("data-link-id") }
            if (linkId.isBlank()) return@forEach

            val nameImg = row.selectFirst("td img")
            val serverHost = nameImg?.attr("alt")?.trim() 
                ?: row.select("td").firstOrNull()?.text()?.trim() 
                ?: "Unknown"

            val version = row.select("td").getOrNull(1)?.text()?.trim().orEmpty()
            val quality = row.select("td").getOrNull(2)?.text()?.trim().orEmpty()

            val displayName = buildString {
                append(serverHost)
                if (version.isNotBlank()) append(" [$version]")
                if (quality.isNotBlank()) append(" ($quality)")
            }

            servers.add(Video.Server(
                id = linkId,
                name = displayName,
                src = "$linkId|$routeToken|$url"
            ))
        }

        return servers.sortedWith(compareByDescending<Video.Server> {
            it.name.contains("dood", ignoreCase = true)
        }.thenByDescending {
            it.name.contains("voe", ignoreCase = true)
        })
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val parts = server.src.split("|")
        if (parts.size < 2) {
            return if (server.src.startsWith("http")) Extractor.extract(server.src, server)
            else Video(source = "")
        }

        val linkId = parts[0]
        val routeToken = parts[1]
        val referer = parts.getOrNull(2) ?: baseUrl

        try {
            val ajaxUrl = "$baseUrl/link/token?link_id=$linkId&rt=$routeToken"
            val client = NetworkClient.default
            val request = Request.Builder()
                .url(ajaxUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", referer)
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val jsonObj = org.json.JSONObject(jsonStr)
                if (jsonObj.optBoolean("ok")) {
                    val encodedUrl = jsonObj.optString("url")
                    val decodedUrl = String(Base64.getDecoder().decode(encodedUrl), Charsets.UTF_8)
                    if (decodedUrl.startsWith("http")) {
                        val finalUrl = if (decodedUrl.contains("tmp-url.pro")) {
                            resolveTmpUrl(decodedUrl) ?: decodedUrl
                        } else {
                            decodedUrl
                        }
                        return Extractor.extract(finalUrl, server.copy(src = finalUrl))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching/decrypting server link: ${e.message}")
        }

        return Video(source = "")
    }

    private fun resolveTmpUrl(url: String): String? {
        try {
            val client = NetworkClient.default
            val request = Request.Builder()
                .url(url)
                .header("Referer", "https://filman.cc/")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                
                val eRegex = """var _e\s*=\s*'([^']*)'""".toRegex()
                val aRegex = """var _a\s*=\s*'([^']*)'""".toRegex()
                val bRegex = """var _b\s*=\s*'([^']*)'""".toRegex()
                val cRegex = """var _c\s*=\s*'([^']*)'""".toRegex()

                val e = eRegex.find(html)?.groups?.get(1)?.value ?: ""
                val a = aRegex.find(html)?.groups?.get(1)?.value ?: ""
                val b = bRegex.find(html)?.groups?.get(1)?.value ?: ""
                val c = cRegex.find(html)?.groups?.get(1)?.value ?: ""

                if (e.isNotEmpty() && a.isNotEmpty() && b.isNotEmpty() && c.isNotEmpty()) {
                    val key = a + b + c
                    val raw = Base64.getDecoder().decode(e)
                    val out = StringBuilder()
                    for (i in raw.indices) {
                        val charCode = (raw[i].toInt() and 0xFF) xor key[i % key.length].code
                        out.append(charCode.toChar())
                    }
                    val decrypted = out.toString().trim()
                    if (decrypted.startsWith("http")) {
                        return decrypted
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving tmp-url: ${e.message}")
        }
        return null
    }

    private fun parsePathId(url: String): String {
        return url
            .replace("https://filman.cc", "")
            .replace("http://filman.cc", "")
            .replace("https://www.filman.cc", "")
            .replace("http://www.filman.cc", "")
            .removePrefix("/").removeSuffix("/")
    }

    private fun parseItems(document: org.jsoup.nodes.Element): List<AppAdapter.Item> {
        // Use a single primary selector to avoid matching the same element multiple times.
        // On filman.cc, items are plain <div>s inside #item-list.
        val candidates = document.select("#item-list > div")
            .ifEmpty { document.select(".item-list > div") }
            .ifEmpty { document.select("#results > div") }
            .ifEmpty { document.select(".poster, .movie-item, .film-item, .col-xs-6") }

        // Deduplicate by element identity (same DOM node matched by multiple selectors)
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<org.jsoup.nodes.Element, Boolean>())
        
        return candidates.mapNotNull { el ->
            if (!seen.add(el)) return@mapNotNull null
            
            val anchor = el.selectFirst(".poster a, a:has(picture), a:has(img)") ?: el.selectFirst("a") ?: return@mapNotNull null
            val href = anchor.attr("href")
            if (href.isBlank()) return@mapNotNull null
            
            // filman.cc uses /m/{id} for movies and /s/{id} for TV shows
            val isMovie = href.contains("/m/") || href.contains("/film/")
            val isShow = href.contains("/s/") || href.contains("/serial/") || href.contains("/e/")
            if (!isMovie && !isShow) return@mapNotNull null
            
            val id = parsePathId(href)
            val title = el.selectFirst(".film_title, .title, h2, h3, h4")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
                ?: el.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: ""
            
            if (title.isEmpty()) return@mapNotNull null
            
            val year = el.selectFirst(".film_year, .year, .date")?.text()?.trim()
            
            val posterImg = anchor.selectFirst("picture source, picture img, img") ?: el.selectFirst("img")
            val posterUrl = posterImg?.let { 
                it.attr("abs:data-src").ifBlank { 
                    it.attr("data-src").ifBlank { 
                        it.attr("abs:src").ifBlank { 
                            it.attr("src") 
                        } 
                    } 
                } 
            } ?: ""

            val titleWithYear = if (!year.isNullOrEmpty() && !title.contains(year)) "$title ($year)" else title

            if (isMovie) {
                Movie(
                    id = id,
                    title = titleWithYear,
                    poster = posterUrl
                )
            } else {
                TvShow(
                    id = id,
                    title = titleWithYear,
                    poster = posterUrl
                )
            }
        }.distinctBy {
            when (it) {
                is Movie -> "movie:${it.id}"
                is TvShow -> "tv:${it.id}"
                else -> it.toString()
            }
        }
    }
}
