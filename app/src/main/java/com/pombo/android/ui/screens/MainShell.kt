package com.pombo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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
    var walletOpen by remember { mutableStateOf(false) }
    val isGuest by vm.isGuest.collectAsState()

    /** The open Settings section; null = the section list. */
    var settingsPanel by remember { mutableStateOf<SettingsPanel?>(null) }

    // Back gesture = navigate back, not leave the app. Declared innermost-last,
    // because the LAST enabled handler is the one that runs: an open settings
    // section wins over the tab, and only on the landing tab does back fall
    // through to the system and actually minimise Pombo.
    val homeTab = if (guestNow) Tab.EXPLORE else Tab.CHATS
    androidx.activity.compose.BackHandler(enabled = tab != homeTab) { tab = homeTab }
    androidx.activity.compose.BackHandler(enabled = tab == Tab.SETTINGS && settingsPanel != null) {
        settingsPanel = null
    }

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
                Tab.CHATS -> ChatsTab(
                    vm,
                    onContacts = { tab = Tab.CONTACTS },
                    onConnect = { showConnect = true },
                    onExplore = { tab = Tab.EXPLORE }
                )
                Tab.EXPLORE -> ExploreTab(
                    vm, onCreate = { showCreate = true }, onJoin = { showJoin = true },
                    onConnect = { showConnect = true }
                )
                Tab.CONTACTS -> ContactsTab(vm)
                Tab.SETTINGS -> SettingsTab(
                    vm, settingsPanel,
                    onOpen = { settingsPanel = it },
                    onBack = { settingsPanel = null }
                )
                Tab.PROFILE -> ProfileTab(vm, onAddAccount = { showConnect = true })
            }
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
            PillNav(
                current = tab,
                address = vm.address.collectAsState().value,
                ensAvatarUrl = vm.ensAvatars.collectAsState().value[vm.address.collectAsState().value?.lowercase()],
                showWallet = !isGuest,
                onWallet = { walletOpen = true },
                onSelect = { picked ->
                    tab = picked
                    // Settings always opens on its section list.
                    if (picked == Tab.SETTINGS) settingsPanel = null
                    // Remember where the user is, so a chat opened next
                    // returns to this tab.
                    vm.setChatOrigin(
                        if (picked == Tab.EXPLORE) com.pombo.android.AppViewModel.ChatOrigin.EXPLORE
                        else com.pombo.android.AppViewModel.ChatOrigin.CHATS
                    )
                }
            )
        }

        // Full-screen panel drawn INSIDE this window (last child = on top), not
        // as an androidx Dialog — see the note in CreateChannelDialog.
        if (showCreate) CreateChannelDialog(vm, onDismiss = { showCreate = false }) { spec ->
            vm.createChannel(spec); showCreate = false
        }
    }

    if (walletOpen) com.pombo.android.ui.WalletSheet(vm) { walletOpen = false }

    if (showJoin) JoinChannelDialog(onDismiss = { showJoin = false }) { id, pwd, name, cls ->
        vm.joinChannel(id, pwd, name, cls); showJoin = false
    }
}

