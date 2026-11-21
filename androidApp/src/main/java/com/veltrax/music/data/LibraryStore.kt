package com.veltrax.music.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User library is kept locally. URI references survive app restarts. */
class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("veltrax_library_2", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = getArray("favorites").toSet()

    fun toggleFavorite(uri: String) {
        val values = favorites().toMutableSet()
        if (!values.add(uri)) values.remove(uri)
        saveArray("favorites", values.toList())
    }

    fun playlists(): Map<String, List<String>> {
        val obj = JSONObject(prefs.getString("playlists", "{}") ?: "{}")
        return obj.keys().asSequence().associateWith { name ->
            obj.optJSONArray(name)?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                ?: emptyList()
        }
    }

    fun createPlaylist(name: String) {
        val clean = name.trim().take(45)
        if (clean.isEmpty()) return
        val maps = playlists().toMutableMap()
        if (!maps.containsKey(clean)) maps[clean] = emptyList()
        savePlaylists(maps)
    }

    fun addToPlaylist(name: String, uri: String) {
        val maps = playlists().toMutableMap()
        val old = maps[name] ?: return
        maps[name] = (old + uri).distinct()
        savePlaylists(maps)
    }

    fun removeFromPlaylist(name: String, uri: String) {
        val maps = playlists().toMutableMap()
        maps[name] = maps[name].orEmpty().filterNot { it == uri }
        savePlaylists(maps)
    }

    fun setList(name: String, uris: List<String>) {
        require(name.isNotBlank())
        savePlaylists(playlists() + (name.trim() to uris.distinct()))
    }

    fun deleteList(name: String) {
        savePlaylists(playlists() - name)
    }

    fun record(uri: String) {
        val h = getArray("history")
        saveArray("history", (listOf(uri) + h).take(1500))
    }

    fun history() = getArray("history")

    fun rememberSong(s: Song) = rememberBatch(listOf(s))

    fun rememberBatch(songs: List<Song>) {
        val o = JSONObject(prefs.getString("saved", "{}").orEmpty())
        songs.forEach { s ->
            o.put(s.uri, JSONArray(listOf(s.title, s.artist, s.album, s.duration, s.artwork)))
        }
        prefs.edit().putString("saved", o.toString()).apply()
    }

    fun savedSongs(): List<Song> {
        val o = JSONObject(prefs.getString("saved", "{}").orEmpty())
        return o.keys()
            .asSequence()
            .map {
                val a = o.getJSONArray(it)
                Song(
                    it,
                    a.optString(0),
                    a.optString(1),
                    a.optString(2),
                    a.optLong(3),
                    a.optString(4),
                )
            }
            .toList()
    }

    fun tag(uri: String, title: String, artist: String, album: String, genre: String) {
        prefs
            .edit()
            .putString("tag:" + uri, JSONArray(listOf(title, artist, album, genre)).toString())
            .apply()
    }

    fun tagged(s: Song): Song {
        val v = prefs.getString("tag:" + s.uri, null) ?: return s
        val a = JSONArray(v)
        return s.copy(
            title = a.optString(0),
            artist = a.optString(1),
            album = a.optString(2),
            genre = a.optString(3),
        )
    }

    private fun savePlaylists(maps: Map<String, List<String>>) {
        val obj = JSONObject()
        maps.forEach { (k, v) -> obj.put(k, JSONArray(v)) }
        prefs.edit().putString("playlists", obj.toString()).apply()
    }

    private fun getArray(key: String) =
        JSONArray(prefs.getString(key, "[]") ?: "[]").let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }

    private fun saveArray(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    data class DownloadItem(
        val id: String,
        val url: String,
        val fileName: String,
        val workId: String = "",
    )

    fun downloads(): List<DownloadItem> =
        JSONArray(prefs.getString("downloads", "[]") ?: "[]").let { array ->
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                DownloadItem(
                    obj.getString("id"),
                    obj.getString("url"),
                    obj.getString("fileName"),
                    obj.optString("workId"),
                )
            }
        }

    fun addDownload(item: DownloadItem) {
        val current = downloads().filterNot { it.id == item.id }
        val array = JSONArray()
        (listOf(item) + current).take(100).forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("url", it.url)
                    .put("fileName", it.fileName)
                    .put("workId", it.workId)
            )
        }
        prefs.edit().putString("downloads", array.toString()).apply()
    }
}
