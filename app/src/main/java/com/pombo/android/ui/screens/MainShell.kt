package com.pombo.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pombo.android.AppViewModel
import com.pombo.android.NetStatus
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.theme.PomboColors

private enum class Tab(val label: String, val icon: ImageVector) {
    CHATS("Home", Icons.Outlined.Home),
    EXPLORE("Explore", Icons.Outlined.Explore),
    CONTACTS("Contacts", Icons.Outlined.People),
    SETTINGS("Settings", Icons.Outlined.Settings),
    PROFILE("Profile", Icons.Outlined.Person)
}

/**
 * Main app shell (web mobile design): per-tab content area +
 * floating bottom pill nav with a glass effect.
 */
@Composable
fun MainShell(vm: AppViewModel) {
    // A guest has no channels, so discovery is the useful landing tab; a
    // signed-in account lands on its conversations.
    val guestNow by vm.isGuest.collectAsState()
    val hasWalletNow by vm.hasWallet.collectAsState()
    val chatOrigin by vm.chatOrigin.collectAsState()

    // Closing a chat re-creates this whole composable, so the starting tab is
    // derived from where the chat was opened from — that state lives in the
    // ViewModel precisely because everything here is thrown away in between.
    var tab by remember {
        mutableStateOf(
            if (chatOrigin == com.pombo.android.AppViewModel.ChatOrigin.EXPLORE) Tab.EXPLORE else Tab.CHATS
        )
    }
    LaunchedEffect(guestNow, hasWalletNow) {
        // The guest session starts asynchronously, so at first frame neither
        // flag is set yet — wait until the identity kind is actually known
        // before choosing the landing tab, otherwise it always lands on Chats.
        if (!vm.landedOnce && (guestNow || hasWalletNow)) {
            tab = if (guestNow) Tab.EXPLORE else Tab.CHATS
            vm.landedOnce = true
        }
    }
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    val isGuest by vm.isGuest.collectAsState()

    // Settings has many panels: the pill's Settings button opens a dropdown to
    // jump straight to one, and panels are swipeable left/right from there.
    val settingsPager = androidx.compose.foundation.pager.rememberPagerState(
        pageCount = { SettingsPanel.entries.size }
    )
    var settingsMenu by remember { mutableStateOf(false) }
    /** Centre x of the Settings pill item, reported by PillNav. */
    var settingsAnchorX by remember { mutableFloatStateOf(0f) }
    // Explore now offers two destinations (2026-08-21): Threads (today's
    // channel Explore) and Live Streams (not built yet — locked). The pill
    // tap opens a picker instead of navigating straight to Threads, same
    // pattern as Settings' panel picker.
    var exploreMenu by remember { mutableStateOf(false) }
    /** Centre x of the Explore pill item, reported by PillNav. */
    var exploreAnchorX by remember { mutableFloatStateOf(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Back gesture = navigate back, not leave the app. Declared innermost-last,
    // because the LAST enabled handler is the one that runs: the open dropdown
    // wins over the tab, and only on the landing tab does back fall through to
    // the system and actually minimise Pombo.
    val homeTab = if (guestNow) Tab.EXPLORE else Tab.CHATS
    androidx.activity.compose.BackHandler(enabled = tab != homeTab) { tab = homeTab }
    androidx.activity.compose.BackHandler(enabled = settingsMenu) { settingsMenu = false }
    androidx.activity.compose.BackHandler(enabled = exploreMenu) { exploreMenu = false }

    var showImportKey by remember { mutableStateOf(false) }
    // Web walletFlows: "Create New Account" opens the avatar/name wizard, not
    // an instant wallet.
    var showAccountSetup by remember { mutableStateOf(false) }
    // Restore-from-backup, reachable before any account exists (guest mode) —
    // the same picker + password flow Settings uses.
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreUri = uri }
    if (showConnect) ConnectAccountDialog(
        onCreate = { showConnect = false; showAccountSetup = true },
        // Web: import opens its own modal, it does not leave the current view.
        onImport = { showConnect = false; showImportKey = true },
        onRestore = { showConnect = false; restoreLauncher.launch(arrayOf("application/json", "*/*")) },
        onDismiss = { showConnect = false }
    )
    restoreUri?.let { uri ->
        BackupPasswordDialog(
            title = "Restore Account Backup",
            hint = "Enter the password this backup was created with.",
            confirmLabel = "Restore",
            onDismiss = { restoreUri = null }
        ) { pwd, _ ->
            restoreUri = null
            vm.importBackupFrom(uri, pwd)
        }
    }
    if (showAccountSetup) CreateAccountWizard(vm, onDismiss = { showAccountSetup = false })
    if (showImportKey) ImportKeyDialog(
        onDismiss = { showImportKey = false },
        onImport = { key -> showImportKey = false; vm.importWallet(key) }
    )

    Box(Modifier.fillMaxSize()) {
        // Tab content, clearing the floating pill. This must track the pill's
        // own inset + offset: raising the pill to the CSS `bottom: 2rem` above
        // the navigation bar without widening this reserve left it overlapping
        // the "New DM" button.
        Box(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                // Explore is the one tab whose list runs to the bottom edge and
                // scrolls UNDER the floating pill, as on the web; its LazyColumn
                // adds the clearance as contentPadding instead, so the last card
                // can still be scrolled clear of the glass. Reserving it out here
                // for every tab left a dead black band under the pill.
                .padding(bottom = if (tab == Tab.EXPLORE) 0.dp else PILL_NAV_BOTTOM_OFFSET + PILL_NAV_HEIGHT)
        ) {
            when (tab) {
                Tab.CHATS -> ChatsTab(vm, onCreate = { showCreate = true }, onJoin = { showJoin = true }, onConnect = { showConnect = true })
                Tab.EXPLORE -> ExploreTab(vm, onCreate = { showCreate = true }, onConnect = { showConnect = true })
                Tab.CONTACTS -> ContactsTab(vm)
                Tab.SETTINGS -> SettingsTab(vm, settingsPager)
                Tab.PROFILE -> ProfileTab(vm, onAddAccount = { showConnect = true })
            }
        }

        // Tap-anywhere-else closes the settings/explore dropdown. Declared
        // before the pill Column, so it is drawn under both the menu and the
        // pill and catches only the taps that miss them.
        if (settingsMenu || exploreMenu) {
            Box(Modifier.fillMaxSize().clickableNoRipple { settingsMenu = false; exploreMenu = false })
        }

        // Floating pill nav + guest label underneath (web: .pill-guest-label).
        // CSS `#mobile-pill-nav { bottom: 2rem; padding-bottom:
        // env(safe-area-inset-bottom) }` — 32px above the safe area. The old
        // flat 14dp sat too low and ignored the inset entirely, so on
        // gesture-nav devices the pill crowded the system gesture bar.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = PILL_NAV_BOTTOM_OFFSET),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PillMenuAnchor(visible = settingsMenu, anchorX = settingsAnchorX, width = 200.dp) {
                SettingsDropdown(
                    current = SettingsPanel.entries[settingsPager.currentPage],
                    // Guests have no inbox to sync to, so the web hides this.
                    syncRow = if (!isGuest) ({ SyncDevicesRow(vm) }) else null
                ) { panel ->
                    settingsMenu = false
                    tab = Tab.SETTINGS
                    scope.launch { settingsPager.animateScrollToPage(panel.ordinal) }
                }
            }
            PillMenuAnchor(visible = exploreMenu, anchorX = exploreAnchorX, width = 220.dp) {
                ExploreDropdown {
                    exploreMenu = false
                    tab = Tab.EXPLORE
                    vm.setChatOrigin(com.pombo.android.AppViewModel.ChatOrigin.EXPLORE)
                }
            }
            PillNav(
                current = tab,
                address = vm.address.collectAsState().value,
                ensAvatarUrl = vm.ensAvatars.collectAsState().value[vm.address.collectAsState().value?.lowercase()],
                onSettingsAnchor = { settingsAnchorX = it },
                onExploreAnchor = { exploreAnchorX = it },
                onSelect = { picked ->
                    when (picked) {
                        Tab.SETTINGS -> {
                            // Settings only offers its panel list — navigation
                            // happens when a panel is picked, not on the tap itself.
                            exploreMenu = false
                            settingsMenu = !settingsMenu
                        }
                        Tab.EXPLORE -> {
                            // Explore now offers Threads / Live Streams — same
                            // picker-first pattern as Settings (2026-08-21).
                            settingsMenu = false
                            exploreMenu = !exploreMenu
                        }
                        else -> {
                            settingsMenu = false
                            exploreMenu = false
                            tab = picked
                            // Remember where the user is, so a chat opened next
                            // returns to this tab.
                            vm.setChatOrigin(com.pombo.android.AppViewModel.ChatOrigin.CHATS)
                        }
                    }
                }
            )
        }

        // Full-screen panel drawn INSIDE this window (last child = on top), not
        // as an androidx Dialog — see the note in CreateChannelDialog.
        if (showCreate) CreateChannelDialog(vm, onDismiss = { showCreate = false }) { spec ->
            vm.createChannel(spec); showCreate = false
        }
    }

    if (showJoin) JoinChannelDialog(onDismiss = { showJoin = false }) { id, pwd, name, cls ->
        vm.joinChannel(id, pwd, name, cls); showJoin = false
    }
}

