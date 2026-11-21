package com.veltrax.music.ui

import android.content.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.veltrax.music.MainViewModel
import com.veltrax.music.data.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
internal fun observe(p: SharedPreferences): Int {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(p) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        p.registerOnSharedPreferenceChangeListener(l)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(l) }
    }
    return tick
}

@Composable
internal fun Chips(items: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { FilterChip(selected == it, { onPick(it) }, label = { Text(it) }) }
    }
}

@Composable
internal fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
internal fun Cover(s: Song, size: Int) {
    Box(
        Modifier.size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        if (s.artwork.isNotEmpty())
            AsyncImage(s.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

internal fun open(c: Context, url: String, m: MainViewModel) {
    try {
        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        m.notifyUser("No se encontró una aplicación para abrir este enlace.")
    }
}

private fun bytes(n: Long) =
    when {
        n >= 1_048_576 -> "%.1f MB".format(n / 1048576.0)
        n >= 1024 -> "${n/1024} KB"
        else -> "$n B"
    }

@Composable
internal fun DownloadsStudio(m: MainViewModel, shared: String, consumed: () -> Unit) {
    val rows by m.rows.collectAsState()
    val p = m.studio
    observe(p)
    var link by rememberSaveable { mutableStateOf("") }
    var inspect by remember { mutableStateOf(false) }
    LaunchedEffect(shared) {
        if (shared.isNotBlank()) {
            link = shared
            inspect = true
            consumed()
        }
    }
    var filter by rememberSaveable { mutableStateOf("Todas") }
    var group by rememberSaveable { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val visible =
        rows.filter {
            when (filter) {
                "Activas" -> it.state !in listOf("Completada", "Cancelada", "Error", "Pausada")
                "Guardadas" -> it.state == "Completada"
                "Errores" -> it.state == "Error"
                else -> true
            }
        }
    val sorted = if (group) visible.sortedBy { it.group } else visible
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Descargas",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${rows.count{it.state=="Completada"}} guardadas · ${bytes(rows.filter{it.state=="Completada"}.sumOf{it.bytes})}"
            )
        }
        item {
            Group("Importar música") {
                OutlinedTextField(
                    link,
                    { link = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Enlace de archivo o playlist") },
                    singleLine = true,
                )
                Button({ inspect = true }, enabled = link.isNotBlank()) { Text("Revisar enlace") }
            }
        }
        item {
            Chips(listOf("Todas", "Activas", "Guardadas", "Errores"), filter) {
                filter = it
                chosen = emptySet()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(group, { group = it })
                Text("Agrupar por playlist")
            }
        }
        if (visible.isNotEmpty())
            item {
                TextButton({
                    chosen =
                        if (chosen.size == visible.size) emptySet()
                        else visible.map { it.id }.toSet()
                }) {
                    Text("Seleccionar todas · ${chosen.size}")
                }
            }
        if (chosen.isNotEmpty())
            item {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("Pausar", "Continuar", "Prioridad", "Cancelar", "Quitar").forEach { op ->
                        TextButton({
                            m.action(chosen, op)
                            chosen = emptySet()
                        }) {
                            Text(op)
                        }
                    }
                }
            }
        if (visible.isEmpty())
            item {
                Text(
                    if (rows.isEmpty()) "Pega un enlace para guardar tu primera canción."
                    else "No hay transferencias en este filtro."
                )
            }
        itemsIndexed(sorted, key = { _, it -> it.id }) { i, r ->
            if (group && (i == 0 || sorted[i - 1].group != r.group))
                Text(
                    r.group.ifBlank { "Individuales" },
                    style = MaterialTheme.typography.titleMedium,
                )
            Group(r.name) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(r.id in chosen, { chosen = if (it) chosen + r.id else chosen - r.id })
                    Text(
                        if (r.state == "Sin red" && p.getBoolean("wifi", false)) "Esperando Wi-Fi"
                        else r.state
                    )
                    if (r.priority > 0) Text(" · Prioridad alta")
                }
                if (r.state in listOf("Descargando", "Guardando", "Reintento", "Pausada")) {
                    if (r.total > 0)
                        LinearProgressIndicator(
                            progress = { (r.bytes.toFloat() / r.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    else if (r.state == "Descargando")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        bytes(r.bytes) +
                            (if (r.total > 0) " de ${bytes(r.total)} · ${100*r.bytes/r.total}%"
                            else "") +
                            (if (r.speed > 0) " · ${bytes(r.speed)}/s" else "")
                    )
                }
                if (r.reason.isNotBlank() && r.state != "Completada")
                    Text(r.reason, color = MaterialTheme.colorScheme.error)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    when (r.state) {
                        "Completada" ->
                            if (r.uri.isNotEmpty())
                                TextButton({
                                    val s = Song(r.uri, r.name, "Descargas", "", 0)
                                    m.play(s, listOf(s))
                                }) {
                                    Text("Reproducir")
                                }
                        "Error",
                        "Reintento",
                        "Pausada",
                        "Sin red" ->
                            TextButton({ m.action(setOf(r.id), "Continuar") }) {
                                Text(if (r.state == "Pausada") "Continuar" else "Reintentar")
                            }
                        "Lista",
                        "Descargando" ->
                            TextButton({ m.action(setOf(r.id), "Pausar") }) { Text("Pausar") }
                    }
                    if (r.state != "Guardando") {
                        val op =
                            if (r.state in listOf("Completada", "Cancelada")) "Quitar"
                            else "Cancelar"
                        TextButton({ m.action(setOf(r.id), op) }) { Text(op) }
                    }
                }
            }
        }
    }
    if (inspect)
        ResultsDialog(link, m, { Catalog.import(link, p.getString("youtube", "").orEmpty()) }) {
            inspect = false
        }
}

