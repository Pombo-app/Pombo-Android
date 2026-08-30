package com.pombo.android.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Download
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.pombo.android.UiMessage
import com.pombo.android.data.Channel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.theme.PomboColors


/**
 * One animated-image loader for the whole app.
 *
 * This used to be built per-composable: a LazyColumn disposes an item when it
 * scrolls out of view, so every image rebuilt its loader and re-decoded on the
 * way back — the same mistake that made avatars flash while scrolling.
 */
private object MediaLoader {
    @Volatile private var instance: coil.ImageLoader? = null
    fun get(context: android.content.Context): coil.ImageLoader = instance ?: synchronized(this) {
        instance ?: coil.ImageLoader.Builder(context.applicationContext)
            .components {
                // GIF/animated WebP need a decoder registered; without one Coil
                // shows the first frame only. The web animates for free via <img>.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    add(coil.decode.ImageDecoderDecoder.Factory())
                } else {
                    add(coil.decode.GifDecoder.Factory())
                }
            }
            .build()
            .also { instance = it }
    }
}

@Composable
private fun ImageBubbleContent(msg: UiMessage, onOpen: (() -> Unit)? = null) {
    val bytes = msg.imageBytes
    if (bytes != null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val loader = MediaLoader.get(context)
        coil.compose.AsyncImage(
            model = coil.request.ImageRequest.Builder(context).data(bytes).build(),
            contentDescription = "image",
            imageLoader = loader,
            // Fit scales UP to the floor as well as down to the cap.
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier
                // A floor as well as a cap: small GIFs/stickers (a 100px sticker
                // rendered at 100px) came out too small to read. Fit grows them
                // into the minimum while keeping their aspect ratio.
                .sizeIn(minWidth = 200.dp, minHeight = 150.dp, maxWidth = 260.dp, maxHeight = 320.dp)
                .clip(RoundedCornerShape(8.dp))
                // Web wires every rendered image to openLightbox(); without this
                // a received photo could never be viewed above 260×320dp.
                .then(if (onOpen != null) Modifier.clickableNoRipple(onOpen) else Modifier)
        )
    } else {
        Box(
            Modifier
                .size(200.dp, 140.dp)
                .background(PomboColors.Background, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = PomboColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text("Receiving image…", color = PomboColors.TextDim, fontSize = 11.sp)
            }
        }
    }
}

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
private fun MediaLightbox(bytes: ByteArray, onDismiss: () -> Unit) {
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
                        .background(Color.White.copy(alpha = 0.10f), CircleShape)
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
                        .background(Color.White.copy(alpha = 0.10f), CircleShape)
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
            val name = "pombo_${System.currentTimeMillis()}.jpg"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
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

// ==================== message grouping (web: MessageGrouper.js) ====================

/** Position of a message inside its group — drives the bubble corner radii. */
private enum class GroupPos { SINGLE, FIRST, MIDDLE, LAST }

/** Consecutive messages from the same sender, sharing one avatar rail. */
internal class MsgGroup(val sender: String, val mine: Boolean, val items: List<UiMessage>)

/** Messages more than 2 minutes apart start a new group, like the web. */
private const val GROUP_TIME_THRESHOLD_MS = 2 * 60 * 1000L

private fun shouldGroup(a: UiMessage?, b: UiMessage?): Boolean {
    if (a == null || b == null) return false
    if (!a.sender.equals(b.sender, ignoreCase = true)) return false
    if (kotlin.math.abs(b.timestamp - a.timestamp) > GROUP_TIME_THRESHOLD_MS) return false
    return sameDay(a.timestamp, b.timestamp)
}

internal fun buildMessageGroups(messages: List<UiMessage>): List<MsgGroup> {
    val out = mutableListOf<MsgGroup>()
    var current = mutableListOf<UiMessage>()
    for (msg in messages) {
        if (current.isEmpty() || shouldGroup(current.last(), msg)) {
            current.add(msg)
        } else {
            out.add(MsgGroup(current.first().sender, current.first().mine, current))
            current = mutableListOf(msg)
        }
    }
    if (current.isNotEmpty()) out.add(MsgGroup(current.first().sender, current.first().mine, current))
    return out
}

private fun groupPosition(index: Int, size: Int): GroupPos = when {
    size == 1 -> GroupPos.SINGLE
    index == 0 -> GroupPos.FIRST
    index == size - 1 -> GroupPos.LAST
    else -> GroupPos.MIDDLE
}

/** Web components.css: the stack effect radii, per side and position. */
private fun bubbleShape(mine: Boolean, pos: GroupPos): RoundedCornerShape = if (mine) when (pos) {
    GroupPos.SINGLE -> RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
    GroupPos.FIRST -> RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    GroupPos.MIDDLE -> RoundedCornerShape(16.dp, 4.dp, 4.dp, 16.dp)
    GroupPos.LAST -> RoundedCornerShape(16.dp, 4.dp, 0.dp, 16.dp)
} else when (pos) {
    GroupPos.SINGLE -> RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
    GroupPos.FIRST -> RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    GroupPos.MIDDLE -> RoundedCornerShape(4.dp, 16.dp, 16.dp, 4.dp)
    GroupPos.LAST -> RoundedCornerShape(4.dp, 16.dp, 16.dp, 0.dp)
}

/**
 * One message group: a single avatar rail plus the stack of bubbles
 * (web MessageRenderer.buildMessageGroupOpenHTML). The avatar sits at the
 * bottom of the group and overhangs the last bubble by 12dp.
 */
@Composable
internal fun MessageGroup(
    group: MsgGroup,
    reactions: Map<String, Map<String, Set<String>>>,
    myAddress: String?,
    /** On-chain DELETE permission on this channel (web isAdminUser). */
    canModerate: Boolean,
    /** Channel creator, who can never be banned. */
    channelCreator: String?,
    /** The list this group lives in, and its item index — for the sticky avatar. */
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemIndex: Int,
    hidden: Set<String>,
    pins: List<com.pombo.android.ChannelManager.Pin>,
    activeId: String?,
    onActivate: (String) -> Unit,
    isContact: (String) -> Boolean,
    onReact: (String, String, Boolean) -> Unit,
    onReply: (UiMessage) -> Unit,
    /** Starts editing this message in the composer (web parity) — no commit here. */
    onEdit: (UiMessage) -> Unit,
    onDelete: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onHide: (String, Boolean) -> Unit,
    /** (address, client enforcement, protocol enforcement) */
    onBan: (String, Boolean, Boolean) -> Unit,
    /** Gated channel: the protocol level has a gate to ban on. */
    banGated: Boolean = false,
    /** Only the creator may publish the client-level ban. */
    canClientBan: Boolean = false,
    onAddContact: (String) -> Unit,
    onSendDm: (String) -> Unit,
    onRemoveContact: (String) -> Unit = {},
    /** Non-null only inside a DM. */
    onBlock: (() -> Unit)? = null,
    onJumpTo: ((String) -> Unit)? = null,
    /** Id of the message currently pulsing after a jump, if any. */
    highlightId: String? = null
) {
    val first = group.items.first()
    // `.message-entry { animation: fadeIn 0.3s ease-in-out }` — opacity 0→1
    // plus a 5px rise. Without it messages snap into place, which is very
    // noticeable in an active channel. Keyed on the group so it plays once per
    // group rather than on every recomposition.
    var entered by remember(first.id) { mutableStateOf(false) }
    LaunchedEffect(first.id) { entered = true }
    val enterAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "message-fade"
    )
    val enterOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (entered) 0.dp else 5.dp,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "message-rise"
    )
    // `.message-group { max-width: 85% }` — the MOBILE value. The desktop rule
    // says 60% and the mobile block overrides it with the comment "Messages
    // take more width on mobile" (components.css:2412-2418). Measured from
    // Chrome's computed styles at a 360px viewport, not read off the sheet.
    //
    // The 85% caps the WHOLE group — avatar rail and gap included — measured
    // against the list's content width, not the raw screen. Applying it to the
    // bubble stack alone (and against the screen) let a long message run about
    // 50dp wider than the web's, eating the gutter the reply/react triggers
    // live in and reaching the screen edge.
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val listContentWidth = screenWidth - 24.dp          // LazyColumn h-padding
    val maxGroupWidth = listContentWidth * 0.85f
    // The rail and its 12dp gap come out of that budget, exactly as they do in
    // the web's flex row.
    val maxStackWidth = maxGroupWidth - 36.dp - 12.dp

    // `.message-group-avatar-rail { position: sticky; bottom: 0 }` — while a
    // tall group is scrolled so its bottom sits below the viewport, the avatar
    // rides down with it and stays pinned to the bottom edge, rather than
    // scrolling away with the group's start. Compose has no sticky-within-item,
    // so it is computed: how far the group's bottom is past the visible bottom,
    // clamped so the avatar never rises above the group's own top.
    val avatarPx = with(LocalDensity.current) { 36.dp.toPx() }
    val stickyInsetPx = with(LocalDensity.current) { 12.dp.toPx() }
    /** Must match the group Row's bottom padding, which reserves the overhang. */
    val bottomReservePx = with(LocalDensity.current) { 14.dp.toPx() }
    // The 12dp overhang is drawn against a bubble's frame. When the group
    // CLOSES with bare content (jumbo emoji, image, file card) the group's
    // bottom edge is the footer (timestamp) hanging under the content — the
    // avatar rises past it and aligns with the CONTENT's bottom instead
    // (web: the :has() rule on the rail's margin-bottom).
    val last = group.items.last()
    val lastBare = last.isImage || last.file != null || last.storageFile != null ||
        (emojiOnlyKind(last.text) != null)
    val overhangPx = with(LocalDensity.current) { if (lastBare) (-18).dp.toPx() else 12.dp.toPx() }

    /**
     * `.message-group-avatar-rail { position: sticky; bottom: 0 }`.
     *
     * Read from [listState] rather than measured: `layoutInfo` already knows
     * every visible item's offset and size, updated by the scroll itself. The
     * first attempt captured the group's position in `onGloballyPositioned` and
     * wrote it into layout state — which invalidates layout, which fires
     * `onGloballyPositioned` again, which never settles. That loop was the
     * violent flicker. Reading here is safe precisely because nothing is
     * written back: layout depends on scroll, scroll does not depend on layout.
     */
    fun stickyShift(): Float {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == itemIndex } ?: return 0f
        // Reversed list: offsets run from the viewport's BOTTOM edge upward,
        // and `item.offset` is the group's bottom edge. A negative offset is
        // how far that bottom sits past the visible area — exactly how far
        // the avatar must ride up to stay on screen.
        val overshoot = (info.viewportStartOffset + stickyInsetPx) - item.offset
        // Clamp so the avatar's TOP never rises above the group's top — the
        // web's sticky is bounded by its containing block the same way. Its
        // unshifted top sits at (item height − bottom reserve − avatar + the
        // overhang offset), and that distance is exactly how far it may travel.
        val maxShift = item.size - bottomReservePx - avatarPx + overhangPx
        return overshoot.coerceIn(0f, maxShift.coerceAtLeast(0f))
    }

    Row(
        Modifier
            .fillMaxWidth()
            // Reserve the overhang so it is not clipped — the web does the same
            // with `.msg-group-last { margin-bottom: 12px }` against the rail's
            // `margin-bottom: -12px`. Two extra dp of slack: at exactly 12dp the
            // avatar's bottom curve landed on the clip edge and still read as
            // shaved.
            .padding(bottom = 14.dp)
            // The alpha layer exists ONLY while the 300ms entry animation
            // runs. A permanent graphicsLayer forces this subtree into
            // offscreen compositing, and a WebView's video frames do not
            // survive that — the YouTube embed played audio over a black
            // picture on phone and emulator alike until this was made
            // conditional.
            .let { m -> if (enterAlpha < 1f) m.graphicsLayer { alpha = enterAlpha } else m }
            .offset(y = enterOffset),
        horizontalArrangement = if (group.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!group.mine) {
            Avatar(
                // `.message-avatar` is 46px on desktop but 36px in the mobile
                // block ("Smaller message avatars", components.css:2420).
                group.sender, size = 36.dp, cornerRadiusFraction = 0.5,
                ensAvatarUrl = first.ensAvatar,
                // .message-group { gap: 12px } — sized in the CSS to clear the
                // bubble tail's 5px overhang. The rail also hangs 12px below
                // the last bubble (`.message-group-avatar-rail`
                // margin-bottom: -12px), which is what makes the avatar sit
                // level with the bubble's bottom edge rather than above it.
                modifier = Modifier.padding(end = 12.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(0, (overhangPx - stickyShift()).toInt()) }
            )
        }
        Column(
            horizontalAlignment = if (group.mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),  // spacing-stack
            modifier = Modifier.widthIn(max = maxStackWidth)
        ) {
            group.items.forEachIndexed { i, msg ->
                MessageBubble(
                    msg = msg,
                    pos = groupPosition(i, group.items.size),
                    reactions = reactions[msg.id].orEmpty(),
                    myAddress = myAddress,
                    canModerate = canModerate,
                    channelCreator = channelCreator,
                    isHidden = msg.id in hidden,
                    isPinned = pins.any { it.targetId == msg.id },
                    isActive = activeId == msg.id,
                    isContact = isContact(msg.sender),
                    onActivate = { onActivate(msg.id) },
                    onReact = { emoji, add -> onReact(msg.id, emoji, add) },
                    onReply = { onReply(msg) },
                    onEdit = { onEdit(msg) },
                    onDelete = { onDelete(msg.id) },
                    onPin = { pin -> onPin(msg.id, pin) },
                    onHide = { hide -> onHide(msg.id, hide) },
                    onBan = { client, protocol -> onBan(msg.sender, client, protocol) },
                    banGated = banGated,
                    canClientBan = canClientBan,
                    onAddContact = { onAddContact(msg.sender) },
                    onSendDm = { onSendDm(msg.sender) },
                    onRemoveContact = { onRemoveContact(msg.sender) },
                    onBlock = onBlock,
                    onJumpTo = onJumpTo,
                    highlighted = highlightId == msg.id
                )
            }
        }
        if (group.mine) {
            Avatar(
                group.sender, size = 36.dp, cornerRadiusFraction = 0.5,
                ensAvatarUrl = first.ensAvatar,
                modifier = Modifier.padding(start = 12.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(0, (overhangPx - stickyShift()).toInt()) }
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun MessageBubble(
    msg: UiMessage,
    pos: GroupPos,
    reactions: Map<String, Set<String>>,
    myAddress: String?,
    canModerate: Boolean = false,
    /** Channel creator, who can never be banned (web `isCreator`). */
    channelCreator: String? = null,
    isHidden: Boolean = false,
    isPinned: Boolean = false,
    isActive: Boolean = false,
    isContact: Boolean = false,
    onActivate: () -> Unit,
    onReact: (String, Boolean) -> Unit,
    onReply: () -> Unit,
    /** Starts editing this message in the composer (web parity) — no commit here. */
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: (Boolean) -> Unit = {},
    onHide: (Boolean) -> Unit = {},
    onBan: (Boolean, Boolean) -> Unit = { _, _ -> },
    banGated: Boolean = false,
    canClientBan: Boolean = false,
    onAddContact: () -> Unit = {},
    onSendDm: () -> Unit = {},
    onRemoveContact: () -> Unit = {},
    /** Non-null only inside a DM — blocking is a DM-scoped action. */
    onBlock: (() -> Unit)? = null,
    /** Jump to another message by id (reply quotes). */
    onJumpTo: ((String) -> Unit)? = null,
    /** True while this message is pulsing after a jump landed on it. */
    highlighted: Boolean = false
) {
    val bubbleColor = if (msg.mine) PomboColors.BubbleOwn else PomboColors.BubbleOther
    val bubbleTextColor = if (msg.mine) PomboColors.BubbleOwnText else PomboColors.BubbleOtherText
    val shape = bubbleShape(msg.mine, pos)
    val emojiKind = remember(msg.text, msg.isImage) {
        if (msg.isImage) null else emojiOnlyKind(msg.text)
    }
    // Web: `[data-type="image"] .message-bubble { padding: .25rem; background:
    // transparent }` — an image carries its own shape, so the bubble behind it
    // would just be a frame. Emoji-only messages lose the fill for the same
    // reason, and so do file transfers: their card IS the bubble on the web,
    // and a filled bubble around it reads as a double frame.
    val bareBubble = emojiKind != null || msg.isImage || msg.file != null || msg.storageFile != null
    // Web MessageRenderer.buildMessageHTML always emits the sender row, for own
    // messages too; only `.msg-group-middle/.msg-group-last .message-sender-row
    // { display: none }` hides it. Excluding `msg.mine` here left our own
    // messages without a name or verification badge.
    val showSender = pos == GroupPos.SINGLE || pos == GroupPos.FIRST
    var menu by remember { mutableStateOf(false) }
    // Where the long-press landed, in window coordinates, so the menu can open
    // under the thumb like the web does instead of anchored to the bubble.
    var menuAt by remember { mutableStateOf(androidx.compose.ui.unit.IntOffset.Zero) }
    var bubbleOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var picker by remember { mutableStateOf(false) }
    var confirmBan by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    var lightbox by remember { mutableStateOf(false) }
    // The CSS keyframe peaks at 30% of the way through; animating to the peak
    // and back reproduces the pulse without a keyframe API.
    val highlightPulse by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (highlighted) 0.10f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (highlighted) 450 else 1050,
            easing = androidx.compose.animation.core.LinearEasing
        ),
        label = "highlight-pulse"
    )
    val me = myAddress?.lowercase()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    if (lightbox) {
        msg.imageBytes?.let { MediaLightbox(it) { lightbox = false } }
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Column(horizontalAlignment = if (msg.mine) Alignment.End else Alignment.Start) {
            Box {
                // Action triggers sit in the gutter beside the bubble
                // (`.react-trigger` / `.reply-trigger`, absolute at ±28px).
                // They are an overlay on the bubble's Box rather than a sibling
                // in the Row: as a sibling they either pushed the bubble or, in
                // the zero-width version, went missing on wide messages because
                // there was no room left in the row for them to be placed into.
                // Centred rather than bottom-anchored as the CSS is: anchored to
                // the bottom, the lower of the two triggers fell behind the
                // composer on the last message and could not be reached.
                if (isActive) MessageActionTriggers(
                    modifier = Modifier
                        .align(if (msg.mine) Alignment.CenterStart else Alignment.CenterEnd)
                        .offset(x = if (msg.mine) (-30).dp else 30.dp),
                    onReply = onReply,
                    onReact = { picker = true }
                )
                Column(
                    Modifier
                        // Tail first so it paints under the bubble's own fill,
                        // in the same colour (`background: inherit`).
                        .bubbleTail(
                            mine = msg.mine, pos = pos,
                            color = if (msg.verified == false) Color(0xFF7F1D1D).copy(alpha = 0.20f)
                                else bubbleColor,
                            // Image and emoji-only bubbles have no fill, so a
                            // tail would be a shape floating on its own.
                            show = !bareBubble
                        )
                        // Width is capped by the group (max-width: 85%); the
                        // bubble itself is fit-content within that.
                        // Web tints the bubble red when the signature fails, and
                        // drops it entirely for emoji-only messages.
                        .background(
                            when {
                                msg.verified == false -> Color(0xFF7F1D1D).copy(alpha = 0.20f)
                                bareBubble -> Color.Transparent
                                else -> bubbleColor
                            },
                            shape
                        )
                        .then(
                            if (msg.verified == false)
                                Modifier.border(1.dp, Color(0xFFEF4444).copy(alpha = 0.50f), shape)
                            else Modifier
                        )
                        // @keyframes highlightPulse: transparent → white 10% →
                        // transparent over 1.5s, so a jump lands somewhere the
                        // eye can actually find.
                        .then(
                            if (highlightPulse > 0f)
                                Modifier.background(Color.White.copy(alpha = highlightPulse), shape)
                            else Modifier
                        )
                        // Track where this bubble sits so a local touch offset
                        // can be turned into a window coordinate for the popup.
                        .onGloballyPositioned { bubbleOrigin = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero) }
                        // detectTapGestures rather than combinedClickable: only
                        // this one reports WHERE the long-press happened, which
                        // is what lets the menu open under the finger.
                        .pointerInput(msg.id) {
                            detectTapGestures(
                                onTap = { onActivate() },
                                onLongPress = { local ->
                                    menuAt = androidx.compose.ui.unit.IntOffset(
                                        (bubbleOrigin.x + local.x).toInt(),
                                        (bubbleOrigin.y + local.y).toInt()
                                    )
                                    menu = true
                                }
                            )
                        }
                        // .message-bubble { padding: 0.20rem 0.7rem } → 3.2/11.2px.
                        // Web drops it to 0.25rem for media bubbles; the file
                        // card brings its own padding, so none here.
                        .padding(
                            horizontal = when {
                                msg.file != null || msg.storageFile != null -> 0.dp
                                msg.isImage -> 4.dp
                                else -> 11.dp
                            },
                            vertical = when {
                                msg.file != null || msg.storageFile != null -> 0.dp
                                msg.isImage -> 4.dp
                                else -> 3.dp
                            }
                        )
                ) {
                    if (showSender) {
                        // Web getVerificationBadge: ENS → circled green check,
                        // trusted contact → amber star, plain valid → ✓.
                        // Web: `trustLevel === 1 || (trustLevel >= 1 && ensName)`
                        // — i.e. "an ENS name is known". There it always holds,
                        // because the web stores the name inside `msg.verified`
                        // at verification time. Ours arrives on two independent
                        // paths (trustLevel at verification, ensName when the
                        // lookup returns), and for history the lookup lands
                        // second, leaving trustLevel 0 next to an .eth name and
                        // a plain tick. Either signal is enough.
                        val hasEns = msg.trustLevel == 1 || msg.ensName != null
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (msg.verified == true) {
                                when {
                                    hasEns -> Icon(
                                        Icons.Filled.CheckCircleOutline, contentDescription = "ENS verified",
                                        tint = Color(0xFF4ADE80), modifier = Modifier.size(14.dp)
                                    )
                                    msg.trustLevel == 2 -> Icon(
                                        Icons.Filled.Star, contentDescription = "Trusted contact",
                                        tint = Color(0xFFD4A544), modifier = Modifier.size(14.dp)
                                    )
                                    else -> Text("✓", color = PomboColors.Success, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(3.dp))
                            }
                            Text(
                                // Web precedence (ChatAreaUI): ENS name wins over the
                                // self-declared username, then the short address.
                                msg.ensName ?: msg.senderName ?: shortAddress(msg.sender),
                                color = addressColor(msg.sender),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    // Quoted message (web .reply-preview)
                    msg.replyTo?.let { r ->
                        Row(
                            Modifier
                                .padding(bottom = 6.dp)
                                .widthIn(max = 280.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                                // Tapping a quote jumps to what it quotes, as on
                                // the web; the quote block was previously inert.
                                .then(
                                    if (onJumpTo != null) Modifier.clickableNoRipple { onJumpTo(r.id) }
                                    else Modifier
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(Modifier.width(2.dp).height(26.dp).background(Color.White.copy(alpha = 0.15f)))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(
                                    r.senderName ?: shortAddress(r.sender),
                                    color = Color.White.copy(alpha = 0.60f),
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                                Text(
                                    // A quoted media message carries no text
                                    // over the wire (web parity) — blank means
                                    // media, never a legitimate empty message.
                                    if (r.text.isBlank()) "[Media]"
                                    else r.text.take(50) + if (r.text.length > 50) "..." else "",
                                    color = Color.White.copy(alpha = 0.50f),
                                    fontSize = 13.sp, maxLines = 1
                                )
                            }
                        }
                    }
                    if (msg.file != null) {
                        com.pombo.android.ui.FileBubbleContent(
                            file = msg.file,
                            messageId = msg.id,
                            mine = msg.mine,
                            textColor = bubbleTextColor
                        )
                    } else if (msg.storageFile != null) {
                        com.pombo.android.ui.StorageFileBubbleContent(
                            file = msg.storageFile,
                            messageId = msg.id,
                            mine = msg.mine,
                            textColor = bubbleTextColor
                        )
                    } else if (msg.isImage) {
                        ImageBubbleContent(msg, onOpen = { lightbox = true })
                    } else {
                        Text(
                            // Bare URLs become tappable links, like the web's
                            // linkify. Emoji-only text never contains one, so
                            // the scan is skipped for that path.
                            if (emojiKind == null) com.pombo.android.ui.linkifiedText(msg.text)
                            else androidx.compose.ui.text.AnnotatedString(msg.text),
                            color = bubbleTextColor,
                            // Web: emoji-only messages render large (2rem for
                            // 1-3, 1.6rem for 4+) with no bubble behind them.
                            fontSize = when (emojiKind) {
                                "few" -> 32.sp
                                "many" -> 26.sp
                                else -> 14.sp
                            },
                            lineHeight = if (emojiKind != null) 40.sp else 20.sp
                        )
                        // Web embedYouTubeLinks: every YouTube link gains a
                        // player card under the text, unless disabled in
                        // Settings → Content.
                        if (com.pombo.android.ui.LocalYouTubeEmbedsEnabled.current && emojiKind == null) {
                            val ytIds = remember(msg.text) { com.pombo.android.ui.youtubeVideoIds(msg.text) }
                            ytIds.forEach { id -> com.pombo.android.ui.YouTubeEmbed(id) }
                        }
                    }
                    // `.message-footer` — reactions sit INSIDE the bubble, on the
                    // same row as the timestamp (MessageRenderer.js:444-447).
                    // They used to hang below the bubble as a separate strip.
                    androidx.compose.foundation.layout.FlowRow(
                        verticalArrangement = Arrangement.Center,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        reactions.forEach { (emoji, users) ->
                            val reactedByMe = me != null && users.contains(me)
                            Row(
                                Modifier
                                    // The web's touch branch (@media hover:none
                                    // and pointer:coarse) computes to a 33px
                                    // pill, but that reads oversized here, so
                                    // this is the whole chip scaled to 85% of
                                    // it — height, emoji, label and padding
                                    // together, to keep the proportions.
                                    .height(28.dp)
                                    .background(
                                        if (reactedByMe) PomboColors.Accent.copy(alpha = 0.15f)
                                        else Color.White.copy(alpha = 0.08f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onReact(emoji, !reactedByMe) }
                                    // `padding: 6px 12px` at 85%
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // The "pop": the emoji is larger than the chip's
                                // label but takes up less room than it draws
                                // (`margin: -6px -3px`), so it cannot stretch
                                // the pill. Offsetting alone would move it while
                                // still reserving its full size.
                                Text(
                                    emoji, fontSize = 20.sp, lineHeight = 20.sp,
                                    // Font padding off, or the glyph box grows
                                    // past the size actually asked for.
                                    style = androidx.compose.ui.text.TextStyle(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                            includeFontPadding = false
                                        )
                                    ),
                                    modifier = Modifier.negativeMargin(horizontal = 3.dp, vertical = 5.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${users.size}",
                                    color = Color.White.copy(alpha = 0.80f),
                                    fontSize = 12.sp, lineHeight = 18.sp
                                )
                            }
                        }
                        Text(
                            formatTime(msg.timestamp) +
                                (if (msg.edited) " · edited" else "") +
                                (if (msg.pending) " · sending…" else ""),
                            color = PomboColors.TextDim,
                            // Web `.message-time` is `text-xs` = 12px.
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        when (msg.verified) {
                            false -> { Spacer(Modifier.width(4.dp)); Text("❌", fontSize = 12.sp) }
                            else -> {}
                        }
                    }
                }

                // Long-press context menu (web #message-context-menu), opened at
                // the touch point. Item order, icons and colours follow
                // index.html:2540-2600.
                if (menu) {
                    val red = Color(0xFFF87171)      // text-red-400
                    com.pombo.android.ui.PomboContextMenu(
                        touchOffset = menuAt,
                        onDismiss = { menu = false }
                    ) {
                        com.pombo.android.ui.ContextMenuItem(
                            "Copy Text", Icons.Outlined.ContentCopy
                        ) { menu = false; clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.text)) }
                        com.pombo.android.ui.ContextMenuItem(
                            "Copy Address", Icons.Outlined.Badge
                        ) { menu = false; clipboard.setText(androidx.compose.ui.text.AnnotatedString(msg.sender)) }
                        com.pombo.android.ui.ContextMenuItem(
                            "Reply", Icons.AutoMirrored.Filled.Reply
                        ) { menu = false; onReply() }

                        if (!msg.mine) {
                            com.pombo.android.ui.ContextMenuDivider()
                            // Web hides "Send DM" when you are already in that DM.
                            if (onBlock == null) com.pombo.android.ui.ContextMenuItem(
                                "Send DM", Icons.Outlined.MailOutline,
                                iconTint = Color(0xFF38BDF8)   // text-sky-400
                            ) { menu = false; onSendDm() }
                            if (isContact) com.pombo.android.ui.ContextMenuItem(
                                "Remove from Contacts", Icons.Outlined.PersonRemove,
                                iconTint = red, labelColor = red
                            ) { menu = false; onRemoveContact() }
                            else com.pombo.android.ui.ContextMenuItem(
                                "Add to Contacts", Icons.Outlined.PersonAddAlt,
                                iconTint = Color(0xFF10B981)   // text-emerald-500
                            ) { menu = false; onAddContact() }
                        }

                        // Own-message actions. Edit is text-only. Owner-delete is
                        // hidden from moderators on purpose: for them deletion is
                        // routed through the admin stream below, so moderation
                        // stays the single source of truth (web showDelete =
                        // isSelf && !isAdminUser, MessageContextMenuUI.js:231).
                        // Web: showEdit = isSelf && dataset.type === 'text' — files
                        // and images are excluded too, not just images.
                        val showEdit = msg.mine && !msg.isImage && msg.file == null && msg.storageFile == null
                        val showOwnerDelete = msg.mine && !canModerate
                        if (showEdit || showOwnerDelete) {
                            com.pombo.android.ui.ContextMenuDivider()
                            if (showEdit) com.pombo.android.ui.ContextMenuItem(
                                "Edit Message", Icons.Outlined.Edit
                            ) { menu = false; onEdit() }
                            if (showOwnerDelete) com.pombo.android.ui.ContextMenuItem(
                                "Delete Message", Icons.Outlined.Delete,
                                iconTint = red, labelColor = red
                            ) { menu = false; onDelete() }
                        }

                        // Moderation block. Every entry needs on-chain DELETE
                        // permission; Ban additionally excludes yourself and the
                        // channel creator, who cannot be banned
                        // (web _toggleAdminItems, MessageContextMenuUI.js:262-269).
                        val isCreator = channelCreator?.equals(msg.sender, ignoreCase = true) == true
                        val showBan = canModerate && !msg.mine && !isCreator
                        if (canModerate) {
                            com.pombo.android.ui.ContextMenuDivider()
                            com.pombo.android.ui.ContextMenuItem(
                                if (isPinned) "Unpin Message" else "Pin Message",
                                Icons.Filled.PushPin,
                                iconTint = Color(0xFFFBBF24)   // text-amber-400
                            ) { menu = false; onPin(!isPinned) }
                            com.pombo.android.ui.ContextMenuItem(
                                if (isHidden) "Unhide Message" else "Hide Message",
                                Icons.Outlined.VisibilityOff,
                                iconTint = red, labelColor = red
                            ) { menu = false; onHide(!isHidden) }
                            if (showBan) com.pombo.android.ui.ContextMenuItem(
                                "Ban User", Icons.Outlined.Block,
                                iconTint = red, labelColor = red
                            ) { menu = false; confirmBan = true }
                        }

                        // DM only: blocking leaves the conversation, which only
                        // makes sense when the conversation IS the peer.
                        if (!msg.mine && onBlock != null) {
                            com.pombo.android.ui.ContextMenuDivider()
                            com.pombo.android.ui.ContextMenuItem(
                                "Block User", Icons.Outlined.Block,
                                iconTint = red, labelColor = red
                            ) { menu = false; confirmBlock = true }
                        }
                    }
                }

                // Reaction palette (web #reaction-picker)
                DropdownMenu(
                    expanded = picker,
                    onDismissRequest = { picker = false },
                    modifier = Modifier
                        .background(Color(0xFF16161B))
                        .width(240.dp)
                ) {
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        REACTION_EMOJIS.forEach { emoji ->
                            val reactedByMe = me != null && reactions[emoji]?.contains(me) == true
                            Text(
                                emoji,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .clickable { picker = false; onReact(emoji, !reactedByMe) }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

        }
    }

    // Web guards the ban behind a confirm(): it revokes the user's access for
    // everyone in the channel.
    if (confirmBan) {
        BanMemberDialog(
            label = "${msg.sender.take(6)}…${msg.sender.takeLast(4)}",
            gated = banGated,
            canClientBan = canClientBan,
            onDismiss = { confirmBan = false },
            onConfirm = { client, protocol -> confirmBan = false; onBan(client, protocol) }
        )
    }

    if (confirmBlock && onBlock != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmBlock = false }) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF16161B), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Text("Block user", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Block ${msg.sender.take(6)}…${msg.sender.takeLast(4)}? All messages from " +
                        "this user will be permanently ignored, and this conversation " +
                        "will be removed from this device.",
                    color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp, lineHeight = 20.sp
                )
                Spacer(Modifier.height(18.dp))
                Row {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmBlock = false }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f)
                            .background(PomboColors.Danger.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmBlock = false; onBlock() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Block", color = PomboColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/**
 * CSS negative margin: draw at full size, but report a smaller one to the
 * parent so surrounding layout closes in around the overflow.
 *
 * Compose has no negative padding, and `offset` is not a substitute — it moves
 * a child without changing the space reserved for it, so the parent still sizes
 * to the full child. That distinction is the whole reason the reaction chips
 * were larger than their emoji.
 */
private fun Modifier.negativeMargin(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val dx = horizontal.roundToPx()
        val dy = vertical.roundToPx()
        val w = (placeable.width - dx * 2).coerceAtLeast(0)
        val h = (placeable.height - dy * 2).coerceAtLeast(0)
        layout(w, h) { placeable.place(-dx, -dy) }
    }

/**
 * The bubble tail — `.message-bubble::before` (components.css:380-401).
 *
 * A 12×12 square pinned to the bubble's bottom, overhanging 5px past the side
 * nearest the avatar, with the corner facing the bubble rounded by 8px and the
 * rest clipped to a triangle. Only SINGLE and LAST positions get one, so a
 * stack of grouped bubbles reads as one block with a single tail at the end.
 */
private fun Modifier.bubbleTail(
    mine: Boolean,
    pos: GroupPos,
    color: Color,
    show: Boolean
): Modifier {
    if (!show || (pos != GroupPos.SINGLE && pos != GroupPos.LAST)) return this
    return this.drawBehind {
        val s = 12.dp.toPx()
        val over = 5.dp.toPx()
        val r = 8.dp.toPx()
        val bottom = size.height
        val top = bottom - s
        val path = androidx.compose.ui.graphics.Path()
        if (!mine) {
            // clip-path: polygon(100% 0, 100% 100%, 0 100%) with the
            // bottom-RIGHT corner rounded — the corner that meets the bubble.
            val left = -over
            val right = left + s
            path.moveTo(right, top)
            path.lineTo(right, bottom - r)
            path.quadraticBezierTo(right, bottom, right - r, bottom)
            path.lineTo(left, bottom)
            path.close()
        } else {
            // polygon(0 0, 100% 100%, 0 100%), bottom-LEFT corner rounded.
            val right = size.width + over
            val left = right - s
            path.moveTo(left, top)
            path.lineTo(left + r, bottom)
            path.lineTo(right, bottom)
            path.lineTo(left, bottom)
            path.close()
            path.reset()
            path.moveTo(left, top)
            path.lineTo(right, bottom)
            path.lineTo(left + r, bottom)
            path.quadraticBezierTo(left, bottom, left, bottom - r)
            path.close()
        }
        drawPath(path, color)
    }
}

/**
 * The reply/react affordances revealed by tapping a bubble (web opacity 0.6).
 *
 * `.react-trigger` / `.reply-trigger` are `position: absolute` at ±28px, so
 * they float in the gutter beside the bubble and take no layout space. Laid out
 * inline — as these were — they push the bubble sideways the moment a message
 * is activated, which is both wrong and jarring. Reporting zero width and
 * placing outside that slot reproduces the absolute behaviour.
 */
@Composable
private fun MessageActionTriggers(modifier: Modifier = Modifier, onReply: () -> Unit, onReact: () -> Unit) {
    Column(
        modifier.padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply",
            tint = Color.White.copy(alpha = 0.60f),
            modifier = Modifier.size(18.dp).clickableNoRipple(onReply)
        )
        Icon(
            Icons.Outlined.SentimentSatisfied, contentDescription = "React",
            tint = Color.White.copy(alpha = 0.60f),
            modifier = Modifier.size(18.dp).clickableNoRipple(onReact)
        )
    }
}