/** Caret and corner geometry, shared by a pill menu's fill and its outline. */
private val PILL_MENU_CARET_HALF_WIDTH = 8.dp
private val PILL_MENU_CARET_HEIGHT = 7.dp
private val PILL_MENU_CORNER = 16.dp

/**
 * Silhouette of a pill dropdown: rounded card plus a caret pointing down at
 * [caretCenterX] (px, in the card's own coordinates). The two are united into
 * one path so fill and outline treat them as a single surface — no seam where
 * the caret meets the card. The caret clamps clear of the rounded corners; the
 * card itself may be clamped to the screen edge, so the caret position is what
 * keeps pointing at the pill icon.
 */
private fun Density.pillMenuPath(
    width: Float,
    height: Float,
    caretCenterX: Float
): Path {
    val caretHalfWidth = PILL_MENU_CARET_HALF_WIDTH.toPx()
    val corner = PILL_MENU_CORNER.toPx()
    val bodyBottom = height - PILL_MENU_CARET_HEIGHT.toPx()
    val body = Path().apply {
        addRoundRect(RoundRect(0f, 0f, width, bodyBottom, CornerRadius(corner)))
    }
    val cx = caretCenterX.coerceIn(corner + caretHalfWidth, width - corner - caretHalfWidth)
    val caret = Path().apply {
        // Starts 1px inside the card so the union has real overlap to fuse.
        moveTo(cx - caretHalfWidth, bodyBottom - 1f)
        lineTo(cx, height)
        lineTo(cx + caretHalfWidth, bodyBottom - 1f)
        close()
    }
    return Path.combine(PathOperation.Union, body, caret)
}

