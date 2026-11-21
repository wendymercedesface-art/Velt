package com.veltrax.music.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import coil.imageLoader
import com.veltrax.music.MainViewModel
import com.veltrax.music.data.Song
import kotlinx.coroutines.delay

private val Background: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val CardBg: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val Purple: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val Violet: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer
private val Muted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val gradient: Brush
    @Composable
    get() {
        val p = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("studio", 0)
        return Brush.linearGradient(
            if (p.getBoolean("gradient", true))
                listOf(Violet, androidx.compose.ui.graphics.lerp(Violet, Purple, .48f), Violet)
            else listOf(Violet, Violet)
        )
    }

private enum class Tab(val label: String) {
    HOME("Inicio"),
    SEARCH("Buscar"),
    LIBRARY("Biblioteca"),
    DOWNLOADS("Descargas"),
    SETTINGS("Ajustes"),
}

@Composable
fun VeltraXScreen(
    model: MainViewModel,
    hasPermission: Boolean,
    shared: String,
    consumed: () -> Unit,
    onPermission: () -> Unit,
) {
    val songs by model.songs.collectAsState()
    val favorites by model.favorites.collectAsState()
    val playlists by model.playlists.collectAsState()
    val prefs = model.studio
    observe(prefs)

    val playing by model.nowPlaying.collectAsState()
    val isPlaying by model.isPlaying.collectAsState()
    val position by model.position.collectAsState()
    val duration by model.duration.collectAsState()
    val shuffle by model.shuffle.collectAsState()
    val repeat by model.repeat.collectAsState()
    val error by model.error.collectAsState()
    var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(shared) { if (shared.isNotBlank()) selected = Tab.DOWNLOADS }
    var showPlayer by remember { mutableStateOf(false) }
    var addSong by remember { mutableStateOf<Song?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var createPlaylist by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                model.refreshProgress()
                model.refreshDownloads()
                delay(if (selected == Tab.DOWNLOADS || playing != null) 850 else 2400)
            }
        }
    }

    val mode = prefs.getString("mode", "Oscuro")
    val light = mode == "Claro" || mode == "Sistema" && !isSystemInDarkTheme()
    val fg = if (light) Color(0xFF171522) else Color(0xFFF5F1FC)
    val bg =
        if (light) Color(0xFFF8F7FA) else if (mode == "AMOLED") Color.Black else Color(0xFF101017)
    val accent =
        Color(
            runCatching { android.graphics.Color.parseColor(prefs.getString("accent", "#A574FF")) }
                .getOrDefault(0xFFA574FF.toInt())
        )
    var coverAccent by remember { mutableStateOf<Color?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(playing?.artwork, prefs.getBoolean("art", false)) {
        coverAccent = null
        val art = playing?.artwork.orEmpty()
        if (prefs.getBoolean("art", false) && art.isNotEmpty())
            coverAccent =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                            val img =
                                ctx.imageLoader
                                    .execute(
                                        coil.request.ImageRequest.Builder(ctx)
                                            .data(art)
                                            .size(24)
                                            .allowHardware(false)
                                            .build()
                                    )
                                    .drawable as? android.graphics.drawable.BitmapDrawable
                            val bmp = img?.bitmap
                            bmp?.let { Color(it.getPixel(it.width / 2, it.height / 2)) }
                        }
                        .getOrNull()
                }
    }
    val hue = coverAccent ?: accent
    val scheme =
        (if (light) lightColorScheme() else darkColorScheme()).copy(
            background = bg,
            surface = bg,
            onSurface = fg,
            onBackground = fg,
            primary = if (light) androidx.compose.ui.graphics.lerp(hue, Color.Black, .35f) else hue,
            primaryContainer = androidx.compose.ui.graphics.lerp(hue, Color.Black, .45f),
            onPrimaryContainer = Color.White,
            surfaceVariant = if (light) Color(0xFFEBE9F1) else Color(0xFF201E29),
        )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val factor =
        when (prefs.getString("density", "Normal")) {
            "Compacta" -> .9f
            "Amplia" -> 1.1f
            else -> 1f
        }
    val family =
        when (prefs.getString("font", "Sans")) {
            "Serif" -> androidx.compose.ui.text.font.FontFamily.Serif
            "Mono" -> androidx.compose.ui.text.font.FontFamily.Monospace
            else -> androidx.compose.ui.text.font.FontFamily.SansSerif
        }
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(
                density.density * factor,
                density.fontScale * prefs.getInt("scale", 100) / 100f,
            )
    ) {
        val type = Typography()
        MaterialTheme(
            colorScheme = scheme,
            typography =
                type.copy(
                    bodyLarge = type.bodyLarge.copy(fontFamily = family),
                    bodyMedium = type.bodyMedium.copy(fontFamily = family),
                    titleMedium = type.titleMedium.copy(fontFamily = family),
                ),
        ) {
            Scaffold(
                containerColor = Background,
                topBar = { BrandBar() },
                bottomBar = {
                    Column {
                        if (playing != null)
                            MiniPlayer(
                                playing!!,
                                isPlaying,
                                onOpen = { showPlayer = true },
                                onToggle = model::togglePlayback,
                                onNext = model::next,
                            )
                        if (!prefs.getBoolean("side", false))
                            NavigationBar(containerColor = Background, tonalElevation = 0.dp) {
                                Tab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = selected == tab,
                                        onClick = {
                                            selected = tab
                                            selectedPlaylist = null
                                        },
                                        icon = {
                                            Icon(
                                                when (tab) {
                                                    Tab.HOME -> Icons.Default.Home
                                                    Tab.SEARCH -> Icons.Default.Search
                                                    Tab.LIBRARY -> Icons.Default.LibraryMusic
                                                    Tab.SETTINGS -> Icons.Default.Settings
                                                    Tab.DOWNLOADS -> Icons.Default.Download
                                                },
                                                null,
                                            )
                                        },
                                        label = { Text(tab.label, maxLines = 1, fontSize = 11.sp) },
                                        colors =
                                            NavigationBarItemDefaults.colors(
                                                selectedIconColor = Color.White,
                                                selectedTextColor = Purple,
                                                indicatorColor = Violet,
                                                unselectedIconColor = Muted,
                                                unselectedTextColor = Muted,
                                            ),
                                    )
                                }
                            }
                    }
                },
            ) { inner ->
                Row(Modifier.padding(inner).fillMaxSize()) {
                    if (prefs.getBoolean("side", false))
                        NavigationRail {
                            Tab.entries.forEach { t ->
                                NavigationRailItem(
                                    selected == t,
                                    { selected = t },
                                    icon = { Text(t.label.take(1)) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        error?.let { message ->
                            Row(
                                Modifier.fillMaxWidth().background(CardBg).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    message,
                                    modifier = Modifier.weight(1f),
                                    color = scheme.error,
                                    fontSize = 13.sp,
                                )
                                IconButton(onClick = model::dismissError) {
                                    Icon(Icons.Default.Close, "Cerrar aviso")
                                }
                            }
                        }
                        if (!hasPermission && selected == Tab.LIBRARY) {
                            Box(
                                Modifier.padding(horizontal = 16.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(CardBg)
                                    .padding(14.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Acceso a música local", fontWeight = FontWeight.Bold)
                                    Text(
                                        "Permite el acceso para incluir la música guardada en otras carpetas del teléfono.",
                                        color = Muted,
                                    )
                                    Button(onClick = onPermission) { Text("Permitir acceso") }
                                }
                            }
                        }
                        when (selected) {
                            Tab.HOME ->
                                HomePage(
                                    songs,
                                    playlists,
                                    model,
                                    { selected = Tab.SEARCH },
                                    { selected = Tab.DOWNLOADS },
                                    onSelectPlaylist = {
                                        selected = Tab.LIBRARY
                                        selectedPlaylist = it
                                    },
                                    onAddSong = { addSong = it },
                                )
                            Tab.SEARCH -> SearchStudio(model, songs) { addSong = it }
                            Tab.LIBRARY ->
                                CollectionPage(model, songs, selectedPlaylist) { addSong = it }
                            Tab.DOWNLOADS -> DownloadsStudio(model, shared, consumed)
                            Tab.SETTINGS -> StudioSettings(model)
                        }
                    }
                }
            }
            if (showPlayer && playing != null)
                Dialog(
                    onDismissRequest = { showPlayer = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    PlayerSheet(
                        playing!!,
                        isPlaying,
                        position,
                        duration,
                        favorites.contains(playing!!.uri),
                        shuffle,
                        repeat,
                        onClose = { showPlayer = false },
                        onToggle = model::togglePlayback,
                        onNext = model::next,
                        onPrevious = model::previous,
                        onSeek = { model.seek(it) },
                        onFavorite = { model.toggleFavorite(playing!!.uri) },
                        onShuffle = model::toggleShuffle,
                        onRepeat = model::cycleRepeat,
                        model = model,
                    )
                }
            addSong?.let { song ->
                AlertDialog(
                    onDismissRequest = { addSong = null },
                    title = { Text("Añadir a playlist") },
                    text = {
                        Column {
                            if (playlists.isEmpty())
                                Text("Crea primero una playlist desde Biblioteca.")
                            playlists.keys.forEach { playlist ->
                                TextButton(
                                    onClick = {
                                        model.addToPlaylist(playlist, song.uri)
                                        addSong = null
                                    }
                                ) {
                                    Text(playlist)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { addSong = null }) { Text("Cerrar") } },
                )
            }
            if (createPlaylist) {
                var name by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { createPlaylist = false },
                    title = { Text("Nueva playlist") },
                    text = {
                        OutlinedTextField(
                            name,
                            onValueChange = { name = it },
                            label = { Text("Nombre") },
                            singleLine = true,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                model.createPlaylist(name)
                                createPlaylist = false
                            }
                        ) {
                            Text("Crear")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { createPlaylist = false }) { Text("Cancelar") }
                    },
                )
            }
        }
    }
}

@Composable
private fun BrandBar() {
    Row(
        Modifier.fillMaxWidth()
            .background(Background)
            .statusBarsPadding()
            .padding(horizontal = 19.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(43.dp).clip(RoundedCornerShape(13.dp)).background(gradient),
            contentAlignment = Alignment.Center,
        ) {
            Text("V", fontWeight = FontWeight.Black, fontSize = 25.sp, color = Color.White)
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Veltra", fontSize = 25.sp, fontWeight = FontWeight.Black)
                Text("X", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Purple)
            }
            Text(
                "M Ú S I C A  S I N  L Í M I T E S",
                fontSize = 8.sp,
                letterSpacing = 1.sp,
                color = Muted,
            )
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.Headphones, contentDescription = null, tint = Purple)
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 17.dp)) {
        Text(title, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun HomePage(
    songs: List<Song>,
    playlists: Map<String, List<String>>,
    model: MainViewModel,
    search: () -> Unit,
    downloads: () -> Unit,
    onSelectPlaylist: (String) -> Unit,
    onAddSong: (Song) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            PageHeading(
                if (hour < 12) "Buenos días"
                else if (hour < 19) "Buenas tardes" else "Buenas noches",
                "Tu música en VeltraX",
            )
        }
        item {
            Group(if (songs.isEmpty()) "Descubre música" else "Tu biblioteca") {
                if (songs.isEmpty()) Text("Busca una canción, artista o playlist en línea.")
                else Text("${songs.size} pistas disponibles")
                Row {
                    Button(
                        if (songs.isEmpty()) search else ({ model.play(songs.first(), songs) })
                    ) {
                        Text(if (songs.isEmpty()) "Buscar música" else "Reproducir")
                    }
                    TextButton(downloads) { Text("Descargas") }
                }
            }
        }
        val index = songs.associateBy { it.uri }
        val rec = model.history.distinct().take(6).mapNotNull(index::get)
        if (rec.isNotEmpty()) item { SectionTitle("Escuchadas recientemente") }
        items(rec) { s -> SongRow(s, model, rec, onAddSong) }
        val fav = songs.filter { it.uri in model.favorites.value }.take(6)
        if (fav.isNotEmpty()) item { SectionTitle("Favoritas") }
        items(fav) { s -> SongRow(s, model, fav, onAddSong) }
        val artist = rec.firstOrNull()?.artist
        val related =
            if (artist.isNullOrBlank()) emptyList()
            else songs.filter { it.artist == artist && it !in rec }.take(5)
        if (related.isNotEmpty()) item { SectionTitle("Más de $artist") }
        items(related) { s -> SongRow(s, model, related, onAddSong) }
        item { SectionTitle("Añadidas recientemente") }
        if (songs.isEmpty())
            item {
                EmptyState(
                    "Todavía no hay canciones",
                    "Concede permiso de audio o descarga un archivo de audio directo.",
                )
            }
        items(songs.take(8), key = { "home_${it.uri}" }) { song ->
            SongRow(song, model, songs, onAddSong)
        }
        item { SectionTitle("Tus playlists") }
        if (playlists.isEmpty())
            item {
                EmptyState(
                    "Crea tu primera playlist",
                    "Desde Biblioteca puedes organizar las canciones.",
                )
            }
        items(playlists.keys.toList()) { name ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBg)
                    .clickable { onSelectPlaylist(name) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.QueueMusic, null, tint = Purple)
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text("${playlists[name]?.size ?: 0} canciones", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun SongRow(song: Song, model: MainViewModel, queue: List<Song>, onAdd: (Song) -> Unit) {
    val favorites by model.favorites.collectAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 69.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clickable { model.play(song, queue) }) { Cover(song, 54) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable { model.play(song, queue) }) {
            Text(
                song.title,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                fontSize = 13.sp,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { model.toggleFavorite(song.uri) }) {
            Icon(
                if (song.uri in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                "Favorito",
                tint = if (song.uri in favorites) Purple else Muted,
            )
        }
        IconButton(onClick = { onAdd(song) }) {
            Icon(Icons.Default.PlaylistAdd, "Añadir a playlist", tint = Muted)
        }
    }
    HorizontalDivider(color = Color(0xFF272131), thickness = .5.dp)
}

@Composable
private fun MiniPlayer(
    song: Song,
    playing: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                CardBg.copy(
                    alpha =
                        (androidx.compose.ui.platform.LocalContext.current
                                .getSharedPreferences("studio", 0)
                                .getInt("opacity", 100) / 100f)
                            .coerceIn(.5f, 1f)
                )
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(gradient)
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.MusicNote, null, tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(
                song.title,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
            )
            Text(song.artist, fontSize = 12.sp, color = Muted, maxLines = 1)
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                "Reproducir o pausar",
                tint = Purple,
                modifier = Modifier.size(35.dp),
            )
        }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Siguiente") }
    }
}

