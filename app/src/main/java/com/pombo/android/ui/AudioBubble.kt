package com.pombo.android.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.pombo.android.UiMessage
import com.pombo.android.ui.theme.PomboColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** m:ss, the same as the web's formatAudioTime. */
fun formatAudioTime(ms: Long): String {
    val total = ((ms.coerceAtLeast(0) + 500) / 1000)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** One voice message plays at a time, across every bubble. */
object VoicePlayer {
    data class State(val id: String? = null, val playing: Boolean = false, val positionMs: Long = 0L)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var ticker: Job? = null
    private val scope = MainScope()

    fun toggle(context: Context, id: String, bytes: ByteArray, mime: String?) {
        val current = player
        if (_state.value.id == id && current != null) {
            if (current.isPlaying) current.pause() else current.play()
            publish()
            return
        }
        stop()
        try {
            val file = writeTemp(context, id, bytes, mime)
            player = ExoPlayer.Builder(context.applicationContext).build().apply {
                addListener(object : Player.Listener {
                    // Qualified: inside apply, a bare stop() is ExoPlayer.stop().
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) VoicePlayer.stop()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
                    override fun onPlayerError(error: PlaybackException) {
                        Log.w("VoicePlayer", "playback error ${error.errorCodeName}: ${error.message}")
                        VoicePlayer.stop()
                    }
                })
                setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                prepare()
                play()
            }
            _state.value = State(id, true, 0L)
            ticker = scope.launch {
                while (isActive) {
                    publish()
                    delay(200)
                }
            }
        } catch (e: Exception) {
            Log.w("VoicePlayer", "cannot play $id: ${e.message}")
            stop()
        }
    }

    fun seek(id: String, fraction: Float, durationMs: Long) {
        val p = player ?: return
        if (_state.value.id != id || durationMs <= 0) return
        p.seekTo((durationMs * fraction.coerceIn(0f, 1f)).toLong())
        publish()
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        val p = player
        player = null
        _state.value = State()
        p?.release()
    }

    private fun publish() {
        val p = player ?: return
        _state.value = _state.value.copy(playing = p.playWhenReady, positionMs = p.currentPosition)
    }

    private fun writeTemp(context: Context, id: String, bytes: ByteArray, mime: String?): File {
        val ext = when {
            mime == null -> "bin"
            mime.startsWith("audio/mp4") -> "m4a"
            mime.startsWith("audio/webm") -> "webm"
            mime.startsWith("audio/ogg") -> "ogg"
            else -> "bin"
        }
        val dir = File(context.cacheDir, "voice-play").apply { mkdirs() }
        dir.listFiles()?.forEach { if (!it.name.startsWith(id)) it.delete() }
        return File(dir, "$id.$ext").apply { if (!exists() || length() != bytes.size.toLong()) writeBytes(bytes) }
    }
}

@Composable
fun AudioBubbleContent(msg: UiMessage, textColor: Color) {
    val context = LocalContext.current
    val state by VoicePlayer.state.collectAsState()
    val bytes = msg.imageBytes
    val active = state.id == msg.id
    val duration = msg.audioDurationMs
    val position = if (active) state.positionMs else 0L
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.width(220.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(PomboColors.Overlay10)
                .clickable(enabled = bytes != null) {
                    if (bytes != null) VoicePlayer.toggle(context, msg.id, bytes, msg.imageMime)
                }
                .semantics { contentDescription = if (active && state.playing) "Pause voice message" else "Play voice message" },
            contentAlignment = Alignment.Center
        ) {
            if (bytes == null) {
                CircularProgressIndicator(Modifier.size(14.dp), color = PomboColors.Overlay40, strokeWidth = 2.dp)
            } else {
                Icon(
                    if (active && state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(16.dp)
                .pointerInput(msg.id, duration) {
                    detectTapGestures { offset ->
                        VoicePlayer.seek(msg.id, offset.x / size.width, duration)
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(PomboColors.Overlay15))
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(PomboColors.Overlay60))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatAudioTime(if (active && (state.playing || position > 0)) position else duration),
            color = PomboColors.Overlay40,
            fontSize = 12.sp
        )
    }
}