private fun Density.pillMenuShape(caretCenterX: Float): GenericShape = GenericShape { size, _ ->
    addPath(pillMenuPath(size.width, size.height, caretCenterX))
}

/**
 * Anchored, animated shell shared by the pill's dropdown menus (web:
 * .pill-settings-dropdown / dropdownOpenUp). Scales up from the caret tip —
 * the menu visibly grows out of the icon that opened it — and the card clamps
 * to the screen edge while the caret keeps following [anchorX].
 */
@Composable
private fun PillMenuAnchor(
    visible: Boolean,
    /** Centre x of the owning pill icon, in root coordinates. */
    anchorX: Float,
    width: Dp,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val screenW = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val menuW = with(density) { width.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val menuX = (anchorX - menuW / 2f).coerceIn(margin, (screenW - menuW - margin).coerceAtLeast(margin))
    val origin = TransformOrigin(if (screenW > 0f) anchorX / screenW else 0.5f, 1f)
    val rise = with(density) { 4.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) +
            scaleIn(tween(160), initialScale = 0.95f, transformOrigin = origin) +
            slideInVertically(tween(160)) { rise },
        exit = fadeOut(tween(120)) +
            scaleOut(tween(120), targetScale = 0.95f, transformOrigin = origin) +
            slideOutVertically(tween(120)) { rise }
    ) {
        val caretCenterX = anchorX - menuX
        val shape = remember(caretCenterX, density) { with(density) { pillMenuShape(caretCenterX) } }
        Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Box(
                Modifier
                    .offset { IntOffset(menuX.toInt(), 0) }
                    .width(width)
                    .shadow(16.dp, shape, clip = false)
                    // Opaque, unlike the web's 0.97 + blur(20px): Compose has
                    // no backdrop blur, and on a near-black ground even 1.5%
                    // transmission leaves bright text behind readable through
                    // the card.
                    .background(Color(0xFF16161B), shape)
                    // Stroked by hand rather than with Modifier.border: given a
                    // generic path, border renders the outline at a fraction of
                    // the requested alpha (measured 2/255 of lift for 0.10
                    // white), which erased the hairline the web draws.
                    .drawBehind {
                        drawPath(
                            pillMenuPath(size.width, size.height, caretCenterX),
                            color = Color.White.copy(alpha = 0.10f),
                            style = Stroke(1.dp.toPx())
                        )
                    }
                    // Keep content out of the shape's caret band.
                    .padding(bottom = PILL_MENU_CARET_HEIGHT)
                    .padding(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun PillNav(
    current: Tab,
    address: String?,
    /** ENS picture for my own address, when the account has one. */
    ensAvatarUrl: String? = null,
    onSelect: (Tab) -> Unit,
    /** Centre x of the Settings item, in root coordinates. */
    onSettingsAnchor: (Float) -> Unit = {},
    /** Centre x of the Explore item, in root coordinates. */
    onExploreAnchor: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // .pill-nav-inner: rgba(255,255,255,0.06) fill, 0.08 border, radius 2rem,
    // and crucially `backdrop-filter: blur(20px)`. Compose cannot blur an
    // in-layout surface, and over a true-black background a bare 6% white fill
    // is nearly invisible — the pill read as a faint outline instead of a
    // frosted slab. The denser base stands in for the blur.
    val pillShape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .background(Color(0xFF0E0E10).copy(alpha = 0.88f), pillShape)
            .background(Color.White.copy(alpha = 0.06f), pillShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), pillShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Tab.entries.forEach { t ->
            if (t == Tab.PROFILE) {
                val active = current == t
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(38.dp)
                        .background(Color.White.copy(alpha = if (active) 0.14f else 0.08f), CircleShape)
                        .border(1.5.dp, if (active) PomboColors.Accent else Color.White.copy(alpha = 0.12f), CircleShape)
                        .clickableNoRipple { onSelect(t) },
                    contentAlignment = Alignment.Center
                ) {
                    val addr = address
        if (addr != null) Avatar(
                        address, size = 34.dp, cornerRadiusFraction = 0.5,
                        ensAvatarUrl = ensAvatarUrl
                    )
                    else Icon(t.icon, contentDescription = t.label, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                }
            } else {
                PillItem(
                    t.label, t.icon, active = current == t,
                    // The settings/explore dropdowns centre on THIS icon, so
                    // its centre has to travel out of the pill's own layout.
                    modifier = when (t) {
                        Tab.SETTINGS -> Modifier.onGloballyPositioned { onSettingsAnchor(it.boundsInRoot().center.x) }
                        Tab.EXPLORE -> Modifier.onGloballyPositioned { onExploreAnchor(it.boundsInRoot().center.x) }
                        else -> Modifier
                    }
                ) { onSelect(t) }
            }
        }
    }
}

@Composable
private fun PillItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clickableNoRipple(onClick)
            .background(if (active) PomboColors.Accent.copy(alpha = 0.22f) else Color.Transparent, RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Selection is carried by the orange trough alone: icon and label keep
        // the idle grey in both states, so the active tab reads as highlighted
        // rather than recoloured.
        Icon(
            icon, contentDescription = label,
            tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(24.dp)
        )
        Text(
            label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun PomboHeader(status: NetStatus, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .background(PomboColors.Background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = com.pombo.android.R.drawable.pombo_logo),
            contentDescription = "Pombo",
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("Pombo", color = PomboColors.Text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/**
 * Reached from a plain "About" button on Profile (2026-08-21), not an inline
 * text block — the disclaimer is a legal notice, copied verbatim from the
 * web's #settings-panel-about rather than paraphrased.
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        // Same frame as AddContactDialog: a 1px hairline border and matching
        // corner radius — the bare M3 surface is true black on true black and
        // reads as text floating with no box around it at all.
        modifier = Modifier.border(1.dp, PomboColors.Border, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        containerColor = PomboColors.Surface,
        titleContentColor = PomboColors.Text,
        textContentColor = PomboColors.Text,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info, contentDescription = null,
                    tint = PomboColors.Text, modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Disclaimer", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        },
        text = {
            Column(Modifier.padding(top = 4.dp)) {
                Text(
                    "Pombo provides access to decentralized communication protocols. " +
                        "You assume full legal responsibility for your actions within your " +
                        "jurisdiction. We disclaim all liability and reserve the right to " +
                        "restrict access to specific channels via this interface.",
                    color = PomboColors.Text, fontSize = 14.sp, lineHeight = 22.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Justify
                )
                Spacer(Modifier.height(16.dp))
                Text("pombo.cc", color = PomboColors.TextDim, fontSize = 13.sp)
            }
        },
        // Web parity + AddContactDialog's own "Cancel": a neutral dismiss, not
        // an accent call-to-action — About has nothing to confirm.
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Close", color = PomboColors.TextDim)
            }
        }
    )
}

/**
 * Explore's picker (2026-08-21): same anatomy as [SettingsDropdown] — opens
 * upward from the Explore pill item. Live Streams is a new channel type that
 * does not exist yet, so it renders locked with a "Soon" badge and does
 * nothing on tap, same behaviour the user asked for.
 */
@Composable
private fun ExploreDropdown(onPickThreads: () -> Unit) {
    // Card chrome (shape, fill, caret, animation) lives in PillMenuAnchor.
    Column {
        Row(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .clickableNoRipple(onPickThreads)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.ChatBubbleOutline, contentDescription = null,
                tint = Color.White.copy(alpha = 0.90f), modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Threads", color = Color.White.copy(alpha = 0.90f), fontSize = 13.sp)
        }
        Row(
            Modifier.fillMaxWidth()
                .padding(top = 2.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PlayArrow, contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Live Streams", color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                "SOON",
                color = Color.White.copy(alpha = 0.40f), fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.03.em,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

/** Web .pill-settings-dropdown — opens upward from the Settings pill item. */
@Composable
private fun SettingsDropdown(
    current: SettingsPanel,
    syncRow: (@Composable () -> Unit)? = null,
    onPick: (SettingsPanel) -> Unit
) {
    // Card chrome (shape, fill, caret, animation) lives in PillMenuAnchor.
    Column {
        SettingsPanel.entries.forEachIndexed { i, panel ->
            // Web: `<hr class="border-white/[0.06] my-0.5">` between sections.
            val previous = SettingsPanel.entries.getOrNull(i - 1)
            if (previous != null && previous.section != panel.section) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                        .background(Color.White.copy(alpha = 0.06f))
                )
            }
            val active = panel == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .clickableNoRipple { onPick(panel) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    panel.icon, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    panel.label,
                    color = Color.White.copy(alpha = if (active) 0.90f else 0.60f),
                    fontSize = 13.sp
                )
            }
        }
        syncRow?.let {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                .background(Color.White.copy(alpha = 0.06f)))
            it()
        }
    }
}

@Composable
private fun ProfileTab(vm: AppViewModel, onAddAccount: () -> Unit) {
    val ensAvatars by vm.ensAvatars.collectAsState()
    val address by vm.address.collectAsState()
    val username by vm.username.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val isGuest by vm.isGuest.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Display precedence everywhere: ENS name → username → address.
        val ensNames by vm.ensNames.collectAsState()
        LaunchedEffect(address) { vm.ensureEns(address) }
        val myEns = address?.let { ensNames[it.lowercase()] }

        Spacer(Modifier.height(24.dp))
        val addr = address
        if (addr != null) Avatar(
            addr, size = 88.dp, cornerRadiusFraction = 0.3,
            ensAvatarUrl = ensAvatars[addr.lowercase()]
        )
        Spacer(Modifier.height(16.dp))
        // Same precedence as everywhere else (ENS → username → short address).
        // "No name" was a dead end for an account without one — a guest, above
        // all, which now has no username at all.
        Text(
            myEns ?: username ?: addr?.let { shortAddr(it) } ?: "No name",
            color = PomboColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        if (myEns != null && username != null) {
            Text(username!!, color = PomboColors.TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
        }
        Text(address ?: "—", color = PomboColors.TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(28.dp))

        // Accounts (multi-wallet switching). Also shown to a guest with a single
        // stored account — otherwise "Browse as guest" would be a one-way door.
        if (accounts.size > 1 || (isGuest && accounts.isNotEmpty())) {
            Text("ACCOUNTS", color = Color.White.copy(alpha = 0.30f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.15.em, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            // Switching identity re-authenticates (M-I2 — the web asks for the
            // keystore password on every unlock/switch; the device credential
            // is this app's equivalent boundary). A device with no lock has
            // nothing to verify against, so it switches directly — same rule
            // DeviceAuth applies everywhere else.
            val switchActivity = androidx.compose.ui.platform.LocalContext.current
                as? androidx.fragment.app.FragmentActivity
            Column(
                Modifier.fillMaxWidth().background(PomboColors.Surface, RoundedCornerShape(12.dp))
                    .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
            ) {
                accounts.forEachIndexed { i, acc ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(color = PomboColors.Border)
                    val isCurrent = acc.address.equals(address, ignoreCase = true)
                    Row(
                        Modifier.fillMaxWidth().clickableNoRipple {
                            if (!isCurrent) {
                                val act = switchActivity
                                if (act != null && com.pombo.android.ui.DeviceAuth.canAuthenticate(act)) {
                                    com.pombo.android.ui.DeviceAuth.authenticate(
                                        act, "Switch account",
                                        "Confirm it is you before switching identity"
                                    ) { ok -> if (ok) vm.switchWallet(acc.address) }
                                } else {
                                    vm.switchWallet(acc.address)
                                }
                            }
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LaunchedEffect(acc.address) { vm.ensureEns(acc.address) }
                        Avatar(
                            acc.address, size = 34.dp, cornerRadiusFraction = 0.5,
                            ensAvatarUrl = ensAvatars[acc.address.lowercase()]
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            ensNames[acc.address.lowercase()]
                                ?: vm.usernameFor(acc.address)
                                ?: shortAddr(acc.address),
                            color = PomboColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        if (isCurrent) Text("current", color = PomboColors.Success, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(
            Modifier.fillMaxWidth().background(PomboColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
        ) {
            ProfileAction("Add account", onClick = onAddAccount)
            androidx.compose.material3.HorizontalDivider(color = PomboColors.Border)
            // Leaves the account behind rather than erasing it: `disconnect()`
            // calls WalletStore.clear(), which drops the current account's key
            // for good. Destroying an account belongs in Settings → Security,
            // behind its device-auth gate and typed confirmation — not one tap
            // away in the profile menu.
            ProfileAction("Browse as guest") { vm.browseAsGuest() }
        }
        // Moved out of Settings (2026-08-21 user call): About is app-level, not
        // account-scoped, and this screen is what "the avatar" means on Android —
        // Settings keeps it on the web only because a guest's route there is
        // cheap and reworking the web dropdown for this was not worth it. A
        // button behind its own dialog, not an inline text block on the main
        // screen — the disclaimer is long enough to want its own space.
        Spacer(Modifier.height(20.dp))
        var showAbout by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().background(PomboColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
        ) {
            ProfileAction("About") { showAbout = true }
        }
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun ProfileAction(label: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label + if (!enabled) "  (coming soon)" else "",
        color = when {
            danger -> PomboColors.Danger
            !enabled -> PomboColors.TextDim
            else -> PomboColors.Text
        },
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clickableNoRipple { if (enabled) onClick() }.padding(16.dp)
    )
}

/**
 * Measured height of the floating pill: 38dp profile avatar + 4dp wrapper
 * padding top and bottom + 6dp inner vertical padding. Named so the content
 * reserve and the pill's own offset cannot drift apart — when they did, the
 * pill sat on top of the "New DM" button.
 */
internal val PILL_NAV_HEIGHT = 58.dp

/**
 * The pill's clearance from the (nav-bar-padded) bottom edge. Was 2rem,
 * matching web's `#mobile-pill-nav { bottom: 2rem }`; brought down on both
 * platforms per user call (2026-08-21) — still clear of the edge, just less
 * floaty. Every "clear the pill" padding below derives from this so they
 * cannot drift out of sync with the pill's actual position.
 */
internal val PILL_NAV_BOTTOM_OFFSET = 20.dp

internal fun shortAddr(a: String) = if (a.length > 10) a.take(6) + "…" + a.takeLast(4) else a

@Composable
internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick
)