@Composable
private fun PlayerSheet(
    song: Song,
    playing: Boolean,
    position: Long,
    duration: Long,
    favorite: Boolean,
    shuffle: Boolean,
    repeat: Int,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onFavorite: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    model: MainViewModel,
) {
    var scrub by remember(song.uri) { mutableFloatStateOf(-1f) }
    val percent = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(
        Modifier.widthIn(max = 480.dp)
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .clip(RoundedCornerShape(30.dp))
            .background(CardBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar") }
        }
        PlayerArt(song, model, playing)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                Text(song.artist, color = Muted)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    "Favorito",
                    tint = Purple,
                )
            }
        }
        Spacer(Modifier.height(15.dp))
        Slider(
            value = if (scrub >= 0f) scrub else percent,
            onValueChange = { scrub = it },
            onValueChangeFinished = {
                onSeek((scrub * duration).toLong())
                scrub = -1f
            },
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(position), fontSize = 12.sp, color = Muted)
            Text(clock(duration), fontSize = 12.sp, color = Muted)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconToggleButton(checked = shuffle, onCheckedChange = { onShuffle() }) {
                Icon(
                    Icons.Default.Shuffle,
                    "Reproducción aleatoria",
                    tint = if (shuffle) Purple else Muted,
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Default.SkipPrevious, "Anterior", modifier = Modifier.size(33.dp))
            }
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Violet),
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    "Reproducir o pausar",
                    modifier = Modifier.size(38.dp),
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, "Siguiente", modifier = Modifier.size(33.dp))
            }
            IconButton(onClick = onRepeat) {
                Icon(
                    if (repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne
                    else Icons.Default.Repeat,
                    when (repeat) {
                        Player.REPEAT_MODE_ONE -> "Repetir una"
                        Player.REPEAT_MODE_ALL -> "Repetir todas"
                        else -> "Repetición desactivada"
                    },
                    tint = if (repeat != Player.REPEAT_MODE_OFF) Purple else Muted,
                )
            }
        }
        PlayerExtras(song, model, position)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun EmptyState(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.LibraryMusic, null, tint = Purple, modifier = Modifier.size(33.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.Bold)
        Text(description, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

private fun clock(ms: Long): String =
    if (ms <= 0) "0:00" else "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2,'0')}"
