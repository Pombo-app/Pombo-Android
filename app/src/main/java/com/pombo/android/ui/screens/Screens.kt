package com.pombo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
// Context-menu icons, one per web menu entry.
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.pombo.android.AppViewModel
import com.pombo.android.data.Channel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.PomboAvatar
import com.pombo.android.ui.theme.PomboColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Composer emoji picker — the web's DEFAULT_EMOJIS (ReactionManager.js:10),
 * all 88, in the same order. We were offering 5 in a single row, which made
 * the picker useless for anything the five did not cover.
 */
internal val COMPOSER_EMOJIS = listOf(
    "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
    "🙂", "🙃", "😉", "😍", "🥰", "😘", "😋", "😛", "😜", "🤪",
    "🤨", "🧐", "🤓", "😎", "🤩", "🥳", "😏", "😔", "😢", "😭",
    "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨",
    "🤗", "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😬", "🙄", "😯",
    "😲", "🥱", "😴", "🤤", "😵", "🤐", "🥴", "🤢", "🤮", "🤧",
    "👍", "👎", "👏", "🙌", "🤝", "🙏", "✌️", "🤞", "🤟", "🤘",
    "👋", "💪", "❤️", "🔥", "⭐", "🎉", "🎊", "💯", "✅", "❌",
    "⚡", "🚀", "💀", "👻", "🤖", "👽", "💩", "🤡"
)

/**
 * Emoji-only detection, mirroring MessageRenderer.isEmojiOnly:
 * null = normal text, "few" = 1-3 emoji, "many" = 4+.
 * Both render without a bubble; "few" is bigger (2rem vs 1.6rem).
 */
private val EMOJI_REGEX =
    Regex("[\\p{So}\\p{Cn}🌀-🗿😀-🙏🚀-🛿☀-➿]")

internal fun emojiOnlyKind(text: String): String? {
    if (text.isBlank()) return null
    val stripped = text.replace(Regex("\\s"), "")
    if (stripped.isEmpty()) return null
    val emojis = EMOJI_REGEX.findAll(stripped).count()
    if (emojis == 0) return null
    // Variation selectors and ZWJ are part of emoji sequences, not content.
    val rest = stripped.replace(EMOJI_REGEX, "").replace(Regex("[️‍]"), "")
    if (rest.isNotEmpty()) return null
    return if (emojis <= 3) "few" else "many"
}

/** Reaction palette — same set and order as the web #reaction-picker. */
internal val REACTION_EMOJIS = listOf(
    "👍", "👎",
    "❤️", "🔥", "💯", "🚀", "🎉", "🍻", "🤩",
    "😂", "😅",
    "👏", "🙏", "🫡",
    "👀", "🤔", "🤨",
    "😱", "🤯",
    "😢", "😭", "✅", "❌",
    "💀", "💩"
)

@Composable
fun PomboApp(vm: AppViewModel) {
    val hasWallet by vm.hasWallet.collectAsState()
    val current by vm.current.collectAsState()
    val mnemonic by vm.newMnemonic.collectAsState()
    val error by vm.lastError.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(PomboColors.Background)
            .systemBarsPadding()
    ) {
        // Web flow: the app is usable as guest; account creation happens in a modal.
        val ytEmbeds by vm.youtubeEmbeds.collectAsState()
        // Collected here so every file bubble recomposes off one subscription
        // to the transfer state, rather than each opening its own.
        val transfers by vm.fileProgress.collectAsState()
        val uploads by vm.uploadStats.collectAsState()
        val savedFileIds by vm.savedFileIds.collectAsState()
        val fileTransfers = remember(transfers, uploads, savedFileIds) {
            object : com.pombo.android.ui.FileTransfers {
                override fun progressFor(fileId: String) = transfers[fileId]
                override fun uploadsFor(fileId: String) = uploads[fileId]
                override fun isSeeding(fileId: String) = vm.isSeedingFile(fileId)
                override fun isSaved(fileId: String) = savedFileIds.contains(fileId)
                override fun onDownload(messageId: String) = vm.downloadFile(messageId)
                override fun onSave(fileId: String, fileName: String) = vm.saveFile(fileId, fileName)
            }
        }
        val storageUploads by vm.storageUploads.collectAsState()
        val storageDownloads by vm.storageDownloads.collectAsState()
        val savedTransferIds by vm.savedTransferIds.collectAsState()
        val storageTransfers = remember(storageUploads, storageDownloads, savedTransferIds) {
            object : com.pombo.android.ui.StorageTransfers {
                override fun uploadFor(transferId: String) = storageUploads[transferId]
                override fun downloadFor(transferId: String) = storageDownloads[transferId]
                override fun completedFor(transferId: String) = vm.storageFileReady(transferId)
                override fun isSaved(transferId: String) = savedTransferIds.contains(transferId)
                override fun onDownload(messageId: String) = vm.downloadStorageFile(messageId)
                override fun onSave(transferId: String, fileName: String) = vm.saveStorageFile(transferId, fileName)
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            com.pombo.android.ui.LocalYouTubeEmbedsEnabled provides ytEmbeds,
            com.pombo.android.ui.LocalFileTransfers provides fileTransfers,
            com.pombo.android.ui.LocalStorageTransfers provides storageTransfers
        ) {
            when {
                mnemonic != null -> MnemonicScreen(mnemonic!!, onDone = vm::dismissMnemonic)
                current != null -> ChatScreen(vm)
                else -> MainShell(vm)
            }
        }

        // Errors surface as toasts, like the web — the banner stays only as a
        // fallback for anything that still sets lastError directly.
        val toasts by vm.toasts.collectAsState()
        LaunchedEffect(error) {
            error?.let {
                vm.toast(it, com.pombo.android.ui.ToastKind.ERROR, 5000L)
                vm.clearError()
            }
        }
        // Invites arriving over DM stack with the toasts, in the same slot and
        // the same 380dp column the web uses.
        val invites by vm.pendingInvites.collectAsState()
        // Toast expiry hides the card but keeps the invite pending — it stays
        // reachable from the bell in the Chats header until answered.
        var expiredInvites by remember { mutableStateOf(setOf<String>()) }
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 29.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            com.pombo.android.ui.ToastHost(toasts = toasts, onDismiss = vm::dismissToast)
            invites.filter { it.inviteId !in expiredInvites }.forEach { invite ->
                androidx.compose.runtime.key(invite.inviteId) {
                    com.pombo.android.ui.InviteToastCard(
                        invite = invite,
                        onAccept = { vm.acceptInvite(invite) },
                        onDismiss = { vm.dismissInvite(invite.inviteId) },
                        onExpire = { expiredInvites = expiredInvites + invite.inviteId }
                    )
                }
            }
        }

        val incoming by vm.incomingInvite.collectAsState()
        incoming?.let { inv ->
            com.pombo.android.ui.IncomingInviteDialog(
                name = inv.name ?: inv.streamId.substringAfter('/'),
                type = inv.type,
                streamId = inv.streamId,
                onDecline = vm::dismissIncomingInvite,
                onAccept = { vm.acceptIncomingInvite(inv) }
            )
        }

        // A `#/channel/…` link into a password channel: the password is the one
        // thing the link cannot carry, so it is asked for here, the same way
        // the Explore tap asks before anything can be decrypted.
        val linkPassword by vm.linkPasswordPrompt.collectAsState()
        linkPassword?.let { target ->
            ChannelPasswordDialog(
                channelName = target.name,
                onDismiss = vm::dismissLinkPasswordPrompt,
                onSubmit = { pwd -> vm.joinChannelFromLink(target, pwd) }
            )
        }

        // Gate entry screen (N-D): a gated channel refused entry — show the
        // on-chain requirement and the pay() action instead of a toast.
        val gateEntry by vm.gateEntry.collectAsState()
        gateEntry?.let { entry -> GateEntryDialog(vm, entry) }

        // Post-join local identity panel: an entry into a channel with no
        // on-chain name (direct link, invite, gate entry) that did not ask
        // for one. Shows over the opening channel, key request included.
        val pendingIdentity by vm.pendingLocalIdentity.collectAsState()
        pendingIdentity?.let { ch ->
            LocalChannelIdentityDialog(
                channel = ch,
                onDismiss = vm::dismissLocalIdentity,
                onSave = { name, cls -> vm.saveLocalIdentity(ch, name, cls) }
            )
        }
    }
}

