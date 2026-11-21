package com.veltrax.music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.veltrax.music.data.LibraryStore
import com.veltrax.music.data.MusicRepository
import com.veltrax.music.data.Song
import com.veltrax.music.player.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = MusicRepository(app)
    private val library = LibraryStore(app)
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()
    private val _favorites = MutableStateFlow(library.favorites())
    val favorites = _favorites.asStateFlow()
    private val _playlists = MutableStateFlow(library.playlists())
    val playlists = _playlists.asStateFlow()
    private val _nowPlaying = MutableStateFlow<Song?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()
    private val _shuffle = MutableStateFlow(false)
    val shuffle = _shuffle.asStateFlow()
    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeat = _repeat.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val q = com.veltrax.music.download.TransferQueue.get(getApplication<Application>())
                q.migrate()
                if (
                    q.entries().any {
                        it.state in
                            setOf("Lista", "Descargando", "Guardando", "Reintento", "Sin red")
                    }
                )
                    q.start()
            }
        }

        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        future =
            MediaController.Builder(context, token).buildAsync().also { f ->
                f.addListener(
                    {
                        try {
                            controller =
                                f.get().also { c ->
                                    c.addListener(
                                        object : Player.Listener {
                                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                                _isPlaying.value = isPlaying
                                            }

                                            override fun onMediaItemTransition(
                                                mediaItem: MediaItem?,
                                                reason: Int,
                                            ) {
                                                syncPlayer()
                                            }

                                            override fun onPlaybackStateChanged(
                                                playbackState: Int
                                            ) {
                                                syncPlayer()
                                            }

                                            override fun onShuffleModeEnabledChanged(
                                                shuffleModeEnabled: Boolean
                                            ) {
                                                _shuffle.value = shuffleModeEnabled
                                            }

                                            override fun onRepeatModeChanged(repeatMode: Int) {
                                                _repeat.value = repeatMode
                                            }

                                            override fun onPlayerError(error: PlaybackException) {
                                                _error.value =
                                                    "No se pudo reproducir esta canción. Comprueba que el archivo exista y sea compatible."
                                            }
                                        }
                                    )
                                    syncPlayer()
                                }
                        } catch (_: Exception) {
                            _error.value =
                                "No se pudo conectar con el reproductor. Vuelve a abrir VeltraX."
                        }
                    },
                    androidx.core.content.ContextCompat.getMainExecutor(context),
                )
            }
    }

    private fun syncPlayer() {
        val c = controller ?: return
        val uri =
            c.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }
                ?: c.currentMediaItem?.localConfiguration?.uri?.toString()
        _nowPlaying.value =
            _songs.value.firstOrNull { it.uri == uri }
                ?: c.currentMediaItem?.let { item ->
                    Song(
                        uri ?: "",
                        item.mediaMetadata.title?.toString() ?: "Canción",
                        item.mediaMetadata.artist?.toString() ?: "",
                        "",
                        c.duration.coerceAtLeast(0),
                    )
                }
        queueSongs.value =
            (0 until c.mediaItemCount).map {
                val m = c.getMediaItemAt(it)
                Song(
                    m.mediaId,
                    m.mediaMetadata.title?.toString().orEmpty(),
                    m.mediaMetadata.artist?.toString().orEmpty(),
                    "",
                    0,
                )
            }
        _isPlaying.value = c.isPlaying
        _position.value = c.currentPosition.coerceAtLeast(0)
        _duration.value = c.duration.coerceAtLeast(0)
        _shuffle.value = c.shuffleModeEnabled
        _repeat.value = c.repeatMode
    }

    suspend fun refreshSongs() {
        val local =
            try {
                repository.readAll()
            } catch (e: SecurityException) {
                emptyList()
            }
        val extra =
            withContext(Dispatchers.IO) {
                transfers
                    .entries()
                    .filter {
                        it.state == "Completada" &&
                            it.uri.isNotBlank() &&
                            (it.folder.isNotBlank() ||
                                local.none { s ->
                                    s.uri.substringAfterLast('/') == it.uri.substringAfterLast('/')
                                })
                    }
                    .map { Song(it.uri, it.name, "Descargas", "VeltraX", 0) }
            }
        _songs.value =
            (local + extra + library.savedSongs()).distinctBy { it.uri }.map { library.tagged(it) }
        syncPlayer()
    }

    fun refreshProgress() {
        val c = controller ?: return
        _position.value = c.currentPosition.coerceAtLeast(0)
        _duration.value = c.duration.coerceAtLeast(0)
    }

    val transfers = com.veltrax.music.download.TransferQueue.get(getApplication<Application>())
    val studio = transfers.prefs
    val rows = MutableStateFlow<List<com.veltrax.music.download.Transfer>>(emptyList())
    val queueSongs = MutableStateFlow<List<Song>>(emptyList())

    fun notifyUser(text: String) {
        _error.value = text
    }

    fun runIO(work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notifyUser(e.message ?: "Error de operación.")
            }
        }
    }

    suspend fun refreshDownloads() {
        val result = withContext(Dispatchers.IO) { transfers.entries() }
        val added =
            result.filter { it.state == "Completada" }.map { it.id }.toSet() !=
                rows.value.filter { it.state == "Completada" }.map { it.id }.toSet()
        rows.value = result
        if (added) refreshSongs()
    }

    fun addFiles(tracks: List<Pair<String, String>>, batch: String = "") {
        if (tracks.isEmpty()) return
        runIO {
            val db = transfers.writableDatabase
            var n = 0
            db.beginTransaction()
            try {
                tracks.forEach { if (transfers.insert(it.first, it.second, batch)) n++ }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (n > 0) transfers.start()
            notifyUser("$n añadidas · ${tracks.size-n} duplicadas")
        }
    }

    fun action(ids: Set<String>, op: String) = runIO { transfers.control(ids, op) }

    fun reloadLibrary() {
        _favorites.value = library.favorites()
        _playlists.value = library.playlists()
        viewModelScope.launch { refreshSongs() }
    }

    val history
        get() = library.history()

    fun tags(s: Song, t: String, a: String, al: String, g: String) {
        library.tag(s.uri, t, a, al, g)
        reloadLibrary()
    }

    fun saveRemoteBatch(tracks: List<Song>) {
        library.rememberBatch(tracks)
        reloadLibrary()
    }

    fun rememberSong(s: Song) {
        library.rememberSong(s)
        viewModelScope.launch { refreshSongs() }
    }

    fun playlistSave(name: String, items: List<String>) {
        library.setList(name, items)
        _playlists.value = library.playlists()
    }

    fun deleteList(name: String) {
        library.deleteList(name)
        _playlists.value = library.playlists()
    }

    fun renameList(name: String, new: String) {
        if (new.isBlank() || new in _playlists.value) return
        val tracks = _playlists.value[name] ?: return
        playlistSave(new, tracks)
        deleteList(name)
    }

    fun moveQueue(from: Int, to: Int) {
        controller?.let {
            if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount)
                it.moveMediaItem(from, to)
        }
        syncPlayer()
    }

    fun dropQueue(i: Int) {
        controller?.let { if (i in 0 until it.mediaItemCount) it.removeMediaItem(i) }
        syncPlayer()
    }

    fun chooseQueue(i: Int) {
        controller?.let {
            if (i in 0 until it.mediaItemCount) {
                it.seekToDefaultPosition(i)
                it.play()
            }
        }
    }

    fun play(song: Song, collection: List<Song>) {
        if (com.veltrax.music.data.Catalog.isYoutube(song.uri)) {
            try {
                getApplication<Application>()
                    .startActivity(
                        android.content
                            .Intent(android.content.Intent.ACTION_VIEW, Uri.parse(song.uri))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                library.record(song.uri)
            } catch (e: android.content.ActivityNotFoundException) {
                notifyUser("Instala un navegador para abrir YouTube.")
            }
            return
        }
        val c =
            controller
                ?: run {
                    _error.value = "El reproductor se está iniciando. Inténtalo de nuevo."
                    return
                }
        _error.value = null
        library.record(song.uri)
        val queue =
            collection
                .filterNot { com.veltrax.music.data.Catalog.isYoutube(it.uri) }
                .ifEmpty { listOf(song) }
        val items =
            queue.map { s ->
                MediaItem.Builder()
                    .setUri(Uri.parse(s.uri))
                    .setMediaId(s.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(s.title)
                            .setArtist(s.artist)
                            .setAlbumTitle(s.album)
                            .build()
                    )
                    .build()
            }
        c.setMediaItems(items, queue.indexOfFirst { it.uri == song.uri }.coerceAtLeast(0), 0)
        c.prepare()
        c.play()
        syncPlayer()
    }

    fun togglePlayback() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
        syncPlayer()
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        syncPlayer()
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode =
                when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
        }
        syncPlayer()
    }

    fun dismissError() {
        _error.value = null
    }

    fun next() {
        controller?.seekToNextMediaItem()
        syncPlayer()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
        syncPlayer()
    }

    fun seek(milliseconds: Long) {
        controller?.seekTo(milliseconds)
        syncPlayer()
    }

    fun toggleFavorite(uri: String) {
        library.toggleFavorite(uri)
        _favorites.value = library.favorites()
    }

    fun createPlaylist(name: String) {
        library.createPlaylist(name)
        _playlists.value = library.playlists()
    }

    fun addToPlaylist(name: String, uri: String) {
        library.addToPlaylist(name, uri)
        _playlists.value = library.playlists()
    }

    fun removeFromPlaylist(name: String, uri: String) {
        library.removeFromPlaylist(name, uri)
        _playlists.value = library.playlists()
    }

    fun enqueue(url: String, filename: String) = addFiles(listOf(url to filename))

    override fun onCleared() {
        future?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
