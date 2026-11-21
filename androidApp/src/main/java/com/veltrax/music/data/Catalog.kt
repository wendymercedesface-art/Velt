package com.veltrax.music.data

import android.net.Uri
import java.io.IOException
import java.net.*
import org.json.*

data class Release(
    val title: String,
    val artist: String = "",
    val seconds: Long = 0,
    val artwork: String = "",
    val stream: String = "",
    val files: List<Pair<String, String>> = emptyList(),
    val children: String = "",
    val external: String = "",
) {
    fun song() = Song(stream.ifBlank { external }, title, artist, "", seconds * 1000, artwork)
}

object Catalog {
    fun isYoutube(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase().orEmpty()
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private const val api = "https://api.audius.co/v1"

    private fun enc(s: String) = Uri.encode(s)

    private fun text(address: String, limit: Int = 6_000_000): String {
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "VeltraX/3.0")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("La fuente respondió HTTP $code.")
            return conn.inputStream.use { stream ->
                val bytes = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    require(bytes.size() + n <= limit) { "La lista supera el tamaño admitido." }
                    bytes.write(buf, 0, n)
                }
                bytes.toString("UTF-8")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun rows(j: JSONArray?) =
        if (j == null) emptyList() else (0 until j.length()).mapNotNull { j.optJSONObject(it) }

    private fun art(j: JSONObject) = j.optJSONObject("artwork")?.optString("480x480").orEmpty()

    private fun track(j: JSONObject): Release {
        val id = j.getString("id")
        val downloadable = j.optBoolean("is_downloadable") && !j.optBoolean("is_download_gated")
        return Release(
            j.getString("title"),
            j.optJSONObject("user")?.optString("name").orEmpty(),
            j.optLong("duration"),
            art(j),
            if (j.optBoolean("is_streamable", true) && !j.optBoolean("is_stream_gated"))
                "$api/tracks/$id/stream?app_name=VeltraX"
            else "",
            if (downloadable) listOf("Original" to "$api/tracks/$id/download?app_name=VeltraX")
            else emptyList(),
            external =
                j.optString("permalink").let {
                    if (it.startsWith("/")) "https://audius.co$it" else ""
                },
        )
    }

    private fun ytURL(path: String, key: String, query: String): JSONObject {
        require(key.isNotBlank()) {
            "La búsqueda interna de YouTube requiere una clave de YouTube Data API en Ajustes."
        }
        return JSONObject(
            text("https://www.googleapis.com/youtube/v3/$path?key=${enc(key)}&$query")
        )
    }

    private fun ytItem(id: String, j: JSONObject, playlist: Boolean = false) =
        Release(
            android.text.Html.fromHtml(j.optString("title"), 0).toString(),
            j.optString("videoOwnerChannelTitle", j.optString("channelTitle")),
            artwork =
                j.optJSONObject("thumbnails")?.optJSONObject("medium")?.optString("url").orEmpty(),
            children = if (playlist) "yt:$id" else "",
            external =
                if (playlist) "https://www.youtube.com/playlist?list=$id"
                else "https://www.youtube.com/watch?v=$id",
        )

    fun search(q: String, source: String, kind: String, key: String): List<Release> {
        if (source == "YouTube") {
            val type = if (kind in listOf("Listas", "Álbumes")) "playlist" else "video"
            val matches =
                rows(
                        ytURL("search", key, "part=snippet&type=$type&maxResults=35&q=${enc(q)}")
                            .optJSONArray("items")
                    )
                    .mapNotNull { obj ->
                        val id =
                            obj.optJSONObject("id")
                                ?.optString(if (type == "playlist") "playlistId" else "videoId")
                                .orEmpty()
                        if (id.isBlank()) null
                        else ytItem(id, obj.getJSONObject("snippet"), type == "playlist")
                    }
            if (type == "playlist" || matches.isEmpty()) return matches
            val ids = matches.map { Uri.parse(it.external).getQueryParameter("v").orEmpty() }
            val lengths =
                rows(
                        ytURL("videos", key, "part=contentDetails&id=" + enc(ids.joinToString(",")))
                            .optJSONArray("items")
                    )
                    .associate { j ->
                        j.getString("id") to
                            runCatching {
                                    java.time.Duration.parse(
                                            j.getJSONObject("contentDetails").getString("duration")
                                        )
                                        .seconds
                                }
                                .getOrDefault(0L)
                    }
            return matches.mapIndexed { i, item -> item.copy(seconds = lengths[ids[i]] ?: 0) }
        }
        if (source == "Internet Archive") {
            val term = q.replace(Regex("[^\\p{L}\\p{N} -]"), " ").take(140)
            val query = if (kind == "Artistas") "creator:(${term})" else "(${term})"
            val matches =
                JSONObject(
                        text(
                            "https://archive.org/advancedsearch.php?q=" +
                                enc("$query AND mediatype:audio AND licenseurl:*") +
                                "&fl[]=identifier&fl[]=title&fl[]=creator&rows=35&output=json"
                        )
                    )
                    .getJSONObject("response")
            return rows(matches.optJSONArray("docs")).map {
                val id = it.getString("identifier")
                Release(
                    it.optString("title", id),
                    it.optString("creator"),
                    artwork = "https://archive.org/services/img/${enc(id)}",
                    children = "ia:$id",
                )
            }
        }
        val endpoint =
            when (kind) {
                "Artistas" -> "users"
                "Listas",
                "Álbumes" -> "playlists"
                else -> "tracks"
            }
        val records =
            rows(
                JSONObject(text("$api/$endpoint/search?app_name=VeltraX&limit=40&query=${enc(q)}"))
                    .optJSONArray("data")
            )
        return records.mapNotNull { j ->
            when (endpoint) {
                "users" ->
                    Release(
                        j.optString("name"),
                        "Artista",
                        artwork =
                            j.optJSONObject("profile_picture")?.optString("480x480").orEmpty(),
                        children = "users:" + j.getString("id"),
                    )
                "playlists" ->
                    if (j.optBoolean("is_album") == (kind == "Álbumes"))
                        Release(
                            j.optString("playlist_name"),
                            j.optJSONObject("user")?.optString("name").orEmpty(),
                            artwork = art(j),
                            children = "playlists:" + j.getString("id"),
                        )
                    else null
                else -> track(j)
            }
        }
    }

    fun expand(ref: String, key: String): List<Release> {
        val (type, id) = ref.split(":", limit = 2)
        if (type == "yt") {
            val result = mutableListOf<Release>()
            var page = ""
            val seen = mutableSetOf<String>()
            do {
                val r =
                    ytURL(
                        "playlistItems",
                        key,
                        "part=snippet&maxResults=50&playlistId=${enc(id)}&pageToken=${enc(page)}",
                    )
                result +=
                    rows(r.optJSONArray("items")).mapNotNull {
                        val j = it.getJSONObject("snippet")
                        j.optJSONObject("resourceId")
                            ?.optString("videoId")
                            ?.takeIf { v -> v.isNotEmpty() }
                            ?.let { v -> ytItem(v, j) }
                    }
                page = r.optString("nextPageToken")
            } while (page.isNotBlank() && seen.add(page) && result.size < 10000)
            return result
        }
        if (type == "ia") {
            val r = JSONObject(text("https://archive.org/metadata/${enc(id)}"))
            val meta = r.getJSONObject("metadata")
            require(meta.optString("licenseurl").isNotBlank() && !r.optBoolean("is_dark")) {
                "La fuente no declara una licencia accesible."
            }
            val files =
                rows(r.optJSONArray("files")).filter {
                    it.optString("name").substringAfterLast('.').lowercase() in
                        listOf("mp3", "ogg", "m4a", "wav", "flac", "mp4") &&
                        !it.optBoolean("private")
                }
            return files
                .groupBy {
                    it.optString("original")
                        .ifBlank { it.getString("name") }
                        .substringBeforeLast('.')
                }
                .map { (name, variants) ->
                    val options =
                        variants.map {
                            val f = it.getString("name")
                            (it.optString("format", f.substringAfterLast('.')) +
                                " · " +
                                f.substringAfterLast('.')) to
                                "https://archive.org/download/${enc(id)}/${Uri.encode(f,"/")}"
                        }
                    Release(
                        variants.first().optString("title", name),
                        meta.optString("creator"),
                        artwork = "https://archive.org/services/img/${enc(id)}",
                        stream = options.first().second,
                        files = options,
                    )
                }
        }
        val result = mutableListOf<Release>()
        val seen = mutableSetOf<String>()
        while (result.size < 10000) {
            val data =
                rows(
                    JSONObject(
                            text(
                                "$api/$type/${enc(id)}/tracks?app_name=VeltraX&limit=100&offset=${result.size}"
                            )
                        )
                        .optJSONArray("data")
                )
            val fresh = data.filter { seen.add(it.getString("id")) }
            result += fresh.map(::track)
            if (fresh.size < 100) break
        }
        return result
    }

    fun import(u: String, key: String): List<Release> {
        val uri = URI(u.trim())
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null) {
            "Pega una URL pública HTTP o HTTPS."
        }
        val host = uri.host.lowercase()
        if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")) {
            val list = Uri.parse(u).getQueryParameter("list")
            return if (!list.isNullOrBlank() && key.isNotBlank()) expand("yt:$list", key)
            else
                listOf(
                    Release(
                        if (list != null) "Playlist de YouTube" else "Reproducir en YouTube",
                        artist = "YouTube",
                        external = u,
                    )
                )
        }
        if (host == "archive.org" && uri.path.startsWith("/details/"))
            return expand("ia:" + uri.path.substringAfter("/details/").substringBefore('/'), key)
        if (uri.path.lowercase().substringAfterLast('.') in listOf("m3u", "m3u8"))
            return m3u(text(u), u)
        val title = Uri.decode(uri.path.substringAfterLast('/')).ifBlank { "Pista" }
        return listOf(Release(title, stream = u, files = listOf("Original" to u)))
    }

    fun m3u(body: String, base: String): List<Release> {
        require("#EXT-X-" !in body) {
            "Es una transmisión HLS. Usa un enlace a archivos individuales."
        }
        var title = ""
        var dur = 0L
        val list = mutableListOf<Release>()
        body
            .removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim() }
            .forEach { l ->
                if (l.startsWith("#EXTINF:")) {
                    title = l.substringAfter(',', "")
                    dur = l.substringAfter(':').substringBefore(',').toDoubleOrNull()?.toLong() ?: 0
                } else if (l.isNotBlank() && !l.startsWith("#")) {
                    val url = URI(base).resolve(l.replace(" ", "%20"))
                    require(list.size < 10000) { "Máximo 10 000 pistas." }
                    if (url.scheme in listOf("https", "http"))
                        list +=
                            Release(
                                title.ifBlank { Uri.decode(url.path.substringAfterLast('/')) },
                                seconds = dur,
                                stream = url.toString(),
                                files = listOf("Original" to url.toString()),
                            )
                    title = ""
                    dur = 0
                }
            }
        return list
    }

    fun lyrics(s: Song): String {
        val j =
            JSONObject(
                text(
                    "https://lrclib.net/api/get?artist_name=${enc(s.artist)}&track_name=${enc(s.title)}"
                )
            )
        return listOf("syncedLyrics", "plainLyrics").firstNotNullOfOrNull {
            if (j.isNull(it)) null else j.optString(it).takeIf(String::isNotBlank)
        } ?: error("No hay letra disponible.")
    }
}