@Composable
private fun PillNav(
    current: Tab,
    address: String?,
    /** ENS picture for my own address, when the account has one. */
    ensAvatarUrl: String? = null,
    /** A guest is a throwaway identity, not an account with funds. */
    showWallet: Boolean,
    onWallet: () -> Unit,
    onSelect: (Tab) -> Unit,
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
            .background(PomboColors.Surface4.copy(alpha = 0.88f), pillShape)
            .background(PomboColors.Overlay6, pillShape)
            .border(1.dp, PomboColors.Overlay8, pillShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(Tab.CHATS, Tab.EXPLORE, null, Tab.SETTINGS, Tab.PROFILE).forEach { t ->
            if (t == null) {
                // Wallet opens a sheet over the current view and is never the active tab.
                if (showWallet) PillItem("Wallet", Icons.Outlined.AccountBalanceWallet, active = false, onClick = onWallet)
            } else if (t == Tab.PROFILE) {
                val active = current == t
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(38.dp)
                        .background(Color.White.copy(alpha = if (active) 0.14f else 0.08f), CircleShape)
                        .border(1.5.dp, if (active) PomboColors.Accent else PomboColors.Overlay12, CircleShape)
                        .clickableNoRipple { onSelect(t) },
                    contentAlignment = Alignment.Center
                ) {
                    val addr = address
        if (addr != null) Avatar(
                        address, size = 34.dp, cornerRadiusFraction = 0.5,
                        ensAvatarUrl = ensAvatarUrl
                    )
                    else Icon(t.icon, contentDescription = t.label, tint = PomboColors.Overlay50, modifier = Modifier.size(20.dp))
                }
            } else {
                PillItem(t.label, t.icon, active = current == t) { onSelect(t) }
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
            tint = PomboColors.Overlay80,
            modifier = Modifier.size(24.dp)
        )
        Text(
            label,
            color = PomboColors.Overlay80,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun PomboHeader(status: NetStatus, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .background(PomboColors.Bg)
            // Less below than above: whatever sits under the header brings its
            // own top padding, and the two together read as a hole.
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.pombo.android.R.drawable.pombo_logo),
                contentDescription = "Pombo",
                modifier = Modifier.size(40.dp)
            )
            if (status == NetStatus.CONNECTING || status == NetStatus.ERROR) {
                NetworkDot(Modifier.align(Alignment.BottomEnd).offset(x = 1.dp, y = 1.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

@Composable
private fun NetworkDot(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(
        modifier.size(11.dp).semantics { contentDescription = "Connecting" }
    ) {
        val outer = size.minDimension / 2f
        drawCircle(PomboColors.Bg, radius = outer)
        val inner = outer - 1.5.dp.toPx()
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                0f to Color(0xFFFCA5A5),
                0.45f to PomboColors.Danger,
                1f to Color(0xFF991B1B),
                center = androidx.compose.ui.geometry.Offset(center.x - inner * 0.3f, center.y - inner * 0.4f),
                radius = inner * 1.4f
            ),
            radius = inner
        )
    }
}

/** A guest's header action, sized like the icon buttons it stands in for. */
@Composable
internal fun CreateAccountButton(onClick: () -> Unit) {
    Row(
        Modifier
            .height(32.dp)
            .background(PomboColors.Accent, RoundedCornerShape(12.dp))
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Person, contentDescription = null,
            tint = Color.White, modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text("Create Account", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Reached from a plain "About" button on Profile (2026-08-21), not an inline
 * text block — the disclaimer is a legal notice, copied verbatim from the
 * web's #settings-panel-about rather than paraphrased.
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    PomboDialogFrame("Disclaimer", onDismiss) {
        // Legal notice, verbatim from web #settings-panel-about — never paraphrase.
        Text(
            "Pombo provides access to decentralized communication protocols. " +
                "You assume full legal responsibility for your actions within your " +
                "jurisdiction. We disclaim all liability and reserve the right to " +
                "restrict access to specific channels via this interface.",
            color = PomboColors.TextPrimary, fontSize = 14.sp, lineHeight = 22.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Justify
        )
        Spacer(Modifier.height(16.dp))
        Text("pombo.cc", color = PomboColors.TextSecondary, fontSize = 13.sp)
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
            color = PomboColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        if (myEns != null && username != null) {
            Text(username!!, color = PomboColors.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
        }
        Text(address ?: "—", color = PomboColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(28.dp))

        // Accounts (multi-wallet switching). Also shown to a guest with a single
        // stored account — otherwise "Browse as guest" would be a one-way door.
        if (accounts.size > 1 || (isGuest && accounts.isNotEmpty())) {
            Text("ACCOUNTS", color = PomboColors.Overlay30, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.15.em, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            // Switching identity re-authenticates (M-I2 — the web asks for the
            // keystore password on every unlock/switch; the device credential
            // is this app's equivalent boundary). A device with no lock has
            // nothing to verify against, so it switches directly — same rule
            // DeviceAuth applies everywhere else.
            val switchActivity = androidx.compose.ui.platform.LocalContext.current
                as? androidx.fragment.app.FragmentActivity
            Column(
                Modifier.fillMaxWidth().background(PomboColors.Bg, RoundedCornerShape(12.dp))
                    .border(1.dp, PomboColors.Overlay8, RoundedCornerShape(12.dp))
            ) {
                accounts.forEachIndexed { i, acc ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(color = PomboColors.Overlay8)
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
                            color = PomboColors.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f)
                        )
                        if (isCurrent) Text("current", color = PomboColors.Success, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(
            Modifier.fillMaxWidth().background(PomboColors.Bg, RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Overlay8, RoundedCornerShape(12.dp))
        ) {
            ProfileAction("Add account", Icons.Outlined.AddCircleOutline, onClick = onAddAccount)
            androidx.compose.material3.HorizontalDivider(color = PomboColors.Overlay8)
            // Leaves the account behind rather than erasing it: `disconnect()`
            // calls WalletStore.clear(), which drops the current account's key
            // for good. Destroying an account belongs in Settings → Security,
            // behind its device-auth gate and typed confirmation — not one tap
            // away in the profile menu.
            ProfileAction("Browse as guest", com.pombo.android.ui.PomboIcons.Ghost) { vm.browseAsGuest() }
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
            Modifier.fillMaxWidth().background(PomboColors.Bg, RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Overlay8, RoundedCornerShape(12.dp))
        ) {
            ProfileAction("About", Icons.Outlined.Info) { showAbout = true }
        }
        if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun ProfileAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = when {
        danger -> PomboColors.Danger
        !enabled -> PomboColors.TextSecondary
        else -> PomboColors.TextPrimary
    }
    Row(
        Modifier.fillMaxWidth().clickableNoRipple { if (enabled) onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = tint.copy(alpha = 0.60f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            label + if (!enabled) "  (coming soon)" else "",
            color = tint,
            fontSize = 14.sp
        )
    }
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

// Layout size stays [size] so the header keeps its height; Compose already
// expands the touch target of a smaller clickable to 48 dp.
@Composable
internal fun HeaderIconButton(
    size: Dp,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier.size(size)
            .drawBehind { if (pressed) drawCircle(PomboColors.Overlay12, radius = 24.dp.toPx()) }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}
