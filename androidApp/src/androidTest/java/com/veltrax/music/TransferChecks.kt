package com.veltrax.music

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import androidx.media3.common.MediaItem
import androidx.media3.session.*
import com.veltrax.music.data.*
import com.veltrax.music.download.TransferQueue
import java.io.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Device test runner. development/device_checks.py supplies a deterministic HTTP origin. */
class TransferChecks : Instrumentation() {
    private var params = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        params = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val log = StringBuilder()
        val result = runCatching {
            val c = targetContext
            val q = TransferQueue.get(c)
            val base = params.getString("url")!!
            val phase = params.getString("phase") ?: "suite"
            val id = UUID.randomUUID().toString()
            fun add(path: String): String {
                val url = "$base/$path?$id"
                check(q.insert(url, "$path-$id.wav", "Verificación"))
                return url
            }
            fun waitFor(
                url: String,
                condition: (com.veltrax.music.download.Transfer) -> Boolean,
            ): com.veltrax.music.download.Transfer {
                val end = SystemClock.elapsedRealtime() + 90000
                while (SystemClock.elapsedRealtime() < end) {
                    val r = q.entries().first { it.url == url }
                    if (condition(r)) return r
                    Thread.sleep(150)
                }
                error("Tiempo agotado: " + q.entries().first { it.url == url })
            }
            fun complete(url: String): com.veltrax.music.download.Transfer {
                val r = waitFor(url) { it.state == "Completada" || it.state == "Error" }
                check(r.state == "Completada") { r.reason }
                check(
                    c.contentResolver.openInputStream(Uri.parse(r.uri))!!.use { input ->
                        val md = java.security.MessageDigest.getInstance("SHA-256")
                        val b = ByteArray(8192)
                        while (true) {
                            val n = input.read(b)
                            if (n < 0) break
                            md.update(b, 0, n)
                        }
                        md.digest().joinToString("") { "%02x".format(it) }
                    } == params.getString("hash")
                )
                return r
            }
            if (phase == "prepare") {
                val r = add("restart")
                q.change("UPDATE files SET state='Descargando' WHERE url=?", r)
                c.getSharedPreferences("device_test", 0)
                    .edit()
                    .putString("url", r)
                    .putInt(
                        "boot",
                        android.provider.Settings.Global.getInt(c.contentResolver, "boot_count"),
                    )
                    .commit()
                q.recovery()
                check(
                    androidx.work.WorkManager.getInstance(c)
                        .getWorkInfosForUniqueWork("veltrax-studio-recovery")
                        .get()
                        .isNotEmpty()
                )
                log.append("Transferencia persistida antes del reinicio")
            } else if (phase == "verify") {
                val p = c.getSharedPreferences("device_test", 0)
                check(
                    android.provider.Settings.Global.getInt(c.contentResolver, "boot_count") >
                        p.getInt("boot", Int.MAX_VALUE)
                )
                complete(p.getString("url", null)!!)
                log.append("Reinicio real y archivo íntegro")
            } else {
                val screen =
                    startActivitySync(
                        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                waitForIdleSync()
                q.migrate()
                val a = add("slow-a")
                val b = add("slow-b")
                check(!q.insert(a, "duplicada"))
                check(runCatching { q.insert("URL inválida", "invalida") }.isFailure)
                q.start()
                waitFor(a) { it.state == "Descargando" && it.bytes > 0 }
                val rb = waitFor(b) { it.state == "Descargando" || it.state == "Completada" }
                check(rb.state == "Descargando")
                val ra = q.entries().first { it.url == a }
                q.control(setOf(ra.id), "Pausar")
                check(q.entries().first { it.url == a }.state == "Pausada")
                q.control(setOf(ra.id), "Continuar")
                complete(a)
                val saved = complete(b)
                log.appendLine("Cola, simultaneidad, pausa, reanudación, duplicado y URL inválida")
                val bad = add("missing")
                val broken = add("interrupted")
                val fail = add("server")
                q.start()
                val failed = waitFor(bad) { it.state == "Error" }
                check("404" in failed.reason && failed.attempt == 1)
                complete(broken)
                check(
                    waitFor(fail) { it.state == "Error" }
                        .let { "503" in it.reason && it.attempt == 4 }
                )
                log.appendLine(
                    "HTTP 404, HTTP 503 acotado, conexión interrumpida y archivo idéntico"
                )
                check(
                    Catalog.m3u((1..1200).joinToString("\n") { "pista$it.wav" }, "$base/lista.m3u")
                        .size == 1200
                )
                val item = MediaItem.Builder().setMediaId(saved.uri).setUri(saved.uri).build()
                val future =
                    MediaController.Builder(
                            c,
                            SessionToken(
                                c,
                                ComponentName(
                                    c,
                                    com.veltrax.music.player.PlaybackService::class.java,
                                ),
                            ),
                        )
                        .setApplicationLooper(Looper.getMainLooper())
                        .buildAsync()
                val player = future.get(15, TimeUnit.SECONDS)
                try {
                    runOnMainSync {
                        player.setMediaItem(item)
                        player.prepare()
                        player.play()
                    }
                    var progress = 0L
                    val end = SystemClock.elapsedRealtime() + 25000
                    while (progress < 200 && SystemClock.elapsedRealtime() < end) {
                        runOnMainSync {
                            progress = if (player.isPlaying) player.currentPosition else 0L
                        }
                        Thread.sleep(100)
                    }
                    check(progress >= 200)
                    runOnMainSync { screen.moveTaskToBack(true) }
                    Thread.sleep(700)
                    runOnMainSync { check(player.isPlaying && player.currentPosition > progress) }
                    log.append("Playlist de 1200 pistas y reproducción en segundo plano")
                } finally {
                    runOnMainSync {
                        player.stop()
                        MediaController.releaseFuture(future)
                        screen.finish()
                    }
                }
            }
        }
        val out = Bundle()
        out.putString(
            "stream",
            if (result.isSuccess) "\nOK\n$log\n"
            else "\nFALLO\n$log\n" + result.exceptionOrNull()?.stackTraceToString(),
        )
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, out)
    }
}
