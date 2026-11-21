package com.veltrax.music.ui

import android.content.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.veltrax.music.MainViewModel
import com.veltrax.music.data.*
import kotlinx.coroutines.*

@Composable
internal fun CollectionPage(
    m: MainViewModel,
    songs: List<Song>,
    start: String?,
    add: (Song) -> Unit,
) {
    val c = androidx.compose.ui.platform.LocalContext.current
    val lists by m.playlists.collectAsState()
    val favorites by m.favorites.collectAsState()
    val transfers by m.rows.collectAsState()
    val p = m.studio
    observe(p)
    var tab by
        rememberSaveable(start) { mutableStateOf(if (start == null) "Canciones" else "Playlists") }
    var sub by rememberSaveable(start) { mutableStateOf(start.orEmpty()) }
    var sort by rememberSaveable { mutableStateOf("Recientes") }
    var mark by remember { mutableStateOf(setOf<String>()) }
    var bulk by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Song?>(null) }
    val scope = rememberCoroutineScope()
    val index = remember(songs) { songs.associateBy { it.uri } }
    val recent = m.history
    val frequencies = remember(recent) { recent.groupingBy { it }.eachCount() }
    val groups =
        remember(tab, songs) {
            when (tab) {
                "Álbumes" -> songs.groupBy { it.album.ifBlank { "Sin álbum" } }
                "Artistas" -> songs.groupBy { it.artist.ifBlank { "Sin artista" } }
                "Carpetas" -> songs.groupBy { it.folder.ifBlank { "Enlaces y documentos" } }
                "Géneros" -> songs.groupBy { it.genre.ifBlank { "Sin género" } }
                else -> emptyMap()
            }
        }
    val rule = p.getString("smart:" + sub, "").orEmpty()
    val playlist =
        if (rule.startsWith("Artista|"))
            songs.filter { it.artist == rule.substringAfter('|') }.map { it.uri }
        else if (rule.startsWith("Género|"))
            songs.filter { it.genre == rule.substringAfter('|') }.map { it.uri }
        else if (rule == "Frecuencia")
            songs
                .filter { it.uri in frequencies }
                .sortedByDescending { frequencies[it.uri] }
                .map { it.uri }
        else lists[sub].orEmpty()
    val tracks =
        when (tab) {
            "Playlists" -> playlist.mapNotNull(index::get)
            "Favoritos" -> songs.filter { it.uri in favorites }
            "Descargadas" -> {
                val saved = transfers.filter { it.state == "Completada" }.map { it.uri }.toSet()
                songs.filter { s -> s.uri in saved || s.folder.startsWith("Music/VeltraX") }
            }
            "Duplicados" ->
                songs
                    .groupBy {
                        listOf(
                            it.title.trim().lowercase(),
                            it.artist.trim().lowercase(),
                            (it.duration / 1000).toString(),
                        )
                    }
                    .values
                    .filter { it.size > 1 }
                    .flatten()
            "Frecuentes" ->
                songs.filter { it.uri in frequencies }.sortedByDescending { frequencies[it.uri] }
            else -> if (groups.isNotEmpty()) groups[sub].orEmpty() else songs
        }
    val ordered =
        if (tab == "Playlists") tracks
        else
            when (sort) {
                "Título" -> tracks.sortedBy { it.title.lowercase() }
                "Artista" -> tracks.sortedBy { it.artist.lowercase() }
                "Duración" -> tracks.sortedByDescending { it.duration }
                else -> tracks
            }
    val cover =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
            if (u != null)
                try {
                    c.contentResolver.takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    p.edit().putString("cover:" + sub, u.toString()).apply()
                } catch (e: Exception) {
                    m.notifyUser(e.message.orEmpty())
                }
        }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("audio/x-mpegurl")
        ) { u ->
            if (u != null)
                m.runIO {
                    c.contentResolver.openOutputStream(u, "wt")!!.bufferedWriter().use { w ->
                        w.appendLine("#EXTM3U")
                        playlist.forEach { id ->
                            val s = index[id]
                            if (s != null)
                                w.appendLine(
                                    "#EXTINF:${s.duration/1000},${s.artist} - ${s.title}"
                                        .replace("\n", " ")
                                )
                            w.appendLine(id)
                        }
                    }
                    m.notifyUser("Playlist exportada")
                }
        }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
            if (u != null)
                scope.launch {
                    try {
                        val lines =
                            withContext(Dispatchers.IO) {
                                c.contentResolver
                                    .openInputStream(u)!!
                                    .bufferedReader(Charsets.UTF_8)
                                    .use { it.readLines() }
                            }
                        val saved = mutableListOf<String>()
                        var title = ""
                        require(lines.size <= 30000) {
                            "La playlist supera el límite de importación."
                        }
                        require(lines.none { it.startsWith("#EXT-X-") }) { "Esta lista es HLS." }
                        val remote = mutableListOf<Song>()
                        for (l in lines.map { it.trim().removePrefix("\uFEFF") }) {
                            if (l.startsWith("#EXTINF:")) {
                                title = l.substringAfter(',', "")
                                continue
                            }
                            if (l.isBlank() || l.startsWith("#")) continue
                            val uri = Uri.parse(l)
                            val ref =
                                if (uri.scheme in listOf("https", "http", "content")) l
                                else
                                    songs
                                        .firstOrNull {
                                            it.uri.substringAfterLast('/') == l ||
                                                (it.folder + it.title).endsWith(
                                                    l.substringBeforeLast('.'),
                                                    true,
                                                ) ||
                                                it.title.equals(
                                                    l.substringAfterLast('/')
                                                        .substringBeforeLast('.'),
                                                    true,
                                                )
                                        }
                                        ?.uri ?: l
                            if (uri.scheme in listOf("http", "https"))
                                remote +=
                                    Song(
                                        ref,
                                        title.ifBlank { uri.lastPathSegment.orEmpty() },
                                        "",
                                        "",
                                        0,
                                    )
                            saved += ref
                            title = ""
                        }
                        val name =
                            "Importada " +
                                java.text
                                    .SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                                    .format(java.util.Date())
                        m.saveRemoteBatch(remote)
                        m.playlistSave(name, saved)
                        sub = name
                        tab = "Playlists"
                        m.notifyUser("${saved.size} referencias importadas")
                    } catch (e: Exception) {
                        m.notifyUser(e.message.orEmpty())
                    }
                }
        }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                if (sub.isEmpty()) "Biblioteca" else sub,
                style = MaterialTheme.typography.headlineLarge,
            )
            Chips(
                listOf(
                    "Canciones",
                    "Playlists",
                    "Artistas",
                    "Álbumes",
                    "Carpetas",
                    "Géneros",
                    "Favoritos",
                    "Descargadas",
                    "Frecuentes",
                    "Duplicados",
                ),
                tab,
            ) {
                tab = it
                sub = ""
                mark = emptySet()
                bulk = false
            }
        }
        if (sub.isNotBlank())
            item {
                TextButton({
                    sub = ""
                    mark = emptySet()
                }) {
                    Text("‹ Volver a $tab")
                }
            }
        if (tab == "Playlists" && sub.isEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton({ dialog = "Nueva playlist" }) { Text("Crear playlist") }
                    TextButton({ dialog = "Lista automática" }) { Text("Crear automática") }
                    TextButton({ import.launch(arrayOf("*/*")) }) { Text("Importar M3U") }
                }
            }
            items(lists.keys.sorted()) { name ->
                ListItem(
                    headlineContent = { Text(name) },
                    leadingContent = {
                        val art = p.getString("cover:" + name, null)
                        if (art != null) AsyncImage(art, null, Modifier.size(42.dp))
                        else Icon(Icons.Default.QueueMusic, null)
                    },
                    modifier = Modifier.clickable { sub = name },
                )
            }
            if (lists.isEmpty()) item { Text("Crea una playlist o importa un archivo M3U.") }
        } else if (groups.isNotEmpty() && sub.isEmpty()) {
            items(groups.keys.sorted()) { value ->
                ListItem(
                    headlineContent = { Text(value) },
                    supportingContent = { Text("${groups[value]?.size} pistas") },
                    modifier = Modifier.clickable { sub = value },
                )
            }
        } else {
            if (tab == "Playlists")
                item {
                    p.getString("cover:" + sub, null)?.let {
                        AsyncImage(it, null, Modifier.fillMaxWidth().height(110.dp))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("Renombrar", "Portada", "Exportar", "Eliminar").forEach { a ->
                            TextButton({
                                when (a) {
                                    "Portada" -> cover.launch(arrayOf("image/*"))
                                    "Exportar" -> export.launch("$sub.m3u8")
                                    else -> dialog = a
                                }
                            }) {
                                Text(a)
                            }
                        }
                    }
                    val missing = playlist.count { it !in index }
                    if (missing > 0) Text("$missing referencias ausentes del dispositivo")
                }
            if (tab != "Playlists")
                item {
                    Chips(listOf("Recientes", "Título", "Artista", "Duración"), sort) { sort = it }
                }
            if (ordered.isNotEmpty())
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton({
                            bulk = !bulk
                            mark = emptySet()
                        }) {
                            Text(if (bulk) "Salir de selección" else "Seleccionar")
                        }
                        if (bulk) {
                            TextButton({
                                mark =
                                    if (mark.size == ordered.size) emptySet()
                                    else ordered.map { it.uri }.toSet()
                            }) {
                                Text("Todas · ${mark.size}")
                            }
                            if (mark.isNotEmpty())
                                TextButton({ dialog = "Añadir selección" }) {
                                    Text("Añadir a playlist")
                                }
                        }
                    }
                }
            itemsIndexed(ordered, key = { _, s -> s.uri }) { _, s ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (bulk)
                            Checkbox(
                                s.uri in mark,
                                { mark = if (it) mark + s.uri else mark - s.uri },
                            )
                        Box(Modifier.weight(1f)) { SongRow(s, m, ordered, add) }
                        IconButton({ edit = s }) { Icon(Icons.Default.Edit, "Editar etiquetas") }
                    }
                    if (tab == "Playlists" && rule.isEmpty()) {
                        var delta by remember { mutableFloatStateOf(0f) }
                        val slot = playlist.indexOf(s.uri)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "☰  Mantén y arrastra",
                                Modifier.weight(1f)
                                    .pointerInput(playlist, slot) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { delta = 0f },
                                            onDragEnd = {
                                                val n =
                                                    (slot + (delta / 55.dp.toPx()).toInt())
                                                        .coerceIn(0, playlist.lastIndex)
                                                if (n != slot) {
                                                    val a = playlist.toMutableList()
                                                    a.add(n, a.removeAt(slot))
                                                    m.playlistSave(sub, a)
                                                }
                                            },
                                        ) { change, drag ->
                                            change.consume()
                                            delta += drag.y
                                        }
                                    }
                                    .padding(8.dp),
                            )
                            TextButton({ m.removeFromPlaylist(sub, s.uri) }) { Text("Quitar") }
                        }
                    }
                }
            }
            if (ordered.isEmpty()) item { Text("No hay pistas en esta vista.") }
        }
    }
    if (dialog.isNotBlank()) {
        var entry by remember(dialog) { mutableStateOf(if (dialog == "Renombrar") sub else "") }
        var type by remember(dialog) { mutableStateOf("Frecuencia") }
        var valRule by remember(dialog) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(dialog) },
            text = {
                Column {
                    if (dialog == "Añadir selección") {
                        Column(
                            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                        ) {
                            lists.keys
                                .sorted()
                                .filter { p.getString("smart:" + it, "").isNullOrEmpty() }
                                .forEach { n ->
                                    TextButton({
                                        m.playlistSave(n, lists[n].orEmpty() + mark)
                                        dialog = ""
                                        mark = emptySet()
                                    }) {
                                        Text(n)
                                    }
                                }
                        }
                    }
                    if (dialog == "Eliminar") Text("¿Eliminar la playlist «$sub»?")
                    else
                        OutlinedTextField(
                            entry,
                            { entry = it },
                            singleLine = true,
                            label = { Text("Nombre de la playlist") },
                        )
                    if (dialog == "Lista automática") {
                        Chips(listOf("Frecuencia", "Artista", "Género"), type) { type = it }
                        if (type != "Frecuencia")
                            OutlinedTextField(valRule, { valRule = it }, label = { Text(type) })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    {
                        val n = entry.trim()
                        when (dialog) {
                            "Eliminar" -> {
                                m.deleteList(sub)
                                p.edit().remove("smart:" + sub).remove("cover:" + sub).apply()
                                sub = ""
                            }
                            "Renombrar" -> {
                                if (n.isNotEmpty() && n !in lists) {
                                    m.renameList(sub, n)
                                    val ed = p.edit()
                                    listOf("smart:", "cover:").forEach { k ->
                                        p.getString(k + sub, null)?.let {
                                            ed.putString(k + n, it)
                                            ed.remove(k + sub)
                                        }
                                    }
                                    ed.apply()
                                    sub = n
                                } else m.notifyUser("El nombre ya existe o está vacío.")
                            }
                            else -> {
                                if (n.isNotEmpty() && n !in lists) {
                                    m.playlistSave(
                                        n,
                                        if (dialog == "Añadir selección") mark.toList()
                                        else emptyList(),
                                    )
                                    if (dialog == "Lista automática")
                                        p.edit()
                                            .putString(
                                                "smart:" + n,
                                                if (type == "Frecuencia") type else "$type|$valRule",
                                            )
                                            .apply()
                                    mark = emptySet()
                                } else m.notifyUser("Escribe un nombre nuevo.")
                            }
                        }
                        dialog = ""
                    },
                    enabled = dialog == "Eliminar" || entry.isNotBlank(),
                ) {
                    Text(if (dialog == "Eliminar") "Eliminar" else "Guardar")
                }
            },
            dismissButton = { TextButton({ dialog = "" }) { Text("Cerrar") } },
        )
    }
    edit?.let { s ->
        var t by remember(s) { mutableStateOf(s.title) }
        var a by remember(s) { mutableStateOf(s.artist) }
        var album by remember(s) { mutableStateOf(s.album) }
        var g by remember(s) { mutableStateOf(s.genre) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text("Etiquetas de biblioteca") },
            text = {
                Column {
                    OutlinedTextField(t, { t = it }, label = { Text("Título") })
                    OutlinedTextField(a, { a = it }, label = { Text("Artista") })
                    OutlinedTextField(album, { album = it }, label = { Text("Álbum") })
                    OutlinedTextField(g, { g = it }, label = { Text("Género") })
                }
            },
            confirmButton = {
                TextButton({
                    m.tags(s, t, a, album, g)
                    edit = null
                }) {
                    Text("Guardar")
                }
            },
            dismissButton = { TextButton({ edit = null }) { Text("Cerrar") } },
        )
    }
}