/** N-D gate entry (web #gate-entry-modal): requirement, standing, actions. */
@Composable
internal fun GateEntryDialog(vm: AppViewModel, entry: AppViewModel.GateEntry) {
    val info = entry.info
    fun fmt(raw: String, dec: Int?): String = try {
        java.math.BigDecimal(raw).movePointLeft(dec ?: 0).stripTrailingZeros().toPlainString()
    } catch (e: Exception) { raw }

    val holds = try {
        when (info.mode) {
            GateModes.TOKEN -> java.math.BigInteger(info.balance) >= java.math.BigInteger(info.minBalance)
            GateModes.NFT -> java.math.BigInteger(info.balance).signum() > 0
            else -> false
        }
    } catch (e: Exception) { false }
    val paidMsLeft = info.paidUntil * 1000L - System.currentTimeMillis()
    val subscribed = info.mode == GateModes.PAID && paidMsLeft > 0
    val days = info.durationSeconds / 86_400.0
    val daysLabel = if (days == days.toLong().toDouble()) days.toLong().toString() else "%.1f".format(days)

    // WPOL-priced gates read POL everywhere the user sees a cost — gatePay
    // auto-wraps, plain POL is literally what they spend. By token ADDRESS:
    // any ERC-20 can call itself "WPOL".
    val paySymbol = if (info.token.lowercase() == com.pombo.android.ChannelManager.WRAPPED_NATIVE)
        "POL" else info.tokenSymbol
    // Access stack — the Explore cards' anatomy (verb / value / qualifier)
    val stack: Triple<String, String, String>? = when (info.mode) {
        GateModes.TOKEN -> Triple("Hold", "${fmt(info.minBalance, info.tokenDecimals)} ${info.tokenSymbol}", "in your wallet")
        GateModes.NFT -> Triple("Hold", "${info.tokenSymbol} NFT", "in your wallet")
        GateModes.PAID -> Triple(
            "Subscribe", "${fmt(info.price, info.tokenDecimals)} $paySymbol",
            "per $daysLabel " + if (daysLabel == "1") "day" else "days"
        )
        else -> null
    }
    // One status line, state-coloured: green = access granted, red = blocked
    val okTone = Color(0xFF34D399).copy(alpha = 0.90f)
    val badTone = Color(0xFFF87171).copy(alpha = 0.80f)
    val dimTone = Color.White.copy(alpha = 0.50f)
    val status: Pair<String, Color>? = when (info.mode) {
        GateModes.TOKEN -> {
            val bal = "Balance: ${fmt(info.balance, info.tokenDecimals)} ${info.tokenSymbol}"
            if (holds) "$bal · access granted" to okTone else bal to badTone
        }
        GateModes.NFT ->
            if (holds) "You hold ${info.balance} · access granted" to okTone
            else "You hold none" to badTone
        GateModes.PAID ->
            if (subscribed) {
                val until = java.text.SimpleDateFormat("dd/MM/yy, HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(info.paidUntil * 1000L))
                "Active until $until · ${com.pombo.android.core.GateFormat.formatRemaining(paidMsLeft)} left" to okTone
            } else if (info.paidUntil > 0) "Subscription expired" to badTone
            else "No active subscription" to dimTone
        else -> null
    }
    val notes = buildList {
        if (entry.renewal && info.mode == GateModes.PAID) add("Renewing extends from the current end.")
    }

    androidx.activity.compose.BackHandler(onBack = vm::dismissGateEntry)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.80f)).clickableNoRipple { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth()
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (entry.renewal) entry.channelName?.let { "Renew $it" } ?: "Renew Subscription"
                else entry.channelName?.let { "Join $it" } ?: "Join Gated Channel",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))
            if (stack != null) {
                val (verb, value, qualifier) = stack
                Text(
                    verb.uppercase(),
                    color = if (info.mode == GateModes.PAID) Color(0xFFF6851B).copy(alpha = 0.70f)
                    else Color.White.copy(alpha = 0.40f),
                    fontSize = 10.sp, letterSpacing = 2.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(qualifier, color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
            } else {
                Text(
                    "This is a closed channel — members are added by the owner. " +
                        "Ask the owner to add your address, then check again.",
                    color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, lineHeight = 20.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            status?.let { (text, tone) ->
                Spacer(Modifier.height(16.dp))
                Text(text, color = tone, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }

            // Author visibility is a privacy promise the user must see before
            // paying or entering (web: gate-entry-authors).
            entry.authorMode?.let { mode ->
                Spacer(Modifier.height(10.dp))
                val members = mode == "members"
                Text(
                    if (members) "Authors visible to members only"
                    else "Every message is signed by its author on the wire",
                    color = if (members) Color.White.copy(alpha = 0.40f)
                    else Color(0xFFFBBF24).copy(alpha = 0.70f),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    notes.joinToString(" "),
                    color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, lineHeight = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))
            val primary: Pair<String, () -> Unit>? = when {
                // Renewal: pay is always the action — the contract extends
                // from the current end, so paying early never loses days
                entry.renewal && info.mode == GateModes.PAID ->
                    (if (subscribed) "Renew — ${fmt(info.price, info.tokenDecimals)} $paySymbol"
                    else "Pay ${fmt(info.price, info.tokenDecimals)} $paySymbol") to { vm.gateEntryPay(); Unit }
                info.mode == GateModes.PAID && !subscribed ->
                    "Pay ${fmt(info.price, info.tokenDecimals)} $paySymbol" to { vm.gateEntryPay(); Unit }
                subscribed || holds -> "Enter Channel" to { vm.gateEntryEnter(); Unit }
                else -> null
            }
            primary?.let { (label, action) ->
                Box(
                    Modifier.fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .clickableNoRipple(action)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) { Text(label, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickableNoRipple(vm::dismissGateEntry)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, fontWeight = FontWeight.Medium) }

                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickableNoRipple { vm.gateEntryRecheck() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Check Again", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(12.dp)
            .fillMaxWidth()
            .background(PomboColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .border(1.dp, PomboColors.Danger, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = PomboColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            "OK",
            color = PomboColors.Accent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(start = 8.dp)
                .clickableNoRipple(onDismiss)
        )
    }
}

/**
 * "Connect Account" modal — exact web spec (index.html #modal-create-wallet):
 * overlay black/80, panel #111113 340dp r16 border white/6%, orange primary card
 * and white import card, discreet "restore" link.
 */
@Composable
fun ConnectAccountDialog(
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Connect Account", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("✕", color = Color.White.copy(alpha = 0.30f), fontSize = 15.sp,
                    modifier = Modifier.clickableNoRipple(onDismiss).padding(4.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .background(PomboColors.Accent, RoundedCornerShape(12.dp))
                    .clickableNoRipple(onCreate)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Create New Account", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Generate a new private key", color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .clickableNoRipple(onImport)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(Color(0xFF09090B), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.AttachFile, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Import Private Key", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("Use existing account", color = Color.Black.copy(alpha = 0.90f), fontSize = 13.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "↻ restore", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp,
                    modifier = Modifier.clickableNoRipple(onRestore).padding(4.dp)
                )
            }
        }
    }
}

/**
 * New-account wizard — port of the web's showNewAccountSetupModal
 * (walletFlows.js): Step 1 picks an avatar from a grid of six freshly
 * generated wallets (the avatar is deterministic from the address, so
 * choosing the picture chooses the account), with a shuffle for six more;
 * Step 2 sets the display name and shows the address and private key with
 * the save-your-key warning. The web's password + confirm steps have no
 * Android counterpart — the key lives in the Keystore, not behind a
 * password — so the wizard ends at step 2.
 */
@Composable
fun CreateAccountWizard(vm: AppViewModel, onDismiss: () -> Unit) {
    var wallets by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var showPk by remember { mutableStateOf(false) }
    var shuffleTick by remember { mutableStateOf(0) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(shuffleTick) {
        wallets = emptyList()
        wallets = runCatching { vm.generateWalletCandidates(6) }.getOrElse {
            vm.toast("Could not generate accounts: ${it.message}", com.pombo.android.ui.ToastKind.ERROR)
            onDismiss()
            return@LaunchedEffect
        }
        selected = 0
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        // The private key is shown on the backup step — no screenshots, no
        // recording, no recents thumbnail for the whole wizard (M-I1).
        com.pombo.android.ui.SecureFlag()
        Box {
        Column(
            Modifier
                .width(360.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
        ) {
            if (step == 0) {
                // ---- Step 1: Choose Your Avatar ----
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Choose Your Avatar", color = Color.White.copy(alpha = 0.90f),
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.Refresh, contentDescription = "Show more avatars",
                        tint = Color.White.copy(alpha = 0.40f),
                        modifier = Modifier.size(22.dp).clickableNoRipple { shuffleTick++ }
                    )
                }
                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Column(Modifier.padding(20.dp)) {
                    if (wallets.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = Color.White.copy(alpha = 0.40f), strokeWidth = 2.dp,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    } else {
                        wallets.chunked(3).forEachIndexed { rowIdx, row ->
                            if (rowIdx > 0) Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEachIndexed { colIdx, (address, _) ->
                                    val index = rowIdx * 3 + colIdx
                                    val active = index == selected
                                    Box(
                                        Modifier.weight(1f).aspectRatio(1f)
                                            .background(
                                                Color.White.copy(alpha = if (active) 0.08f else 0.03f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .border(
                                                2.dp,
                                                Color.White.copy(alpha = if (active) 0.40f else 0.06f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickableNoRipple { selected = index }
                                            .padding(10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Avatar(address, size = 72.dp, cornerRadiusFraction = 0.5)
                                        if (active) {
                                            Box(
                                                Modifier.align(Alignment.TopEnd)
                                                    .size(20.dp)
                                                    .background(Color.White, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Filled.Check, contentDescription = null,
                                                    tint = Color.Black, modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Your avatar is a unique visual identifier tied to your account.",
                        color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WizardButton("Cancel", primary = false, modifier = Modifier.weight(1f), onClick = onDismiss)
                    WizardButton(
                        "Continue", primary = true, enabled = wallets.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { step = 1 }
                }
            } else {
                // ---- Step 2: Set Up Account ----
                val (address, privateKey) = wallets[selected]
                Row(
                    Modifier.fillMaxWidth().padding(20.dp, 16.dp, 20.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(address, size = 38.dp, cornerRadiusFraction = 0.5)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Set Up Account", color = Color.White.copy(alpha = 0.90f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Save your key before you continue", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp)
                ) {
                    Text("DISPLAY NAME", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 18) name = it },
                        placeholder = { Text("Your name (optional)", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                        colors = pomboFieldColors(), singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("ADDRESS", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            address, color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("PRIVATE KEY", color = Color.White.copy(alpha = 0.40f), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (showPk) privateKey else "••••••••••••••••••••••••••••••••••••",
                            color = Color.White.copy(alpha = if (showPk) 0.60f else 0.30f),
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (showPk) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPk) "Hide key" else "Reveal key",
                            tint = Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(18.dp).clickableNoRipple { showPk = !showPk }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .clickableNoRipple {
                                com.pombo.android.ui.SensitiveClipboard.copy(context, privateKey)
                                vm.toast(
                                    "Private key copied — clears from the clipboard in 60s",
                                    com.pombo.android.ui.ToastKind.SUCCESS
                                )
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Color.White.copy(alpha = 0.70f), modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy Private Key", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth()
                            .background(Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber, contentDescription = null,
                            tint = Color(0xFFFBBF24), modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Save your private key!", color = Color(0xFFFBBF24), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Store it safely offline. This is the only way to recover your account.",
                                color = Color(0xFFFBBF24).copy(alpha = 0.60f), fontSize = 12.sp, lineHeight = 16.sp
                            )
                        }
                    }
                }
                androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WizardButton("Back", primary = false, modifier = Modifier.weight(1f)) { step = 0 }
                    WizardButton("Create Account", primary = true, modifier = Modifier.weight(1f)) {
                        vm.createAccountFromSetup(privateKey, address, name)
                        onDismiss()
                    }
                }
            }
        }
        // Toast mirror: "Private key copied" fired from inside this Dialog
        // window landed in the main window's host, underneath it.
        val wizardToasts by vm.toasts.collectAsState()
        com.pombo.android.ui.ToastHost(
            toasts = wizardToasts,
            onDismiss = vm::dismissToast,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
        )
        }
    }
}

/** Web wizard footer buttons: dim bordered secondary, orange primary. */
@Composable
private fun WizardButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(
                when {
                    primary && enabled -> PomboColors.Accent
                    primary -> PomboColors.Accent.copy(alpha = 0.40f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                if (primary) Color.Transparent else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(12.dp)
            )
            .clickableNoRipple { if (enabled) onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (primary) Color.White else Color.White.copy(alpha = 0.70f),
            fontSize = 14.sp, fontWeight = FontWeight.Medium
        )
    }
}

/**
 * "Import Private Key" modal — web walletFlows.js showImportPrivateKeyModal.
 *
 * The web's second step sets a password that encrypts the keystore in
 * localStorage. Android stores the key in EncryptedSharedPreferences, backed by
 * the hardware keystore, so that step has no equivalent here and is omitted.
 */
@Composable
fun ImportKeyDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    // Accepts a 64-hex private key (0x optional) or a recovery phrase.
    val valid = key.trim().let {
        Regex("^(0x)?[a-fA-F0-9]{64}$").matches(it) || it.split(Regex("\\s+")).size >= 12
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(380.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(Color(0xFF1A1F2E), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.VpnKey, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Import Private Key", color = Color.White.copy(alpha = 0.90f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("Restore your account", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
                }
                Text("✕", color = Color.White.copy(alpha = 0.40f), fontSize = 15.sp,
                    modifier = Modifier.clickableNoRipple(onDismiss).padding(4.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Column(Modifier.padding(20.dp)) {
                Text("PRIVATE KEY", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    placeholder = {
                        Text("64 hex characters (with or without 0x)",
                            color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
                    },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Icon(
                            if (reveal) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = "Toggle visibility",
                            tint = Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(20.dp).clickableNoRipple { reveal = !reveal }
                        )
                    },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp, color = PomboColors.Text,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                if (key.isNotBlank() && !valid) {
                    Spacer(Modifier.height(8.dp))
                    Text("Invalid format - must be 64 hex characters",
                        color = Color(0xFFF87171), fontSize = 12.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .clickableNoRipple(onDismiss)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(1f)
                        .background(
                            if (valid) Color.White else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickableNoRipple { if (valid) onImport(key.trim()) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Import",
                        color = if (valid) Color.Black else Color.White.copy(alpha = 0.30f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ==================== Onboarding ====================

@Composable
fun OnboardingScreen(vm: AppViewModel, canCancel: Boolean = false) {
    val busy by vm.busy.collectAsState()
    var importText by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(if (canCancel) "Add account" else "Pombo", color = PomboColors.Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(
            "Decentralized P2P communication",
            color = PomboColors.TextDim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(40.dp))

        if (busy) {
            CircularProgressIndicator(color = PomboColors.Accent)
        } else if (!showImport) {
            PrimaryButton("Create new wallet", onClick = vm::createWallet)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showImport = true },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Import existing wallet", color = PomboColors.Text) }
            if (canCancel) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.cancelAddAccount() },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel", color = PomboColors.TextDim) }
            }
        } else {
            OutlinedTextField(
                value = importText,
                onValueChange = { importText = it },
                label = { Text("Private key or recovery phrase") },
                colors = pomboFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Import", enabled = importText.isNotBlank()) { vm.importWallet(importText) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showImport = false },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back", color = PomboColors.TextDim) }
        }
    }
}

@Composable
fun MnemonicScreen(mnemonic: String, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Save your recovery phrase", color = PomboColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "It's the only way to recover your account. Write it down somewhere safe — it's never sent anywhere.",
            color = PomboColors.TextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(
            mnemonic,
            color = PomboColors.Accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = 28.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(PomboColors.SurfaceHigh, RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
                .padding(20.dp)
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("I've saved it", onClick = onDone)
    }
}

/** Web new-channel modal: Open / Protected / Closed / Gated / Paid, same copy.
 *  The last three share `id = "gated"` — one PomboGate clone per channel; the
 *  tab only picks the MODE (Closed = NONE allowlist, Gated = TOKEN/NFT
 *  holding, Paid = subscription), mirroring the web's handleCreate. */
enum class ChannelKind(val id: String, val label: String, val blurb: String) {
    OPEN(
        "public", "Open",
        "Open channel that anyone can discover and join. Messages are visible to all participants."
    ),
    PROTECTED(
        "password", "Protected",
        "Password protected channel. Only users with the correct password can join and read messages."
    ),
    CLOSED(
        "gated", "Closed",
        "Private channel with on-chain verified membership. Each member is authorized by account address."
    ),
    GATED(
        "gated", "Gated",
        "Access follows an on-chain asset: anyone holding the token or NFT can join, read and write. " +
            "Selling the asset cuts new access."
    ),
    PAID(
        "gated", "Paid",
        "Subscription channel: members pay a fixed price in an ERC-20 token for a period of access. " +
            "Payments go in full, directly to your wallet."
    )
}

/** PomboGate.Mode — ABI order, never reorder. */
object GateModes {
    const val NONE = 0; const val TOKEN = 1; const val NFT = 2; const val PAID = 3
}

/**
 * Quick-pick tokens (Polygon PoS mainnet), mirroring web CONFIG.gate
 * .tokenPresets. POL diverges by context: 0x…1010 is the native coin's
 * system contract — balanceOf mirrors the native balance so balance GATES
 * work, but it has no usable transferFrom, so the PAID side prices in WPOL
 * (gatePay auto-wraps the payer's shortfall from plain POL).
 */
val GATE_TOKEN_PRESETS = listOf(
    Triple("pol", "POL", "0x0000000000000000000000000000000000001010"),
    Triple("usdc", "USDC", "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359"),
    Triple("data", "DATA", "0x3a9a81d576d83ff21f26f325066054540720fc34")
)
val PAY_TOKEN_PRESETS = listOf(
    Triple("pol", "POL", "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270"),
    Triple("usdc", "USDC", "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359"),
    Triple("data", "DATA", "0x3a9a81d576d83ff21f26f325066054540720fc34")
)

val CHANNEL_LANGUAGES = listOf(
    "en" to "English", "pt" to "Português", "es" to "Español", "fr" to "Français",
    "de" to "Deutsch", "it" to "Italiano", "zh" to "中文", "ja" to "日本語",
    "ko" to "한국어", "ru" to "Русский", "ar" to "العربية", "other" to "Other"
)

val CHANNEL_CATEGORIES = listOf(
    "politics" to "Politics", "news" to "News", "tech" to "Tech & AI",
    "crypto" to "Crypto & Web3", "finance" to "Business & Finance", "science" to "Science",
    "gaming" to "Gaming", "entertainment" to "Entertainment", "sports" to "Sports",
    "education" to "Education", "comedy" to "Comedy & Memes", "general" to "General",
    // index.html:727-728 — the web offers these two; omitting them meant an
    // Android user could not classify a channel the web can.
    "nsfw" to "NSFW 18+", "adult" to "Adult 18+"
)

/** Everything the web's create form collects. */
data class NewChannel(
    val name: String,
    val type: String,
    val password: String?,
    val exposure: String,
    val readOnly: Boolean,
    val description: String,
    val language: String,
    val category: String,
    val classification: String,
    val members: List<String>,
    val storageProvider: String,
    val customStorageAddress: String?,
    val storageDays: Int,
    // N-D gate params (type == "gated"); raw units as decimal strings.
    val gateMode: Int = GateModes.NONE,
    val gateToken: String? = null,
    val gateMinBalance: String? = null,
    val gatePrice: String? = null,
    val gateDurationSeconds: Long? = null,
    /** Author visibility ('members' | 'everyone'), IMMUTABLE post-creation. */
    val authorMode: String = "members"
)

/** Quick-pick token chips (N-D): presets + Custom. */
@Composable
private fun TokenPresetRow(
    presets: List<Triple<String, String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (presets.map { it.first to it.second } + ("custom" to "Custom")).forEach { (value, label) ->
            val active = selected == value
            Box(
                Modifier.weight(1f)
                    .background(
                        if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, Color.White.copy(alpha = if (active) 0.10f else 0.05f), RoundedCornerShape(8.dp))
                    .clickableNoRipple { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Create Channel — the web's #new-channel-modal adapted to the phone: the
 * desktop type sidebar becomes a row of tabs, everything else keeps the same
 * fields, copy and conditional logic (visible-only fields, member list for
 * Closed channels, classification).
 */
@Composable
internal fun CreateChannelDialog(vm: AppViewModel, onDismiss: () -> Unit, onCreate: (NewChannel) -> Unit) {
    var kind by remember { mutableStateOf(ChannelKind.OPEN) }
    // Author visibility (gated variants; immutable post-creation): false =
    // Members only (the default), true = Everyone.
    var authorEveryone by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var description by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("en") }
    var category by remember { mutableStateOf("general") }
    var classification by remember { mutableStateOf("personal") }
    var membersText by remember { mutableStateOf("") }
    var readOnly by remember { mutableStateOf(false) }
    var storageProvider by remember { mutableStateOf("streamr") }
    var customStorage by remember { mutableStateOf("") }
    var storageDays by remember { mutableStateOf(180f) }
    var showPassword by remember { mutableStateOf(false) }

    // N-D gate fields (Gated/Paid tabs)
    var gateAsset by remember { mutableStateOf("token") }      // token | nft
    var gatePreset by remember { mutableStateOf("pol") }       // pol | usdc | data | custom
    var gateTokenCustom by remember { mutableStateOf("") }
    var gateMinBalanceText by remember { mutableStateOf("") }
    var paidPreset by remember { mutableStateOf("usdc") }
    var paidTokenCustom by remember { mutableStateOf("") }
    var paidPriceText by remember { mutableStateOf("") }
    var paidDurationDays by remember { mutableStateOf("30") }

    // One line per address, exactly like the web (ChannelModalsUI.js:567).
    // Splitting on any whitespace and silently dropping non-matching entries
    // meant a typo'd address just vanished with no feedback; here the invalid
    // ones are counted and surfaced instead.
    val memberLines = remember(membersText) {
        membersText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    val evmRegex = remember { Regex("^0x[a-fA-F0-9]{40}$") }
    val members = remember(memberLines) { memberLines.filter { evmRegex.matches(it) } }
    val invalidMembers = memberLines.size - members.size

    val customStorageValid = storageProvider != "custom" || evmRegex.matches(customStorage.trim())

    // Estimated on-chain cost, refreshed once when the dialog opens (web:
    // ChannelModalsUI.updateGasEstimates on modal show).
    val gasCosts by vm.gasCosts.collectAsState()
    LaunchedEffect(Unit) { vm.refreshGas() }

    // Validation and the balance pre-flight both run on submit, like the web's
    // handleCreateChannel — the button is never disabled there.
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<Pair<String, com.pombo.android.ui.ToastKind>?>(null) }
    var lowBalance by remember { mutableStateOf<AppViewModel.CostCheck.Low?>(null) }
    var confirmingPassword by remember { mutableStateOf(false) }

    fun buildSpec() = NewChannel(
        name = name.trim(),
        type = kind.id,
        password = password.ifBlank { null },
        // Closed channels are always hidden — the toggle is not even shown.
        exposure = if (visible && kind != ChannelKind.CLOSED) "visible" else "hidden",
        readOnly = readOnly,
        description = description.trim(),
        language = language,
        category = category,
        classification = classification,
        members = members,
        storageProvider = storageProvider,
        customStorageAddress = customStorage.trim().ifBlank { null },
        storageDays = storageDays.toInt(),
        authorMode = if (authorEveryone) "everyone" else "members"
    )

    // Low-balance confirm fires AFTER async gate resolution — remember the
    // resolved spec so "Create anyway" does not rebuild it without the params.
    var pendingSpec by remember { mutableStateOf<NewChannel?>(null) }

    /**
     * Gated/Paid tabs (N-D): resolve preset/custom token, probe it on-chain
     * (an address without a working balanceOf would mint a gate that fails
     * checkAccess for everyone, forever) and convert human amounts with the
     * token's REAL decimals. Returns null after setting `notice`.
     */
    suspend fun resolveGateSpec(): NewChannel? {
        if (kind != ChannelKind.GATED && kind != ChannelKind.PAID) return buildSpec()
        val warn = com.pombo.android.ui.ToastKind.WARNING
        val isPaid = kind == ChannelKind.PAID
        val presets = if (isPaid) PAY_TOKEN_PRESETS else GATE_TOKEN_PRESETS
        val presetKey = if (isPaid) paidPreset else gatePreset
        val custom = (if (isPaid) paidTokenCustom else gateTokenCustom).trim()
        // NFT gates are always a custom collection address (presets are ERC-20)
        val useCustom = presetKey == "custom" || (!isPaid && gateAsset == "nft")
        val token = if (useCustom) custom.lowercase()
            else presets.first { it.first == presetKey }.third
        if (!evmRegex.matches(token)) {
            notice = "Enter a valid token contract address (0x…)" to warn
            return null
        }
        val meta = try {
            vm.gateTokenBalance(token)
            vm.gateTokenMeta(token)
        } catch (e: Exception) {
            notice = "That address does not look like a token contract on Polygon" to warn
            return null
        }
        fun parseUnits(amount: String, dec: Int): java.math.BigInteger? = try {
            java.math.BigDecimal(amount.trim()).movePointRight(dec)
                .toBigIntegerExact().takeIf { it.signum() > 0 }
        } catch (e: Exception) { null }
        return when {
            isPaid -> {
                val dec = meta.second
                if (dec == null) {
                    notice = "The payment token must be an ERC-20 contract" to warn
                    return null
                }
                val price = parseUnits(paidPriceText, dec)
                if (price == null) {
                    notice = "Enter a price greater than zero" to warn
                    return null
                }
                val days = paidDurationDays.trim().toIntOrNull()?.takeIf { it >= 1 }
                if (days == null) {
                    notice = "Enter a subscription period of at least 1 day" to warn
                    return null
                }
                buildSpec().copy(
                    gateMode = GateModes.PAID, gateToken = token,
                    gatePrice = price.toString(), gateDurationSeconds = days.toLong() * 86_400L)
            }
            gateAsset == "nft" -> buildSpec().copy(gateMode = GateModes.NFT, gateToken = token)
            else -> {
                val minBal = parseUnits(gateMinBalanceText, meta.second ?: 0)
                if (minBal == null) {
                    notice = "Enter a minimum balance greater than zero" to warn
                    return null
                }
                buildSpec().copy(
                    gateMode = GateModes.TOKEN, gateToken = token,
                    gateMinBalance = minBal.toString())
            }
        }
    }

    /** Balance pre-flight, then create. */
    fun runPreflight() {
        scope.launch {
            val spec = resolveGateSpec() ?: return@launch
            pendingSpec = spec
            when (val check = vm.checkSpendCost(kind.id)) {
                is AppViewModel.CostCheck.Blocked ->
                    notice = check.message to com.pombo.android.ui.ToastKind.ERROR
                is AppViewModel.CostCheck.Low -> lowBalance = check
                AppViewModel.CostCheck.Ok -> onCreate(spec)
            }
        }
    }

    fun submit() {
        val warn = com.pombo.android.ui.ToastKind.WARNING
        // Same order and copy as the web.
        if (storageProvider == "custom" && !customStorageValid) {
            notice = "Enter a valid EVM address (0x followed by 40 hex characters)." to warn
            return
        }
        if (name.isBlank()) { notice = "Please enter a channel name" to warn; return }
        if (kind == ChannelKind.PROTECTED && password.isBlank()) {
            notice = "Please enter a password" to warn
            return
        }
        // A typo in a channel password is unrecoverable — it is the encryption
        // key, not a credential that can be reset — so re-type it first.
        if (kind == ChannelKind.PROTECTED) { confirmingPassword = true; return }
        runPreflight()
    }

    // NOT an androidx Dialog: a Dialog is its own window, and on a real phone
    // that window is laid out beyond the display bounds, so its footer fell off
    // the bottom of the screen. It also hid the app's toasts and double-padded
    // the status bar. Rendering the panel in the activity's own window makes
    // the insets the same ones the rest of the app uses.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(PomboColors.Background)
            // Swallow taps so nothing behind the panel reacts.
            .clickableNoRipple { }
    ) {
        // safeDrawing = system bars + cutout + IME, in one place. On a phone
        // with gesture navigation and no gesture bar the bottom inset is 0, so
        // the footer ends up flush against the screen edge — top it up to a
        // minimum clearance without double-padding devices that do inset.
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(bottom = (12.dp - bottomInset).coerceAtLeast(0.dp))
        ) {
            // Header — fixed 52dp height with the title pushed to the right,
            // same as Channel Details, so it hugs the status bar instead of
            // sitting in a padded band.
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel",
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(20.dp).clickableNoRipple(onDismiss)
                )
                Text("Create Channel", color = Color.White.copy(alpha = 0.90f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(20.dp)
            ) {
                // Type tabs (web sidebar). Five entries since N-D — a scroll
                // row of chips instead of equal weights, or "Protected" clips.
                SectionLabel("Type")
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChannelKind.entries.forEach { k ->
                        val active = k == kind
                        Box(
                            Modifier
                                .background(
                                    if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.03f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickableNoRipple { kind = k }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                k.label,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.60f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // Type blurb
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Text(kind.blurb, color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp, lineHeight = 20.sp)
                }

                Spacer(Modifier.height(20.dp))
                SectionLabel("Name")
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("Channel name", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )

                if (kind == ChannelKind.PROTECTED) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Password")
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        placeholder = { Text("Channel password", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                        singleLine = true, shape = RoundedCornerShape(12.dp),
                        // Web has a reveal toggle (#channel-password-toggle):
                        // this password can never be recovered, so letting the
                        // user check it before committing it on-chain matters.
                        visualTransformation = if (showPassword)
                            androidx.compose.ui.text.input.VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            Icon(
                                if (showPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = Color.White.copy(alpha = 0.40f),
                                modifier = Modifier.size(18.dp)
                                    .clickableNoRipple { showPassword = !showPassword }
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                        colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Everyone needs this password to read the channel.",
                        color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (kind == ChannelKind.GATED) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Asset Type")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("token" to "Token", "nft" to "NFT").forEach { (value, label) ->
                            val active = gateAsset == value
                            Box(
                                Modifier.weight(1f)
                                    .background(
                                        if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickableNoRipple { gateAsset = value }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label, color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    if (gateAsset == "token") {
                        SectionLabel("Token")
                        TokenPresetRow(GATE_TOKEN_PRESETS, gatePreset) { gatePreset = it }
                        if (gatePreset == "custom") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = gateTokenCustom, onValueChange = { gateTokenCustom = it },
                                placeholder = { Text("0x…", color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp) },
                                singleLine = true, shape = RoundedCornerShape(12.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp, color = PomboColors.Text,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            when (gatePreset) {
                                "pol" -> "Gates on the member's native POL balance."
                                "custom" -> "ERC-20 contract on Polygon."
                                else -> "${GATE_TOKEN_PRESETS.first { it.first == gatePreset }.second} on Polygon."
                            },
                            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Minimum Balance")
                        OutlinedTextField(
                            value = gateMinBalanceText, onValueChange = { gateMinBalanceText = it },
                            placeholder = { Text("e.g. 100", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Balance a member must hold to read and write.",
                            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else {
                        SectionLabel("Collection Contract")
                        OutlinedTextField(
                            value = gateTokenCustom, onValueChange = { gateTokenCustom = it },
                            placeholder = { Text("0x…", color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp) },
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp, color = PomboColors.Text,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "ERC-721 collection on Polygon — holding any token grants access.",
                            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                if (kind == ChannelKind.PAID) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Payment Token")
                    TokenPresetRow(PAY_TOKEN_PRESETS, paidPreset) { paidPreset = it }
                    if (paidPreset == "custom") {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = paidTokenCustom, onValueChange = { paidTokenCustom = it },
                            placeholder = { Text("0x… (e.g. USDC)", color = Color.White.copy(alpha = 0.20f), fontSize = 13.sp) },
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp, color = PomboColors.Text,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        when (paidPreset) {
                            "pol" -> "Priced in Wrapped POL (WPOL) — plain POL is wrapped automatically at payment."
                            "custom" -> "ERC-20 token subscribers pay with, on Polygon."
                            else -> "Subscribers pay in ${PAY_TOKEN_PRESETS.first { it.first == paidPreset }.second}."
                        },
                        color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel("Price")
                            OutlinedTextField(
                                value = paidPriceText, onValueChange = { paidPriceText = it },
                                placeholder = { Text("e.g. 5", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                                singleLine = true, shape = RoundedCornerShape(12.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            SectionLabel("Period (days)")
                            OutlinedTextField(
                                value = paidDurationDays, onValueChange = { paidDurationDays = it },
                                singleLine = true, shape = RoundedCornerShape(12.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (kind == ChannelKind.CLOSED) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Members")
                    OutlinedTextField(
                        value = membersText, onValueChange = { membersText = it },
                        placeholder = {
                            Text("0x… addresses, one per line", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
                        },
                        shape = RoundedCornerShape(12.dp), maxLines = 4,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 13.sp, color = PomboColors.Text,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        buildString {
                            append("${members.size} valid address${if (members.size == 1) "" else "es"} — each is granted access on-chain.")
                            if (invalidMembers > 0) append("  $invalidMembers line${if (invalidMembers == 1) "" else "s"} will be ignored.")
                        },
                        color = if (invalidMembers > 0) PomboColors.Danger.copy(alpha = 0.80f)
                            else Color.White.copy(alpha = 0.30f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                }

                // Author visibility applies to every gated variant (Closed
                // included) and is IMMUTABLE after creation.
                if (kind == ChannelKind.CLOSED || kind == ChannelKind.GATED || kind == ChannelKind.PAID) {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Identity on the wire")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple(false, "Sealed", Icons.Outlined.People),
                            Triple(true, "Visible", Icons.Outlined.Public)
                        ).forEach { (value, label, icon) ->
                            val active = authorEveryone == value
                            val activeText = if (value) Color(0xFFFBBF24).copy(alpha = 0.90f) else Color.White
                            Row(
                                Modifier.weight(1f)
                                    .background(
                                        if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = if (active) 0.10f else 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickableNoRipple { authorEveryone = value }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    icon, contentDescription = null,
                                    tint = if (active) activeText else Color.White.copy(alpha = 0.50f),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    label,
                                    color = if (active) activeText else Color.White.copy(alpha = 0.50f),
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Text(
                        if (authorEveryone)
                            "Storage is protected from pollution. Every message exposes its author's account."
                        else
                            "Full author privacy. Removed members can pollute storage until you reset the key with a paid on-chain action.",
                        color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                Spacer(Modifier.height(16.dp))

                // Closed channels are never discoverable, so the web hides this
                // section outright rather than showing a toggle that cannot be
                // honoured (ChannelModalsUI.js:184-190).
                if (kind != ChannelKind.CLOSED) {
                    ToggleRow(
                        label = "VISIBLE",
                        hint = "Channel appears in Explore",
                        checked = visible,
                        onToggle = { visible = !visible }
                    )

                    if (visible) {
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Description")
                        OutlinedTextField(
                            value = description, onValueChange = { if (it.length <= 200) description = it },
                            placeholder = { Text("What's this channel about?", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                            shape = RoundedCornerShape(12.dp), maxLines = 3,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                            colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Language")
                        ChipPicker(CHANNEL_LANGUAGES, language) { language = it }
                        Spacer(Modifier.height(16.dp))
                        SectionLabel("Category")
                        ChipPicker(CHANNEL_CATEGORIES, category) { category = it }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // Read-only: grants public subscribe WITHOUT publish. Missing
                // this meant every Android-created channel granted public
                // publish, with no way to make a broadcast channel.
                ToggleRow(
                    label = "READ-ONLY",
                    hint = "Only you can post; everyone else can read",
                    checked = readOnly,
                    onToggle = { readOnly = !readOnly }
                )

                // ---- Message storage (web #storage-section) ----
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                Spacer(Modifier.height(16.dp))
                SectionLabel("Storage Provider")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("streamr", "Pombo", Icons.Outlined.Public),
                        Triple("custom", "Custom", Icons.Outlined.Code)
                    ).forEach { (value, label, icon) ->
                        val active = storageProvider == value
                        Row(
                            Modifier.weight(1f)
                                .background(
                                    if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp, Color.White.copy(alpha = if (active) 0.10f else 0.05f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickableNoRipple { storageProvider = value }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon, contentDescription = null,
                                tint = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (storageProvider == "streamr") {
                    // Web #streamr-address-display: a 10px label over a bordered
                    // read-only box holding the FULL address, wrapped.
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "STORAGE CLUSTER", color = Color.White.copy(alpha = 0.30f),
                        fontSize = 10.sp, letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        com.pombo.android.ChannelManager.STORAGE_NODE,
                        color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                } else {
                    // Same box as the Pombo address above (web #custom-storage-
                    // address: `px-3 py-2 text-xs font-mono`). An OutlinedTextField
                    // forces a 56dp minimum, which made this field tower over it.
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "STORAGE CLUSTER", color = Color.White.copy(alpha = 0.30f),
                        fontSize = 10.sp, letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    val badAddress = customStorage.isNotBlank() && !customStorageValid
                    androidx.compose.foundation.text.BasicTextField(
                        value = customStorage, onValueChange = { customStorage = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp, color = PomboColors.Text,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (badAddress) PomboColors.Danger.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.10f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            if (customStorage.isEmpty()) Text(
                                "0x...", color = Color.White.copy(alpha = 0.20f), fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            inner()
                        }
                    )
                    if (customStorage.isNotBlank() && !customStorageValid) {
                        Text(
                            "Enter a valid EVM address (0x followed by 40 hex characters).",
                            color = PomboColors.Danger.copy(alpha = 0.80f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                // Retention slider — web #storage-days-input, range 1..365.
                // Above 30 days the effective value snaps to exact months.
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MESSAGE RETENTION", color = Color.White.copy(alpha = 0.70f),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        retentionLabel(storageDays.toInt()),
                        color = PomboColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
                androidx.compose.material3.Slider(
                    value = storageDays,
                    onValueChange = { storageDays = snapRetentionDays(it.roundToInt()).toFloat() },
                    valueRange = 1f..365f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = PomboColors.Accent,
                        activeTrackColor = PomboColors.Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.10f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                // Web puts scale ticks under the slider, not a hint sentence.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("1 day", "6 months", "1 year").forEach {
                        Text(it, color = Color.White.copy(alpha = 0.60f), fontSize = 10.sp)
                    }
                }

                // Classification: local-only organization, so it closes the page.
                if (kind == ChannelKind.CLOSED || kind == ChannelKind.GATED || kind == ChannelKind.PAID) {
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("Classification")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("personal" to "Personal", "community" to "Community").forEach { (value, label) ->
                            val active = classification == value
                            Box(
                                Modifier.weight(1f)
                                    .background(
                                        if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = if (active) 0.10f else 0.05f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickableNoRipple { classification = value }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Text(
                        "For organizing your channels locally.",
                        color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Spacer(Modifier.height(24.dp))
            }

            // Footer — web: `flex items-center justify-between px-6 py-4
            // border-t border-white/5`, with the estimate on the left. Its
            // Cancel button is `hidden md:block`, i.e. desktop-only: on the
            // phone the back arrow in the header is the way out.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Est. cost:", color = Color.White.copy(alpha = 0.40f), fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        gasCosts?.formattedFor(kind.id) ?: "calculating...",
                        color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
                // Web `bg-[#F6851B]/15 border-[#F6851B]/40 rounded-lg px-5
                // py-2.5` — never disabled, submit() does the validating.
                Box(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                        .clickableNoRipple { submit() }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Create Channel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Validation and pre-flight feedback live INSIDE the dialog window —
        // the app's toast host is in the main window and would be covered.
        DialogToast(notice) { notice = null }
        if (confirmingPassword) ConfirmPasswordDialog(
            expected = password,
            onCancel = { confirmingPassword = false },
            onConfirmed = { confirmingPassword = false; runPreflight() }
        )
        lowBalance?.let { low ->
            ConfirmPopup(
                title = "Low POL balance",
                message = "Your balance (${low.balance}) is close to the estimated cost " +
                    "(${low.estimate}). If gas spikes, the transaction may fail. Continue anyway?",
                confirmLabel = "Create anyway",
                onConfirm = { lowBalance = null; onCreate(pendingSpec ?: buildSpec()) },
                onCancel = { lowBalance = null }
            )
        }
    }
}

/**
 * "Create DM Inbox" modal — web index.html #dm-inbox-setup-modal: one-time
 * setup explanation, storage provider (Pombo cluster / custom node), the
 * 1..365-day retention slider and the estimated cost in the footer. The
 * balance pre-flight itself runs in AppViewModel.setupDmInbox, like the
 * channel dialog's does before creation.
 */
@Composable
fun CreateDmInboxDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onCreate: (storageProvider: String, customStorageAddress: String?, storageDays: Int) -> Unit
) {
    var storageProvider by remember { mutableStateOf("streamr") }
    var customStorage by remember { mutableStateOf("") }
    var storageDays by remember { mutableStateOf(180f) }
    val customStorageValid = Regex("^0x[a-fA-F0-9]{40}$").matches(customStorage.trim())
    val gasCosts by vm.gasCosts.collectAsState()
    LaunchedEffect(Unit) { vm.refreshGas() }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(380.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(36.dp).background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.MailOutline, contentDescription = null,
                        tint = PomboColors.Accent, modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "Create DM Inbox", color = Color.White.copy(alpha = 0.90f),
                    fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
                )
                Text(
                    "✕", color = Color.White.copy(alpha = 0.40f), fontSize = 15.sp,
                    modifier = Modifier.clickableNoRipple(onDismiss).padding(4.dp)
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))

            Column(
                Modifier.weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline, contentDescription = null,
                        tint = PomboColors.Accent, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color.White.copy(alpha = 0.90f), fontWeight = FontWeight.SemiBold)) {
                                    append("One-time setup")
                                }
                                append(" — Creating your DM inbox requires a small gas fee. Once created, there are no additional costs.")
                            },
                            color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, lineHeight = 19.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Anyone with your Pombo/Ethereum address can send you direct messages. " +
                                "You can also message any address that has a DM inbox.",
                            color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, lineHeight = 19.sp
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
                Spacer(Modifier.height(16.dp))

                // ---- Message storage (same controls as the channel dialog) ----
                Text(
                    "STORAGE PROVIDER", color = Color.White.copy(alpha = 0.70f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("streamr", "Pombo", Icons.Outlined.Public),
                        Triple("custom", "Custom", Icons.Outlined.Code)
                    ).forEach { (value, label, icon) ->
                        val active = storageProvider == value
                        Row(
                            Modifier.weight(1f)
                                .background(
                                    if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp, Color.White.copy(alpha = if (active) 0.10f else 0.05f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickableNoRipple { storageProvider = value }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                icon, contentDescription = null,
                                tint = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                                fontSize = 12.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (storageProvider == "streamr") {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "STORAGE NODE", color = Color.White.copy(alpha = 0.30f),
                        fontSize = 10.sp, letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        com.pombo.android.ChannelManager.STORAGE_NODE,
                        color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "STORAGE NODE", color = Color.White.copy(alpha = 0.30f),
                        fontSize = 10.sp, letterSpacing = 0.8.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    val badAddress = customStorage.isNotBlank() && !customStorageValid
                    androidx.compose.foundation.text.BasicTextField(
                        value = customStorage, onValueChange = { customStorage = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 12.sp, color = PomboColors.Text,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .border(
                                1.dp,
                                if (badAddress) PomboColors.Danger.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.10f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            if (customStorage.isEmpty()) Text(
                                "0x...", color = Color.White.copy(alpha = 0.20f), fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            inner()
                        }
                    )
                    if (badAddress) {
                        Text(
                            "Enter a valid EVM address (0x followed by 40 hex characters).",
                            color = PomboColors.Danger.copy(alpha = 0.80f), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MESSAGE RETENTION", color = Color.White.copy(alpha = 0.70f),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        retentionLabel(storageDays.toInt()),
                        color = PomboColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
                androidx.compose.material3.Slider(
                    value = storageDays,
                    onValueChange = { storageDays = snapRetentionDays(it.roundToInt()).toFloat() },
                    valueRange = 1f..365f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = PomboColors.Accent,
                        activeTrackColor = PomboColors.Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.10f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("1 day", "6 months", "1 year").forEach {
                        Text(it, color = Color.White.copy(alpha = 0.60f), fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Footer — estimate left, action right (web bg-[#0a0a0a] strip).
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text("Est. cost:", color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        gasCosts?.formattedFor("dmInbox") ?: "calculating...",
                        color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                        .clickableNoRipple {
                            if (storageProvider == "custom" && !customStorageValid) return@clickableNoRipple
                            onCreate(
                                storageProvider,
                                customStorage.trim().ifEmpty { null },
                                storageDays.toInt()
                            )
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Create Inbox", color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false
                    )
                }
            }
        }
    }
}

/** Web ChannelModalsUI.updateStorageDaysDisplay — days collapse into months. */
private fun retentionLabel(days: Int): String = when {
    days == 1 -> "1 day"
    days < 30 -> "$days days"
    days < 365 -> (days / 30.0).roundToInt().let { if (it == 1) "1 month" else "$it months" }
    else -> "1 year"
}

/**
 * Retention slider stays a proportional 1-365 day range (so dragging to the
 * middle lands near 6 months). Above the 30-day mark the effective value is
 * magnet-snapped to the nearest exact month so the label never shows an
 * ambiguous in-between day count. Mirrors web's utils/retention.js.
 */
private fun snapRetentionDays(days: Int): Int = when {
    days <= 30 -> days
    days >= 365 -> 365
    else -> (days / 30.0).roundToInt() * 30
}

/**
 * Toast rendered inside a Dialog's own window, styled like the app's ToastHost
 * (#16161B panel, 29dp from the top). Self-dismisses after the 6s the web gives
 * its balance errors; tapping closes it sooner.
 */
@Composable
private fun DialogToast(notice: Pair<String, com.pombo.android.ui.ToastKind>?, onDismiss: () -> Unit) {
    if (notice == null) return
    val (message, kind) = notice
    LaunchedEffect(notice) {
        kotlinx.coroutines.delay(6000)
        onDismiss()
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .padding(top = 29.dp)
                .background(Color(0xFF16161B), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .clickableNoRipple(onDismiss)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (kind == com.pombo.android.ui.ToastKind.WARNING) Icons.Outlined.WarningAmber
                else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = kind.color, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(message, color = Color.White.copy(alpha = 0.90f), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

/**
 * Re-type gate for a Protected channel's password. The password IS the
 * encryption key: nobody — not even the owner — can recover the history if it
 * was mistyped, so the only safe moment to catch it is before creation.
 */
@Composable
private fun ConfirmPasswordDialog(expected: String, onCancel: () -> Unit, onConfirmed: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var mismatch by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lock, contentDescription = null,
                    tint = PomboColors.Accent, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text("Confirm password", color = Color.White.copy(alpha = 0.90f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Type the channel password again.",
                color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, lineHeight = 19.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    value = typed,
                    onValueChange = { typed = it; mismatch = false },
                    singleLine = true,
                    visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                    modifier = Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            if (mismatch) PomboColors.Danger.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        if (typed.isEmpty()) Text(
                            "Channel password", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp
                        )
                        inner()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (reveal) "Hide password" else "Show password",
                    tint = Color.White.copy(alpha = 0.40f),
                    modifier = Modifier.size(20.dp).clickableNoRipple { reveal = !reveal }
                )
            }
            if (mismatch) {
                Spacer(Modifier.height(8.dp))
                Text("Passwords do not match", color = PomboColors.Danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .clickableNoRipple(onCancel)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                        .clickableNoRipple { if (typed == expected) onConfirmed() else mismatch = true }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Confirm", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** Web NotificationUI.showConfirmToast — a warning that needs a decision. */
@Composable
private fun ConfirmPopup(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .background(Color(0xFF16161B), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.WarningAmber, contentDescription = null,
                    tint = com.pombo.android.ui.ToastKind.WARNING.color, modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(title, color = Color.White.copy(alpha = 0.90f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .clickableNoRipple(onCancel)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = 0.90f), RoundedCornerShape(8.dp))
                        .clickableNoRipple(onConfirm)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) { Text(confirmLabel, color = Color(0xFF0A0A0A), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/** Label + hint on the left, web-style pill switch on the right. */
@Composable
private fun ToggleRow(label: String, hint: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                label, color = Color.White.copy(alpha = 0.70f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp
            )
            Text(hint, color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
        }
        Box(
            Modifier
                .width(36.dp).height(20.dp)
                .background(
                    if (checked) PomboColors.Accent else Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(10.dp)
                )
                .clickableNoRipple(onToggle)
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .size(16.dp)
                    .background(
                        if (checked) Color.White else Color.White.copy(alpha = 0.30f),
                        CircleShape
                    )
            )
        }
    }
}

/** Horizontal chip picker used for language and category. */
@Composable
private fun ChipPicker(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                label,
                color = if (active) PomboColors.Background else Color.White.copy(alpha = 0.60f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(if (active) Color.White else Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                    .clickableNoRipple { onPick(value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
internal fun JoinChannelDialog(onDismiss: () -> Unit, onJoin: (String, String?, String?, String?) -> Unit) {
    var id by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localName by remember { mutableStateOf("") }
    var classification by remember { mutableStateOf("personal") }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Same frame as AddContactDialog/AboutDialog: a 1px hairline border
        // and matching corner radius — the bare M3 surface is true black on
        // true black and reads as text floating with no box around it.
        modifier = Modifier.border(1.dp, PomboColors.Border, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        containerColor = PomboColors.Surface,
        titleContentColor = PomboColors.Text,
        textContentColor = PomboColors.Text,
        title = { Text("Join a channel") },
        text = {
            Column {
                Text("Paste the channel ID (e.g. 0x…/name-1)", color = PomboColors.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = id, onValueChange = { id = it },
                    label = { Text("Channel ID") }, colors = pomboFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password (if any)") },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = localName, onValueChange = { localName = it },
                    label = { Text("Local name (optional)") }, colors = pomboFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Stored locally only. Channels with a public name keep it.",
                    color = Color.White.copy(alpha = 0.30f), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(12.dp))
                ClassificationChips(classification) { classification = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onJoin(id, password.ifEmpty { null }, localName.trim().ifEmpty { null }, classification) },
                enabled = id.isNotBlank()
            ) {
                Text("Join", color = if (id.isNotBlank()) PomboColors.Accent else PomboColors.TextDim, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = PomboColors.TextDim) } }
    )
}

/** Personal/Community selector shared by the join dialog and the local identity panel. */
@Composable
internal fun ClassificationChips(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("personal" to "Personal", "community" to "Community").forEach { (value, label) ->
            val active = selected == value
            Box(
                Modifier.weight(1f)
                    .background(
                        if (active) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.05f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        Color.White.copy(alpha = if (active) 0.10f else 0.05f),
                        RoundedCornerShape(8.dp)
                    )
                    .clickableNoRipple { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.50f),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Post-join panel for a channel with no on-chain name (web: "Name This
 * Channel" mode of the join-closed modal). Skipping keeps the ID-derived or
 * invite-suggested name; both are editable later in Channel Details.
 */
@Composable
internal fun LocalChannelIdentityDialog(
    channel: Channel,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember(channel.messageStreamId) { mutableStateOf(channel.name) }
    var classification by remember(channel.messageStreamId) {
        mutableStateOf(channel.classification ?: "personal")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, PomboColors.Border, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        containerColor = PomboColors.Surface,
        titleContentColor = PomboColors.Text,
        textContentColor = PomboColors.Text,
        title = { Text("Name This Channel") },
        text = {
            Column {
                Text(
                    "This channel has no public name. Give it one for your devices.",
                    color = PomboColors.TextDim, fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Channel name") }, colors = pomboFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "This name is stored locally only",
                    color = Color.White.copy(alpha = 0.30f), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(12.dp))
                ClassificationChips(classification) { classification = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, classification) }, enabled = name.isNotBlank()) {
                Text("Save", color = if (name.isNotBlank()) PomboColors.Accent else PomboColors.TextDim, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Skip", color = PomboColors.TextDim) } }
    )
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) PomboColors.Background else PomboColors.Text,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .background(if (selected) PomboColors.Accent else PomboColors.SurfaceHigh, RoundedCornerShape(50))
            .border(1.dp, if (selected) PomboColors.Accent else PomboColors.Border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

/**
 * Who is online — a dropdown anchored under the "N Online" count in the chat
 * header (web #online-users-list): #1e1e1e, white/10 border, r-xl, 224dp wide,
 * capped height with scroll, no title. Each row: avatar, ENS name (green check)
 * / nickname / short address, "(you)" for self.
 */
@Composable
internal fun OnlineUsersDropdown(
    expanded: Boolean,
    users: List<com.pombo.android.ChannelManager.OnlineUser>,
    me: String?,
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val ensAvatars by vm.ensAvatars.collectAsState()
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        // Shape/color/border on the menu's own surface — a rounded background
        // on the content leaves the surface's square corners showing behind.
        shape = RoundedCornerShape(12.dp),
        containerColor = Color(0xFF1E1E1E),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        modifier = Modifier.width(224.dp).heightIn(max = 240.dp)
    ) {
        if (users.isEmpty()) {
            Text(
                "No one online", color = Color.White.copy(alpha = 0.30f), fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        users.forEach { u ->
            LaunchedEffect(u.address) { vm.ensureEns(u.address) }
            val ens = vm.ensNameFor(u.address)
            val isMe = u.address.equals(me, ignoreCase = true)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.pombo.android.ui.Avatar(
                    address = u.address, size = 32.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = ensAvatars[u.address.lowercase()]
                )
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (ens != null) {
                        Text("✓", color = Color(0xFF4ADE80), fontSize = 12.sp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        ens ?: u.nickname ?: (u.address.take(6) + "…" + u.address.takeLast(4)),
                        color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp,
                        fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (isMe) {
                        Spacer(Modifier.width(4.dp))
                        Text("(you)", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

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
private fun SectionLabel(text: String) {
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

// ==================== util ====================

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = PomboColors.Accent, contentColor = PomboColors.Background,
            disabledContainerColor = PomboColors.SurfaceHigh, disabledContentColor = PomboColors.TextDim
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/**
 * The Ethereum logo (two stacked triangles), the glyph the web uses for a
 * gated channel's "Verified Membership" access line (HeaderUI.js). Material
 * has no Ethereum icon and Icons.Diamond is a gemstone, not this mark, so it is
 * drawn straight from the web's SVG path (24x24 viewport).
 */
@Composable
internal fun EthereumIcon(tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.minDimension / 24f
        val upper = androidx.compose.ui.graphics.Path().apply {
            moveTo(12f * s, 1.5f * s); lineTo(4f * s, 15f * s)
            lineTo(12f * s, 19.5f * s); lineTo(20f * s, 15f * s); close()
        }
        val lower = androidx.compose.ui.graphics.Path().apply {
            moveTo(12f * s, 19.5f * s); lineTo(4f * s, 15f * s)
            lineTo(12f * s, 24f * s); lineTo(20f * s, 15f * s); close()
        }
        // Slight alpha split gives the logo its two-tone depth, like the web.
        drawPath(upper, tint)
        drawPath(lower, tint.copy(alpha = tint.alpha * 0.85f))
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

internal fun channelTypeLabel(type: String): String = when (type) {
    "public" -> "Public"
    "password" -> "Password protected"
    "gated" -> "Private (members)"
    "dm" -> "Direct message"
    else -> type
}

internal fun addressColor(address: String): Color =
    try { Color(android.graphics.Color.parseColor(PomboAvatar.addressColor(address))) } catch (e: Exception) { PomboColors.Accent }

internal fun shortAddress(address: String): String =
    if (address.length > 10) address.take(6) + "…" + address.takeLast(4) else address

/** True when two epoch millis fall on the same calendar day. */
internal fun sameDay(a: Long, b: Long): Boolean {
    val f = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return f.format(Date(a)) == f.format(Date(b))
}

/**
 * Web renderDateSeparator: Today / Yesterday / "July 11" within the current
 * year / "July 11, 2023" otherwise. We were printing "11 Jul 2026" for every
 * date, which repeated the year needlessly and used a different word order.
 */
internal fun dayLabel(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> "Today"
        sameDay(ts, now - 86_400_000L) -> "Yesterday"
        else -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
            val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val sameYear = cal.get(java.util.Calendar.YEAR) == nowCal.get(java.util.Calendar.YEAR)
            val pattern = if (sameYear) "MMMM d" else "MMMM d, yyyy"
            // Locale.US so the month name matches the web, which pins
            // 'en-US' explicitly rather than following the device locale.
            SimpleDateFormat(pattern, Locale.US).format(Date(ts))
        }
    }
}

internal fun formatTime(ts: Long): String =
    if (ts <= 0) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

@Composable
internal fun pomboFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PomboColors.Text,
    unfocusedTextColor = PomboColors.Text,
    focusedBorderColor = PomboColors.Accent,
    unfocusedBorderColor = PomboColors.Border,
    focusedLabelColor = PomboColors.Accent,
    unfocusedLabelColor = PomboColors.TextDim,
    cursorColor = PomboColors.Accent,
    focusedContainerColor = PomboColors.SurfaceHigh,
    unfocusedContainerColor = PomboColors.SurfaceHigh
)

/**
 * Password prompt shown before opening a Protected channel — the password is
 * needed to decrypt anything, and verification is fail-closed against the
 * channel's stored challenge (web: verifyPasswordChallenge).
 */
@Composable
internal fun ChannelPasswordDialog(
    channelName: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(340.dp)
                .background(Color(0xFF111113), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = PomboColors.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Protected channel", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "$channelName requires a password to read its messages.",
                color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                placeholder = { Text("Channel password", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .clickableNoRipple(onDismiss)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                Box(
                    Modifier.weight(1f)
                        .background(
                            if (password.isNotBlank()) Color.White else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickableNoRipple { if (password.isNotBlank()) onSubmit(password) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Unlock",
                        color = if (password.isNotBlank()) Color.Black else Color.White.copy(alpha = 0.30f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
