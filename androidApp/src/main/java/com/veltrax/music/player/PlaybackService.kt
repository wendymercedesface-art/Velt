package com.veltrax.music.player

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.Equalizer
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.veltrax.music.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

object AudioStatus {
    val equalizer = MutableStateFlow("Reproduce una pista para activar el ecualizador.")
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var eq: Equalizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("studio", 0) }
    private var tail: ExoPlayer? = null
    private var start = 0L
    private var window = 0L
    private var advancing = false

    private fun endFade() {
        tail?.release()
        tail = null
        player.volume = 1f
    }

    private fun tune() {
        val e = eq ?: return
        runCatching {
                e.enabled = prefs.getBoolean("eq", false)
                val n = e.numberOfBands.toInt()
                val limits = e.bandLevelRange
                for (i in 0 until n) {
                    val fraction = i.toFloat() / (n - 1).coerceAtLeast(1)
                    val db =
                        prefs.getInt("bass", 0) * (1 - fraction) +
                            prefs.getInt("treble", 0) * fraction
                    e.setBandLevel(
                        i.toShort(),
                        (db * 100).toInt().coerceIn(limits[0].toInt(), limits[1].toInt()).toShort(),
                    )
                }
                AudioStatus.equalizer.value =
                    if (e.enabled) "Ecualizador activo · $n bandas" else "Disponible · $n bandas"
            }
            .onFailure { AudioStatus.equalizer.value = "La salida actual no admite este efecto." }
    }

    private val listener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            when (k) {
                "eq",
                "bass",
                "treble" -> tune()
                "speed" -> player.setPlaybackSpeed(prefs.getInt("speed", 100) / 100f)
                "fade" -> endFade()
            }
        }

    override fun onCreate() {
        super.onCreate()
        player =
            ExoPlayer.Builder(this).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                setHandleAudioBecomingNoisy(true)
                setPlaybackSpeed(prefs.getInt("speed", 100) / 100f)
            }
        player.addListener(
            object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    eq?.release()
                    eq = null
                    if (audioSessionId <= 0) return
                    eq = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
                    if (eq == null)
                        AudioStatus.equalizer.value = "Ecualizador no disponible en esta salida."
                    else tune()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) endFade()
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (!advancing && tail != null) endFade()
                }
            }
        )
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        session =
            MediaSession.Builder(this, player)
                .setSessionActivity(open)
                .setCallback(
                    object : MediaSession.Callback {
                        override fun onAddMediaItems(
                            s: MediaSession,
                            c: MediaSession.ControllerInfo,
                            items: List<MediaItem>,
                        ): ListenableFuture<List<MediaItem>> {
                            if (c.packageName != packageName)
                                return Futures.immediateFuture(emptyList())
                            return Futures.immediateFuture(
                                items.mapNotNull {
                                    val u =
                                        it.localConfiguration?.uri
                                            ?: android.net.Uri.parse(it.mediaId)
                                    if (u.scheme in listOf("content", "http", "https"))
                                        it.buildUpon().setUri(u).build()
                                    else null
                                }
                            )
                        }
                    }
                )
                .build()
        scope.launch {
            while (isActive) {
                delay(120)
                val due = prefs.getLong("sleep", 0)
                if (due > 0 && System.currentTimeMillis() >= due) {
                    player.pause()
                    prefs.edit().remove("sleep").apply()
                }
                val fading = tail
                if (fading != null) {
                    val t =
                        ((SystemClock.elapsedRealtime() - start).toFloat() /
                                window.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                    fading.volume = 1 - t
                    player.volume = t
                    if (t >= 1) endFade()
                    continue
                }
                val length = prefs.getInt("fade", 0) * 1000L
                val remain = player.duration - player.currentPosition
                if (
                    length == 0L ||
                        !player.isPlaying ||
                        player.repeatMode == Player.REPEAT_MODE_ONE ||
                        !player.hasNextMediaItem() ||
                        player.duration <= 2 * length ||
                        remain !in 180L..length
                )
                    continue
                val current = player.currentMediaItem ?: continue
                val next = player.getMediaItemAt(player.nextMediaItemIndex)
                if (listOf(current, next).any { it.localConfiguration?.uri?.scheme != "content" })
                    continue
                val secondary = ExoPlayer.Builder(this@PlaybackService).build()
                secondary.setAudioAttributes(player.audioAttributes, false)
                secondary.setPlaybackSpeed(player.playbackParameters.speed)
                secondary.setMediaItem(current, player.currentPosition)
                secondary.prepare()
                secondary.play()
                tail = secondary
                window = remain
                start = SystemClock.elapsedRealtime()
                player.volume = 0f
                advancing = true
                try {
                    player.seekToNextMediaItem()
                } finally {
                    advancing = false
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        scope.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        endFade()
        eq?.release()
        session?.release()
        player.release()
        super.onDestroy()
    }
}
