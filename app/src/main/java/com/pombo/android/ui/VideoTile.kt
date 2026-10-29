package com.pombo.android.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.pombo.android.R
import com.pombo.android.ui.theme.PomboColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

/** Videos whose play started a download: they play as soon as the bytes land. */
private val autoplayRequests: MutableSet<String> = Collections.synchronizedSet(HashSet())

/**
 * The video shown fullscreen, drawn by [VideoFullscreenHost] over the whole app.
 * Not a Dialog: some ROMs clamp a dialog window short of the notch and navigation areas.
 */
val LocalVideoFullscreen = staticCompositionLocalOf<MutableState<Uri?>?> { null }

/**
 * A video message is the player itself (web `.video-tile`). On a video not yet
 * here, play is what downloads it. The player is created only on play, so a
 * list of videos holds none.
 */
@Composable
fun VideoTile(
    id: String,
    fileName: String,
    size: String,
    source: Uri?,
    downloading: Boolean,
    progress: Float,
    status: String,
    working: Boolean = false,
    thumbnail: String? = null,
    onDownload: (() -> Unit)?,
    onSave: (() -> Unit)?,
    onRetry: (() -> Unit)? = null
) {
    var playing by remember(id, source) { mutableStateOf(source != null && autoplayRequests.remove(id)) }
    val fullscreen = LocalVideoFullscreen.current
    val pill = status.ifEmpty { if (source == null) size else "" }
    val streaming = source != null && playing

    Column(Modifier.width(300.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(PomboColors.Overlay6),
            contentAlignment = Alignment.Center
        ) {
            if (source == null) ThumbnailPoster(thumbnail)
            when {
                source != null && playing -> VideoPlayer(source, Modifier.fillMaxSize())
                source != null -> {
                    VideoPoster(source)
                    RoundButton(Icons.Filled.PlayArrow, "Play video", 52.dp) { playing = true }
                }
                downloading -> Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
                onDownload != null -> RoundButton(Icons.Filled.PlayArrow, "Play video", 52.dp) {
                    autoplayRequests.add(id)
                    onDownload()
                }
            }
            if (source != null && fullscreen != null) RoundButton(
                Icons.Filled.Fullscreen, "Fullscreen", 28.dp,
                Modifier.align(Alignment.TopEnd).padding(6.dp)
            ) { playing = false; fullscreen.value = source }
            if (pill.isNotEmpty()) Text(
                pill,
                color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(if (source != null) Alignment.TopStart else Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            )
            if (downloading && !streaming) DownloadBar(progress, working, Modifier.align(Alignment.BottomCenter))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(fileName, color = PomboColors.Overlay40, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(size, color = PomboColors.Overlay40, fontSize = 11.sp, maxLines = 1)
            if (onSave != null) Text(
                "Save", color = PomboColors.Overlay40, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.clickable(onClick = onSave)
            )
            if (onRetry != null) Text(
                "Retry", color = PomboColors.Overlay40, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.clickable(onClick = onRetry)
            )
        }
    }
}

@Composable
private fun DownloadBar(progress: Float, working: Boolean, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.12f))) {
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                .alpha(workingAlpha(working)).background(Color.White.copy(alpha = 0.85f))
        )
    }
}

/** A full progress bar pulses while its upload confirms what it sent (web `.is-working`). */
@Composable
fun workingAlpha(working: Boolean): Float {
    if (!working) return 1f
    val pulse by rememberInfiniteTransition(label = "working").animateFloat(
        initialValue = 1f, targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "working-alpha"
    )
    return pulse
}

/** The fullscreen player, placed last in the app's root so it covers everything but the toasts. */
@Composable
fun VideoFullscreenHost(state: MutableState<Uri?>) {
    val source = state.value ?: return
    BackHandler { state.value = null }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
    ) {
        VideoPlayer(source, Modifier.fillMaxSize())
        RoundButton(
            Icons.Filled.Close, "Close", 40.dp,
            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp)
        ) { state.value = null }
    }
}

/** The announce's first-frame thumbnail, shown until the video itself is here. */
@Composable
private fun ThumbnailPoster(thumbnail: String?) {
    val image = remember(thumbnail) {
        thumbnail?.let {
            runCatching {
                val bytes = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    } ?: return
    Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

@Composable
private fun VideoPoster(source: Uri) {
    val context = LocalContext.current
    val frame by produceState<Bitmap?>(null, source) {
        value = withContext(Dispatchers.IO) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, source)
                r.getFrameAtTime(0)
            } catch (e: Exception) {
                null
            } finally {
                r.release()
            }
        }
    }
    frame?.let {
        Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(source: Uri, modifier: Modifier) {
    val context = LocalContext.current
    var failed by remember(source) { mutableStateOf(false) }
    val player = remember(source) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { VideoDataSource(context) }))
            .build().apply {
                setMediaItem(MediaItem.fromUri(source))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    if (failed) {
        Text("This video can't be played here", color = PomboColors.Overlay60, fontSize = 13.sp)
    } else {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.pombo_player_view, null) as PlayerView)
                    .also { it.player = player }
            },
            modifier = modifier
        )
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.55f))
    }
}
