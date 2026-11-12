package com.pombo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pombo.android.ui.theme.PomboColors
import kotlinx.coroutines.launch

/**
 * Fullscreen media viewer — the web's `MediaHandler.openLightbox`.
 *
 * Matches the web's affordances: fills the screen over a black backdrop, allows
 * pinch-zoom and pan, closes on backdrop tap or the system back gesture, and
 * offers a save action. Double-tap toggles fit/zoom, which is the Android
 * idiom for the web's pinch-zoom unlock.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun MediaLightbox(bytes: ByteArray, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loader = MediaLoader.get(context)
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Tapping the backdrop closes, like clicking the web overlay.
                .clickableNoRipple(onDismiss),
            contentAlignment = Alignment.Center
        ) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(context).data(bytes).build(),
                contentDescription = "image",
                imageLoader = loader,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // Clamp so the image can never be shrunk away or
                            // zoomed so far that panning back is impossible.
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale <= 1f) androidx.compose.ui.geometry.Offset.Zero
                            else offset + pan
                        }
                    }
                    .combinedClickable(
                        // Consume taps on the image itself so they don't reach
                        // the dismissing backdrop while the user is zooming.
                        onClick = { if (scale > 1f) Unit else onDismiss() },
                        onDoubleClick = {
                            if (scale > 1f) {
                                scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero
                            } else scale = 2.5f
                        }
                    )
            )

            val bars = WindowInsets.systemBars.asPaddingValues()
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = bars.calculateTopPadding() + 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .background(PomboColors.Overlay10, CircleShape)
                        .clickableNoRipple {
                            if (!saving) {
                                saving = true
                                scope.launch {
                                    saveImageToGallery(context, bytes)
                                    saving = false
                                }
                            }
                        }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            color = Color.White, strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            Icons.Filled.Download, contentDescription = "Save image",
                            tint = Color.White, modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(PomboColors.Overlay10, CircleShape)
                        .clickableNoRipple(onDismiss)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Close",
                        tint = Color.White, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Write a received image into the device gallery.
 *
 * Uses MediaStore's scoped-storage path on Q+, which needs no permission at
 * all; the legacy branch needs WRITE_EXTERNAL_STORAGE, so it degrades to a
 * no-op rather than crashing if that was never granted.
 */
private suspend fun saveImageToGallery(context: android.content.Context, bytes: ByteArray): Boolean =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val (mime, ext) = imageType(bytes)
            val name = "pombo_${System.currentTimeMillis()}.$ext"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pombo")
                }
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return@withContext false
            true
        } catch (e: Exception) {
            android.util.Log.w("PomboMedia", "Save to gallery failed: ${e.message}")
            false
        }
    }
