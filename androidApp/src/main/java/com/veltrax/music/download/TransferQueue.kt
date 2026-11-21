package com.veltrax.music.download

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.database.sqlite.*
import android.net.*
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.veltrax.core.DirectAudioDownloader
import com.veltrax.music.data.LibraryStore
import java.io.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Transfer(
    val id: String,
    val url: String,
    val name: String,
    val group: String,
    val state: String,
    val bytes: Long,
    val total: Long,
    val speed: Long,
    val reason: String,
    val folder: String,
    val uri: String,
    val attempt: Int,
    val due: Long,
    val priority: Int,
)

class TransferQueue private constructor(val ctx: Context) :
    SQLiteOpenHelper(ctx, "transfers_3.db", null, 1) {
    val prefs = ctx.getSharedPreferences("studio", 0)
    val runner = Mutex()
    private val active =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE files(id TEXT PRIMARY KEY,url TEXT UNIQUE NOT NULL,name TEXT NOT NULL,batch TEXT,state TEXT,bytes INTEGER DEFAULT 0,total INTEGER DEFAULT -1,speed INTEGER DEFAULT 0,reason TEXT DEFAULT '',folder TEXT DEFAULT '',uri TEXT DEFAULT '',attempt INTEGER DEFAULT 0,due INTEGER DEFAULT 0,priority INTEGER DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    fun entries(): List<Transfer> =
        readableDatabase.rawQuery("SELECT * FROM files ORDER BY priority DESC,rowid", null).use { c
            ->
            buildList {
                while (c.moveToNext()) add(
                    Transfer(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getLong(5),
                        c.getLong(6),
                        c.getLong(7),
                        c.getString(8),
                        c.getString(9),
                        c.getString(10),
                        c.getInt(11),
                        c.getLong(12),
                        c.getInt(13),
                    )
                )
            }
        }

    fun change(sql: String, vararg args: Any) = writableDatabase.execSQL(sql, args)

    fun insert(
        url: String,
        name: String,
        batch: String = "",
        id: String = UUID.randomUUID().toString(),
        state: String = "Lista",
    ): Boolean {
        val u = Uri.parse(url.trim())
        require(
            u.scheme in listOf("http", "https") &&
                !u.host.isNullOrBlank() &&
                u.userInfo.isNullOrBlank()
        ) {
            "Usa un enlace público HTTP o HTTPS."
        }
        val canonical = u.buildUpon().fragment(null).build().toString()
        val row =
            ContentValues().apply {
                put("id", id)
                put("url", canonical)
                put("name", name.ifBlank { u.lastPathSegment ?: "Pista" }.take(160))
                put("batch", batch)
                put("state", state)
                put("folder", prefs.getString("folder", ""))
            }
        return writableDatabase.insertWithOnConflict(
            "files",
            null,
            row,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    fun control(ids: Set<String>, action: String) {
        ids.forEach { id ->
            when (action) {
                "Pausar" -> {
                    change(
                        "UPDATE files SET state='Pausada',speed=0 WHERE id=? AND state IN ('Lista','Descargando','Reintento','Sin red')",
                        id,
                    )
                    active[id]?.set(true)
                }
                "Continuar" -> {
                    val old = entries().firstOrNull { it.id == id }
                    if (
                        old != null &&
                            old.state == "Error" &&
                            old.uri.isNotEmpty() &&
                            old.folder != prefs.getString("folder", "")
                    ) {
                        runCatching { ctx.contentResolver.delete(Uri.parse(old.uri), null, null) }
                        change("UPDATE files SET uri='' WHERE id=?", id)
                    }
                    change(
                        "UPDATE files SET state='Lista',reason='',attempt=0,due=0,folder=CASE WHEN state='Error' AND uri='' THEN ? ELSE folder END WHERE id=? AND state IN ('Pausada','Error','Sin red','Reintento')",
                        prefs.getString("folder", "").orEmpty(),
                        id,
                    )
                }
                "Prioridad" -> change("UPDATE files SET priority=1-priority WHERE id=?", id)
                "Cancelar" -> {
                    change(
                        "UPDATE files SET state='Cancelada',speed=0 WHERE id=? AND state NOT IN ('Guardando','Completada')",
                        id,
                    )
                    active[id]?.set(true)
                    if (!active.containsKey(id)) clearParts(id)
                }
                "Quitar" ->
                    change(
                        "DELETE FROM files WHERE id=? AND state IN ('Cancelada','Completada','Error','Pausada')",
                        id,
                    )
            }
        }
        if (action == "Continuar") start()
    }

    private fun clearParts(id: String) {
        File(ctx.filesDir, "pending_downloads")
            .listFiles()
            ?.filter { it.name.startsWith("$id.") }
            ?.forEach { it.delete() }
    }

    fun connected(): Boolean {
        val m = ctx.getSystemService(ConnectivityManager::class.java)
        val c = m.getNetworkCapabilities(m.activeNetwork) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (!prefs.getBoolean("wifi", false) || c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    fun recovery(afterBoot: Boolean = false): Operation {
        return WorkManager.getInstance(ctx)
            .enqueueUniqueWork(
                "veltrax-studio-recovery",
                if (afterBoot) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<QueueRecovery>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(
                                if (prefs.getBoolean("wifi", false)) NetworkType.UNMETERED
                                else NetworkType.CONNECTED
                            )
                            .build()
                    )
                    .setInitialDelay(2, TimeUnit.SECONDS)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                    .build(),
            )
    }

    fun start() {
        recovery()
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
        } catch (e: RuntimeException) {
            Log.w("VeltraXQueue", "Inicio diferido por Android", e)
        }
    }

    suspend fun drain() =
        runner.withLock {
            withContext(Dispatchers.IO) {
                change(
                    "UPDATE files SET state='Lista',speed=0 WHERE state IN ('Descargando','Guardando')"
                )
                coroutineScope {
                    while (isActive) {
                        val online = connected()
                        if (online) change("UPDATE files SET state='Lista' WHERE state='Sin red'")
                        else {
                            change(
                                "UPDATE files SET state='Sin red',speed=0 WHERE state IN ('Lista','Reintento')"
                            )
                        }
                        val rows = entries().filter { it.state in setOf("Lista", "Reintento") }
                        if (online)
                            for (row in
                                rows
                                    .filter {
                                        it.due <= System.currentTimeMillis() &&
                                            it.id !in active.keys
                                    }
                                    .take(
                                        (prefs.getInt("parallel", 2).coerceIn(1, 4) - active.size)
                                            .coerceAtLeast(0)
                                    )) {
                                val flag = java.util.concurrent.atomic.AtomicBoolean()
                                active[row.id] = flag
                                launch {
                                    try {
                                        runInterruptible { runFile(row, flag) }
                                    } finally {
                                        active.remove(row.id)
                                        if (
                                            entries().none {
                                                it.id == row.id && it.state != "Cancelada"
                                            }
                                        )
                                            clearParts(row.id)
                                    }
                                }
                            }
                        if (active.isEmpty() && (rows.isEmpty() || !online)) break
                        delay(240)
                    }
                }
            }
        }

    private fun runFile(r: Transfer, flag: java.util.concurrent.atomic.AtomicBoolean) {
        val count =
            writableDatabase
                .compileStatement(
                    "UPDATE files SET state='Descargando',speed=0 WHERE id=? AND state IN ('Lista','Reintento')"
                )
                .use {
                    it.bindString(1, r.id)
                    it.executeUpdateDelete()
                }
        if (count == 0) return
        try {
            Log.i("VeltraXQueue", "Inicio ${r.id} host=${Uri.parse(r.url).host}")
            var last = SystemClock.elapsedRealtime()
            var previous = r.bytes
            val dir = File(ctx.filesDir, "pending_downloads")
            val ready = File(dir, "${r.id}.completed")
            val file =
                if (ready.isFile && ready.length() > 0 && validReady(r.id)) ready
                else
                    DirectAudioDownloader().download(
                        r.url,
                        dir,
                        r.id,
                        r.name,
                        3,
                        { flag.get() || Thread.currentThread().isInterrupted },
                    ) { p ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - last >= 350 || p.phase == "Completado") {
                            val speed =
                                (p.transferred - previous).coerceAtLeast(0) * 1000 /
                                    (now - last).coerceAtLeast(1)
                            change(
                                "UPDATE files SET bytes=?,total=?,speed=? WHERE id=? AND state='Descargando'",
                                p.transferred,
                                p.total,
                                speed,
                                r.id,
                            )
                            last = now
                            previous = p.transferred
                        }
                    }
            File(dir, "${r.id}.ready").writeText(file.length().toString())
            val save =
                writableDatabase
                    .compileStatement(
                        "UPDATE files SET state='Guardando',speed=0 WHERE id=? AND state='Descargando'"
                    )
                    .use {
                        it.bindString(1, r.id)
                        it.executeUpdateDelete()
                    }
            if (save == 0) return
            val (ext, mime) = format(file, r.name)
            val cleaned =
                r.name
                    .substringBeforeLast('.', r.name)
                    .replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_")
                    .take(110)
                    .ifBlank { "Pista" } + ".$ext"
            val resolver = ctx.contentResolver
            val dest =
                if (r.uri.isNotEmpty()) Uri.parse(r.uri)
                else {
                    val created =
                        if (r.folder.isNotEmpty()) {
                            val tree = Uri.parse(r.folder)
                            DocumentsContract.createDocument(
                                resolver,
                                DocumentsContract.buildDocumentUriUsingTree(
                                    tree,
                                    DocumentsContract.getTreeDocumentId(tree),
                                ),
                                mime,
                                cleaned,
                            )
                        } else {
                            val video = mime.startsWith("video/")
                            val collection =
                                if (video)
                                    MediaStore.Video.Media.getContentUri(
                                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                                    )
                                else
                                    MediaStore.Audio.Media.getContentUri(
                                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                                    )
                            resolver.insert(
                                collection,
                                ContentValues().apply {
                                    put("_display_name", cleaned)
                                    put("mime_type", mime)
                                    put(
                                        "relative_path",
                                        if (video) "Movies/VeltraX/" else "Music/VeltraX/",
                                    )
                                    put("is_pending", 1)
                                },
                            )
                        } ?: error("No se pudo crear el archivo. Revisa la carpeta de destino.")
                    change("UPDATE files SET uri=? WHERE id=?", created.toString(), r.id)
                    created
                }
            resolver.openOutputStream(dest, "wt")?.use { out ->
                file.inputStream().use { it.copyTo(out, 131072) }
            } ?: error("La carpeta no permite escribir.")
            if (r.folder.isEmpty())
                check(
                    resolver.update(
                        dest,
                        ContentValues().apply { put("is_pending", 0) },
                        null,
                        null,
                    ) == 1
                ) {
                    "No se pudo publicar el archivo."
                }
            change(
                "UPDATE files SET state='Completada',name=?,bytes=?,total=?,reason='' WHERE id=?",
                cleaned,
                file.length(),
                file.length(),
                r.id,
            )
            clearParts(r.id)
        } catch (e: Exception) {
            if (flag.get()) return
            val msg = e.message.orEmpty()
            val http = Regex("HTTP (\\d{3})").find(msg)?.groupValues?.get(1)?.toIntOrNull()
            val retry =
                when {
                    http != null -> http == 408 || http == 429 || http >= 500
                    e is SecurityException ||
                        e is IllegalArgumentException ||
                        listOf("espacio", "space", "ENOSPC", "texto", "reconocido", "vacío").any {
                            msg.contains(it, true)
                        } -> false
                    else -> e is IOException && e !is FileNotFoundException
                }
            val detail =
                when {
                    e is SecurityException ->
                        "Permiso de carpeta perdido. Elige otra carpeta y vuelve a intentar."
                    msg.contains("space", true) || msg.contains("ENOSPC") ->
                        "Espacio insuficiente. Libera almacenamiento."
                    else -> msg.ifBlank { e.javaClass.simpleName }.take(200)
                }
            val n = r.attempt + 1
            val state =
                if (retry && !connected()) "Sin red"
                else if (retry && n < 4) "Reintento" else "Error"
            change(
                "UPDATE files SET state=?,reason=?,attempt=?,due=?,speed=0 WHERE id=? AND state IN ('Descargando','Guardando')",
                state,
                detail,
                if (state == "Sin red") r.attempt else n,
                System.currentTimeMillis() + if (state == "Reintento") (1L shl n) * 1000 else 0,
                r.id,
            )
            Log.w("VeltraXQueue", "${r.id} $state HTTP=$http tipo=${e.javaClass.simpleName}")
        }
    }

    private fun validReady(id: String): Boolean {
        val d = File(ctx.filesDir, "pending_downloads")
        return runCatching {
                File(d, "$id.ready").readText().toLong() == File(d, "$id.completed").length()
            }
            .getOrDefault(false)
    }

    @Synchronized
    fun migrate() {
        if (prefs.getBoolean("migrated", false)) return
        val old = LibraryStore(ctx).downloads()
        if (old.isNotEmpty()) {
            val wm = WorkManager.getInstance(ctx)
            val states = wm.getWorkInfosByTag("veltrax").get().associateBy { it.id.toString() }
            wm.cancelAllWorkByTag("veltrax").result.get()
            old.forEach { r ->
                runCatching {
                    insert(
                        r.url,
                        r.fileName,
                        id = r.id,
                        state =
                            when (states[r.workId]?.state) {
                                WorkInfo.State.SUCCEEDED -> "Completada"
                                WorkInfo.State.CANCELLED,
                                null -> "Pausada"
                                WorkInfo.State.FAILED -> "Error"
                                else -> "Lista"
                            },
                    )
                }
            }
        }
        prefs.edit().putBoolean("migrated", true).commit()
    }

    companion object {
        @Volatile private var instance: TransferQueue? = null

        fun get(c: Context): TransferQueue =
            instance
                ?: synchronized(this) {
                    instance ?: TransferQueue(c.applicationContext).also { instance = it }
                }

        private fun format(f: File, name: String): Pair<String, String> {
            val b = ByteArray(32)
            f.inputStream().use { it.read(b) }
            val h = String(b, Charsets.ISO_8859_1)
            return when {
                h.startsWith("RIFF") -> "wav" to "audio/wav"
                h.startsWith("fLaC") -> "flac" to "audio/flac"
                h.startsWith("OggS") -> "ogg" to "audio/ogg"
                h.startsWith("FORM") -> "aiff" to "audio/aiff"
                h.startsWith("0&") -> "wma" to "audio/x-ms-wma"
                h.substring(4, 8) == "ftyp" -> {
                    val video =
                        runCatching {
                                val m = android.media.MediaMetadataRetriever()
                                try {
                                    m.setDataSource(f.path)
                                    m.extractMetadata(
                                        android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
                                    ) == "yes"
                                } finally {
                                    m.release()
                                }
                            }
                            .getOrDefault(name.endsWith(".mp4", true))
                    if (video) "mp4" to "video/mp4" else "m4a" to "audio/mp4"
                }
                h.startsWith("ADIF") || (b[0].toInt() == -1 && b[1].toInt() and 246 == 240) ->
                    "aac" to "audio/aac"
                else -> "mp3" to "audio/mpeg"
            }
        }
    }
}

private fun notice(c: Context): Notification {
    val channel = "veltrax_transfer"
    c.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(
            NotificationChannel(channel, "Transferencias", NotificationManager.IMPORTANCE_LOW)
        )
    return NotificationCompat.Builder(c, channel)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("VeltraX · Descargas")
        .setContentText("Procesando la cola de transferencias")
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .build()
}

class TransferService : Service() {
    val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var job: Job? = null
    private var latest = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latest = startId
        startForeground(1824, notice(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (job?.isActive != true)
            job =
                scope.launch {
                    try {
                        do {
                            val start = latest
                            TransferQueue.get(this@TransferService).drain()
                        } while (start != latest)
                    } finally {
                        stopSelfResult(latest)
                    }
                }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        TransferQueue.get(this).recovery()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class QueueRecovery(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val q = TransferQueue.get(applicationContext)
            if (
                q.entries().none {
                    it.state in setOf("Lista", "Descargando", "Guardando", "Reintento", "Sin red")
                }
            )
                return@withContext Result.success()
            if (!q.connected() || q.runner.isLocked) return@withContext Result.retry()
            try {
                try {
                    setForeground(
                        ForegroundInfo(
                            1825,
                            notice(applicationContext),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        )
                    )
                } catch (e: IllegalStateException) {
                    // A scheduled worker can still copy files within its system execution window.
                    Log.w(
                        "VeltraXQueue",
                        "Android mantuvo la recuperación en ejecución programada",
                        e,
                    )
                }
                q.drain()
                if (q.entries().any { it.state == "Sin red" }) Result.retry() else Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("VeltraXQueue", "Recuperación diferida", e)
                Result.retry()
            }
        }
}

class QueueBootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
                try {
                    val q = TransferQueue.get(c)
                    if (
                        q.entries().any {
                            it.state in
                                setOf("Lista", "Descargando", "Guardando", "Reintento", "Sin red")
                        }
                    ) {
                        q.recovery(afterBoot = true).result.get(7, TimeUnit.SECONDS)
                        Log.i("VeltraXQueue", "Recuperación programada tras el reinicio")
                    }
                } catch (e: Exception) {
                    Log.w("VeltraXQueue", "No se pudo actualizar la programación", e)
                } finally {
                    pending.finish()
                }
            }
            .start()
    }
}
