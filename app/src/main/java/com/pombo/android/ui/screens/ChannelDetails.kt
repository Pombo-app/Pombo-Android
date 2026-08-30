package com.pombo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.pombo.android.AppViewModel
import com.pombo.android.data.Channel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.theme.PomboColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * Channel Details — the web's mobile presentation of #channel-settings-modal:
 * a full-screen sheet that slides in from the right, with NO sidebar/tabs.
 * Info and Storage flow inline, then a nav list for Members / Moderation /
 * Delete (`#channel-mobile-unified`).
 */
@Composable
internal fun ChannelSettingsSheet(vm: AppViewModel, channel: Channel, canModerate: Boolean, onDismiss: () -> Unit) {
    var sub by remember { mutableStateOf<ChannelSubPanel?>(null) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        // Edge-to-edge window + safeDrawingPadding, same as CreateChannelDialog.
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // A Dialog dismisses itself on back, which would skip the sub-panel
        // level entirely — from Members straight out of Channel Details. Same
        // rule as the header arrow: leave the sub-panel first.
        androidx.activity.compose.BackHandler(enabled = sub != null) { sub = null }
        com.pombo.android.ui.DialogBackdropBlur()
        // Minimum bottom clearance, as in CreateChannelDialog: a phone using
        // gesture navigation with no gesture bar reports a 0 bottom inset and
        // the panel would sit flush against the screen edge.
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(PomboColors.Background)
                .safeDrawingPadding()
                .padding(bottom = (12.dp - bottomInset).coerceAtLeast(0.dp))
        ) {
            // Header: on mobile the web lays this out `justify-between` with the
            // back button first and the title pushed to the RIGHT edge
            // (index.html:1405 — the md:order swap only right-aligns the title on
            // desktop; on mobile it stays where the DOM puts it, at the end).
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(20.dp).clickableNoRipple {
                        if (sub != null) sub = null else onDismiss()
                    }
                )
                Text(
                    sub?.label ?: "Channel Details",
                    color = Color.White.copy(alpha = 0.90f),
                    fontSize = 18.sp, fontWeight = FontWeight.Medium
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(20.dp)
            ) {
                when (sub) {
                    null -> ChannelDetailsMain(vm, channel, canModerate, onOpenSub = { sub = it }, onDismiss = onDismiss)
                    ChannelSubPanel.MEMBERS -> ChannelMembersPanel(vm, channel, canModerate)
                    ChannelSubPanel.MODERATION -> ChannelModerationPanel(vm, channel, canModerate)
                    ChannelSubPanel.STORAGE -> ChannelStoragePanel(vm, channel, canModerate)
                    ChannelSubPanel.DELETE -> ChannelDeletePanel(vm, channel, onDismiss)
                    ChannelSubPanel.DESTROY -> ChannelDestroyPanel(vm, channel, onDismiss)
                }
            }
        }
        // Mirror of the main window's toast host. This sheet is its own OS
        // window layered ABOVE the activity, so every toast fired from inside
        // it — copy confirmations, member-batch results, moderation and
        // delete-channel progress — was drawn underneath and never seen. Same
        // shared state, so dismissal in either window clears both.
        val sheetToasts by vm.toasts.collectAsState()
        com.pombo.android.ui.ToastHost(
            toasts = sheetToasts,
            onDismiss = vm::dismissToast,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .padding(top = 29.dp, start = 8.dp, end = 8.dp)
        )
        }
    }
}

private enum class ChannelSubPanel(val label: String) {
    MEMBERS("Members"),
    MODERATION("Moderation"),
    STORAGE("Storage"),
    /** Local leave — the streams stay on-chain. */
    DELETE("Leave Channel"),
    /** On-chain delete of all three streams — irreversible for everyone. */
    DESTROY("Delete Channel")
}