@Composable
private fun ResultsDialog(
    title: String,
    m: MainViewModel,
    load: () -> List<Release>,
    close: () -> Unit,
) {
    var data by remember { mutableStateOf(listOf<Release>()) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    val variants = remember { mutableStateMapOf<Int, Int>() }
    val c = LocalContext.current
    var name by remember { mutableStateOf("") }
    LaunchedEffect(title, attempt) {
        loading = true
        err = ""
        try {
            data = withContext(Dispatchers.IO) { load() }
            selected = data.indices.filter { data[it].files.isNotEmpty() }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err = e.message.orEmpty()
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Importar pistas") },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (loading) item { CircularProgressIndicator() }
                if (err.isNotBlank())
                    item {
                        Text(err)
                        TextButton({ attempt++ }) { Text("Reintentar") }
                    }
                if (!loading && data.isEmpty() && err.isBlank())
                    item { Text("La fuente no devolvió pistas compatibles.") }
                if (data.size > 1)
                    item {
                        TextButton({
                            val all = data.indices.filter { data[it].files.isNotEmpty() }.toSet()
                            selected = if (selected == all) emptySet() else all
                        }) {
                            Text("Seleccionar todas · ${selected.size}/${data.size}")
                        }
                    }
                if (data.size == 1 && data[0].files.isNotEmpty())
                    item {
                        OutlinedTextField(
                            name,
                            { name = it },
                            singleLine = true,
                            label = { Text("Nombre opcional") },
                        )
                    }
                itemsIndexed(data) { i, r ->
                    Column {
                        Text(r.title, fontWeight = FontWeight.Bold)
                        if (r.artist.isNotBlank()) Text(r.artist)
                        if (r.files.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    i in selected,
                                    { selected = if (it) selected + i else selected - i },
                                )
                                Text("Guardar archivo")
                            }
                            if (r.files.size > 1)
                                Chips(r.files.map { it.first }, r.files[variants[i] ?: 0].first) { v
                                    ->
                                    variants[i] = r.files.indexOfFirst { it.first == v }
                                }
                        } else Text("La fuente permite reproducción externa o en línea.")
                        if (r.external.isNotBlank())
                            TextButton({ open(c, r.external, m) }) { Text("Abrir fuente") }
                        if (r.stream.isNotBlank() || Catalog.isYoutube(r.external))
                            TextButton({
                                m.rememberSong(r.song())
                                m.notifyUser("Enlace guardado en Biblioteca")
                            }) {
                                Text("Guardar enlace")
                            }
                    }
                }
                if (data.size > 1)
                    item {
                        TextButton({
                            val tracks =
                                data.filter {
                                    it.stream.isNotBlank() || Catalog.isYoutube(it.external)
                                }
                            m.saveRemoteBatch(tracks.map { it.song() })
                            if (tracks.isNotEmpty())
                                m.playlistSave(
                                    title.substringAfterLast('/').take(80).ifBlank {
                                        "Playlist importada"
                                    },
                                    tracks.map { it.song().uri },
                                )
                            m.notifyUser("${tracks.size} pistas guardadas")
                        }) {
                            Text("Guardar como playlist")
                        }
                    }
            }
        },
        confirmButton = {
            if (selected.isNotEmpty())
                Button({
                    m.addFiles(
                        selected.sorted().map { i ->
                            data[i].files[variants[i] ?: 0].second to
                                (if (data.size == 1 && name.isNotBlank()) name else data[i].title)
                        },
                        if (data.size > 1) title.take(120) else "",
                    )
                    close()
                }) {
                    Text("Descargar ${selected.size}")
                }
        },
        dismissButton = { TextButton(close) { Text("Cerrar") } },
    )
}

