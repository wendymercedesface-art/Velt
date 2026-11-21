package com.veltrax.core

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class DirectAudioDownloaderTest {
    private fun withServer(
        bytes: ByteArray,
        ranges: Boolean = true,
        mime: String = "audio/mpeg",
        refuseResume: Boolean = false,
        responseCode: Int? = null,
        action: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/audio") { exchange ->
            try {
                if (responseCode != null) {
                    exchange.sendResponseHeaders(responseCode, -1)
                    return@createContext
                }
                exchange.responseHeaders.add("Content-Type", mime)
                exchange.responseHeaders.add("ETag", "\"test-v1\"")
                if (exchange.requestMethod == "HEAD") {
                    exchange.responseHeaders.add("Content-Length", bytes.size.toString())
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    val range = if (ranges) exchange.requestHeaders.getFirst("Range") else null
                    val bounds = range?.removePrefix("bytes=")?.split('-')
                    val start = bounds?.firstOrNull()?.toIntOrNull() ?: 0
                    val end = bounds?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex
                    if (start >= bytes.size || refuseResume && start > 0) {
                        exchange.sendResponseHeaders(416, -1)
                    } else {
                        if (range != null)
                            exchange.responseHeaders.add(
                                "Content-Range",
                                "bytes $start-$end/${bytes.size}",
                            )
                        exchange.sendResponseHeaders(
                            if (range == null) 200 else 206,
                            (end - start + 1).toLong(),
                        )
                        exchange.responseBody.write(bytes, start, end - start + 1)
                    }
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            action("http://127.0.0.1:${server.address.port}/audio")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun audio(size: Int) =
        ByteArray(size) { (it % 251).toByte() }.apply { "ID3".toByteArray().copyInto(this) }

    @Test
    fun segmentedDownloadPreservesEveryByte() {
        val bytes = audio(3_100_010)
        val folder = Files.createTempDirectory("veltrax-segments").toFile()
        try {
            withServer(bytes) { url ->
                val output = DirectAudioDownloader().download(url, folder, "segments", "test.mp3")
                assertArrayEquals(bytes, output.readBytes())
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun serverWithoutRangesDownloadsNormally() {
        val bytes = audio(150_000)
        val folder = Files.createTempDirectory("veltrax-single").toFile()
        try {
            withServer(bytes, ranges = false) { url ->
                assertArrayEquals(
                    bytes,
                    DirectAudioDownloader().download(url, folder, "single", "test.mp3").readBytes(),
                )
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun pauseOnLastByteCanResumeWithoutHttp416() {
        val bytes = audio(150_000)
        val folder = Files.createTempDirectory("veltrax-resume").toFile()
        try {
            withServer(bytes) { url ->
                val downloader = DirectAudioDownloader()
                var stopped = false
                try {
                    downloader.download(url, folder, "resume", "test.mp3", stopped = { stopped }) {
                        if (it.transferred == bytes.size.toLong()) stopped = true
                    }
                    fail("Should pause before publication")
                } catch (_: DirectAudioDownloader.Paused) {}
                assertArrayEquals(
                    bytes,
                    downloader.download(url, folder, "resume", "test.mp3").readBytes(),
                )
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun htmlResponseIsRejected() {
        val folder = Files.createTempDirectory("veltrax-html").toFile()
        try {
            withServer("<html>Not audio</html>".toByteArray(), mime = "text/html") { url ->
                try {
                    DirectAudioDownloader().download(url, folder, "html", "test.mp3")
                    fail("HTML must not be saved as music")
                } catch (expected: IOException) {
                    assertTrue(expected.message.orEmpty().contains("no un archivo de audio"))
                }
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun insufficientStorageReportsErrorBeforeWritingAudio() {
        val dir = Files.createTempDirectory("storage").toFile()
        try {
            withServer(audio(200000)) { url ->
                val failure =
                    runCatching {
                            DirectAudioDownloader { 1L }.download(url, dir, "space", "space.mp3")
                        }
                        .exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("Espacio insuficiente"))
                assertTrue(dir.listFiles().orEmpty().none { it.extension == "completed" })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun refusedRangeAfterPauseStartsFromZero() {
        val bytes = audio(520000)
        val dir = Files.createTempDirectory("range-changed").toFile()
        try {
            withServer(bytes, refuseResume = true) { url ->
                val download = DirectAudioDownloader()
                var stop = false
                try {
                    download.download(url, dir, "changed", "p.mp3", stopped = { stop }) {
                        if (it.transferred in 1 until bytes.size.toLong()) stop = true
                    }
                } catch (_: DirectAudioDownloader.Paused) {}
                assertTrue(stop)
                assertArrayEquals(
                    bytes,
                    download.download(url, dir, "changed", "p.mp3").readBytes(),
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun serverErrorsIncludeHttpStatus() {
        val dir = Files.createTempDirectory("http").toFile()
        try {
            for (code in listOf(404, 503)) withServer(audio(64), responseCode = code) { url ->
                val e =
                    runCatching { DirectAudioDownloader().download(url, dir, "bad", "p.mp3") }
                        .exceptionOrNull()
                assertTrue(e?.message.orEmpty().contains(code.toString()))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
