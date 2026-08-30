package com.pombo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pombo.android.AppViewModel
import com.pombo.android.NetStatus
import com.pombo.android.data.Channel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.theme.PomboColors


private enum class ChannelFilter(val label: String) { ALL("All"), PERSONAL("Personal"), COMMUNITIES("Communities") }

/**
 * Folds a reordered slice back into the full channel order.
 *
 * A filtered tab shows only some of the channels, so a drag there says how
 * those rows sit relative to EACH OTHER — nothing about the ones hidden by the
 * filter. Keeping the global positions the slice already occupies and refilling
 * them in the new sequence is what makes that true: the dragged channel really
 * moves in the one saved order (so every other view sees it), while channels
 * the filter hid never shift under the user.
 *
 * In the All tab the slice IS the whole list, so this is the identity it used
 * to be.
 */
private fun mergeOrder(global: List<String>, slice: List<String>): List<String> {
    val inSlice = slice.toHashSet()
    val slots = global.withIndex().filter { it.value in inSlice }.map { it.index }
    if (slots.size != slice.size) return slice + global.filterNot { it in inSlice }
    val out = global.toMutableList()
    slots.forEachIndexed { i, pos -> out[pos] = slice[i] }
    return out
}

@Composable
internal fun ChatsTab(vm: AppViewModel, onCreate: () -> Unit, onJoin: () -> Unit, onConnect: () -> Unit = {}) {
    val status by vm.status.collectAsState()
    val channels by vm.channels.collectAsState()
    val busy by vm.busy.collectAsState()
    val isGuest by vm.isGuest.collectAsState()
    val channelImages by vm.channelImages.collectAsState()
    val channelPreviews by vm.channelPreviews.collectAsState()
    val ensAvatars by vm.ensAvatars.collectAsState()
    // Preview sender labels resolve ENS at RENDER — the name usually arrives after
    // the preview was cached, so baking it in at fetch time is not enough.
    val ensNames by vm.ensNames.collectAsState()
    val unreadCounts by vm.unreadCounts.collectAsState()
    val channelOrder by vm.channelOrder.collectAsState()
    var filter by remember { mutableStateOf(ChannelFilter.ALL) }

    // Landing on Chats is the moment the list is about to be read, so it is
    // when the background channels are worth a look. Fires on every entry into
    // the tab; the per-channel floor inside keeps that from hammering.
    LaunchedEffect(Unit) { vm.scanChannelsActivity() }

    // Apply the user's manual drag order: known ids first in their saved order,
    // everything else after in natural order (stable sort).
    val ordered = remember(channels, channelOrder) {
        channels.sortedBy { ch ->
            channelOrder.indexOf(ch.messageStreamId).let { if (it < 0) Int.MAX_VALUE else it }
        }
    }
    val filtered = when (filter) {
        ChannelFilter.ALL -> ordered
        ChannelFilter.PERSONAL -> ordered.filter { it.type == "dm" || it.type == "gated" }
        ChannelFilter.COMMUNITIES -> ordered.filter { it.type == "public" || it.type == "password" }
    }

    Column(Modifier.fillMaxSize()) {
        PomboHeader(status) {
            if (isGuest) {
                // Web: guest shows the orange "Create Account" button instead of the actions
                Row(
                    Modifier.background(PomboColors.Accent, RoundedCornerShape(12.dp))
                        .clickableNoRipple(onConnect)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Create Account", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Bell: recent channel invites. Android addition — on the
                    // web an invite only ever exists as a transient toast; here
                    // unanswered ones stay reachable until accepted/dismissed.
                    val invites by vm.pendingInvites.collectAsState()
                    val dismissedInvites by vm.dismissedInvites.collectAsState()
                    var invitesOpen by remember { mutableStateOf(false) }
                    // false = pending only (default on every open); true appends
                    // dismissed invites, dimmed, with Accept still available.
                    var showAllInvites by remember { mutableStateOf(false) }
                    Box {
                        Box(
                            Modifier.size(32.dp)
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                                .clickableNoRipple { showAllInvites = false; invitesOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Notifications, contentDescription = "Channel invites",
                                tint = PomboColors.Text, modifier = Modifier.size(17.dp)
                            )
                        }
                        if (invites.isNotEmpty()) {
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .size(16.dp)
                                    .background(PomboColors.Accent, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (invites.size > 9) "9+" else invites.size.toString(),
                                    color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                    lineHeight = 8.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    // Default line-height metrics push single-digit
                                    // text a couple px low in a circle this tight —
                                    // the classic Compose includeFontPadding offset.
                                    style = androidx.compose.ui.text.TextStyle(
                                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                                    )
                                )
                            }
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = invitesOpen,
                            onDismissRequest = { invitesOpen = false },
                            // End-aligned to the bell, the right edge stops 40dp
                            // short of the screen's (8dp spacer + 32dp join
                            // button) — at this width that reads as floating
                            // mid-screen. The offset walks it to the margin.
                            offset = androidx.compose.ui.unit.DpOffset(40.dp, 0.dp),
                            // Shape/color/border belong on the menu's own surface:
                            // a rounded background on the content leaves the
                            // surface's square corners showing behind it.
                            shape = RoundedCornerShape(12.dp),
                            containerColor = Color(0xFF16161B),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                            // FIXED width: transfer stats redraw every second and a
                            // wrap-content menu visibly breathes with each repaint.
                            modifier = Modifier.width(320.dp)
                        ) {
                            ChannelInvitesSection(
                                vm = vm,
                                invites = invites,
                                dismissedInvites = dismissedInvites,
                                showAll = showAllInvites,
                                onShowAll = { showAllInvites = it },
                                onAccepted = { invitesOpen = false }
                            )
                            ActiveTransfersSection(vm, channels)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Join by stream ID. Creating a channel lives in Explore.
                    Box(
                        Modifier.size(32.dp).background(Color.White.copy(alpha = 0.08f), CircleShape).clickableNoRipple(onJoin),
                        contentAlignment = Alignment.Center
                    ) { Text("#", color = PomboColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Filter tabs (All / Personal / Communities) — active gets an orange underline
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            androidx.compose.material3.HorizontalDivider(
                color = Color.White.copy(alpha = 0.06f),
                modifier = Modifier.align(Alignment.BottomCenter)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ChannelFilter.entries.forEach { f ->
                    val active = f == filter
                    Column(
                        Modifier.weight(1f).clickableNoRipple { filter = f },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            f.label.uppercase(),
                            color = if (active) PomboColors.Accent else Color.White.copy(alpha = 0.30f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.15.em,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                        Box(
                            Modifier.fillMaxWidth().height(2.dp)
                                .background(if (active) PomboColors.Accent else Color.Transparent)
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (busy) "…" else "No channels yet",
                    color = PomboColors.TextDim, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            // Swipe left/right anywhere on the list to move between the filter
            // tabs, like the web (initChannelFilterSwipe: >50px horizontal).
            var swipeAccum by remember { mutableFloatStateOf(0f) }
            val swipeThreshold = with(LocalDensity.current) { 56.dp.toPx() }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            // Drag-to-reorder (hold and drag) in EVERY tab, like the web: there
            // is one saved order (secureStorage.channelOrder) and each view is a
            // window onto it, so a drag in Personal moves the channel for All
            // and Communities too. See [mergeOrder] for how a reordered slice
            // folds back into the full list.
            var draggingKey by remember { mutableStateOf<String?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            var localOrder by remember { mutableStateOf(filtered) }
            LaunchedEffect(filtered) { if (draggingKey == null) localOrder = filtered }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .pointerInput(filter) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val order = ChannelFilter.entries
                                val i = order.indexOf(filter)
                                if (swipeAccum < -swipeThreshold && i < order.lastIndex) filter = order[i + 1]
                                else if (swipeAccum > swipeThreshold && i > 0) filter = order[i - 1]
                                swipeAccum = 0f
                            },
                            onDragCancel = { swipeAccum = 0f },
                            onHorizontalDrag = { _, delta -> swipeAccum += delta }
                        )
                    },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(localOrder, key = { _, ch -> ch.messageStreamId }) { _, ch ->
                    val isDragging = ch.messageStreamId == draggingKey
                    LaunchedEffect(ch.messageStreamId) { vm.ensureChannelPreview(ch) }
                    Box(
                        Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                            .then(
                                Modifier.pointerInput(ch.messageStreamId) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggingKey = ch.messageStreamId; dragOffset = 0f },
                                        onDragEnd = {
                                            vm.setChannelOrder(
                                                mergeOrder(
                                                    ordered.map { it.messageStreamId },
                                                    localOrder.map { it.messageStreamId }
                                                )
                                            )
                                            draggingKey = null; dragOffset = 0f
                                        },
                                        onDragCancel = { draggingKey = null; dragOffset = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            val cur = localOrder.indexOfFirst { it.messageStreamId == draggingKey }
                                            if (cur < 0) return@detectDragGesturesAfterLongPress
                                            val row = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == draggingKey } ?: return@detectDragGesturesAfterLongPress
                                            val step = row.size + 2.dp.toPx()   // row height + spacing
                                            if (dragOffset > step / 2 && cur < localOrder.lastIndex) {
                                                localOrder = localOrder.toMutableList().apply { add(cur + 1, removeAt(cur)) }
                                                dragOffset -= step
                                            } else if (dragOffset < -step / 2 && cur > 0) {
                                                localOrder = localOrder.toMutableList().apply { add(cur - 1, removeAt(cur)) }
                                                dragOffset += step
                                            }
                                        }
                                    )
                                }
                            )
                    ) {
                        ChannelListItem(
                            ch,
                            image = channelImages[ch.adminStreamId],
                            preview = channelPreviews[ch.messageStreamId],
                            peerEnsAvatarUrl = ch.peerAddress?.let { ensAvatars[it.lowercase()] },
                            ensNames = ensNames,
                            unread = unreadCounts[ch.messageStreamId] ?: 0,
                            onOpen = { vm.openChannel(ch.messageStreamId, com.pombo.android.AppViewModel.ChatOrigin.CHATS) }
                        )
                    }
                }
            }
        }

        // DM affordance under the channel list (web: #dm-inbox-setup /
        // #dm-new-btn-wrap). Hidden for guests; before the inbox exists it
        // offers to create it, after that it starts new conversations.
        val isGuest by vm.isGuest.collectAsState()
        val hasDmInbox by vm.hasDmInbox.collectAsState()
        var showNewDm by remember { mutableStateOf(false) }
        var showDmSetup by remember { mutableStateOf(false) }
        LaunchedEffect(isGuest) { vm.refreshDmInbox() }

        if (!isGuest) {
            // Extra bottom room so the button clears the floating pill nav
            // instead of sitting against it. Independent of PILL_NAV_BOTTOM_OFFSET
            // on purpose: the wrapper's own bottom padding (MainScreen) already
            // reserves exactly enough to reach the pill's top edge regardless of
            // that offset's value, so this is the ENTIRE gap between the two —
            // tying it to the offset (as a 2026-08-21 edit briefly did) shrank it
            // to the point the button sat flush against the pill.
            Box(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (hasDmInbox) {
                    AccentPillButton("New DM", Icons.Filled.Add) { showNewDm = true }
                } else {
                    // Web only shows the cost line inside its create-inbox
                    // modal. `hasDmInbox` reads false while the bridge is
                    // still connecting, so a cost line here flashed on every start.
                    AccentPillButton("Create DM Inbox", Icons.Filled.MailOutline) { showDmSetup = true }
                }
            }
        }
        if (showNewDm) NewDmDialog(
            onDismiss = { showNewDm = false },
            onStart = { address, localName -> showNewDm = false; vm.startDm(address, localName) }
        )
        if (showDmSetup) CreateDmInboxDialog(
            vm,
            onDismiss = { showDmSetup = false },
            onCreate = { provider, custom, days -> vm.setupDmInbox(provider, custom, days) }
        )
    }
}

/**
 * Every download in flight and every file this device is serving, with a way
 * out. Sits under the invites in the same dropdown; the two share nothing but
 * the surface they are drawn on.
 */
@Composable
private fun ActiveTransfersSection(vm: AppViewModel, channels: List<Channel>) {
    val transferProgress by vm.fileProgress.collectAsState()
    val transferUploads by vm.uploadStats.collectAsState()
    val activeSeeds by vm.activeSeeds.collectAsState()
    val storageUploads by vm.storageUploads.collectAsState()
    val storageDownloads by vm.storageDownloads.collectAsState()
    val storagePhases by vm.storageTransferPhases.collectAsState()
    val activeDownloads = transferProgress.values
        .filter { !it.done && it.failure == null }
    val activeStorageUp = storageUploads.values.filter { it.stage != "done" && it.error == null }
    val activeStorageDown = storageDownloads.values.filter { it.status == "downloading" || it.status == "paused" }
    androidx.compose.material3.HorizontalDivider(
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier.padding(vertical = 4.dp)
    )
    // Inactive = complete on disk but not served (user-stopped,
    // or the channel was not opened this session). Recomputed
    // off activeSeeds plus a manual bump: deleting an INACTIVE
    // seed touches no StateFlow, so nothing else would refresh.
    var showInactiveSeeds by remember { mutableStateOf(false) }
    var inactiveRefresh by remember { mutableStateOf(0) }
    val inactiveSeeds = remember(showInactiveSeeds, activeSeeds, inactiveRefresh) {
        if (showInactiveSeeds) vm.inactiveSeeds() else emptyList()
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "TRANSFERS",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.em,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (showInactiveSeeds) "Hide inactive" else "Show inactive",
            color = Color.White.copy(alpha = if (showInactiveSeeds) 0.70f else 0.30f),
            fontSize = 10.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickableNoRipple { showInactiveSeeds = !showInactiveSeeds }
        )
    }
    if (activeDownloads.isEmpty() && activeSeeds.isEmpty() &&
        activeStorageUp.isEmpty() && activeStorageDown.isEmpty() &&
        inactiveSeeds.isEmpty()
    ) {
        Text(
            "No active transfers",
            color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp)
                .padding(bottom = 12.dp)
        )
    } else {
        activeDownloads.forEach { p ->
            val speed = com.pombo.android.ui.formatSpeed(p.bytesPerSecond)
            TransferRow(
                icon = Icons.Filled.ArrowDownward, iconTint = PomboColors.Accent,
                badge = "mesh", name = p.fileName,
                // Mesh pause is synchronous (timers cleared on the spot),
                // so "Paused" is confirmed the moment the flag flips — no
                // pausing/resuming gap to name, unlike the storage rows.
                sub = (if (p.paused) "Paused · " else "") +
                    com.pombo.android.ui.formatBytes(p.received) + " of " +
                    com.pombo.android.ui.formatBytes(p.total) + if (speed.isEmpty() || p.paused) "" else " · $speed",
                onCancel = { vm.cancelTransfer(p.fileId) },
                paused = p.paused,
                onPauseToggle = {
                    if (p.paused) vm.resumeTransfer(p.fileId) else vm.pauseTransfer(p.fileId)
                },
                onRowClick = { vm.openChannel(p.messageStreamId) }
            )
        }
        activeSeeds.forEach { seed ->
            val up = transferUploads[seed.fileId]
            val rate = com.pombo.android.ui.formatSpeed(up?.bytesPerSecond)
            TransferRow(
                icon = Icons.Filled.ArrowUpward, iconTint = Color(0xFF4ADE80).copy(alpha = 0.7f),
                badge = "mesh", name = seed.fileName.ifEmpty { seed.fileId.take(8) },
                sub = com.pombo.android.ui.formatBytes(seed.fileSize) +
                    (if (rate.isEmpty()) "" else " · ↑ $rate") +
                    (if (up != null && up.leechers > 0) " · ${up.leechers} peer${if (up.leechers == 1) "" else "s"}" else ""),
                onCancel = { vm.stopSeeding(seed.fileId) },
                onRowClick = { vm.openChannel(seed.messageStreamId) }
            )
        }
        // Storage-node transfers — survive a channel switch, so they carry
        // their own channel name (the open channel may be a different one).
        activeStorageUp.forEach { u ->
            val info = vm.storageTransferInfo(u.transferId)
            val stats = if (u.stage == "sending") {
                val sp = com.pombo.android.ui.formatSpeed(u.instBps ?: u.avgBps)
                "${u.percent}%" + if (sp.isEmpty()) "" else " · $sp"
            } else u.phase
            TransferRow(
                icon = Icons.Filled.ArrowUpward, iconTint = Color(0xFF8B5CF6),
                badge = "storage", name = info?.fileName ?: u.transferId.take(8),
                sub = (info?.channelName?.let { "$it · " } ?: "") + stats,
                onCancel = null,
                onRowClick = info?.messageStreamId?.let { streamId -> { vm.openChannel(streamId) } }
            )
        }
        activeStorageDown.forEach { d ->
            val info = vm.storageTransferInfo(d.transferId)
            // The tap-to-confirmation gap (cancellation is cooperative):
            // "pausing" until the engine's status flips to paused,
            // "resuming" until it flips back to downloading. The icon
            // tracks the REQUEST, the text names the gap.
            val transferPhase = storagePhases[d.transferId]
            val pausing = transferPhase == "pausing" && d.status == "downloading"
            val resuming = transferPhase == "resuming" && d.status != "downloading"
            val paused = pausing || (d.status == "paused" && !resuming)
            val transferred = if (d.total > 0)
                com.pombo.android.ui.formatBytes(d.received.toLong() * d.fileSize / d.total) else "0 B"
            val sp = com.pombo.android.ui.formatSpeed(d.bytesPerSec)
            val base = "${d.percent}% · $transferred"
            val stats = d.phase ?: when {
                pausing -> "$base · Pausing…"
                resuming -> "$base · Resuming…"
                d.status == "paused" -> "Paused · $base"
                else -> base + if (sp.isEmpty()) "" else " · $sp"
            }
            TransferRow(
                icon = Icons.Filled.ArrowDownward, iconTint = Color(0xFF8B5CF6),
                badge = "storage", name = info?.fileName ?: d.transferId.take(8),
                sub = (info?.channelName?.let { "$it · " } ?: "") + stats,
                onCancel = { vm.cancelStorageTransfer(d.transferId) },
                paused = paused,
                onPauseToggle = {
                    if (paused) vm.resumeStorageTransfer(d.transferId) else vm.pauseStorageTransfer(d.transferId)
                },
                onRowClick = info?.messageStreamId?.let { streamId -> { vm.openChannel(streamId) } }
            )
        }
        // Inactive seeds: play = reseed (needs the channel's
        // password, so only offered while still a member),
        // X = delete for good.
        inactiveSeeds.forEach { seed ->
            val member = channels.any { it.messageStreamId == seed.messageStreamId }
            TransferRow(
                icon = Icons.Filled.ArrowUpward, iconTint = Color.White.copy(alpha = 0.30f),
                badge = "mesh", name = seed.fileName.ifEmpty { seed.fileId.take(8) },
                sub = com.pombo.android.ui.formatBytes(seed.fileSize) + " · Inactive",
                onCancel = { vm.deleteSeed(seed.fileId); inactiveRefresh++ },
                paused = true,
                onPauseToggle = if (member) {
                    { vm.reseedFile(seed.fileId, seed.messageStreamId); inactiveRefresh++ }
                } else null,
                onRowClick = if (member) {
                    { vm.openChannel(seed.messageStreamId) }
                } else null,
                dimmed = true
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ChannelInvitesSection(
    vm: AppViewModel,
    invites: List<com.pombo.android.ChannelManager.PendingInvite>,
    dismissedInvites: List<com.pombo.android.ChannelManager.PendingInvite>,
    showAll: Boolean,
    onShowAll: (Boolean) -> Unit,
    onAccepted: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "CHANNEL INVITES",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.em,
            modifier = Modifier.weight(1f)
        )
        Text(
            "Pending",
            color = Color.White.copy(alpha = if (showAll) 0.30f else 0.70f),
            fontSize = 10.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clickableNoRipple { onShowAll(false) }
        )
        Text("·", color = Color.White.copy(alpha = 0.20f), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp))
        Text(
            "All",
            color = Color.White.copy(alpha = if (showAll) 0.70f else 0.30f),
            fontSize = 10.sp, fontWeight = FontWeight.Medium,
            // The deep replay backfills historical invites
            // (once per session) the moment the view can
            // actually show them.
            modifier = Modifier.clickableNoRipple { onShowAll(true); vm.fetchAllInvites() }
        )
    }
    val visibleDismissed = if (showAll) dismissedInvites else emptyList()
    if (invites.isEmpty() && visibleDismissed.isEmpty()) {
        Text(
            if (showAll) "No invites" else "No pending invites",
            color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp)
                .padding(bottom = 12.dp)
        )
    } else {
        invites.asReversed().forEach { invite ->
            InviteRow(
                invite = invite, dimmed = false,
                onAccept = { onAccepted(); vm.acceptInvite(invite) },
                onDismiss = { vm.dismissInvite(invite.inviteId) }
            )
        }
        // Dismissed rows: dimmed, Accept only — the "All"
        // view exists to recover a mis-tapped dismiss.
        visibleDismissed.forEach { invite ->
            InviteRow(
                invite = invite, dimmed = true,
                onAccept = { onAccepted(); vm.acceptInvite(invite) },
                onDismiss = null
            )
        }
    }
}

/**
 * One row of the bell's Channel Invites list. [dimmed] renders the dismissed
 * variant of the "All" view; [onDismiss] null hides the Dismiss button (a
 * dismissed invite can only be accepted or left alone).
 */
@Composable
private fun InviteRow(
    invite: com.pombo.android.ChannelManager.PendingInvite,
    dimmed: Boolean,
    onAccept: () -> Unit,
    onDismiss: (() -> Unit)?
) {
    Column(
        Modifier
            .alpha(if (dimmed) 0.5f else 1f)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            invite.name, color = PomboColors.Text,
            fontSize = 13.sp, fontWeight = FontWeight.Medium
        )
        Text(
            "From: ${invite.from.take(6)}…${invite.from.takeLast(4)}",
            color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.background(Color.White.copy(alpha = 0.90f), RoundedCornerShape(6.dp))
                    .clickableNoRipple(onAccept)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Accept", color = Color(0xFF0A0A0A), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            if (onDismiss != null) {
                Box(
                    Modifier.background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                        .clickableNoRipple(onDismiss)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Dismiss", color = Color.White.copy(alpha = 0.70f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * One row of the Active Transfers list — shared by mesh and storage transfers.
 * The transport badge (MESH/STORAGE) distinguishes them, and storage rows prefix
 * their stats with the channel name (they survive channel switches). [onCancel]
 * null hides the cancel affordance (storage uploads have no cancel — the
 * chunks already published cannot be unpublished). [onPauseToggle]
 * null hides the pause/resume affordance; [paused] picks which of the two icons
 * shows — status text ("Paused"/"Pausing…"/…) is the caller's to put in [sub],
 * since only the caller knows the request-vs-confirmed gap (storage rows).
 * [onRowClick], when given, opens
 * the transfer's channel — the action icons have their own clickable modifiers
 * so a tap on Pause/Cancel does not also fire the row's navigation.
 */
@Composable
private fun TransferRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    badge: String,
    name: String,
    sub: String,
    onCancel: (() -> Unit)?,
    paused: Boolean = false,
    onPauseToggle: (() -> Unit)? = null,
    onRowClick: (() -> Unit)? = null,
    dimmed: Boolean = false
) {
    Row(
        Modifier
            .alpha(if (dimmed) 0.5f else 1f)
            .then(if (onRowClick != null) Modifier.clickableNoRipple(onRowClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.pombo.android.ui.TransportBadge(badge)
                Text(
                    name, color = PomboColors.Text, fontSize = 12.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Text(
                sub, color = Color.White.copy(alpha = 0.40f), fontSize = 10.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        if (onPauseToggle != null) {
            Icon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (paused) "Resume" else "Pause",
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(15.dp).clickableNoRipple { onPauseToggle() }
            )
        }
        if (onCancel != null) {
            Icon(
                Icons.Filled.Close, contentDescription = "Cancel",
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(15.dp).clickableNoRipple { onCancel() }
            )
        }
    }
}

/** Web: the shared accent button style used by both DM buttons. */
@Composable
private fun AccentPillButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PomboColors.Text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Web: #new-dm-modal — address/ENS plus an optional device-local name. */
@Composable
private fun NewDmDialog(onDismiss: () -> Unit, onStart: (String, String?) -> Unit) {
    var address by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text("New DM", color = PomboColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))

            Text("ADDRESS OR ENS", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                placeholder = { Text("0x... or name.eth", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Text("LOCAL NAME (OPTIONAL)", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = localName, onValueChange = { if (it.length <= 30) localName = it },
                placeholder = { Text("e.g. Alice", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text("Stored locally, only visible to you", color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp)

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(1f)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .clickable(enabled = address.isNotBlank()) { onStart(address.trim(), localName) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Start DM", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun ChannelListItem(
    channel: Channel,
    image: ByteArray?,
    preview: com.pombo.android.core.LatestMessageStore.Preview?,
    /** ENS picture of the DM peer, when they have one. */
    peerEnsAvatarUrl: String? = null,
    /** Resolved ENS names (address -> name) for the preview's sender label. */
    ensNames: Map<String, String> = emptyMap(),
    /** Unread messages since this channel was last opened. */
    unread: Int = 0,
    onOpen: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            // `#channel-list .channel-item` on mobile: white 5% fill and a
            // white 8.5% border (components.css:2389). The rows were drawn
            // flat, so the list read as loose text on black rather than the
            // stack of cards the web shows.
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.085f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = remember(image) {
                    android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size).asImageBitmap()
                },
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(46.dp).clip(CircleShape)
            )
        } else if (channel.type == "dm" && channel.peerAddress != null) {
            // Peer's ENS picture when they have one, otherwise the avatar
            // generated from their address. Keying on messageStreamId
            // (peer/Pombo-DM-1) generated a different picture from the one
            // their messages carry inside the chat.
            Avatar(
                channel.peerAddress, size = 46.dp, cornerRadiusFraction = 0.5,
                ensAvatarUrl = peerEnsAvatarUrl
            )
        } else {
            Avatar(channel.messageStreamId, size = 46.dp, cornerRadiusFraction = 0.5)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.name, color = PomboColors.Text, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Web: the ONLY row indicator is a megaphone for read-only
                // (announcement) channels — no lock for password channels
                // (the chat header already shows the type). ChannelListUI.js:609.
                if (channel.readOnly) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Outlined.Campaign, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(14.dp)
                    )
                }
            }
            // Last message preview, cached like the web sidebar. DMs drop the
            // sender prefix (web omitSender), channels keep "sender: body".
            // The ENS name is resolved HERE (not baked into the stored label) —
            // it usually resolves after the preview was cached.
            preview?.let { p ->
                val ensLabel = p.senderAddress.takeIf { it.isNotEmpty() }
                    ?.let { ensNames[it.lowercase()] }
                Text(
                    if (channel.type == "dm") p.text else "${ensLabel ?: p.sender}: ${p.text}",
                    color = PomboColors.TextDim, fontSize = 11.sp, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        // Unread badge — web caps the label at "+30" and tints it accent.
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (unread >= 30) "+30" else "$unread",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(PomboColors.Accent.copy(alpha = 0.20f), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun StatusDot(status: NetStatus) {
    val color = when (status) {
        NetStatus.CONNECTED -> PomboColors.Success
        NetStatus.CONNECTING, NetStatus.BOOTING -> PomboColors.Accent
        else -> PomboColors.Danger
    }
    Box(Modifier.size(8.dp).background(color, CircleShape))
}