@Composable
internal fun SearchStudio(m: MainViewModel, songs: List<Song>, add: (Song) -> Unit) {
    val p = m.studio
    val c = LocalContext.current
    var source by rememberSaveable { mutableStateOf("Audius") }
    var kind by rememberSaveable { mutableStateOf("Canciones") }
    var query by rememberSaveable { mutableStateOf("") }
    var request by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var hits by remember { mutableStateOf(listOf<Release>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var expand by remember { mutableStateOf<Release?>(null) }
    var history by remember {
        mutableStateOf(p.getString("queries", "").orEmpty().lines().filter { it.isNotBlank() })
    }
    fun submit(q: String) {
        query = q
        request = q.trim()
        attempt++
        history = (listOf(request) + history).filter { it.isNotBlank() }.distinct().take(10)
        p.edit().putString("queries", history.joinToString("\n")).apply()
    }
    LaunchedEffect(source, kind, request, attempt) {
        hits = emptyList()
        error = ""
        selected = emptySet()
        busy = false
        if (
            request.isBlank() ||
                source == "Biblioteca" ||
                source == "YouTube" && p.getString("youtube", "").isNullOrBlank()
        )
            return@LaunchedEffect
        busy = true
        try {
            hits =
                withContext(Dispatchers.IO) {
                    Catalog.search(request, source, kind, p.getString("youtube", "").orEmpty())
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message.orEmpty()
        } finally {
            busy = false
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item {
            Text(
                "Buscar música",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Título, artista, álbum o playlist") },
                trailingIcon = {
                    IconButton({ submit(query) }, enabled = query.isNotBlank()) {
                        Icon(Icons.Default.Search, "Buscar")
                    }
                },
            )
            Chips(listOf("Audius", "Internet Archive", "YouTube", "Biblioteca"), source) {
                source = it
            }
            if (source != "Biblioteca")
                Chips(listOf("Canciones", "Artistas", "Álbumes", "Listas"), kind) { kind = it }
        }
        if (history.isNotEmpty() && request.isEmpty())
            item {
                Text("Últimas búsquedas")
                Chips(history, "") { submit(it) }
            }
        if (source == "Biblioteca") {
            val filtered =
                songs.filter {
                    listOf(it.title, it.album, it.artist).any { v -> v.contains(query, true) }
                }
            items(filtered, key = { it.uri }) { s -> SongRow(s, m, filtered, add) }
        } else if (source == "YouTube" && p.getString("youtube", "").isNullOrBlank())
            item {
                Text(
                    "Abre la búsqueda oficial o configura tu clave de YouTube en Ajustes para ver resultados aquí."
                )
                Button(
                    {
                        open(
                            c,
                            "https://www.youtube.com/results?search_query=" + Uri.encode(query),
                            m,
                        )
                    },
                    enabled = query.isNotBlank(),
                ) {
                    Text("Buscar en YouTube")
                }
            }
        else {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank())
                item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton({ attempt++ }) { Text("Reintentar") }
                }
            if (!busy && error.isBlank() && hits.isEmpty())
                item {
                    Text(
                        if (request.isBlank()) "Escribe una búsqueda para explorar el catálogo."
                        else "Sin resultados para «$request»."
                    )
                }
            if (hits.any { it.files.isNotEmpty() })
                item {
                    Row {
                        TextButton({
                            val all = hits.indices.filter { hits[it].files.isNotEmpty() }.toSet()
                            selected = if (all == selected) emptySet() else all
                        }) {
                            Text("Seleccionar todas")
                        }
                        if (selected.isNotEmpty())
                            Button({
                                m.addFiles(
                                    selected.map {
                                        hits[it].files.first().second to hits[it].title
                                    },
                                    request,
                                )
                                selected = emptySet()
                            }) {
                                Text("Descargar ${selected.size}")
                            }
                    }
                }
            itemsIndexed(hits) { i, h ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(h.song(), 50)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title, fontWeight = FontWeight.Bold)
                            Text(
                                h.artist +
                                    (if (h.seconds > 0)
                                        " · ${h.seconds/60}:${(h.seconds%60).toString().padStart(2,'0')}"
                                    else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (h.files.isNotEmpty())
                            Checkbox(
                                i in selected,
                                { selected = if (it) selected + i else selected - i },
                            )
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        if (h.children.isNotBlank())
                            TextButton({ expand = h }) { Text("Ver pistas") }
                        else {
                            if (h.stream.isNotBlank()) {
                                TextButton({ m.play(h.song(), listOf(h.song())) }) {
                                    Text("Reproducir")
                                }
                                TextButton({
                                    m.rememberSong(h.song())
                                    m.notifyUser("Guardada en Biblioteca")
                                }) {
                                    Text("Guardar")
                                }
                            } else if (h.external.isNotBlank())
                                TextButton({ open(c, h.external, m) }) { Text("Abrir fuente") }
                            if (h.files.isNotEmpty())
                                TextButton({ expand = h }) { Text("Descargar") }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    expand?.let { h ->
        ResultsDialog(
            h.title,
            m,
            {
                if (h.children.isBlank()) listOf(h)
                else Catalog.expand(h.children, p.getString("youtube", "").orEmpty())
            },
        ) {
            expand = null
        }
    }
}

@Composable
internal fun StudioSettings(m: MainViewModel) {
    val p = m.studio
    observe(p)
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("Apariencia") }
    val data = c.getSharedPreferences("veltrax_library_2", 0)
    val themeKeys =
        setOf(
            "mode",
            "accent",
            "density",
            "font",
            "motion",
            "player",
            "art",
            "side",
            "opacity",
            "gradient",
            "blur",
            "scale",
        )
    fun applyObj(obj: JSONObject, pref: SharedPreferences, allowed: (String) -> Boolean) {
        val ed = pref.edit()
        obj.keys().forEach { key ->
            if (allowed(key))
                when (val v = obj.get(key)) {
                    is String -> ed.putString(key, v)
                    is Int -> ed.putInt(key, v)
                    is Boolean -> ed.putBoolean(key, v)
                    is Long -> ed.putLong(key, v)
                }
        }
        ed.apply()
    }
    fun transfer(u: Uri, writing: Boolean) {
        val theme = page == "Apariencia"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = c.contentResolver
                    if (writing) {
                        val settings = JSONObject()
                        p.all.forEach { (k, v) ->
                            if (
                                k in themeKeys ||
                                    !theme &&
                                        k in
                                            setOf(
                                                "eq",
                                                "bass",
                                                "treble",
                                                "fade",
                                                "speed",
                                                "wifi",
                                                "parallel",
                                            )
                            )
                                settings.put(k, v)
                        }
                        val doc =
                            JSONObject()
                                .put("version", 1)
                                .put("tipo", if (theme) "tema" else "copia")
                                .put("ajustes", settings)
                        if (!theme) {
                            doc.put(
                                "biblioteca",
                                JSONObject(data.all.filterKeys { it != "downloads" }),
                            )
                            doc.put(
                                "listas",
                                JSONObject(
                                    p.all.filterKeys {
                                        it.startsWith("smart:") || it.startsWith("cover:")
                                    }
                                ),
                            )
                        }
                        resolver.openOutputStream(u, "wt")!!.bufferedWriter().use {
                            it.write(doc.toString(2))
                        }
                    } else {
                        val obj =
                            resolver.openInputStream(u)!!.bufferedReader().use {
                                JSONObject(it.readText())
                            }
                        require(obj.getString("tipo") == if (theme) "tema" else "copia") {
                            "Archivo incompatible."
                        }
                        applyObj(obj.getJSONObject("ajustes"), p) {
                            it in themeKeys ||
                                !theme &&
                                    it in
                                        setOf(
                                            "eq",
                                            "bass",
                                            "treble",
                                            "fade",
                                            "speed",
                                            "wifi",
                                            "parallel",
                                        )
                        }
                        if (!theme) {
                            applyObj(obj.getJSONObject("biblioteca"), data) { it != "downloads" }
                            obj.optJSONObject("listas")?.let {
                                applyObj(it, p) { k ->
                                    k.startsWith("smart:") || k.startsWith("cover:")
                                }
                            }
                        }
                    }
                }
                m.reloadLibrary()
                m.notifyUser(if (writing) "Archivo exportado" else "Configuración restaurada")
            } catch (e: Exception) {
                m.notifyUser(e.message.orEmpty())
            }
        }
    }
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) {
            it?.let { u -> transfer(u, true) }
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let { u -> transfer(u, false) }
        }
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
            if (u != null)
                try {
                    c.contentResolver.takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    p.edit().putString("folder", u.toString()).apply()
                } catch (e: Exception) {
                    m.notifyUser("No se obtuvo acceso a la carpeta.")
                }
        }
    fun str(k: String, v: String) {
        p.edit().putString(k, v).apply()
    }
    fun num(k: String, v: Int) {
        p.edit().putInt(k, v).apply()
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Ajustes",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Chips(listOf("Apariencia", "Audio", "Descarga", "Datos"), page) { page = it }
        }
        when (page) {
            "Apariencia" -> {
                item {
                    Group("Tema") {
                        Chips(
                            listOf("Sistema", "Oscuro", "Claro", "AMOLED"),
                            p.getString("mode", "Oscuro").orEmpty(),
                        ) {
                            str("mode", it)
                        }
                        var hex by
                            remember(p.getString("accent", "")) {
                                mutableStateOf(p.getString("accent", "#A574FF").orEmpty())
                            }
                        OutlinedTextField(
                            hex,
                            {
                                hex = it
                                if (Regex("#[0-9A-Fa-f]{6}").matches(it)) str("accent", it)
                            },
                            singleLine = true,
                            label = { Text("Acento hexadecimal") },
                        )
                        Chips(listOf("Violeta", "Jade", "Océano", "Ámbar"), "") {
                            str(
                                "accent",
                                mapOf(
                                        "Violeta" to "#A574FF",
                                        "Jade" to "#22B791",
                                        "Océano" to "#529DFF",
                                        "Ámbar" to "#E3A944",
                                    )
                                    .getValue(it),
                            )
                        }
                        SwitchPref(p, "gradient", "Degradados", true)
                        SwitchPref(p, "art", "Acento de la carátula", false)
                        SwitchPref(p, "side", "Navegación lateral", false)
                        SwitchPref(p, "motion", "Animar carátula", false)
                    }
                }
                item {
                    Group("Lectura y reproductor") {
                        Chips(
                            listOf("Normal", "Compacta", "Amplia"),
                            p.getString("density", "Normal").orEmpty(),
                        ) {
                            str("density", it)
                        }
                        Chips(
                            listOf("Sans", "Serif", "Mono"),
                            p.getString("font", "Sans").orEmpty(),
                        ) {
                            str("font", it)
                        }
                        Adjust("Tamaño de texto", p.getInt("scale", 100), 85, 125) {
                            num("scale", it)
                        }
                        Chips(
                            listOf("Clásico", "Disco", "Compacto"),
                            p.getString("player", "Clásico").orEmpty(),
                        ) {
                            str("player", it)
                        }
                        Adjust("Opacidad del mini reproductor", p.getInt("opacity", 100), 50, 100) {
                            num("opacity", it)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 31)
                            Adjust("Desenfoque de fondo", p.getInt("blur", 0), 0, 24) {
                                num("blur", it)
                            }
                    }
                }
                item {
                    Group("Mis temas") {
                        TextButton({ export.launch("VeltraX-tema.json") }) { Text("Exportar tema") }
                        TextButton({ restore.launch(arrayOf("application/json", "text/plain")) }) {
                            Text("Importar tema")
                        }
                        TextButton({
                            p.edit().also { e -> themeKeys.forEach(e::remove) }.apply()
                        }) {
                            Text("Restaurar apariencia original")
                        }
                    }
                }
            }
            "Audio" ->
                item {
                    Group("Procesamiento de audio") {
                        val status by
                            com.veltrax.music.player.AudioStatus.equalizer.collectAsState()
                        Text(status)
                        SwitchPref(p, "eq", "Ecualizador", false)
                        Chips(listOf("Plano", "Graves", "Voz", "Brillo"), "") {
                            num("bass", if (it == "Graves") 6 else if (it == "Voz") -3 else 0)
                            num("treble", if (it == "Brillo") 6 else if (it == "Voz") 3 else 0)
                            p.edit().putBoolean("eq", true).apply()
                        }
                        Adjust("Graves · dB", p.getInt("bass", 0), -12, 12) { num("bass", it) }
                        Adjust("Agudos · dB", p.getInt("treble", 0), -12, 12) { num("treble", it) }
                        Adjust("Velocidad · %", p.getInt("speed", 100), 50, 200) {
                            num("speed", it)
                        }
                        Adjust("Crossfade local · segundos", p.getInt("fade", 0), 0, 8) {
                            num("fade", it)
                        }
                        Text("Temporizador de apagado")
                        Chips(listOf("Apagado", "15 min", "30 min", "60 min"), "") {
                            val n = it.substringBefore(' ').toLongOrNull() ?: 0
                            p.edit()
                                .putLong(
                                    "sleep",
                                    if (n == 0L) 0 else System.currentTimeMillis() + n * 60000,
                                )
                                .apply()
                            m.notifyUser(
                                if (n > 0) "Se detendrá en $n minutos"
                                else "Temporizador desactivado"
                            )
                        }
                    }
                }
            "Descarga" ->
                item {
                    Group("Transferencias") {
                        Adjust("Archivos simultáneos", p.getInt("parallel", 2), 1, 4) {
                            num("parallel", it)
                        }
                        SwitchPref(p, "wifi", "Solo Wi-Fi", false)
                        Text(
                            p.getString("folder", "").orEmpty().ifBlank {
                                "Music/VeltraX · Movies/VeltraX"
                            }
                        )
                        TextButton({ folder.launch(null) }) { Text("Elegir carpeta") }
                        TextButton({ p.edit().remove("folder").apply() }) {
                            Text("Usar carpeta de VeltraX")
                        }
                    }
                }
            else ->
                item {
                    Group("Biblioteca y privacidad") {
                        var key by remember { mutableStateOf(p.getString("youtube", "").orEmpty()) }
                        OutlinedTextField(
                            key,
                            { key = it },
                            label = { Text("Clave personal de YouTube Data API") },
                            singleLine = true,
                            visualTransformation =
                                androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        )
                        TextButton({
                            str("youtube", key.trim())
                            m.notifyUser("Clave guardada")
                        }) {
                            Text("Guardar clave")
                        }
                        TextButton({ export.launch("VeltraX-copia.json") }) {
                            Text("Exportar biblioteca y ajustes")
                        }
                        TextButton({ restore.launch(arrayOf("application/json", "text/plain")) }) {
                            Text("Restaurar desde copia JSON")
                        }
                        TextButton({
                            p.edit().remove("queries").apply()
                            data.edit().remove("history").apply()
                            m.notifyUser("Historial eliminado")
                        }) {
                            Text("Borrar historial de búsqueda y escucha")
                        }
                    }
                }
        }
    }
}

