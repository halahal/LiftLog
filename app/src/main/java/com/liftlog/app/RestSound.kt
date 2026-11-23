package com.liftlog.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.annotation.RawRes
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RestTone(val label: String, @RawRes internal val clip: Int) {
    Bell("柔和铃声", R.raw.rest_bell),
    DoubleBeep("双声提示", R.raw.rest_double),
    Rising("渐升旋律", R.raw.rest_rising),
    Electronic("电子短音", R.raw.rest_electronic),
}

data class RestSoundSettings(val tone: RestTone = RestTone.Bell, val volume: Int = 55)

/** Shares one player between previews and real alerts; call from the main thread. */
@MainThread
class RestSoundController(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("rest_sound", Context.MODE_PRIVATE)
    private fun readSaved(): RestSoundSettings {
        val savedTone = prefs.getString("tone", null)
        return RestSoundSettings(
            tone = RestTone.entries.firstOrNull { it.name == savedTone } ?: RestTone.Bell,
            volume = prefs.getInt("volume", 55).coerceIn(0, 100),
        )
    }
    private val mutableSettings = MutableStateFlow(readSaved())
    val settings: StateFlow<RestSoundSettings> = mutableSettings.asStateFlow()

    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val duckRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private var ownsAudioFocus = false
    private var player: MediaPlayer? = null
    private var playingPreview = false

    fun save(settings: RestSoundSettings) {
        val valid = settings.copy(volume = settings.volume.coerceIn(0, 100))
        prefs.edit().putString("tone", valid.tone.name).putInt("volume", valid.volume).apply()
        mutableSettings.value = valid
    }

    /** True means playback started, or zero volume intentionally produced silence. */
    fun preview(settings: RestSoundSettings): Boolean {
        // A simultaneous preview need not interrupt an actual alert.
        if (player != null && !playingPreview) return false
        return play(settings, preview = true)
    }

    /** Read prefs at expiry, including changes made since the Service was created. */
    internal fun playLoop(): Boolean = play(readSaved(), preview = false)

    internal fun stopLoop() {
        if (!playingPreview) stop()
    }

    fun stopPreview() {
        if (playingPreview) stop()
    }

    fun stop() {
        val previous = player
        player = null
        playingPreview = false
        // Releasing an active player stops playback as well.
        try {
            previous?.release()
        } finally {
            if (ownsAudioFocus) {
                ownsAudioFocus = false
                runCatching { audioManager?.abandonAudioFocusRequest(duckRequest) }
            }
        }
    }

    fun close() = stop()

    private fun play(settings: RestSoundSettings, preview: Boolean): Boolean {
        stop()
        if (settings.volume <= 0) return true
        return runCatching {
            val next = MediaPlayer.create(app, settings.tone.clip, attributes, 0)
                ?: return false
            player = next
            playingPreview = preview
            next.setOnCompletionListener { completed -> if (player === completed) stop() }
            next.setOnErrorListener { failed, _, _ ->
                if (player === failed) stop()
                true
            }
            val level = settings.volume.coerceIn(0, 100) / 100f
            next.setVolume(level, level)
            next.isLooping = !preview
            if (!preview) {
                // Other players may duck if Android grants the request. A denial
                // should not prevent a timer alarm from playing on its own stream.
                ownsAudioFocus = runCatching {
                    audioManager?.requestAudioFocus(duckRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }.getOrDefault(false)
            }
            next.start()
            true
        }.getOrElse {
            stop()
            false
        }
    }
}