@Composable
private fun ChannelDetailsMain(
    vm: AppViewModel,
    channel: Channel,
    canModerate: Boolean,
    onOpenSub: (ChannelSubPanel) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val image by vm.channelImage.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var editName by remember(channel.name) { mutableStateOf(channel.name) }
    var editDesc by remember(channel.description) { mutableStateOf(channel.description) }
    val busy by vm.busy.collectAsState()
    // A member of a channel whose name never went on-chain (hidden exposure)
    // renames locally, like a DM nickname. Name only — the description stays
    // the owner's.
    val memberLocalRename = channel.type != "dm" && !canModerate && channel.exposure != "visible"
    // Picked photo goes through the crop dialog first (web crop modal): drag
    // to frame, pinch/slider to zoom, and only the confirmed 512² is published.
    var cropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) cropUri = uri }
    cropUri?.let { uri ->
        com.pombo.android.ui.ChannelImageCropDialog(
            uri = uri,
            onCancel = { cropUri = null },
            onConfirm = { bytes, mime ->
                cropUri = null
                vm.setChannelImage(bytes, mime)
            }
        )
    }

    // Notification chip at the very top (web #mobile-notif-chip): a pill, not a
    // row with a switch. Left-aligned, mb-3. Visible to all users.
    val pushRev by vm.pushRev.collectAsState()
    val notified = remember(channel.messageStreamId, pushRev) {
        // A DM's relay registration is the inbox-wide one; the per-DM state
        // is the local mute of this peer.
        if (channel.type == "dm") !vm.isDmMuted(channel.peerAddress)
        else vm.isChannelNotified(channel.messageStreamId)
    }
    Row(
        Modifier
            .background(
                if (notified) PomboColors.Accent.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                RoundedCornerShape(999.dp)
            )
            .border(
                1.dp,
                if (notified) PomboColors.Accent.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(999.dp)
            )
            .clickableNoRipple { vm.setChannelNotifications(channel, !notified) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (notified) Icons.Filled.Notifications else Icons.Outlined.Notifications,
            contentDescription = null,
            tint = if (notified) PomboColors.Accent else Color.White.copy(alpha = 0.40f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (notified) "Notifications On" else "Notifications Off",
            color = if (notified) PomboColors.Accent else Color.White.copy(alpha = 0.40f),
            fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
    Spacer(Modifier.height(16.dp))

    // Image (circular 80dp, border white/8) + name, gap 16 — web layout.
    // A DM has no room identity: the face here is the PEER's, ENS picture when
    // they have one and the generated avatar otherwise, exactly as in the chat
    // header and the channel list. Keying it off the stream id gave a room
    // avatar for a conversation with one person.
    val ensAvatars by vm.ensAvatars.collectAsState()
    val dmPeer = channel.peerAddress?.takeIf { channel.type == "dm" }
    LaunchedEffect(dmPeer) { dmPeer?.let { vm.ensureEns(it) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(80.dp)) {
            if (dmPeer != null) {
                Avatar(
                    dmPeer, size = 80.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = ensAvatars[dmPeer.lowercase()],
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                )
            } else if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = remember(image) {
                        android.graphics.BitmapFactory.decodeByteArray(image, 0, image!!.size).asImageBitmap()
                    },
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(80.dp).clip(CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                )
            } else {
                Avatar(
                    channel.messageStreamId, size = 80.dp, cornerRadiusFraction = 0.5,
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                )
            }
            if (canModerate) {
                // 28dp upload affordance pinned bottom-right, like the web.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .background(Color(0xFF16161B), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape)
                        .clickableNoRipple {
                            picker.launch(androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            ))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.FileUpload, contentDescription = "Upload image",
                        tint = Color.White.copy(alpha = 0.60f), modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.name, color = Color.White.copy(alpha = 0.90f),
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                )
                // Web canEditName = (type === 'dm' || canDelete || no
                // on-chain name) && !preview. A DM's name is a local
                // nickname, so it is always yours to change even though you
                // have no permissions on the stream.
                val canEditName = channel.type == "dm" || canModerate || memberLocalRename
                if (canEditName) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Edit, contentDescription = "Edit channel info",
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier.size(16.dp).clickableNoRipple { editing = !editing }
                    )
                }
            }
        }
    }

    if (editing) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = editName, onValueChange = { editName = it },
            placeholder = { Text("Enter name", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
            singleLine = true, shape = RoundedCornerShape(8.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
        )
    }

    // DESCRIPTION — the web hides this section entirely when the channel has no
    // description and you are not editing (#non-native-message is toggled on
    // hasDescription). We used to always show a "No description" box, which the
    // web never does.
    if (editing) {
        // Web saveChannelName: only non-DM edits touch the description and
        // the chain. A DM's name is a local nickname — no description, no
        // on-chain write, no gas caveat. A member's local rename is the same
        // deal, and the hint replaces the gas warning.
        if (memberLocalRename) {
            Spacer(Modifier.height(6.dp))
            Text(
                "This name is stored locally only",
                color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp
            )
        }
        if (channel.type != "dm" && !memberLocalRename) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Description")
            OutlinedTextField(
                value = editDesc, onValueChange = { editDesc = it },
                placeholder = { Text("Enter description", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                shape = RoundedCornerShape(8.dp), maxLines = 3,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            // Gas warning — the web shows the same caveat before an on-chain
            // edit. Hidden channels save locally (updateChannelMetadata skips
            // the chain), so no caveat there.
            if (channel.exposure == "visible") {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFFF59E0B).copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        "Saving writes the stream metadata on-chain and costs gas.",
                        color = Color(0xFFF59E0B).copy(alpha = 0.80f), fontSize = 12.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .background(PomboColors.Accent, RoundedCornerShape(8.dp))
                    .clickableNoRipple {
                        if (!busy) {
                            when {
                                channel.type == "dm" -> vm.renameDm(channel, editName)
                                memberLocalRename -> vm.renameChannelLocal(channel, editName)
                                else -> vm.updateChannelMetadata(editName, editDesc)
                            }
                            editing = false
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text("Save", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .clickableNoRipple {
                        editName = channel.name; editDesc = channel.description; editing = false
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp) }
        }
    } else if (channel.description.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        SectionLabel("Description")
        ValueBox(channel.description)
    }

    // ID (tap to copy, exactly like the web's <code> block)
    Spacer(Modifier.height(20.dp))
    SectionLabel("ID")
    Text(
        channel.messageStreamId,
        color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .clickableNoRipple {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(channel.messageStreamId))
                vm.toast("Stream ID copied", com.pombo.android.ui.ToastKind.SUCCESS)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )

    // ACCESS — the web shows an icon + label per type (HeaderUI type lines):
    // native = Ethereum diamond + "Verified Membership", password = lock +
    // "Password Protected", public = globe + "Open".
    // N-D: only Closed (NONE) keeps "Verified Membership" — token/NFT show
    // the condition, paid the price/period, same lineup as Create Channel.
    // Async on purpose: the default stands until the (cached) chain answers.
    var gateAccess by remember(channel.messageStreamId) { mutableStateOf<String?>(null) }
    if (channel.type == "gated") {
        LaunchedEffect(channel.messageStreamId) {
            gateAccess = vm.gateAccessLabel()
        }
    }
    Spacer(Modifier.height(20.dp))
    SectionLabel("Access")
    Row(verticalAlignment = Alignment.CenterVertically) {
        val accessTint = Color.White.copy(alpha = 0.70f)
        val accessText: String
        // Gated uses the Ethereum mark, drawn (not a Material icon).
        if (channel.type == "gated" && !channel.readOnly) {
            EthereumIcon(accessTint, Modifier.size(16.dp))
            accessText = gateAccess ?: "Verified Membership"
        } else {
            val icon = when {
                channel.readOnly -> Icons.Outlined.Campaign
                channel.type == "password" -> Icons.Outlined.Lock
                channel.type == "public" -> Icons.Outlined.Public
                channel.type == "dm" -> Icons.Outlined.MailOutline
                else -> Icons.Outlined.Public
            }
            accessText = when {
                channel.readOnly -> "Announcements"
                channel.type == "password" -> "Password Protected"
                channel.type == "public" -> "Open"
                channel.type == "dm" -> "Direct Message"
                else -> channelTypeLabel(channel.type)
            }
            Icon(icon, contentDescription = null, tint = accessTint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(accessText, color = accessTint, fontSize = 14.sp)
    }

    // PAID member view: the subscription clock lives here, under the access
    // line — the chat header stays clean (N-F). paidUntil 0 = moderator on a
    // paid gate (never pays), no clock.
    val detailsPaidStatus by vm.paidStatus.collectAsState()
    detailsPaidStatus?.takeIf { it.paidUntil > 0 }?.let { ps ->
        val msLeft = ps.paidUntil * 1000L - System.currentTimeMillis()
        Spacer(Modifier.height(4.dp))
        Text(
            if (msLeft > 0) "${com.pombo.android.core.GateFormat.formatRemaining(msLeft)} left"
            else "Subscription expired",
            color = when {
                msLeft <= 0 -> Color(0xFFF87171).copy(alpha = 0.80f)
                msLeft < com.pombo.android.core.GateFormat.WARNING_MS -> Color(0xFFFBBF24)
                else -> Color.White.copy(alpha = 0.40f)
            },
            fontSize = 12.sp
        )
    }

    // Nav rows — the web's #channel-mobile-unified shows exactly three, gated by
    // channel type and permission (showMembersTab/showModerationTab/showDangerTab):
    //   Members   — native (closed / on-chain) channels only. Membership there
    //               is a set of on-chain grants; password and public channels
    //               have no explicit member list, so the tab is hidden entirely.
    //   Moderation— any non-DM channel you can moderate (password/public too).
    //   Delete    — DELETE permission only; irreversible for every member.
    // Invite lives in the chat header, Leave in the header kebab — neither is a
    // nav row here, matching the web.
    Spacer(Modifier.height(24.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
    Spacer(Modifier.height(16.dp))
    val isGated = channel.type == "gated"
    if (isGated) {
        ChannelNavRow("Members", leadingIcon = Icons.Outlined.People) { onOpenSub(ChannelSubPanel.MEMBERS) }
        Spacer(Modifier.height(8.dp))
    }
    if (canModerate) {
        ChannelNavRow("Moderation", leadingIcon = Icons.Outlined.VerifiedUser) { onOpenSub(ChannelSubPanel.MODERATION) }
        Spacer(Modifier.height(8.dp))
    }
    if (channel.type != "dm") {
        ChannelNavRow("Storage", leadingIcon = Icons.Outlined.Storage) { onOpenSub(ChannelSubPanel.STORAGE) }
        Spacer(Modifier.height(8.dp))
    }
    if (isGated && canModerate) {
        // Key responder: THIS device keeps answering key requests for the
        // channel — foreground sweep plus the background worker.
        val responderRev by vm.keyResponderRev.collectAsState()
        val responderOn = remember(responderRev, channel.messageStreamId) { vm.isKeyResponder(channel) }
        Row(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .clickableNoRipple { vm.setKeyResponder(channel, !responderOn) }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Key, contentDescription = null,
                tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Background Key Responder", color = Color.White.copy(alpha = 0.70f),
                fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (responderOn) "ON" else "OFF",
                color = if (responderOn) Color(0xFF34D399) else Color.White.copy(alpha = 0.30f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    if (canModerate) {
        // Delete Channel has no leading icon in the web.
        ChannelNavRow("Delete Channel") { onOpenSub(ChannelSubPanel.DESTROY) }
    }
    // Clear the gesture bar: the last row was flush against it.
    Spacer(Modifier.height(40.dp))
}

/** Web: uppercase label, 12sp medium, white/80, tracking-wider, mb 6dp. */
@Composable
internal fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = 0.80f),
        fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/** Web: value box — text-sm white/70 on white/5, rounded-lg, px-3 py-2.5. */
@Composable
private fun ValueBox(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/**
 * Web .channel-mobile-nav-item: white/3 fill, white/5 border, r12, py-4, with a
 * leading icon (18dp, white/40) and a trailing chevron. Members and Moderation
 * carry an icon; Delete Channel does not (index.html:1755 has no leading svg).
 */
@Composable
private fun ChannelNavRow(
    label: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = Color.White.copy(alpha = 0.20f), modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Members — web #channel-panel-members: the on-chain permission list with a
 * refresh, plus add/remove for the owner. Open channels have no member list
 * (anyone can read), so only presence is shown there.
 */
@Composable
private fun ChannelMembersPanel(vm: AppViewModel, channel: Channel, canModerate: Boolean) {
    val busy by vm.busy.collectAsState()
    val perms by vm.perms.collectAsState()
    val myAddress by vm.address.collectAsState()
    val ensNames by vm.ensNames.collectAsState()
    val ensAvatars by vm.ensAvatars.collectAsState()
    var members by remember { mutableStateOf<List<com.pombo.android.ChannelManager.MemberRow>?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var newMember by remember { mutableStateOf("") }
    var batchOpen by remember { mutableStateOf(false) }
    var batchText by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var confirmBan by remember { mutableStateOf<String?>(null) }
    var kebabFor by remember { mutableStateOf<String?>(null) }
    // N-D: TOKEN/NFT/PAID gates have no owner-minted members — allow() is
    // NONE-only on-chain, so manual add would be a guaranteed revert there.
    var gateMode by remember { mutableStateOf<Int?>(null) }

    // Managing membership is a CONTRACT role (gate owner or moderator), never
    // a stream permission: a moderator holds none, every grant is the clone's.
    var canManageMembers by remember(channel.messageStreamId) { mutableStateOf(false) }

    LaunchedEffect(channel.messageStreamId, reloadKey) {
        members = vm.channelMembers()
        gateMode = vm.currentGateMode()
        canManageMembers = vm.canManageGate()
    }
    val manualAddAllowed = channel.type != "gated" || gateMode == null || gateMode == GateModes.NONE
    val creatorAddr = (channel.createdBy ?: channel.messageStreamId.substringBefore('/')).lowercase()

    // Names for the rows, in the app's order: ENS, contact nickname, the name
    // published with their messages, address. The roster carries no name, so a
    // member who never posted and is not a contact shows their address.
    val contacts by vm.contacts.collectAsState()
    val nicknames = remember(contacts) {
        contacts.mapNotNull { c -> c.nickname?.let { c.address.lowercase() to it } }.toMap()
    }
    val messages by vm.messages.collectAsState()
    val declaredNames = remember(messages) {
        messages.mapNotNull { m ->
            m.senderName?.takeIf { it.isNotBlank() }?.let { m.sender.lowercase() to it }
        }.toMap()
    }

    // ── CURRENT MEMBERS ─────────────────────────────────────────────
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "CURRENT MEMBERS", color = Color.White.copy(alpha = 0.40f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f)
        )
        RefreshButton { members = null; reloadKey++ }
    }
    Spacer(Modifier.height(12.dp))

    when {
        members == null -> Text("Loading...", color = Color.White.copy(alpha = 0.30f), fontSize = 13.sp)
        members!!.isEmpty() -> Text(
            "No members found",
            color = Color.White.copy(alpha = 0.30f), fontSize = 13.sp
        )
        else -> members!!.forEach { row ->
            val addr = row.address
            val lower = addr.lowercase()
            val isCreator = lower == creatorAddr || row.isOwner
            val isMe = myAddress?.lowercase() == lower
            // Moderators are out of a moderator's reach on-chain
            // (_requireModerationTarget), so the kebab does not offer it.
            val canManage = !isMe && canManageMembers && !isCreator &&
                (!row.moderator || myAddress?.lowercase() == creatorAddr)
            val ensName = ensNames[lower]
            LaunchedEffect(lower) { vm.ensureEns(addr) }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ENS avatar when the address has one, else the generated
                // identicon (the web shows a 2-hex text circle; we prefer the
                // real ENS picture, since these addresses often resolve).
                Avatar(
                    addr, size = 32.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = ensAvatars[lower]
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    MemberLabel(addr, ensName, nicknames[lower], declaredNames[lower])
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        if (isCreator) MemberBadge("Owner", Color(0xFFEAB308))
                        else if (row.moderator) MemberBadge("Moderator", Color(0xFFA855F7))
                        // Paid gates: each subscriber's own clock (N-F). Rows
                        // passed the access filter, so the date is normally
                        // in the future.
                        if (row.paidUntil > 0 && !isCreator) {
                            val expired = row.paidUntil * 1000L <= System.currentTimeMillis()
                            Text(
                                (if (expired) "expired " else "until ") + java.text.SimpleDateFormat(
                                    "d MMM yyyy", java.util.Locale.getDefault()
                                ).format(java.util.Date(row.paidUntil * 1000L)),
                                color = if (expired) Color(0xFFF87171).copy(alpha = 0.80f)
                                    else Color.White.copy(alpha = 0.40f),
                                fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        if (isMe) Text("(you)", color = Color.White.copy(alpha = 0.60f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                if (canManage) {
                    Box {
                        Icon(
                            Icons.Filled.MoreVert, contentDescription = "Manage member",
                            tint = Color.White.copy(alpha = 0.30f),
                            modifier = Modifier.size(18.dp).clickableNoRipple { kebabFor = addr }
                        )
                        MemberKebab(
                            expanded = kebabFor == addr,
                            shortAddr = "${addr.take(8)}...${addr.takeLast(6)}",
                            canGrant = row.moderator,
                            // Appointing moderators is the gate owner's alone.
                            isOwner = myAddress?.lowercase() == creatorAddr,
                            // Only Closed gates have an allowlist to remove
                            // from; elsewhere Ban is the only cut.
                            canRemove = gateMode == GateModes.NONE,
                            onDismiss = { kebabFor = null },
                            onToggleGrant = { vm.setMemberGrant(addr, !row.moderator) { reloadKey++ }; kebabFor = null },
                            onBan = { confirmBan = addr; kebabFor = null },
                            onRemove = { confirmRemove = addr; kebabFor = null }
                        )
                    }
                }
            }
        }
    }

    // ── ADD MEMBERS (gate owner / moderator; NONE gates — allow() reverts
    // WrongMode on TOKEN/NFT/PAID, whose members hold or pay() themselves) ──
    if (canManageMembers && channel.type == "gated" && manualAddAllowed) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(16.dp))
        Text(
            "ADD MEMBERS", color = Color.White.copy(alpha = 0.40f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Accept a raw address OR an ENS name (resolved on submit).
            val t = newMember.trim()
            val valid = Regex("^0x[a-fA-F0-9]{40}$").matches(t) || (t.contains('.') && t.length > 3)
            androidx.compose.foundation.text.BasicTextField(
                value = newMember, onValueChange = { newMember = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                modifier = Modifier.weight(1f)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (newMember.isEmpty()) Text("0x… or name.eth", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
                    inner()
                }
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                    .clickableNoRipple {
                        if (valid && !busy) { vm.addMember(newMember.trim()) { reloadKey++ }; newMember = "" }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text("Add", color = if (valid && !busy) Color.White else Color.White.copy(alpha = 0.30f), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        // Batch add — collapsible disclosure, like the web <details>.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickableNoRipple { batchOpen = !batchOpen }
        ) {
            Text(
                if (batchOpen) "▾ Batch add multiple addresses" else "▸ Batch add multiple addresses",
                color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp
            )
        }
        if (batchOpen) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = batchText, onValueChange = { batchText = it },
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp, color = PomboColors.Text,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (batchText.isEmpty()) Text(
                        "Paste addresses (one per line)\n0x...\n0x...",
                        color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    inner()
                }
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                    .clickableNoRipple {
                        if (!busy) {
                            val addrs = batchText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                            vm.addMembers(addrs) { reloadKey++; batchText = "" }
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("Add All Addresses", color = Color.White, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(12.dp))
        // Same bordered amber box as the storage fees warning.
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFFF59E0B).copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Color(0xFFFBBF24).copy(alpha = 0.80f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Adding members requires on-chain transactions and gas fees.",
                color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 12.sp, lineHeight = 17.sp
            )
        }
    }

    // Revoking is destructive and costs gas — always confirm first.
    confirmRemove?.let { addr ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmRemove = null }) {
            Column(
                Modifier.width(320.dp)
                    .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Text("Remove member", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${shortAddress(addr)} comes off the allowlist and stops receiving channel " +
                        "keys. No ban mark, so you can add them back later. One transaction.",
                    color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmRemove = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                    Box(
                        Modifier.weight(1f)
                            .background(PomboColors.Danger.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickableNoRipple {
                                vm.removeMember(addr) { reloadKey++ }
                                confirmRemove = null
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Remove", color = PomboColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }

    confirmBan?.let { addr ->
        BanMemberDialog(
            label = shortAddress(addr),
            gated = channel.type == "gated",
            // Receivers reject an ADMIN_STATE from anyone but the creator, so
            // a moderator can only reach for the protocol level.
            canClientBan = myAddress?.lowercase() == creatorAddr,
            onDismiss = { confirmBan = null },
            onConfirm = { client, protocol ->
                vm.banMemberLevels(addr, client, protocol) { reloadKey++ }
                confirmBan = null
            }
        )
    }
}

/** Web "↻ Refresh" text button (white/60, hover white). */
@Composable
private fun RefreshButton(onClick: () -> Unit) {
    Text(
        "↻ Refresh", color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp,
        modifier = Modifier.clickableNoRipple(onClick)
    )
}

/**
 * How a person is named in the members and banned lists, in the app's own
 * order: ENS name, then the local contact nickname, then the display name they
 * publish with their messages, then the address in full. The address is not
 * shortened here — in a list of members it is the identity, not a decoration,
 * and a truncated one cannot be checked against anything.
 */
@Composable
internal fun MemberLabel(
    address: String,
    ensName: String?,
    nickname: String?,
    declaredName: String?
) {
    val name = ensName ?: nickname ?: declaredName
    if (name != null) {
        Text(name, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1)
    } else {
        Text(
            address,
            color = Color.White.copy(alpha = 0.70f), fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}

/** Role pill — Owner (yellow) / Admin (purple), color/20 fill + color text. */
@Composable
private fun MemberBadge(text: String, color: Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.20f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 1.dp)
    ) { Text(text, color = color, fontSize = 11.sp) }
}

/**
 * Ban with its two enforcement levels, either or both.
 *
 * CLIENT hides the author's messages in every client: free, reversible, and
 * publishable only by the channel creator, since receivers reject an
 * ADMIN_STATE from anyone else. PROTOCOL bans on the gate: no responder
 * hands them keys again and the rotation that follows cuts their reads.
 * That one costs gas, and only gated channels have it.
 */
@Composable
internal fun BanMemberDialog(
    label: String,
    gated: Boolean,
    canClientBan: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (client: Boolean, protocol: Boolean) -> Unit
) {
    var client by remember { mutableStateOf(canClientBan) }
    var protocol by remember { mutableStateOf(gated) }
    val red = PomboColors.Danger

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(Color(0xFF16161B), RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                .padding(20.dp)
        ) {
            Text("Ban $label", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))

            BanLevelRow(
                title = "Client enforcement",
                detail = if (canClientBan)
                    "Hides their messages for everyone. Free and reversible."
                else "Only the channel creator can publish this.",
                checked = client && canClientBan,
                enabled = canClientBan
            ) { client = it }

            Spacer(Modifier.height(10.dp))

            BanLevelRow(
                title = "Protocol enforcement",
                detail = if (gated)
                    "Cuts their access on the gate and rotates the channel key. One transaction."
                else "Only gated channels have a gate to ban on.",
                checked = protocol && gated,
                enabled = gated
            ) { protocol = it }

            Spacer(Modifier.height(18.dp))
            Row {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .clickableNoRipple(onDismiss)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                Spacer(Modifier.width(10.dp))
                val armed = (client && canClientBan) || (protocol && gated)
                Box(
                    Modifier.weight(1f)
                        .background(
                            if (armed) red.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f),
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            1.dp,
                            if (armed) red.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.06f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickableNoRipple {
                            if (armed) onConfirm(client && canClientBan, protocol && gated)
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Ban",
                        color = if (armed) red else Color.White.copy(alpha = 0.25f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun BanLevelRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .clickableNoRipple { if (enabled) onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        androidx.compose.material3.Checkbox(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = PomboColors.Accent,
                uncheckedColor = Color.White.copy(alpha = 0.30f),
                checkmarkColor = Color.White
            ),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                color = Color.White.copy(alpha = if (enabled) 0.85f else 0.35f),
                fontSize = 14.sp
            )
            Text(
                detail,
                color = Color.White.copy(alpha = if (enabled) 0.40f else 0.25f),
                fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Per-member kebab menu: Admin toggle (owner only), Ban and Remove. */
@Composable
private fun MemberKebab(
    expanded: Boolean,
    shortAddr: String,
    canGrant: Boolean,
    isOwner: Boolean,
    canRemove: Boolean,
    onDismiss: () -> Unit,
    onToggleGrant: () -> Unit,
    onBan: () -> Unit,
    onRemove: () -> Unit
) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        // Shape/color/border on the menu's own surface — a rounded background
        // on the content leaves the surface's square corners showing behind.
        shape = RoundedCornerShape(12.dp),
        containerColor = Color(0xFF16161B),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        modifier = Modifier.width(230.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Member", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp)
            Text(shortAddr, color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        com.pombo.android.ui.ContextMenuDivider()
        if (isOwner) {
            val purple = Color(0xFFA855F7)
            Row(
                Modifier.fillMaxWidth().clickableNoRipple(onToggleGrant).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.VpnKey, contentDescription = null, tint = if (canGrant) purple else Color.White.copy(alpha = 0.70f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Moderator", color = if (canGrant) purple else Color.White.copy(alpha = 0.70f), fontSize = 14.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(if (canGrant) "ON" else "OFF", color = if (canGrant) purple else Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
            }
            com.pombo.android.ui.ContextMenuDivider()
        }
        // Remove takes the member off the allowlist without the ban mark, so
        // adding them back later is a plain allow(). Ban is the harder verb.
        if (canRemove) Row(
            Modifier.fillMaxWidth().clickableNoRipple(onRemove).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.PersonRemove, contentDescription = null, tint = Color.White.copy(alpha = 0.50f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Remove from channel", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp)
        }
        Row(
            Modifier.fillMaxWidth().clickableNoRipple(onBan).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Block, contentDescription = null, tint = PomboColors.Danger, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ban", color = PomboColors.Danger, fontSize = 14.sp)
        }
    }
}

/** One Channel Permissions row: who + the active permission icons. */
@Composable
private fun StreamPermissionRow(
    p: com.pombo.android.core.GraphApi.StreamPermission,
    creatorAddr: String,
    ensNames: Map<String, String>
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val gold = Color(0xFFEAB308).copy(alpha = 0.80f)
        val dim = Color.White.copy(alpha = 0.40f)
        if (p.isPublic) {
            Icon(Icons.Outlined.Public, contentDescription = null, tint = gold, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("PUBLIC", color = gold, fontSize = 12.sp)
        } else {
            val ensName = ensNames[p.userAddress.lowercase()]
            if (ensName != null) {
                Text(ensName, color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp, maxLines = 1)
            } else {
                Text(
                    "${p.userAddress.take(8)}...${p.userAddress.takeLast(4)}",
                    color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            if (p.userAddress.lowercase() == creatorAddr) {
                Spacer(Modifier.width(5.dp))
                // Owner marker: a grey crown (the web used a phone glyph).
                CrownIcon(dim, Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        // Presence-only, like the web: an icon shows when the permission is active.
        if (p.canSubscribe) PermIcon(Icons.Outlined.Visibility, dim)
        if (p.canPublish) PermIcon(Icons.Outlined.Edit, dim)
        if (p.canEdit) PermIcon(Icons.Outlined.Settings, dim)
        if (p.canDelete) PermIcon(Icons.Outlined.Delete, dim)
        if (p.canGrant) PermIcon(Icons.Filled.VpnKey, dim)
    }
}

@Composable
private fun PermIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp).padding(start = 3.dp))
}

/** The sub/pub/edit/del/grant legend under the permission list. */
@Composable
private fun PermissionLegend() {
    val dim = Color.White.copy(alpha = 0.20f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    ) {
        listOf(
            Icons.Outlined.Visibility to "sub",
            Icons.Outlined.Edit to "pub",
            Icons.Outlined.Settings to "edit",
            Icons.Outlined.Delete to "del",
            Icons.Filled.VpnKey to "grant"
        ).forEach { (icon, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = dim, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text(label, color = dim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun ChannelStoragePanel(vm: AppViewModel, channel: Channel, canModerate: Boolean) {
    // Ask the SDK which nodes actually hold these streams, rather than trusting
    // the flag we stored when joining.
    var info by remember { mutableStateOf<com.pombo.android.ChannelManager.StorageInfo?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    LaunchedEffect(channel.messageStreamId, reloadKey) { info = vm.channelStorageInfo() }

    var addOpen by remember { mutableStateOf(false) }
    var customProvider by remember { mutableStateOf(false) }
    var customAddress by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var daysText by remember { mutableStateOf("") }
    LaunchedEffect(info?.storageDays) { info?.storageDays?.let { daysText = it.toString() } }

    // Gas warning at the TOP of the storage section, as a bordered amber box
    // (web #channel-storage-gas-warning), admin only. The web has no "Storage:
    // Enabled" status line — it goes straight from here to Retention Period.
    if (canModerate) {
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFFF59E0B).copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Outlined.WarningAmber, contentDescription = null,
                tint = Color(0xFFFBBF24).copy(alpha = 0.80f), modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Storage changes require on-chain transactions and gas fees.",
                color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(20.dp))
    } else if (info != null && !info!!.enabled) {
        // Non-admins only see a note when there is no storage at all.
        Text(
            "Storage is not enabled for this channel. Messages are not persisted.",
            color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))
    }

    // Retention: editable for the admin, read-only for everyone else.
    SectionLabel("Retention Period")
    if (canModerate) {
        // Web: a narrow `w-24` number field, then "days", then a small Save —
        // not a full-width input. Ours used to stretch the field edge-to-edge.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A compact BasicTextField: OutlinedTextField forces a ~56dp min
            // height (Material spec), far taller than the web's px-3 py-1.5
            // input. This box matches the web's small field.
            androidx.compose.foundation.text.BasicTextField(
                value = daysText,
                onValueChange = { v -> daysText = v.filter { c -> c.isDigit() }.take(5) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                modifier = Modifier.width(96.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    if (daysText.isEmpty()) {
                        Text("Days", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
                    }
                    inner()
                }
            )
            Spacer(Modifier.width(10.dp))
            Text("days", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            val days = daysText.toIntOrNull() ?: 0
            val valid = com.pombo.android.ChannelManager.canSaveRetention(
                days, info?.storageDays, info?.retentionInSync != false)
            Box(
                Modifier
                    .background(
                        if (valid) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .clickableNoRipple { if (valid) vm.setStorageDays(days) { reloadKey++ } }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "Save",
                    color = if (valid) Color.White.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.25f),
                    fontSize = 12.sp
                )
            }
        }
    } else {
        Text(
            info?.storageDays?.let { "$it ${if (it == 1) "day" else "days"}" }
                ?: if (info?.enabled == true) "Not set" else "-",
            color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp
        )
    }

    // Outside both retention states on purpose: the editor is hidden for a
    // reader and the figure is hidden for an admin, so a warning inside
    // either never reaches half the people who need it.
    if (info?.retentionInSync == false) {
        Spacer(Modifier.height(8.dp))
        // Same box as the web's #channel-storage-retention-mixed: mt-2,
        // p-2.5, rounded-lg, amber/5 on amber/10.
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFFF59E0B).copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Outlined.WarningAmber, contentDescription = null,
                tint = Color(0xFFFBBF24).copy(alpha = 0.80f),
                // The web nudges the icon down half a step (mt-0.5) so it sits
                // on the first line rather than above it.
                modifier = Modifier.padding(top = 2.dp).size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (canModerate)
                    "Retention is not the same on all of this channel’s streams. Save it again to apply one value to all of them."
                else
                    "Retention is not the same on all of this channel’s streams.",
                color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 12.sp, lineHeight = 17.sp
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    SectionLabel("Storage Provider")
    val nodes = info?.nodes ?: emptyList()
    if (info != null && nodes.isEmpty()) {
        Text("No storage nodes", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
    }
    nodes.forEach { node ->
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp)
                .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (node.address.equals(vm.pomboStorageNode, ignoreCase = true)) "Pombo" else "Custom",
                        color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp
                    )
                    // The two streams disagree — a previous add/remove only half
                    // applied. Removing and re-adding heals it.
                    if (node.partial) {
                        Spacer(Modifier.width(6.dp))
                        Text("partial", color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 10.sp)
                    }
                }
                Text(
                    node.address,
                    color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            if (canModerate) {
                Spacer(Modifier.width(8.dp))
                // Web: a trash-can icon button, white/40 — not a "Remove" label.
                Icon(
                    Icons.Outlined.Delete, contentDescription = "Remove storage node",
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(18.dp).clickableNoRipple { confirmRemove = node.address }
                )
            }
        }
    }

    if (canModerate) {
        Spacer(Modifier.height(6.dp))
        if (!addOpen) {
            // Web #channel-storage-add-toggle-btn is text-white/60, not accent.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickableNoRipple { addOpen = true }
            ) {
                Icon(
                    Icons.Filled.Add, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.60f), modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Add storage node", color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp)
            }
        } else {
            SectionLabel("Provider")
            listOf(false to "Pombo", true to "Custom storage node").forEach { (custom, label) ->
                Row(
                    Modifier.fillMaxWidth().clickableNoRipple { customProvider = custom }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(18.dp)
                            .border(
                                1.5.dp,
                                if (customProvider == custom) PomboColors.Accent else Color.White.copy(alpha = 0.25f),
                                androidx.compose.foundation.shape.CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (customProvider == custom) {
                            Box(
                                Modifier.size(9.dp)
                                    .background(PomboColors.Accent, androidx.compose.foundation.shape.CircleShape)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
            }
            if (customProvider) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = customAddress,
                    onValueChange = { customAddress = it.trim() },
                    placeholder = {
                        Text("0x… node address", color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp)
                    },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp, color = PomboColors.Text,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(10.dp))
            val target = if (customProvider) customAddress else vm.pomboStorageNode
            val canAdd = Regex("^0x[a-fA-F0-9]{40}$").matches(target)
            Row {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .clickableNoRipple { addOpen = false; customAddress = "" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.weight(1f)
                        .background(
                            if (canAdd) PomboColors.Accent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f),
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            1.dp,
                            if (canAdd) PomboColors.Accent.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.06f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickableNoRipple {
                            if (canAdd) {
                                vm.addStorageNode(target) { reloadKey++ }
                                addOpen = false; customAddress = ""
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Add",
                        color = if (canAdd) PomboColors.Accent else Color.White.copy(alpha = 0.25f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    confirmRemove?.let { addr ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmRemove = null }) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF16161B), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Text("Remove storage node", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "${addr.take(6)}…${addr.takeLast(4)} stops serving this channel's history. " +
                        "This is an on-chain transaction and costs gas.",
                    color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp, lineHeight = 20.sp
                )
                Spacer(Modifier.height(18.dp))
                Row {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmRemove = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f)
                            .background(PomboColors.Danger.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickableNoRipple {
                                vm.removeStorageNode(addr) { reloadKey++ }
                                confirmRemove = null
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Remove", color = PomboColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun ChannelModerationPanel(vm: AppViewModel, channel: Channel, canModerate: Boolean) {
    val pins by vm.pins.collectAsState()
    val hidden by vm.hiddenIds.collectAsState()
    val banned by vm.bannedMembers.collectAsState()
    val ensNames by vm.ensNames.collectAsState()
    val ensAvatars by vm.ensAvatars.collectAsState()
    var confirmUnban by remember { mutableStateOf<String?>(null) }
    var chainBanned by remember(channel.messageStreamId) { mutableStateOf<List<String>>(emptyList()) }
    var permissions by remember { mutableStateOf<List<com.pombo.android.core.GraphApi.StreamPermission>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }
    val contacts by vm.contacts.collectAsState()
    val nicknames = remember(contacts) {
        contacts.mapNotNull { c -> c.nickname?.let { c.address.lowercase() to it } }.toMap()
    }
    val messages by vm.messages.collectAsState()
    val declaredNames = remember(messages) {
        messages.mapNotNull { m ->
            m.senderName?.takeIf { it.isNotBlank() }?.let { m.sender.lowercase() to it }
        }.toMap()
    }

    LaunchedEffect(channel.messageStreamId, reloadKey) {
        chainBanned = if (channel.type == "gated") vm.gateBannedMembers() else emptyList()
        permissions = if (canModerate) vm.streamPermissions() else emptyList()
    }

    // Banned members, both enforcement levels in one list. They are separate
    // mechanisms: the client ban hides an author's messages everywhere and
    // costs nothing, while the gate ban cuts key distribution and reads.
    val chainSet = remember(chainBanned) { chainBanned.map { it.lowercase() }.toSet() }
    val allBanned = remember(banned, chainSet) {
        (banned.map { it.lowercase() } + chainSet).distinct().sorted()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "BANNED MEMBERS", color = Color.White.copy(alpha = 0.40f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f)
        )
        Text("${allBanned.size}", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))

    if (allBanned.isEmpty()) {
        Text("No banned members", color = Color.White.copy(alpha = 0.30f), fontSize = 13.sp)
    } else {
        allBanned.forEach { addr ->
            LaunchedEffect(addr) { vm.ensureEns(addr) }
            val onChain = addr in chainSet
            val onClient = banned.any { it.equals(addr, ignoreCase = true) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(addr, size = 28.dp, cornerRadiusFraction = 0.5, ensAvatarUrl = ensAvatars[addr])
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    MemberLabel(addr, ensNames[addr], nicknames[addr], declaredNames[addr])
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        if (onChain) MemberBadge("Protocol", Color(0xFFF87171))
                        if (onChain && onClient) Spacer(Modifier.width(4.dp))
                        if (onClient) MemberBadge("Client", Color(0xFFA855F7))
                    }
                }
                // Unban only makes sense for a moderator; a plain member sees the
                // list read-only (the web hides the whole Moderation tab from
                // non-admins, but if it is reachable the action must be gated).
                if (canModerate) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                            .clickableNoRipple { confirmUnban = addr }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text("Unban", color = Color.White.copy(alpha = 0.80f), fontSize = 12.sp) }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "Client bans hide the author's messages for everyone and cost nothing. " +
            "Protocol bans cut access on the gate, so the member stops receiving " +
            "keys, and take a transaction to apply and to lift.",
        color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
    )

    // Pins/hidden counts kept as a small summary below the banned list — not in
    // the web, but a harmless at-a-glance of the rest of the moderation state.
    Spacer(Modifier.height(20.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            SectionLabel("Pinned")
            Text("${pins.size}", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp)
        }
        Column(Modifier.weight(1f)) {
            SectionLabel("Hidden")
            Text("${hidden.size}", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Pin and hide from a message's own menu.",
        color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
    )

    if (canModerate && channel.type == "gated" && channel.authorMode == "members") {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(20.dp))
        SectionLabel("Reset Publish Key")
        Spacer(Modifier.height(6.dp))
        Text(
            "Replaces the channel's shared publish key (2 transactions). Former members " +
                "who kept the old key lose the ability to write. Current members pick up " +
                "the new key automatically.",
            color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        val amber = Color(0xFFFBBF24)
        Box(
            Modifier
                .fillMaxWidth()
                .background(amber.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .border(1.dp, amber.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                .clickableNoRipple { vm.rekeyPublishKey() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text("Reset Publish Key", color = amber, fontSize = 13.sp) }
    }

    // ── STREAM GRANTEES ──────────────────────────────────────────────
    // The technical view: who holds a grant on the streams themselves. On a
    // gated channel that is the clone and the storage node, never the members,
    // whose access is proven per-message against the contract.
    if (canModerate && channel.type == "gated") {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "STREAM GRANTEES", color = Color.White.copy(alpha = 0.40f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f)
            )
            RefreshButton { reloadKey++ }
        }
        Spacer(Modifier.height(10.dp))
        if (permissions.isEmpty()) {
            Text("Owner only (private)", color = Color.White.copy(alpha = 0.30f), fontSize = 13.sp)
        } else {
            LaunchedEffect(permissions) {
                permissions.forEach { if (!it.isPublic) vm.ensureEns(it.userAddress) }
            }
            val creatorAddr = (channel.createdBy ?: channel.messageStreamId.substringBefore('/')).lowercase()
            permissions.forEach { p -> StreamPermissionRow(p, creatorAddr, ensNames) }
            Spacer(Modifier.height(8.dp))
            PermissionLegend()
        }
    }

    confirmUnban?.let { addr ->
        val onChain = addr.lowercase() in chainSet
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmUnban = null }) {
            Column(
                Modifier.width(320.dp)
                    .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Text("Unban member", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${ensNames[addr.lowercase()] ?: shortAddress(addr)} can take part in this " +
                        "channel again. " + if (onChain)
                            "Lifting the gate ban is one transaction; the client ban, if any, goes with it."
                        else "This clears the client ban for everyone, with no transaction.",
                    color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { confirmUnban = null }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 14.sp) }
                    Box(
                        Modifier.weight(1f)
                            .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                            .clickableNoRipple {
                                vm.unbanMemberLevels(addr, onChain) { reloadKey++ }
                                confirmUnban = null
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Unban", color = PomboColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun ChannelDeletePanel(vm: AppViewModel, channel: Channel, onDismiss: () -> Unit) {
    Text("Leave channel", color = PomboColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Removes it from this device. The streams stay on-chain and you can rejoin with the stream ID.",
        color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
    )
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth()
            .background(PomboColors.Danger.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Danger.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
            .clickableNoRipple { onDismiss(); vm.removeChannel(channel.messageStreamId) }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text("Leave channel", color = PomboColors.Danger, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/**
 * On-chain deletion of the whole channel (web #delete-channel-modal).
 *
 * Behind a typed confirmation rather than a plain button: this destroys the
 * streams for every member, not just this device, and there is no undo.
 */
@Composable
private fun ChannelDestroyPanel(vm: AppViewModel, channel: Channel, onDismiss: () -> Unit) {
    var confirmText by remember { mutableStateOf("") }
    val armed = confirmText.trim().equals(channel.name.trim(), ignoreCase = true)

    Text("Delete channel", color = PomboColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Deletes all three streams on-chain. The channel disappears for every " +
            "member, its history stops being served, and it cannot be recovered " +
            "or rejoined. Three transactions, so it costs gas.",
        color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp, lineHeight = 18.sp
    )
    Spacer(Modifier.height(14.dp))
    Text(
        "Type the channel name to confirm",
        color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = confirmText, onValueChange = { confirmText = it },
        placeholder = { Text(channel.name, color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
        singleLine = true, shape = RoundedCornerShape(12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
        colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth()
            .background(
                PomboColors.Danger.copy(alpha = if (armed) 0.12f else 0.04f),
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                PomboColors.Danger.copy(alpha = if (armed) 0.30f else 0.10f),
                RoundedCornerShape(12.dp)
            )
            .then(
                if (armed) Modifier.clickableNoRipple {
                    onDismiss()
                    vm.deleteChannelOnChain(channel.messageStreamId, channel.name)
                } else Modifier
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Delete channel permanently",
            color = PomboColors.Danger.copy(alpha = if (armed) 1f else 0.35f),
            fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
}


/**
 * A small crown — the owner marker in Stream Permissions (replaces the web's odd
 * phone glyph). Drawn because Material has no crown. Tinted grey like the other
 * permission icons, not gold.
 */
@Composable
private fun CrownIcon(tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val body = androidx.compose.ui.graphics.Path().apply {
            // Base band + three spikes with two valleys (24x24 proportions).
            moveTo(0.14f * w, 0.78f * h)
            lineTo(0.10f * w, 0.34f * h)
            lineTo(0.30f * w, 0.54f * h)   // left valley up
            lineTo(0.50f * w, 0.24f * h)   // centre peak
            lineTo(0.70f * w, 0.54f * h)   // right valley up
            lineTo(0.90f * w, 0.34f * h)
            lineTo(0.86f * w, 0.78f * h)
            close()
        }
        drawPath(body, tint)
    }
}