@Composable
private fun SwitchPref(p: SharedPreferences, key: String, title: String, default: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(p.getBoolean(key, default), { p.edit().putBoolean(key, it).apply() })
    }
}

@Composable
private fun Adjust(title: String, value: Int, min: Int, max: Int, set: (Int) -> Unit) {
    Text("$title · $value")
    Slider(
        value.coerceIn(min, max).toFloat(),
        { set(it.toInt().coerceIn(min, max)) },
        valueRange = min.toFloat()..max.toFloat(),
        steps = if (max - min < 12) max - min - 1 else 0,
    )
}

@Composable
internal fun PlayerArt(song: Song, m: MainViewModel, playing: Boolean) {
    val p = m.studio
    observe(p)
    val style = p.getString("player", "Clásico")
    val shape =
        if (style == "Disco") androidx.compose.foundation.shape.CircleShape
        else RoundedCornerShape(24.dp)
    val spinning = playing && style == "Disco" && p.getBoolean("motion", false)
    val angle =
        if (spinning) {
            val transition =
                androidx.compose.animation.core.rememberInfiniteTransition(label = "Carátula")
            val rotation by
                transition.animateFloat(
                    0f,
                    360f,
                    animationSpec =
                        androidx.compose.animation.core.infiniteRepeatable(
                            androidx.compose.animation.core.tween(
                                18000,
                                easing = androidx.compose.animation.core.LinearEasing,
                            )
                        ),
                    label = "Giro",
                )
            rotation
        } else 0f
    Box(
        Modifier.fillMaxWidth(if (style == "Compacto") .52f else .94f)
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Album,
            null,
            Modifier.size(88.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (song.artwork.isNotEmpty()) {
            val blur = p.getInt("blur", 0).coerceIn(0, 24)
            if (blur > 0)
                AsyncImage(
                    song.artwork,
                    null,
                    Modifier.fillMaxSize().blur(blur.dp),
                    contentScale = ContentScale.Crop,
                )
            AsyncImage(
                song.artwork,
                null,
                Modifier.fillMaxSize(if (blur > 0) .78f else 1f).rotate(angle).clip(shape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun PlayerExtras(s: Song, m: MainViewModel, position: Long) {
    var page by rememberSaveable { mutableStateOf("") }
    var lyric by
        remember(s.uri) { mutableStateOf(m.studio.getString("lrc:" + s.uri, "").orEmpty()) }
    var error by remember(s.uri) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val c = LocalContext.current
    val doc =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
            if (u != null)
                scope.launch {
                    try {
                        lyric =
                            withContext(Dispatchers.IO) {
                                c.contentResolver.openInputStream(u)!!.bufferedReader().use {
                                    it.readText().take(250000)
                                }
                            }
                        m.studio.edit().putString("lrc:" + s.uri, lyric).apply()
                    } catch (e: Exception) {
                        error = e.message.orEmpty()
                    }
                }
        }
    Chips(listOf("Cola", "Letras"), page) { page = if (page == it) "" else it }
    if (page == "Cola") {
        val queue by m.queueSongs.collectAsState()
        var limit by remember { mutableIntStateOf(30) }
        queue.take(limit).forEachIndexed { i, t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, Modifier.weight(1f).clickable { m.chooseQueue(i) }, maxLines = 1)
                TextButton({ m.moveQueue(i, i - 1) }, enabled = i > 0) { Text("↑") }
                TextButton({ m.moveQueue(i, i + 1) }, enabled = i < queue.lastIndex) { Text("↓") }
                IconButton({ m.dropQueue(i) }) { Icon(Icons.Default.Close, "Quitar") }
            }
        }
        if (limit < queue.size) TextButton({ limit += 30 }) { Text("Ver más") }
    }
    if (page == "Letras") {
        Row {
            TextButton(
                {
                    busy = true
                    error = ""
                    scope.launch {
                        try {
                            lyric = withContext(Dispatchers.IO) { Catalog.lyrics(s) }
                            m.studio.edit().putString("lrc:" + s.uri, lyric).apply()
                        } catch (e: Exception) {
                            error = e.message.orEmpty()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
            ) {
                Text("Buscar letra")
            }
            TextButton({ doc.launch(arrayOf("text/*", "application/octet-stream")) }) {
                Text("Abrir LRC o TXT")
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error)
        val lines =
            remember(lyric) {
                val re = Regex("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
                lyric.lines().flatMap { l ->
                    val times = re.findAll(l).toList()
                    val value = l.replace(re, "").trim()
                    if (times.isEmpty()) listOf(-1L to value)
                    else
                        times.map { v ->
                            ((v.groupValues[1].toLong() * 60 + v.groupValues[2].toLong()) * 1000 +
                                v.groupValues[3].padEnd(3, '0').toLong()) to value
                        }
                }
            }
        val current = lines.indexOfLast { it.first >= 0 && it.first <= position }
        lines.forEachIndexed { i, (time, line) ->
            if (line.isNotBlank())
                Text(
                    line,
                    Modifier.fillMaxWidth()
                        .clickable(enabled = time >= 0) { m.seek(time) }
                        .padding(3.dp),
                    color =
                        if (i == current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                )
        }
    }
}
