package com.pombo.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.pombo.android.AppViewModel
import com.pombo.android.NetStatus
import com.pombo.android.UiMessage
import com.pombo.android.data.Channel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.theme.PomboColors
import java.util.Date


// ==================== Chat ====================

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun ChatScreen(vm: AppViewModel) {
    val channel by vm.current.collectAsState()
    val messages by vm.messages.collectAsState()
    val reactions by vm.reactions.collectAsState()
    val online by vm.onlineCount.collectAsState()
    val onlineUsers by vm.onlineUsers.collectAsState()
    val netStatus by vm.status.collectAsState()
    val presenceReady by vm.presenceReady.collectAsState()
    val typing by vm.typingFrom.collectAsState()
    var onlineOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showInfo by remember { mutableStateOf(false) }

    // The back gesture is navigation, not "leave the app". Everything here is
    // Compose state rather than a back stack, so each screen has to say what
    // back means for it — otherwise the system default (finish the activity)
    // wins and a swipe from the chat minimises Pombo instead of returning to
    // the list. Same action as the header's arrow.
    androidx.activity.compose.BackHandler(enabled = !showInfo) { vm.closeChannel() }
    var replyTarget by remember { mutableStateOf<UiMessage?>(null) }
    // Edit happens in the main composer, not inline in the bubble (web
    // parity, 2026-08-21 user call): tapping "Edit" pre-fills this shared
    // field and the send button commits to editMessage instead of sendMessage.
    var editTarget by remember { mutableStateOf<UiMessage?>(null) }
    val composerInput = rememberTextFieldState()
    val listState = rememberLazyListState()
    val ch = channel ?: return

    // These targets point at messages of ONE channel; this composable
    // survives channel switches, so without this reset a send in channel B
    // would commit an edit/reply against a message that lives in channel A.
    // The composer only clears when it holds an edit's pre-filled text —
    // an ordinary draft survives the switch, as it always has.
    LaunchedEffect(ch.messageStreamId) {
        if (editTarget != null) {
            editTarget = null
            composerInput.clearText()
        }
        replyTarget = null
    }

    val isPreview by vm.isPreview.collectAsState()
    val hasMoreHistory by vm.hasMoreHistory.collectAsState()
    val loadingHistory by vm.loadingHistory.collectAsState()
    val loadingInitial by vm.initialLoad.collectAsState()
    val waitingForKeys by vm.waitingForKeys.collectAsState()
    val paidStatus by vm.paidStatus.collectAsState()

    // NATIVE reverse-layout chat (deliberate divergence from the web's
    // top-down DOM): the LazyColumn runs with reverseLayout, so index 0 is the
    // NEWEST message, drawn at the bottom. Opening a channel lands on the
    // newest with no positioning dance, and loading older history APPENDS
    // items past the far end of the list — the reading position cannot move,
    // by construction: no index math, no re-anchoring, no drift when the seam
    // groups merge. (Item-index anchoring was tried twice and lost both times:
    // the prepend and the re-measure collapse into one snapshot emission, and
    // same-sender pages merge into the seam group, growing it in place.)
    LaunchedEffect(ch.messageStreamId) { listState.scrollToItem(0) }
    // Follow the conversation when a NEW message lands. Keying on the last id
    // (not the count) keeps history appends from yanking the view down.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    // Infinite scroll: the web uses an IntersectionObserver on a top sentinel;
    // in the reversed list the sentinel is the LAST item, so "near the top"
    // means its index range is entering the viewport.
    val nearTop by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 1 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
        }
    }
    // An empty render auto-loads too (web `_autoLoadIfContentShort`): the initial
    // resend stays optimistic about `hasMoreHistory`, so only the bounded
    // windowed paginate can prove a channel is actually empty. Without this a
    // brand-new channel sits on "Loading messages..." forever.
    val isDm = channel?.type == "dm"
    val searchOlder by vm.dmSearchOlder.collectAsState()
    // Web `_autoLoadIfContentShort`: keep fetching only while the content
    // cannot scroll at all (scrollHeight <= clientHeight there).
    val contentShort by remember {
        derivedStateOf { !listState.canScrollForward && !listState.canScrollBackward }
    }
    LaunchedEffect(nearTop, contentShort, hasMoreHistory, loadingHistory, loadingInitial, messages.isEmpty(), searchOlder) {
        // The reversed list is born at index 0 = the NEWEST message, with the
        // sentinel a full page away — `nearTop` cannot fire during opening, so
        // the chain-load that once parked whole channels at their oldest
        // message is structurally impossible; no landing hand-shake needed.
        if (hasMoreHistory && !loadingHistory && !loadingInitial &&
            (messages.isEmpty() || contentShort || nearTop)
        ) {
            // A DM whose last window was empty waits for an explicit tap; the
            // inbox is shared across peers, so empty weeks are normal and
            // auto-scanning them would be a chain of slow resends.
            if (isDm) {
                if (!searchOlder) vm.loadMoreDmHistory()
            } else {
                vm.loadMoreHistory()
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        val pins by vm.pins.collectAsState()
        val hidden by vm.hiddenIds.collectAsState()
        // Moderation is gated on the real on-chain permission, not on who
        // created the channel — see ChannelManager.canModerate. Channel Details
        // reads the same flag, so both surfaces agree on what this account can do.
        val canModerate by vm.canModerate.collectAsState()
        val myAddr = vm.address.collectAsState().value

        if (showInfo) ChannelSettingsSheet(vm, ch, canModerate) { showInfo = false }

        // Messages (hidden ones are dropped for non-owners; greyed for the owner).
        // While the initial resend is running the list stays empty: edits,
        // deletes (P0 or P1) and moderation (-3/P0) all land during that window,
        // and painting first would flash messages that are about to disappear.
        // No admin bypass: the web hides a moderated message for everyone,
        // the admin included (ChatAreaUI filters on hiddenIds with no owner
        // check). Exempting the owner made the person who hid the message the
        // only one who still saw it.
        // Banned senders are filtered too (web ChatAreaUI.js:543): banning was
        // only revoking on-chain publish, so everything the member had already
        // posted stayed on screen — the moderation action looked like it had
        // done nothing to the existing conversation.
        val banned by vm.bannedMembers.collectAsState()
        // Both of these are remembered on their real inputs. The filter used to
        // run unmemoized, allocating a new list on every recomposition — which
        // meant the `remember(visible)` key below never matched and the whole
        // grouping pass re-ran every frame, on the main thread, for the entire
        // conversation. That was a large part of why scrolling felt heavier
        // here than in the PWA.
        val visible = remember(messages, hidden, banned, loadingInitial) {
            if (loadingInitial) emptyList()
            else messages.filter { it.id !in hidden && it.sender.lowercase() !in banned }
        }
        val groups = remember(visible) { buildMessageGroups(visible) }
        // Only one message shows its action triggers at a time (web: .message-active).
        var activeId by remember { mutableStateOf<String?>(null) }
        // Scrolling dismisses them — the web hides the triggers as soon as the
        // list moves, and leaving them stuck to a message that has scrolled
        // away is worse than not showing them at all.
        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) activeId = null
        }
        val contacts by vm.contacts.collectAsState()

        // Jump-to-message, shared by the pinned banner and reply quotes
        // (web ChatAreaUI.scrollToMessage): scroll the group into view and
        // pulse it for 1.5s so the eye can find it after the jump.
        var highlightId by remember { mutableStateOf<String?>(null) }
        val jumpScope = rememberCoroutineScope()
        val scrollToMessage: (String) -> Unit = { targetId ->
            val gi = groups.indexOfFirst { g -> g.items.any { it.id == targetId } }
            if (gi >= 0) {
                jumpScope.launch {
                    // Reversed list: the newest group is item 0 and each group
                    // occupies two items (group, separator). Landing on the
                    // group item aligns its bottom edge with the viewport
                    // bottom, which brings the group into view.
                    listState.animateScrollToItem((groups.size - 1 - gi) * 2)
                    highlightId = targetId
                    kotlinx.coroutines.delay(1500)
                    highlightId = null
                }
            }
        }

        // Dismissals are per-channel and session-scoped, and are dropped when
        // the pin itself disappears so that unpin-then-repin surfaces the
        // banner again (web _reconcileDismissals).
        var dismissedPins by remember(ch.messageStreamId) { mutableStateOf(emptySet<String>()) }
        val livePinIds = pins.map { it.targetId }.toSet()
        LaunchedEffect(livePinIds) { dismissedPins = dismissedPins intersect livePinIds }
        // NEWEST first: `pins` arrives in pin order (oldest first), so the banner
        // used to sit on the stalest pin forever. Reversed, it opens on the most
        // recent one and each dismissal walks back one pin through history.
        val shownPins = pins.filter { it.targetId !in dismissedPins }.asReversed()

        // Channel header
        Row(
            // `#chat-header { height: 3.5rem; padding: 0 0.5rem }` — a fixed
            // 56px, not a padding-derived height.
            Modifier.fillMaxWidth().height(56.dp)
                .background(PomboColors.Surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::closeChannel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PomboColors.Text)
            }
            // Channel image (admin stream) wins over the generated avatar.
            val chImage by vm.channelImage.collectAsState()
            if (chImage != null) {
                androidx.compose.foundation.Image(
                    bitmap = remember(chImage) {
                        android.graphics.BitmapFactory.decodeByteArray(chImage, 0, chImage!!.size).asImageBitmap()
                    },
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                )
            } else if (ch.type == "dm" && ch.peerAddress != null) {
                // Same rule as the chat list: a DM header shows the peer.
                Avatar(
                    ch.peerAddress, size = 36.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = vm.ensAvatars.collectAsState().value[ch.peerAddress.lowercase()]
                )
            } else {
                Avatar(ch.messageStreamId, size = 36.dp, cornerRadiusFraction = 0.5)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // `#current-channel-name { font-size: 1rem }` on mobile. Tapping
                // the name opens Channel Details (web opens it from the kebab, but
                // the name is the natural mobile target).
                Text(
                    ch.name, color = PomboColors.Text, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.clickableNoRipple { showInfo = true }
                )
                // Web subtitle: an icon-only channel-type glyph, then "· N Online"
                // (the type label text lives in Channel Details, not here).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dim = Color.White.copy(alpha = 0.30f)
                    ChannelTypeIcon(ch.type, ch.readOnly, dim, size = 13.dp)
                    // This row must never be empty, or the header grows once
                    // presence lands and everything above it jumps. The web
                    // never had the problem: its count element is always in the
                    // DOM and just gets `textContent = users.length`. So the
                    // count shows even at zero, and while the node is still
                    // connecting the same slot reads "Connecting…".
                    // Two independent things can be pending: the node itself,
                    // and this channel's subscriptions. Opening a channel while
                    // already connected is the common case, so the per-channel
                    // flag is the one that actually covers the gap.
                    val connecting = netStatus != NetStatus.CONNECTED || !presenceReady
                    if (ch.type == "dm") {
                        // A DM has one counterpart: show the peer's live status
                        // next to the envelope (their presence heartbeat within
                        // the online timeout = Online, else Offline).
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (connecting) "Connecting…" else if (online > 0) "Online" else "Offline",
                            color = dim, fontSize = 13.sp
                        )
                    } else {
                        Spacer(Modifier.width(6.dp))
                        Text("·", color = dim, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Box {
                            Text(
                                if (connecting) "Connecting…" else "$online Online",
                                color = dim, fontSize = 13.sp,
                                modifier = Modifier.clickableNoRipple {
                                    if (!connecting) onlineOpen = !onlineOpen
                                }
                            )
                            // Web: dropdown anchored under the count (#online-users-list):
                            // #1e1e1e, white/10 border, r-xl, w-56, max-h-48, scroll.
                            OnlineUsersDropdown(
                                expanded = onlineOpen,
                                users = onlineUsers,
                                me = myAddr?.lowercase(),
                                vm = vm,
                                onDismiss = { onlineOpen = false }
                            )
                        }
                    }
                }
            }
            // Preview mode: "+ Join" adds the channel to My Channels
            // (web index.html #join-channel-btn — accent 15% fill, 30% border).
            if (isPreview) {
                Row(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .clickable { vm.joinPreview() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = PomboColors.Text, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Join", color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(4.dp))
            }
            // Invite icon (web #invite-users-btn) — lives in the chat header,
            // not in Channel Details. Shown for every non-DM channel and hidden
            // in preview, exactly like the web (ChannelViewUI.js:124-129).
            var inviteOpen by remember { mutableStateOf(false) }
            if (ch.type != "dm" && !isPreview) {
                IconButton(onClick = { inviteOpen = true }) {
                    Icon(
                        Icons.Outlined.PersonAddAlt,
                        contentDescription = "Invite users",
                        tint = Color.White.copy(alpha = 0.30f)
                    )
                }
            }
            if (inviteOpen) {
                val inviteClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val link = remember(ch.messageStreamId) { vm.inviteLink(ch) }
                val inviteToasts by vm.toasts.collectAsState()
                com.pombo.android.ui.InviteToChannelDialog(
                    channelName = ch.name,
                    channelType = ch.type,
                    inviteLink = link,
                    onCopy = {
                        inviteClipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
                        vm.toast("Link copied to clipboard!", com.pombo.android.ui.ToastKind.SUCCESS)
                    },
                    onSend = { addr -> vm.sendChannelInvite(addr, ch) { inviteOpen = false } },
                    onDismiss = { inviteOpen = false },
                    toasts = inviteToasts,
                    onDismissToast = vm::dismissToast
                )
            }
            // Channel options (web: mobile kebab on the right)
            var chMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { chMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Channel options", tint = Color.White.copy(alpha = 0.30f))
                }
                // Same visual language as the message context menu: #16161b,
                // white-10% border, radius 12, compact rows with icons. The
                // Material default was oversized and icon-less. Shape/color/
                // border go on the menu's own surface — a rounded background
                // on the content leaves the surface's square corners showing.
                DropdownMenu(
                    expanded = chMenu,
                    onDismissRequest = { chMenu = false },
                    shape = RoundedCornerShape(12.dp),
                    containerColor = Color(0xFF16161B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                    modifier = Modifier.widthIn(min = 180.dp)
                ) {
                    val headerClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    // The panel this opens is titled "Channel Details" in the
                    // web (index.html:1414) and here; the entry said "Channel
                    // settings", so the label and its destination disagreed.
                    com.pombo.android.ui.ContextMenuItem(
                        "Channel Details", Icons.Outlined.Settings
                    ) { chMenu = false; showInfo = true }
                    // Web DropdownManager copy-stream-id: the id was only
                    // reachable by opening Channel Details and selecting it.
                    com.pombo.android.ui.ContextMenuItem(
                        "Copy Channel ID", Icons.Outlined.ContentCopy
                    ) {
                        chMenu = false
                        headerClipboard.setText(androidx.compose.ui.text.AnnotatedString(ch.messageStreamId))
                        vm.toast("Channel ID copied", com.pombo.android.ui.ToastKind.SUCCESS)
                    }
                    // Web shows this entry only when the channel has pins.
                    if (shownPins.isNotEmpty()) com.pombo.android.ui.ContextMenuItem(
                        "Pinned", Icons.Filled.PushPin,
                        iconTint = Color(0xFFFBBF24)
                    ) {
                        chMenu = false
                        dismissedPins = emptySet()   // re-show, then jump to it
                        scrollToMessage(pins.last().targetId)   // the newest pin, as in the banner
                    }
                    // Nothing to leave while previewing — the channel isn't saved.
                    if (!isPreview) {
                        com.pombo.android.ui.ContextMenuDivider()
                        com.pombo.android.ui.ContextMenuItem(
                            "Leave channel", Icons.AutoMirrored.Filled.Logout,
                            iconTint = Color(0xFFF87171), labelColor = Color(0xFFF87171)
                        ) { chMenu = false; vm.removeChannel(ch.messageStreamId) }
                    }
                }
            }
        }
        // Web `#chat-header { border-b border-white/[0.04] }` — a faint 1px rule
        // between the header and the message body.
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.04f)))

        // Paid-subscription strip (web .subscription-banner): static above the
        // messages — it states the channel's access state, so it must not
        // float over content or scroll away. Amber warning is dismissible per
        // viewing session; the expired strip is not.
        var subWarnDismissed by remember(ch.messageStreamId) { mutableStateOf(false) }
        paidStatus?.let { ps ->
            val msLeft = ps.paidUntil * 1000L - System.currentTimeMillis()
            val expired = msLeft <= 0 && !ps.accessNow
            val warning = msLeft in 1 until com.pombo.android.core.GateFormat.WARNING_MS
            if (expired || (warning && !subWarnDismissed)) {
                val tint = if (expired) Color(0xFFF87171) else Color(0xFFFBBF24)
                Row(
                    Modifier.fillMaxWidth()
                        .background(tint.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (expired) "Subscription expired — new messages stay locked until you renew"
                        else "Subscription ends in ${com.pombo.android.core.GateFormat.formatRemaining(msLeft)}",
                        color = tint, fontSize = 13.sp, lineHeight = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .background(PomboColors.Accent.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                            .border(1.dp, PomboColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .clickableNoRipple { vm.renewSubscription() }
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Renew", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (!expired) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Close, contentDescription = "Dismiss subscription warning",
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(16.dp).clickableNoRipple { subWarnDismissed = true }
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(tint.copy(alpha = 0.15f)))
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Web renderMessages: while any loading signal is still live the
            // area shows a centred spinner; only once everything is quiescent
            // and still empty does it say "No messages yet".
            if (visible.isEmpty()) {
                val terminalEmpty = !loadingInitial && !loadingHistory && !hasMoreHistory
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // A lapsed subscription and "admin offline" are identical
                    // at the key layer (refusals are silent) — the chain-read
                    // paid status decides which empty state this is.
                    val subExpired = paidStatus?.let {
                        it.paidUntil * 1000L <= System.currentTimeMillis() && !it.accessNow
                    } == true
                    if (terminalEmpty && waitingForKeys && subExpired) {
                        Text(
                            "Your subscription has expired",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Messages stay locked until you renew — renewing extends from the current end",
                            color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .background(PomboColors.Accent.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                                .border(1.dp, PomboColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .clickableNoRipple { vm.renewSubscription() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Renew subscription", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (terminalEmpty && waitingForKeys) {
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.30f),
                            strokeWidth = 2.dp, modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Waiting for channel keys…",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Another member needs to be online to share them",
                            color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp
                        )
                    } else if (terminalEmpty) {
                        Text(
                            "No messages yet. Start the conversation!",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White.copy(alpha = 0.40f),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("Loading messages...", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                    }
                }
            }
            LazyColumn(
                state = listState,
                // Chat-native orientation: item 0 (the newest group) sits at
                // the bottom and older items stack upward, so history loads
                // append past the far end and never move the viewport. Items
                // are emitted newest-first below to match.
                reverseLayout = true,
                // A reversed LazyColumn defaults to Arrangement.Bottom, which
                // is why a nearly empty room glued its one message to the
                // composer with the screen blank above it. The arrangement only
                // has any effect while the content is SHORTER than the
                // viewport, so asking for Top costs nothing once there is
                // enough history to scroll — and the reverse order is
                // untouched: the block still reads oldest-to-newest downward,
                // it is just parked at the top like the web's top-down list.
                verticalArrangement = Arrangement.Top,
                modifier = Modifier
                    .fillMaxSize()
                    // A tap anywhere that a bubble does not consume dismisses
                    // the triggers. Without this they stayed up until another
                    // message was tapped, so tapping empty space did nothing.
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { activeId = null })
                    },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp,
                    // Headroom only while the banner is actually showing.
                    top = if (shownPins.isNotEmpty()) 72.dp else 12.dp,
                    bottom = 12.dp
                )
            ) {
                // Emission is NEWEST-FIRST: with reverseLayout, index 0 draws
                // at the bottom, so each group is followed by the separator
                // that visually sits ABOVE it, and the history sentinel is the
                // LAST item — the visual result is identical to the old
                // top-down list.
                //
                // The separator/spacer is its OWN list item, so a group's item
                // bounds are exactly the group. They used to share one item,
                // which meant the sticky avatar's "don't go above the top"
                // clamp was measured against the item — leading space included
                // — and the avatar could ride up past the bubble it belongs to.
                for (gi in groups.indices.reversed()) {
                    val group = groups[gi]
                    val groupKey = group.items.first().id
                    item(key = groupKey) {
                    MessageGroup(
                        group = group,
                        reactions = reactions,
                        myAddress = myAddr,
                        canModerate = canModerate,
                        channelCreator = ch.createdBy,
                        listState = listState,
                        // Two items per group counted from the newest end
                        // (group, then its separator above).
                        itemIndex = (groups.size - 1 - gi) * 2,
                        hidden = hidden,
                        pins = pins,
                        activeId = activeId,
                        onActivate = { id -> activeId = if (activeId == id) null else id },
                        isContact = { addr -> contacts.any { it.address.equals(addr, ignoreCase = true) } },
                        onReact = { id, emoji, add -> vm.toggleReaction(id, emoji, add) },
                        onReply = { m -> editTarget = null; replyTarget = m; activeId = null },
                        onEdit = { m ->
                            replyTarget = null
                            editTarget = m
                            composerInput.setTextAndPlaceCursorAtEnd(m.text)
                        },
                        onDelete = { id -> vm.deleteMessage(id) },
                        onPin = { id, pin -> vm.pinMessage(id, pin) },
                        onHide = { id, hide -> vm.hideMessage(id, hide) },
                        onBan = { addr, client, protocol -> vm.banMemberLevels(addr, client, protocol) },
                        banGated = ch.type == "gated",
                        canClientBan = myAddr?.lowercase() ==
                            (ch.createdBy ?: ch.messageStreamId.substringBefore('/')).lowercase(),
                        onAddContact = { addr -> vm.addContact(addr, null) },
                        onSendDm = { addr -> vm.startDm(addr) },
                        onRemoveContact = { addr -> vm.removeContact(addr) },
                        // Blocking is DM-scoped: it leaves the conversation, so
                        // it only appears where the conversation is the peer.
                        onBlock = if (ch.type == "dm") ({ vm.blockPeer(ch.messageStreamId) }) else null,
                        onJumpTo = scrollToMessage,
                        highlightId = highlightId
                    )
                    }
                    item(key = "sep-$groupKey") {
                        // Date separator chip when the day changes (web: "Today" pill)
                        val prevGroup = groups.getOrNull(gi - 1)
                        val prevTs = prevGroup?.items?.last()?.timestamp
                        if (prevTs == null || !sameDay(prevTs, group.items.first().timestamp)) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    dayLabel(group.items.first().timestamp),
                                    color = Color.White.copy(alpha = 0.40f),
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .padding(vertical = 6.dp)
                                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(50))
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                        } else {
                            // Web spacing scale: ping-pong 14dp between sides,
                            // same-side-different-sender 20dp.
                            Spacer(Modifier.height(if (prevGroup.mine == group.mine) 20.dp else 14.dp))
                        }
                    }
                }
                // History sentinel — the last item, drawn at the very top:
                // spinner while a page loads, otherwise the end-of-history
                // marker once the stream is exhausted. NOT emitted while the
                // list is empty: it would be the only item, and when the first
                // page lands the LazyColumn follows its key to the far end,
                // dragging the viewport away from the newest message. (The
                // searchOlder tap target must survive an empty DM window.)
                if (groups.isNotEmpty() || searchOlder) item(key = "history-top") {
                    when {
                        // The initial-load spinner is drawn over the whole area,
                        // so the sentinel stays empty during that phase.
                        loadingInitial -> Unit
                        // DM window came back empty — the web offers this rather
                        // than scanning older weeks on its own.
                        searchOlder -> Row(
                            Modifier.fillMaxWidth()
                                .clickableNoRipple { vm.loadMoreDmHistory() }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Search, contentDescription = null,
                                tint = Color.White.copy(alpha = 0.30f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "No messages found in this time range — Search older",
                                color = Color.White.copy(alpha = 0.30f), fontSize = 14.sp
                            )
                        }
                        loadingHistory -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White.copy(alpha = 0.30f),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Loading older messages...",
                                color = Color.White.copy(alpha = 0.30f),
                                fontSize = 14.sp
                            )
                        }
                        !hasMoreHistory && visible.isNotEmpty() -> Box(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "— beginning of conversation —",
                                color = Color.White.copy(alpha = 0.12f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Pinned banner floats over the messages (web .pinned-banner: glass
            // white 6%, border 8%, radius 16, inset 10/12).
            if (shownPins.isNotEmpty()) {
                val first = shownPins.first()
                // Prefer the ENS name over the raw address, like everywhere else.
                val ensNames by vm.ensNames.collectAsState()
                LaunchedEffect(first.sender) { vm.ensureEns(first.sender) }
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .fillMaxWidth()
                        // Tapping the banner jumps to the pinned message — the
                        // banner was inert, so a pin could be seen but not found.
                        .clickableNoRipple { scrollToMessage(first.targetId) }
                        // The web gets its frost from backdrop-filter: blur(20px).
                        // Compose has no backdrop blur, so a near-opaque dark base
                        // carries the same "floating glass" read without letting
                        // the scrolled text behind show through as noise.
                        .background(Color(0xFF0E0E10).copy(alpha = 0.88f), RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.PushPin, contentDescription = null,
                        tint = PomboColors.Accent, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    // `.pinned-banner-body { line-height: 1.25 }` — 13px text on
                    // a 16px line. Compose's default line height (~1.45) made the
                    // banner noticeably taller than the web's, and the extra
                    // height ate the room the text could have used.
                    Column(Modifier.weight(1f)) {
                        if (first.sender.isNotEmpty()) {
                            Text(
                                // Web parity (PinnedBannerUI._buildPreviewParts):
                                // live ENS cache first (resolves after pin time),
                                // then the frozen snapshot's ENS, then its display
                                // name, then the truncated address.
                                ensNames[first.sender.lowercase()]
                                    ?: first.ensName
                                    ?: first.senderName
                                    ?: shortAddress(first.sender),
                                color = PomboColors.Accent,
                                fontSize = 13.sp, lineHeight = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(1.dp))   // .pinned-banner-text margin-top
                        }
                        Text(
                            first.text.ifEmpty { "Pinned message" },
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 13.sp, lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    if (shownPins.size > 1) {
                        Text(
                            "+${shownPins.size - 1}",
                            color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    // Dismiss reveals the pin before this one, or hides the banner
                    // once they run out. It does NOT unpin — unpinning is a
                    // moderation action for everyone, this is only "stop showing
                    // me this one".
                    Icon(
                        Icons.Filled.Close, contentDescription = "Dismiss pinned message",
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier
                            .size(16.dp)
                            .clickableNoRipple { dismissedPins = dismissedPins + first.targetId }
                    )
                }
            }
        }

        // Typing indicator. Kept mounted so the wave can animate in and out
        // instead of the row popping the composer up and down.
        val typingEns by vm.ensNames.collectAsState()
        // Rebuilt on every recomposition, not frozen when the signal arrived:
        // the ENS name usually lands a moment after the first "typing".
        val typingLabel = typingSentence(typing, typingEns)
        // Held past the clear, or the line would blank out mid-fade-out.
        var lastTypingLabel by remember { mutableStateOf("") }
        LaunchedEffect(typingLabel) { typingLabel?.let { lastTypingLabel = it } }
        AnimatedVisibility(
            visible = typing.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(140))
        ) {
            TypingIndicator(lastTypingLabel)
        }

        // Reply bar above the composer (web #reply-bar)
        replyTarget?.let { target ->
            Row(
                Modifier.fillMaxWidth().background(PomboColors.Surface)
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(2.dp).height(30.dp).background(Color.White.copy(alpha = 0.15f)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        target.ensName ?: target.senderName ?: shortAddress(target.sender),
                        color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (target.isImage || target.file != null || target.storageFile != null) "[Media]"
                        else target.text.take(100),
                        color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, maxLines = 1
                    )
                }
                IconButton(onClick = { replyTarget = null }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel reply", tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(16.dp))
                }
            }
        }

        // Edit bar above the composer (web #edit-bar) — no text preview on
        // the web either, just the label; the composer itself carries the text.
        if (editTarget != null) {
            Row(
                Modifier.fillMaxWidth().background(PomboColors.Surface)
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(2.dp).height(30.dp).background(PomboColors.Accent.copy(alpha = 0.5f)))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Editing message",
                    color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { editTarget = null; composerInput.clearText() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel edit", tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(16.dp))
                }
            }
        }

        // A read-only channel grants public subscribe but not publish, so a
        // send here fails at the network layer with nothing shown to the user.
        // The web disables the field and swaps the placeholder
        // (PreviewModeUI.js:390-392); we only ever rendered a label.
        // Expired subscription cuts the composer too: the transport would
        // still accept the publish (sticky membership), but honest receivers
        // drop it at ingest — writing into that void is a trap, not a feature.
        val subExpired = paidStatus?.let {
            it.paidUntil * 1000L <= System.currentTimeMillis() && !it.accessNow
        } == true
        val canPost = (!ch.readOnly || ch.createdBy?.equals(myAddr, ignoreCase = true) == true) &&
            !subExpired
        ChatComposer(
            input = composerInput,
            canPost = canPost,
            disabledPlaceholder = if (subExpired) "Subscription expired — renew to write"
                else "This channel is read-only",
            onTyping = { vm.notifyTyping() },
            onPickImage = { vm.sendImage(it) },
            onPickVideo = { vm.sendVideo(it) },
            onPickFile = { vm.sendFile(it) },
            onPickStorageFile = { vm.sendStorageFile(it) },
            onSend = { text ->
                val et = editTarget
                if (et != null) {
                    vm.editMessage(et.id, text)
                    editTarget = null
                } else {
                    vm.sendMessage(text, replyTarget?.let {
                        com.pombo.android.ReplyRef(it.id, it.sender, it.senderName ?: it.ensName, it.text)
                    })
                    replyTarget = null
                }
            }
        )
    }
}

/**
 * "Bob and 0x1234 are typing".
 *
 * Two names are worth spelling out; past that the line would grow without
 * bound and start eating the composer, so the tail collapses into a count.
 * Whoever has no ENS name and no nickname shows as an address prefix — long
 * enough to tell two strangers apart, short enough not to dominate the line.
 */
private fun typingSentence(
    peers: List<com.pombo.android.ChannelManager.TypingPeer>,
    ensNames: Map<String, String>
): String? {
    if (peers.isEmpty()) return null
    val names = peers.map { p ->
        ensNames[p.address.lowercase()] ?: p.nickname ?: p.address.take(6)
    }
    return when (names.size) {
        1 -> "${names[0]} is typing"
        2 -> "${names[0]} and ${names[1]} are typing"
        else -> "${names[0]} and ${names.size - 1} others are typing"
    }
}

/**
 * The typing line: a three-dot wave and one dim sentence, nothing else — no
 * chip, no border. It sits directly above the composer, where any surface of
 * its own would read as a second input bar.
 *
 * One infinite driver phase-shifted per dot rather than three independent
 * animations: that is what makes it read as a travelling wave instead of three
 * blinking dots, and it stays in phase forever. Each dot lifts, brightens and
 * grows on the same curve, and the positive half of a sine leaves a natural
 * rest between passes.
 */
@Composable
private fun TypingIndicator(name: String) {
    val wave = rememberInfiniteTransition(label = "typing")
    val phase by wave.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "typingPhase"
    )
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { i ->
                // Each dot runs the same cycle, a fifth of a period behind the
                // one before it.
                val local = (phase - i * 0.16f + 1f) % 1f
                val bump = kotlin.math.sin(local * 2 * Math.PI).toFloat().coerceAtLeast(0f)
                Box(
                    Modifier
                        .size(6.dp)
                        .graphicsLayer {
                            translationY = -bump * 4.dp.toPx()
                            val s = 0.82f + 0.30f * bump
                            scaleX = s; scaleY = s
                            alpha = 0.40f + 0.60f * bump
                        }
                        .background(PomboColors.Accent, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            name, color = PomboColors.TextDim, fontSize = 13.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * The input bar, split out of ChatScreen with the draft state owned here: a
 * keystroke used to invalidate the whole ChatScreen scope (header, pins, the
 * list call site and every lambda in it re-executed per key press), which on a
 * phone read as typing lag. Now a key press recomposes only this Row.
 */
@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
private fun ChatComposer(
    // Hoisted to the caller (web parity): editing a message pre-fills and
    // reuses this same field instead of opening a separate one inline.
    input: TextFieldState,
    canPost: Boolean,
    disabledPlaceholder: String = "This channel is read-only",
    onTyping: () -> Unit,
    onPickImage: (android.net.Uri) -> Unit,
    onPickVideo: (android.net.Uri) -> Unit,
    onPickFile: (android.net.Uri) -> Unit,
    onPickStorageFile: (android.net.Uri) -> Unit,
    onSend: (String) -> Unit
) {
    // State-based field, NOT the legacy value/onValueChange one: only the
    // TextFieldState pipeline advertises accepted MIME types to the IME via
    // contentReceiver — with the old field, Gboard greyed its GIFs out with
    // "can't insert this content here".
    LaunchedEffect(Unit) {
        var first = true
        androidx.compose.runtime.snapshotFlow { input.text.toString() }.collect {
            if (first) first = false else onTyping()
        }
    }
    // Image opens a tray of ours (ImagePickerSheet) instead of a launcher: the
    // system photo picker is a closed Activity and cannot carry the camera
    // tile, so that grid has to be drawn here. Video and File keep the system
    // pickers — nothing about them wants a camera.
    var imageTrayOpen by remember { mutableStateOf(false) }
    if (imageTrayOpen) {
        com.pombo.android.ui.ImagePickerSheet(
            onPick = onPickImage,
            onDismiss = { imageTrayOpen = false }
        )
    }
    val videoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPickVideo(uri) }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPickFile(uri) }
    // Storage Cluster (Persistent File Sharing): one document picker for both the
    // File and Video "Storage Cluster" entries — the storage path is type-agnostic.
    val storageFilePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onPickStorageFile(uri) }
    // Web #message-input-container, but on the app's pure-black surface: a 1px
    // hairline on TOP (border-t border-white/[0.04]) mirroring the header's
    // bottom rule, and p-3 pb-4.
    Column(Modifier.fillMaxWidth().background(PomboColors.Surface)) {
    androidx.compose.material3.HorizontalDivider(
        color = Color.White.copy(alpha = 0.04f), thickness = 1.dp
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Web input bar: attach (paperclip) + emoji, then the text field.
        // DMs get it too — the transport is the same, only the sealing
        // differs (ECDH envelope instead of the channel password).
        // Web attach menu parity (index.html attach-menu): File and Video are
        // parents that expand a transport submenu — Mesh live, Persistent
        // greyed out with "(soon)" — while Image picks directly. A hover
        // flyout does not exist on touch, so the submenu replaces the menu
        // body in place, with a back row.
        var attachOpen by remember { mutableStateOf(false) }
        var attachSubmenu by remember { mutableStateOf<String?>(null) }
        Box {
            IconButton(
                onClick = { attachSubmenu = null; attachOpen = true },
                enabled = canPost,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach", tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = attachOpen,
                onDismissRequest = { attachOpen = false; attachSubmenu = null },
                modifier = Modifier.background(Color(0xFF16161B))
            ) {
                when (attachSubmenu) {
                    null -> {
                        DropdownMenuItem(
                            text = { Text("File  ›", color = Color.White) },
                            onClick = { attachSubmenu = "file" }
                        )
                        DropdownMenuItem(
                            text = { Text("Video  ›", color = Color.White) },
                            onClick = { attachSubmenu = "video" }
                        )
                        DropdownMenuItem(
                            text = { Text("Image", color = Color.White) },
                            onClick = { attachOpen = false; imageTrayOpen = true }
                        )
                    }
                    "file" -> {
                        DropdownMenuItem(
                            text = { Text("‹  File", color = Color.White.copy(alpha = 0.55f)) },
                            onClick = { attachSubmenu = null }
                        )
                        DropdownMenuItem(
                            text = { Text("P2P", color = Color.White) },
                            onClick = {
                                attachOpen = false; attachSubmenu = null
                                filePicker.launch(arrayOf("*/*"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Storage", color = Color.White) },
                            onClick = {
                                attachOpen = false; attachSubmenu = null
                                storageFilePicker.launch(arrayOf("*/*"))
                            }
                        )
                    }
                    "video" -> {
                        DropdownMenuItem(
                            text = { Text("‹  Video", color = Color.White.copy(alpha = 0.55f)) },
                            onClick = { attachSubmenu = null }
                        )
                        DropdownMenuItem(
                            text = { Text("P2P", color = Color.White) },
                            onClick = {
                                attachOpen = false; attachSubmenu = null
                                videoPicker.launch(androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.VideoOnly
                                ))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Storage", color = Color.White) },
                            onClick = {
                                attachOpen = false; attachSubmenu = null
                                storageFilePicker.launch(arrayOf("video/*"))
                            }
                        )
                    }
                }
            }
        }
        var emojiOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { emojiOpen = true }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.SentimentSatisfied, contentDescription = "Emoji", tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = emojiOpen,
                onDismissRequest = { emojiOpen = false },
                modifier = Modifier.background(Color(0xFF16161B))
            ) {
                // A FlowRow, NOT a LazyVerticalGrid: DropdownMenu already
                // wraps its content in a vertical scroller, so nesting a
                // second vertical scroller inside it measures against an
                // infinite height and hangs the main thread (this ANR'd).
                // 88 items render eagerly for nothing anyway.
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .width(304.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    COMPOSER_EMOJIS.forEach { e ->
                        Text(
                            e, fontSize = 22.sp,
                            // The menu stays open on pick, so several
                            // emoji can be added in a row.
                            modifier = Modifier
                                .clickableNoRipple { input.edit { append(e) } }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
        val fieldInteraction = remember { MutableInteractionSource() }
        val fieldFocused by fieldInteraction.collectIsFocusedAsState()
        androidx.compose.foundation.text.BasicTextField(
            state = input,
            enabled = canPost,
            interactionSource = fieldInteraction,
            lineLimits = TextFieldLineLimits.MultiLine(1, 4),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = PomboColors.Text, fontSize = 16.sp
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
            // Rebuilt OutlinedTextField look (pomboFieldColors + 24dp shape).
            decorator = { inner ->
                // Web #message-input: bg-white/[0.05], rounded-2xl, border-white/[0.08].
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .border(
                            1.dp,
                            // Focus is a faint GREY lift, not an orange rule —
                            // the web uses focus:ring-1 ring-white/10.
                            if (fieldFocused) Color.White.copy(alpha = 0.16f) else PomboColors.Border,
                            RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (input.text.isEmpty()) {
                        Text(
                            if (canPost) "Message…" else disabledPlaceholder,
                            color = PomboColors.TextDim, fontSize = 16.sp
                        )
                    }
                    inner()
                }
            },
            // Gboard GIFs/stickers (IME commitContent) and image paste land
            // here instead of being silently dropped (web InputUI.js handles
            // paste the same way). Anything that is not an image passes
            // through to the normal text pipeline.
            modifier = Modifier.weight(1f).let { base ->
                if (!canPost) base
                else base.contentReceiver { transferable ->
                    if (!transferable.hasMediaType(
                            androidx.compose.foundation.content.MediaType.Image
                        )
                    ) return@contentReceiver transferable
                    transferable.consume { item ->
                        val uri = item.uri
                        if (uri != null) { onPickImage(uri); true } else false
                    }
                }
            }
        )
        Spacer(Modifier.width(8.dp))
        // Web #send-message-btn: no button chrome at all, just the outline
        // paper-plane in the accent amber, sized to stand level with the input
        // field (16sp line + 10dp padding top/bottom). One single appearance —
        // there is no filled/empty state, the glyph never changes.
        // A Box, NOT an IconButton: Material3 clips IconButton's content to a
        // circle, which sliced the plane's nose and tail corners off once the
        // glyph grew to fill the box.
        Box(
            Modifier
                .size(40.dp)
                // Stays clickable while the channel is writable and simply no-ops
                // on an empty box — nothing about the button reflects whether
                // there is text.
                .clickableNoRipple {
                    if (canPost && input.text.isNotBlank()) {
                        onSend(input.text.toString())
                        input.clearText()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // tint = Unspecified so the vector keeps its own stroke colour
            // instead of being flattened by Icon's tint.
            Icon(
                SendPlaneIcon,
                contentDescription = "Send",
                tint = Color.Unspecified,
                modifier = Modifier.size(36.dp)
            )
        }
    }
    }
}

/**
 * The web's send glyph (index.html #send-message-btn): the Heroicons outline
 * paper-plane, stroke 1.8 with round caps — Material's Send is a solid plane and
 * read as a different icon next to the web.
 *
 * Outline only, one appearance — the button has no filled/empty state.
 *
 * The viewport is cropped to the drawing's real bounds instead of the source
 * 24x24 box. The plane only spans x 3.269..21.485 / y 3.126..20.876, so a 24-box
 * wastes a quarter of the icon on empty margin and the glyph can never grow to
 * the height of the input field next to it.
 *
 * PLANE_VIEW is the drawing's width (18.216) plus 0.8 of stroke room on each
 * side, kept square so Modifier.size() does not distort it.
 */
private const val PLANE_VIEW = 19.816f

private val SendPlaneIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "SendPlane",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = PLANE_VIEW, viewportHeight = PLANE_VIEW
    ).apply {
        // Shift the path into the cropped viewport; the y offset also re-centres
        // the (slightly shorter) glyph inside the squared-off box.
        addGroup(translationX = -2.469f, translationY = -2.093f)
        addPath(
            // Same geometry as the web's path, but with the arc flags written
            // out ("0 0 1" instead of the compressed "0 01"): Compose's
            // PathParser mis-reads the packed form and the plane came out
            // deformed.
            pathData = androidx.compose.ui.graphics.vector.PathParser()
                .parsePathString(
                    "M6 12 L3.269 3.126 A59.768 59.768 0 0 1 21.485 12 " +
                        "A59.77 59.77 0 0 1 3.27 20.876 L5.999 12 Z M6 12 H13.5"
                ).toNodes(),
            fill = null,
            stroke = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
            // The web's 1.8 restated in the cropped viewport (1.8 * 19.816/24),
            // so the rule keeps its weight relative to the drawing. Left at the
            // raw 1.8 it reads far too heavy; thinner and the outline looks
            // washed out rather than orange.
            strokeLineWidth = 1.486f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        )
        clearGroup()
    }.build()
}

/**
 * Channel-type glyph for the chat-header subtitle (web #current-channel-info,
 * icon-only). native = Ethereum diamond, password = lock, public = globe, dm =
 * envelope; a read-only channel adds a megaphone, like the web's roIcon.
 */
@Composable
private fun ChannelTypeIcon(type: String, readOnly: Boolean, tint: Color, size: Dp = 13.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (type) {
            "gated" -> EthereumIcon(tint, Modifier.size(size))
            "password" -> Icon(Icons.Outlined.Lock, null, tint = tint, modifier = Modifier.size(size))
            "dm" -> Icon(Icons.Outlined.MailOutline, null, tint = tint, modifier = Modifier.size(size))
            else -> Icon(Icons.Outlined.Public, null, tint = tint, modifier = Modifier.size(size))
        }
        if (readOnly && type != "dm") {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Campaign, null, tint = tint, modifier = Modifier.size(size))
        }
    }
}
