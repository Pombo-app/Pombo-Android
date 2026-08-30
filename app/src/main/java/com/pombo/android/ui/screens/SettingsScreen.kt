package com.pombo.android.ui.screens

import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pombo.android.AppViewModel
import com.pombo.android.data.Channel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.theme.PomboColors


/**
 * Settings-only header: the current panel's name sits centred and marked
 * orange with an underline — the treatment the web gives its active filter
 * tab (.channel-filter-tab: uppercase, tracking-widest, #F6851B + 2px
 * border). The other tabs keep the brand header above.
 */
@Composable
private fun SettingsHeader(title: String) {
    Box(
        Modifier.fillMaxWidth()
            .background(PomboColors.Background)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .heightIn(min = 36.dp)
    ) {
        Column(
            Modifier.align(Alignment.Center).width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title.uppercase(),
                color = PomboColors.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.15.em
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().height(2.dp)
                    .background(PomboColors.Accent, RoundedCornerShape(1.dp))
            )
        }
    }
}

/**
 * Order and grouping follow the web's #pill-settings-dropdown, which separates
 * the panels with `<hr>` into: what the account IS, what it KEEPS PRIVATE, and
 * what the app is. `section` marks which group a panel belongs to, so the
 * dropdown can draw the dividers without hard-coding positions.
 *
 * The enum order also drives the settings pager, so swiping between panels
 * follows the same sequence as the menu.
 */
internal enum class SettingsPanel(val label: String, val icon: ImageVector, val section: Int) {
    ACCOUNT("Account", Icons.Outlined.Person, 0),
    WALLET("Wallet", Icons.Outlined.AccountBalanceWallet, 0),
    NOTIFICATIONS("Notifications", Icons.Outlined.Notifications, 0),
    API("API", Icons.Outlined.Link, 0),
    CONTENT("Content", Icons.Outlined.SmartDisplay, 0),
    PRIVACY("Privacy", Icons.Outlined.Shield, 1),
    SECURITY("Security", Icons.Outlined.Lock, 1),
    DM_INBOX("DM Inbox", Icons.Outlined.MailOutline, 1)
    // ABOUT moved to the Profile screen (2026-08-21) — see AboutPanel's call
    // site in ProfileTab.
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SettingsTab(vm: AppViewModel, pagerState: androidx.compose.foundation.pager.PagerState) {
    val status by vm.status.collectAsState()

    // Any tap outside a text field drops its focus, which is also what
    // commits the display-name edit below.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { focusManager.clearFocus() }
        }
    ) {
        // The header names the panel being viewed (Account, Wallet, …), so the
        // old label under it would just repeat the word.
        SettingsHeader(SettingsPanel.entries[pagerState.currentPage].label)
        Spacer(Modifier.height(8.dp))
        // Panels are swiped horizontally; the pill dropdown jumps straight to one.
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            // Scroll lives HERE, once, for every panel. Panels used to be fixed
            // Columns that simply clipped whatever ran past the screen — Account
            // grew past it and Import/Export became unreachable. Security and
            // Privacy carried their own scroller; those were removed, since two
            // verticalScrolls of the same orientation nested means the inner one
            // measures against infinite height and never scrolls at all.
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 16.dp)
            ) {
                when (SettingsPanel.entries[page]) {
                    SettingsPanel.ACCOUNT -> AccountPanel(vm)
                    SettingsPanel.WALLET -> WalletPanel(vm)
                    SettingsPanel.NOTIFICATIONS -> NotificationsSection(vm)
                    SettingsPanel.SECURITY -> SecurityPanel(vm)
                    SettingsPanel.DM_INBOX -> DmInboxPanel(vm)
                    SettingsPanel.PRIVACY -> PrivacyPanel(vm)
                    SettingsPanel.API -> ApiPanel(vm)
                    SettingsPanel.CONTENT -> ContentPanel(vm)
                }
            }
        }
    }
}

/**
 * Web settings-panel-profile: display name beside the avatar, the account
 * address with a copy button, then Backup & Restore. The backup file is the
 * web's `pombo-account-backup` format, so it restores on either client.
 */
@Composable
private fun AccountPanel(vm: AppViewModel) {
    val username by vm.username.collectAsState()
    val address by vm.address.collectAsState()
    val ensAvatars by vm.ensAvatars.collectAsState()
    val isGuest by vm.isGuest.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // A primary ENS name takes over the display name entirely (web
    // SettingsUI.show): the field shows it, locked, with an "ENS Verified"
    // badge where the hint normally sits.
    val ensNames by vm.ensNames.collectAsState()
    LaunchedEffect(address) { address?.let { vm.ensureEns(it) } }
    // Device Sync below is shown only with an inbox, and the flag is otherwise
    // only refreshed on connect — re-check on entry so the section is not
    // missing on a panel opened before that landed.
    LaunchedEffect(address) { vm.refreshDmInbox() }
    val ensName = address?.let { ensNames[it.lowercase()] }
    var editing by remember(username, ensName) { mutableStateOf(ensName ?: username ?: "") }

    // Export: ask for a password, then pick where to save. The password (and
    // the media choice) ride in state between the two steps because SAF
    // answers via callback.
    var askExportPassword by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var exportIncludeMedia by remember { mutableStateOf(true) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val pwd = exportPassword
        exportPassword = null
        if (uri != null && pwd != null) vm.exportBackupTo(uri, pwd, exportIncludeMedia)
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importUri = uri }

    if (askExportPassword) BackupPasswordDialog(
        title = "Export Account Backup",
        hint = "Choose a password to protect the backup (min 8 characters). You will need it to restore, on Android or on the web.",
        confirmLabel = "Export",
        minLength = 8,
        toggleLabel = "Include sent DM media",
        confirmPassword = true,
        onDismiss = { askExportPassword = false }
    ) { pwd, includeMedia ->
        askExportPassword = false
        exportPassword = pwd
        exportIncludeMedia = includeMedia
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        exportLauncher.launch("pombo-account-backup-$stamp.json")
    }
    importUri?.let { uri ->
        BackupPasswordDialog(
            title = "Restore Account Backup",
            hint = "Enter the password this backup was created with.",
            confirmLabel = "Restore",
            onDismiss = { importUri = null }
        ) { pwd, _ ->
            importUri = null
            vm.importBackupFrom(uri, pwd)
        }
    }

    SettingsSection {
        // Web: name field capped at 180px with the 56px avatar beside it.
        // Saved when the field loses focus — the same commit point as the
        // web's `change` event; setUsername shows the confirmation toast.
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.widthIn(max = 180.dp)) {
                SettingsFieldLabel("Display Name")
                OutlinedTextField(
                    // Web: with ENS active the field CARRIES the ENS name.
                    value = ensName ?: editing,
                    onValueChange = { if (it.length <= 18) editing = it },
                    placeholder = { Text("Enter your name", color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp) },
                    colors = pomboFieldColors(),
                    singleLine = true,
                    // Web: with a primary ENS name the field is `disabled` and
                    // carries it — the on-chain name takes precedence over a
                    // local one.
                    enabled = ensName == null,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { state ->
                            if (ensName == null && !state.isFocused &&
                                editing.trim() != (username ?: "")
                            ) {
                                vm.setUsername(editing)
                            }
                        }
                )
                Spacer(Modifier.height(6.dp))
                // The ENS badge replaces the default hint, as on the web.
                if (ensName != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircleOutline, contentDescription = null,
                            tint = Color(0xFF4ADE80), modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        // text-xs text-blue-400
                        Text("ENS Verified", color = Color(0xFF60A5FA), fontSize = 12.sp)
                    }
                } else {
                    Text("Visible to other users", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
                }
            }
            // Centred in the gap between the name field and the card's edge,
            // not glued to the margin.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Avatar(
                    address ?: "guest", size = 72.dp, cornerRadiusFraction = 0.5,
                    ensAvatarUrl = address?.let { ensAvatars[it.lowercase()] }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SettingsFieldLabel("Account Address")
        // Tapping the address itself copies it — no separate button.
        Box(
            Modifier.fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .clickableNoRipple {
                    address?.let {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
                        vm.toast("Address copied", com.pombo.android.ui.ToastKind.SUCCESS)
                    }
                }
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                address ?: "—",
                color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1
            )
        }

        // Sync publishes to the account's own DM inbox (partition 1);
        // pushSync/pullSync both bail on `inboxExists()`, so without an inbox
        // the whole block is absent rather than disabled.
        val hasDmInbox by vm.hasDmInbox.collectAsState()
        if (hasDmInbox) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
            Spacer(Modifier.height(20.dp))

            // Moves this account's state between the user's own devices —
            // separate from which endpoints the client talks to (API panel).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Sync, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.80f), modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Device Sync", color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Sync your app state, including sent DMs from other devices",
                color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
            )
            Spacer(Modifier.height(14.dp))
            val syncMode by vm.syncMode.collectAsState()
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(vertical = 4.dp)
            ) {
                com.pombo.android.data.SyncMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickableNoRipple { vm.setSyncMode(mode) }
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = syncMode == mode,
                            onClick = { vm.setSyncMode(mode) },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                selectedColor = PomboColors.Accent,
                                unselectedColor = Color.White.copy(alpha = 0.30f)
                            )
                        )
                        Text(
                            mode.label,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Storage, contentDescription = null,
                tint = Color.White.copy(alpha = 0.80f), modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Backup & Restore", color = Color.White.copy(alpha = 0.80f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        Text("Export data to restore on another device", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupButton(
                label = "Export", icon = Icons.Outlined.FileUpload,
                emphasized = true, enabled = !isGuest, modifier = Modifier.weight(1f)
            ) { askExportPassword = true }
            BackupButton(
                label = "Import", icon = Icons.Outlined.FileDownload,
                emphasized = false, enabled = true, modifier = Modifier.weight(1f)
            ) { importLauncher.launch(arrayOf("application/json")) }
        }
        // Channel passwords are included: Channel.toJson() carries `password`,
        // and the backup payload is exportSyncState(), which builds `channels`
        // from it — the whole file is scrypt/AES-GCM sealed with the export
        // password anyway.
    }
}

/** Web label style: 12px uppercase, wide tracking. */
@Composable
private fun SettingsFieldLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = 0.80f), fontSize = 11.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.08.em
    )
    Spacer(Modifier.height(8.dp))
}

/** The web's Export (bright) / Import (dim) pair of bordered buttons. */
@Composable
private fun BackupButton(
    label: String,
    icon: ImageVector,
    emphasized: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val fg = when {
        !enabled -> Color.White.copy(alpha = 0.25f)
        emphasized -> Color.White
        else -> Color.White.copy(alpha = 0.60f)
    }
    Row(
        modifier
            .background(
                Color.White.copy(alpha = if (emphasized) 0.10f else 0.05f),
                RoundedCornerShape(12.dp)
            )
            .border(
                1.dp,
                Color.White.copy(alpha = if (emphasized) 0.20f else 0.10f),
                RoundedCornerShape(12.dp)
            )
            .clickableNoRipple { if (enabled) onClick() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 14.sp)
    }
}

/** Password prompt for backup export/restore (web showPasswordPrompt). */
@Composable
internal fun BackupPasswordDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    minLength: Int = 1,
    toggleLabel: String? = null,
    confirmPassword: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (password: String, toggled: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var toggled by remember { mutableStateOf(true) }
    val valid = password.length >= minLength && (!confirmPassword || confirm == password)
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
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            Text(hint, color = Color.White.copy(alpha = 0.50f), fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                placeholder = { Text("Password", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
            )
            if (confirmPassword) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it },
                    placeholder = { Text("Confirm password", color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp) },
                    singleLine = true, shape = RoundedCornerShape(12.dp),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                    colors = pomboFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                if (confirm.isNotEmpty() && confirm != password) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Passwords don't match",
                        color = Color(0xFFE57373), fontSize = 12.sp
                    )
                }
            }
            toggleLabel?.let { label ->
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickableNoRipple { toggled = !toggled }
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .background(
                                if (toggled) Color.White else Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(5.dp)
                            )
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (toggled) Icon(
                            Icons.Filled.Check, contentDescription = null,
                            tint = Color.Black, modifier = Modifier.size(13.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp)
                }
            }
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
                            if (valid) Color.White else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickableNoRipple { if (valid) onSubmit(password, toggled) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        confirmLabel,
                        color = if (valid) Color.Black else Color.White.copy(alpha = 0.30f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Global notification switch — web index.html "Channel Invites" / push settings.
 * It only obtains the delivery token; which channels actually notify is chosen
 * per channel, exactly as on web (subscribeToChannel refuses without this).
 */
@Composable
private fun NotificationsSection(vm: AppViewModel) {
    val enabled by vm.pushEnabled.collectAsState()
    val dmPush by vm.dmPushEnabled.collectAsState()
    val invites by vm.inviteNotifications.collectAsState()
    LaunchedEffect(Unit) { vm.refreshDmInbox() }
    SettingsSection {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Push Notifications", color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Get notified about new messages. Turn on per channel afterwards.",
                    color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp, lineHeight = 17.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Switch(
                checked = enabled,
                onCheckedChange = { vm.setPushEnabled(it) },
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFFF6851B),
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.60f)
                )
            )
        }
        // Web #dm-push-section: the "Direct Messages" sub-option, indented
        // under the global toggle with a left border, only while push is on.
        if (enabled) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(start = 6.dp)) {
                Box(
                    Modifier.width(1.dp).heightIn(min = 40.dp)
                        .background(Color.White.copy(alpha = 0.10f))
                )
                Spacer(Modifier.width(12.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Direct Messages",
                            color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Get notified for new DMs",
                            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Switch(
                        checked = dmPush,
                        onCheckedChange = { vm.setDmPushEnabled(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFFF6851B),
                            checkedThumbColor = Color.White,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                            uncheckedThumbColor = Color.White.copy(alpha = 0.60f)
                        )
                    )
                }
            }
        }
        // Web: divider + "Channel Invites" toggle (#notifications-enabled).
        // Muting unsubscribes the inbox P3 partition, so invites stop arriving
        // at all — same behaviour as the web's always-open tab.
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
        Spacer(Modifier.height(16.dp))
        ContentToggleRow(
            title = "Channel Invites",
            hint = "Show notifications when you receive channel invites",
            checked = invites,
            trackColor = Color(0xFFF6851B)
        ) { vm.setInviteNotifications(it) }
        // Web #notifications-status: "Muted" under the toggle while off.
        if (!invites) {
            Spacer(Modifier.height(8.dp))
            Text("Muted", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
        }
    }
}

/**
 * Web settings-panel-linkpreviews ("Content"): what message links expand into
 * and whether Explore surfaces sensitive channels.
 */
@Composable
private fun ContentPanel(vm: AppViewModel) {
    val ytEnabled by vm.youtubeEmbeds.collectAsState()
    val ensAvatarsEnabled by vm.ensAvatarsEnabled.collectAsState()
    val nsfwEnabled by vm.nsfwEnabled.collectAsState()
    SettingsSection {
        ContentToggleRow(
            title = "YouTube Embeds",
            hint = "Show video player when YouTube links are shared",
            checked = ytEnabled,
            // The web paints this one toggle YouTube red, not accent orange.
            trackColor = Color(0xFFFF0000)
        ) { vm.setYoutubeEmbeds(it) }
        Spacer(Modifier.height(16.dp))
        ContentToggleRow(
            title = "ENS Avatars",
            hint = "Load profile pictures from the server each ENS name points at. " +
                "That server sees your IP address.",
            checked = ensAvatarsEnabled,
            trackColor = PomboColors.Accent
        ) { vm.setEnsAvatars(it) }
        Spacer(Modifier.height(16.dp))
        ContentToggleRow(
            title = "Show Sensitive Content",
            hint = "Enable NSFW filter in channel exploration",
            checked = nsfwEnabled,
            trackColor = PomboColors.Accent
        ) { vm.setNsfwEnabled(it) }
    }
}

/**
 * Web settings-panel-repair, renamed "DM Inbox": diagnose/repair the inbox
 * streams and manage its storage nodes. Diagnosis is read-only; repair and
 * every storage change are on-chain and cost gas.
 */
@Composable
private fun DmInboxPanel(vm: AppViewModel) {
    val health by vm.inboxHealth.collectAsState()
    val storage by vm.inboxStorage.collectAsState()
    var showDmSetup by remember { mutableStateOf(false) }
    // Storage shows on open, not gated behind a Diagnose (like the channel
    // storage panel).
    LaunchedEffect(Unit) { vm.loadInboxStorage() }

    if (showDmSetup) CreateDmInboxDialog(
        vm,
        onDismiss = { showDmSetup = false },
        onCreate = { provider, custom, days -> vm.setupDmInbox(provider, custom, days) }
    )

    SettingsSection {
        Text("Check and repair your DM inbox", color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))

        // ---- Unified status card (web #repair-status-card) ----
        if (health !is AppViewModel.InboxHealth.NoInbox) {
            val (accent, title, detail) = when (val h = health) {
                is AppViewModel.InboxHealth.Idle ->
                    Triple(Color.White.copy(alpha = 0.20f), "Not checked", "Run a diagnosis to check inbox health")
                is AppViewModel.InboxHealth.Loading ->
                    Triple(Color.White.copy(alpha = 0.40f), "Diagnosing...", "Checking streams, permissions and storage")
                is AppViewModel.InboxHealth.Healthy ->
                    Triple(Color(0xFF4ADE80), "Inbox healthy", h.detail)
                is AppViewModel.InboxHealth.Issues ->
                    Triple(Color(0xFFFACC15), "Issues found", h.summary)
                is AppViewModel.InboxHealth.Failed ->
                    Triple(Color(0xFFF87171), "Error", h.message)
                is AppViewModel.InboxHealth.Repairing ->
                    Triple(PomboColors.Accent, "Repairing...", h.step)
                is AppViewModel.InboxHealth.Repaired ->
                    Triple(Color(0xFF4ADE80), "Repaired", h.detail)
                else -> Triple(Color.White.copy(alpha = 0.20f), "Not checked", "")
            }
            Row(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                    .border(1.dp, accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(32.dp).background(accent.copy(alpha = 0.10f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    when (health) {
                        is AppViewModel.InboxHealth.Loading, is AppViewModel.InboxHealth.Repairing ->
                            androidx.compose.material3.CircularProgressIndicator(
                                color = accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp)
                            )
                        is AppViewModel.InboxHealth.Healthy, is AppViewModel.InboxHealth.Repaired ->
                            Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                        is AppViewModel.InboxHealth.Issues ->
                            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                        is AppViewModel.InboxHealth.Failed ->
                            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                        else ->
                            Icon(Icons.Outlined.Search, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(detail, color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        val busyNow = health is AppViewModel.InboxHealth.Loading || health is AppViewModel.InboxHealth.Repairing
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (health is AppViewModel.InboxHealth.NoInbox) {
                // Web: the primary action morphs into "Create DM Inbox".
                Row(
                    Modifier.weight(1f)
                        .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .clickableNoRipple { showDmSetup = true }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.MailOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Create DM Inbox", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Row(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .clickableNoRipple { if (!busyNow) vm.diagnoseInbox() }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.70f), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Diagnose", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
                }
                if (health is AppViewModel.InboxHealth.Issues) {
                    Row(
                        Modifier.weight(1f)
                            .background(PomboColors.Accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .border(1.dp, PomboColors.Accent.copy(alpha = 0.20f), RoundedCornerShape(8.dp))
                            .clickableNoRipple { if (!busyNow) vm.repairInbox() }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Repair", color = PomboColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // ---- Inbox storage (web #inbox-storage-section) ----
        val info = storage
        if (info != null && health !is AppViewModel.InboxHealth.NoInbox) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.05f)))
            Spacer(Modifier.height(16.dp))
            Text("Inbox Storage", color = Color.White.copy(alpha = 0.90f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Change the storage node that persists your DM inbox.",
                color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp
            )

            Spacer(Modifier.height(14.dp))
            Text(
                "RETENTION PERIOD", color = Color.White.copy(alpha = 0.30f),
                fontSize = 10.sp, letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(6.dp))
            var retentionDraft by remember(info.storageDays) {
                mutableStateOf(info.storageDays?.toString() ?: "")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    value = retentionDraft,
                    onValueChange = { v -> retentionDraft = v.filter { it.isDigit() }.take(4) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    modifier = Modifier.width(90.dp)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("days", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                val days = retentionDraft.toIntOrNull()
                val valid = days != null && days in 1..3650 && days != info.storageDays
                Box(
                    Modifier
                        .background(Color.White.copy(alpha = if (valid) 0.10f else 0.05f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .clickableNoRipple { if (valid) vm.setInboxRetention(days!!) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Save",
                        color = Color.White.copy(alpha = if (valid) 0.80f else 0.30f), fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "STORAGE NODES", color = Color.White.copy(alpha = 0.30f),
                fontSize = 10.sp, letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(6.dp))
            if (info.nodes.isEmpty()) {
                Text("No storage nodes", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
            } else {
                info.nodes.forEach { node ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            // Same identification the channel storage panel
                            // uses: "Pombo" vs "Custom" against pomboStorageNode.
                            Text(
                                if (node.equals(vm.pomboStorageNode, ignoreCase = true)) "Pombo" else "Custom",
                                color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp
                            )
                            Text(
                                node, color = Color.White.copy(alpha = 0.70f), fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Delete, contentDescription = "Remove storage node",
                            tint = Color.White.copy(alpha = 0.40f),
                            modifier = Modifier.size(18.dp).clickableNoRipple { vm.removeInboxStorageNode(node) }
                        )
                    }
                }
            }

            // Add node (web #inbox-storage-add-form)
            Spacer(Modifier.height(6.dp))
            var addingNode by remember { mutableStateOf(false) }
            if (!addingNode) {
                Row(
                    Modifier.clickableNoRipple { addingNode = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White.copy(alpha = 0.60f), modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add storage node", color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp)
                }
            } else {
                var provider by remember { mutableStateOf("streamr") }
                var customAddr by remember { mutableStateOf("") }
                var daysDraft by remember { mutableStateOf("180") }
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf("streamr" to "Pombo", "custom" to "Custom storage node").forEach { (value, label) ->
                            Row(
                                Modifier.clickableNoRipple { provider = value },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = provider == value,
                                    onClick = { provider = value },
                                    colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                        selectedColor = PomboColors.Accent
                                    )
                                )
                                Text(label, color = Color.White.copy(alpha = 0.80f), fontSize = 13.sp)
                            }
                        }
                    }
                    if (provider == "custom") {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.text.BasicTextField(
                            value = customAddr, onValueChange = { customAddr = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 12.sp, color = PomboColors.Text,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                            modifier = Modifier.fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            decorationBox = { inner ->
                                if (customAddr.isEmpty()) Text(
                                    "0x… custom storage node address",
                                    color = Color.White.copy(alpha = 0.20f), fontSize = 12.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                                inner()
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = daysDraft,
                            onValueChange = { v -> daysDraft = v.filter { it.isDigit() }.take(4) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            modifier = Modifier.width(90.dp)
                                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("retention", color = Color.White.copy(alpha = 0.50f), fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            "Cancel", color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp,
                            modifier = Modifier.clickableNoRipple { addingNode = false }.padding(8.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        val addDays = daysDraft.toIntOrNull()
                        val addValid = addDays != null && addDays in 1..3650 &&
                            (provider == "streamr" || Regex("^0x[a-fA-F0-9]{40}$").matches(customAddr.trim()))
                        Box(
                            Modifier
                                .background(
                                    if (addValid) PomboColors.Accent else PomboColors.Accent.copy(alpha = 0.40f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickableNoRipple {
                                    if (addValid) {
                                        vm.addInboxStorageNode(provider, customAddr.trim().ifEmpty { null }, addDays!!)
                                        addingNode = false
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) { Text("Add", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }

            // Gas warning (web amber card)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFFF59E0B).copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Icon(
                    Icons.Outlined.WarningAmber, contentDescription = null,
                    tint = Color(0xFFFBBF24).copy(alpha = 0.80f), modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Storage changes require on-chain transactions and gas fees.",
                    color = Color(0xFFFBBF24).copy(alpha = 0.80f), fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun ContentToggleRow(
    title: String,
    hint: String,
    checked: Boolean,
    trackColor: Color,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                hint,
                color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp, lineHeight = 17.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = trackColor,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.60f)
            )
        )
    }
}

/**
 * Web settings-panel-wallet: the POL balance card with a refresh action and
 * the "Fund with MetaMask" button. Additions here: a DATA token balance card
 * (not on the web) — and no DM inbox status, which was never part of the
 * web's wallet panel.
 */
@Composable
private fun WalletPanel(vm: AppViewModel) {
    val address by vm.address.collectAsState()
    val balanceWei by vm.balanceWei.collectAsState()
    val dataBalanceWei by vm.dataBalanceWei.collectAsState()
    val balancesFailed by vm.balancesFailed.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    // Web: SettingsUI refreshes the balance when the wallet panel is shown.
    LaunchedEffect(address) { if (address != null) vm.refreshGas() }
    SettingsSection {
        WalletBalanceCard(
            label = "POL Balance",
            value = when {
                balanceWei != null -> com.pombo.android.core.GasEstimator.formatBalancePOL(balanceWei)
                balancesFailed -> "Unavailable — tap refresh"
                else -> "Loading..."
            },
            onRefresh = { vm.refreshGas() }
        )
        Spacer(Modifier.height(16.dp))
        WalletBalanceCard(
            label = "DATA Balance",
            value = when {
                dataBalanceWei != null -> com.pombo.android.core.GasEstimator.formatBalanceDATA(dataBalanceWei)
                balancesFailed -> "Unavailable — tap refresh"
                else -> "Loading..."
            },
            onRefresh = { vm.refreshGas() }
        )
        Spacer(Modifier.height(16.dp))
        // Web fund-metamask-btn talks to the injected extension; on Android
        // the MetaMask APP deep link opens its send screen pre-filled with
        // this address on Polygon (chain 137) — the amount is chosen there.
        // Without MetaMask installed the link lands on their install page.
        Row(
            Modifier.fillMaxWidth()
                .background(PomboColors.Accent.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                .clickableNoRipple {
                    address?.let { addr ->
                        // MetaMask's /send deep-link route demands a chain id
                        // it then can't match against the wallet's networks —
                        // "network not found" with @137 and @0x89, "missing
                        // chain_id" without. So: copy the address, open the
                        // MetaMask APP directly, and the user pastes into
                        // Send. Not installed → Play Store page.
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(addr))
                        vm.toast(
                            "Address copied — paste it in MetaMask's Send",
                            com.pombo.android.ui.ToastKind.SUCCESS, 5000L
                        )
                        val launch = context.packageManager.getLaunchIntentForPackage("io.metamask")
                        if (launch != null) {
                            context.startActivity(launch)
                        } else {
                            uriHandler.openUri("https://play.google.com/store/apps/details?id=io.metamask")
                        }
                    }
                }
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AccountBalanceWallet, contentDescription = null,
                tint = PomboColors.Accent, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Fund with MetaMask", color = PomboColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Send POL from your MetaMask wallet",
            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

/** Web wallet panel balance card: value + refresh icon, network hint below. */
@Composable
private fun WalletBalanceCard(label: String, value: String, onRefresh: () -> Unit) {
    Text(
        label.uppercase(),
        color = Color.White.copy(alpha = 0.80f), fontSize = 11.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.08.em
    )
    Spacer(Modifier.height(8.dp))
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                value, color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Outlined.Refresh, contentDescription = "Refresh",
                tint = Color.White.copy(alpha = 0.40f),
                modifier = Modifier.size(16.dp).clickableNoRipple(onRefresh)
            )
        }
        Spacer(Modifier.height(2.dp))
        Text("On Polygon network", color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp)
    }
}

/**
 * Text field matching the RPC selector row's height — the stock
 * OutlinedTextField's 56dp minimum towers over the other list elements.
 * Commits when focus leaves (tap away or IME done), like the web's `change`
 * event; no save button.
 */
@Composable
private fun CompactSettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onCommit: () -> Unit
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = PomboColors.Text),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(PomboColors.Accent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Done
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = { focusManager.clearFocus() }
        ),
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { state ->
                // Guard against the initial not-focused event at composition:
                // only a real focus loss commits.
                if (state.isFocused) hadFocus = true
                else if (hadFocus) { hadFocus = false; onCommit() }
            }
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, color = Color.White.copy(alpha = 0.20f), fontSize = 14.sp)
            }
            inner()
        }
    )
}

@Composable
private fun ApiPanel(vm: AppViewModel) {
    // Both cards read as a summary until opened; the list of endpoints is the
    // kind of thing you set once and then only want reassurance about.
    GraphApiCard(vm)
    Spacer(Modifier.height(12.dp))
    PolygonRpcCard(vm)
}

/** Channel discovery goes through The Graph; without a key of your own it
 *  uses the shared one bundled with the app. */
@Composable
private fun GraphApiCard(vm: AppViewModel) {
    val graphKey by vm.graphApiKey.collectAsState()
    val health by vm.graphHealth.collectAsState()
    var open by remember { mutableStateOf(false) }
    var keyDraft by remember(graphKey) { mutableStateOf(graphKey) }

    LaunchedEffect(Unit) { vm.refreshGraphHealth() }

    SettingsSection {
        Row(
            Modifier.fillMaxWidth().clickableNoRipple { open = !open },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "THE GRAPH API", color = Color.White.copy(alpha = 0.80f),
                    fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (dot, label, labelColor) = when (health) {
                        null -> Triple(Color.White.copy(alpha = 0.20f), "Not checked", Color.White.copy(alpha = 0.40f))
                        true -> Triple(Color(0xFF22C55E), "OK", Color(0xFF4ADE80))
                        else -> Triple(Color(0xFFEF4444), "Not responding", Color(0xFFF87171))
                    }
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = labelColor, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (graphKey.isEmpty()) "Using default key (rate limited)" else "Using your own key",
                        color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp
                    )
                }
            }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.30f), modifier = Modifier.size(18.dp)
            )
        }

        if (open) {
            Spacer(Modifier.height(12.dp))
            Text("Your API key", color = Color.White.copy(alpha = 0.30f), fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            // Commits when the field loses focus (tap away / IME done), like the
            // web's `change` event — setGraphApiKey shows the toast.
            CompactSettingsField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                placeholder = "Optional - uses default if empty"
            ) {
                if (keyDraft.trim() != graphKey) vm.setGraphApiKey(keyDraft)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Get yours at thegraph.com/studio",
                color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp
            )
        }
    }
}

/** Web #rpc-endpoint-list: which endpoints the app talks to, and their health. */
@Composable
private fun PolygonRpcCard(vm: AppViewModel) {
    val selection by vm.rpcSelection.collectAsState()
    val probes by vm.rpcProbes.collectAsState()
    val testing by vm.rpcTesting.collectAsState()
    val notice by vm.rpcNotice.collectAsState()
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    // Probe the endpoints already in use when the panel appears, so one that
    // stopped answering shows up without anyone asking. The ones not picked are
    // left alone until Test all: opening Settings must not hand the user's
    // address to a provider they did not choose.
    LaunchedEffect(Unit) {
        vm.clearRpcProbes()
        vm.testRpc()
    }

    SettingsSection {
        val selected = selection.urls
        val tested = selected.filter { probes.containsKey(it) }
        val working = tested.filter { probes[it]?.let { p -> p.alive && p.onPolygon } == true }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickableNoRipple { open = !open }) {
                Text(
                    "POLYGON RPC", color = Color.White.copy(alpha = 0.80f),
                    fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (dot, label, labelColor) = when {
                        notice != null -> Triple(Color(0xFFEF4444), notice!!, Color(0xFFF87171))
                        testing -> Triple(Color(0xFFFACC15), "Testing...", Color(0xFFFACC15))
                        tested.isEmpty() ->
                            Triple(Color.White.copy(alpha = 0.20f), "Not tested", Color.White.copy(alpha = 0.40f))
                        working.isEmpty() -> Triple(Color(0xFFEF4444), "Not connected", Color(0xFFF87171))
                        else -> Triple(Color(0xFF22C55E), "Connected", Color(0xFF4ADE80))
                    }
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = labelColor, fontSize = 12.sp)
                    if (notice == null && !testing && selected.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${working.size}/${selected.size}",
                            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // The endpoint actually in front, which is the first one ticked: a
            // row that is off is not talked to, so naming it here would mislead.
            selection.rows.firstOrNull { it.on }?.let { first ->
                Text(
                    if (first.key == com.pombo.android.core.RpcEndpoints.CUSTOM_KEY)
                        selection.customUrl.removePrefix("https://")
                    else selection.labelFor(first.key),
                    color = Color.White.copy(alpha = 0.50f), fontSize = 12.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.30f),
                modifier = Modifier.size(18.dp).clickableNoRipple { open = !open }
            )
        }

        if (!open) return@SettingsSection

        Spacer(Modifier.height(12.dp))
        Text(
            "Checked endpoints are the ones used, in this order. The first is preferred.",
            color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))

        selection.rows.forEachIndexed { index, row ->
            val url = selection.urlFor(row.key)
            RpcEndpointRow(
                name = selection.labelFor(row.key),
                host = (url ?: "").removePrefix("https://"),
                checked = row.on,
                probe = url?.let { probes[it] },
                testing = testing,
                canMoveUp = index > 0,
                canMoveDown = index < selection.rows.lastIndex,
                onCheck = { vm.setRpcRow(row.key, it) },
                onMove = { vm.moveRpcRow(row.key, it) },
                onRemove = if (row.key == com.pombo.android.core.RpcEndpoints.CUSTOM_KEY) {
                    { vm.removeRpcCustomUrl() }
                } else null
            )
        }

        // Add endpoint, the same shape as adding a storage node. There is
        // nothing to press anywhere else on this card, so the field saves when
        // it is left, like the Graph API key above it.
        Spacer(Modifier.height(6.dp))
        if (!adding) {
            Row(
                Modifier.clickableNoRipple { adding = true }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Add, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.60f), modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Add custom endpoint", color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp)
            }
        } else {
            var urlDraft by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                CompactSettingsField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it; error = null },
                    placeholder = "https://your-rpc-endpoint.com"
                ) {
                    val entered = urlDraft.trim()
                    when {
                        // Left empty: nothing was being added, so it goes away.
                        entered.isEmpty() -> adding = false
                        vm.addRpcCustomUrl(entered) -> adding = false
                        else -> error = "Enter an https:// URL"
                    }
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFF87171), fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Any Polygon JSON-RPC endpoint.",
                    color = Color.White.copy(alpha = 0.25f), fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .clickableNoRipple { if (!testing) vm.testRpc(all = true) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Test all", color = Color.White.copy(alpha = 0.60f), fontSize = 12.sp)
            }
        }
    }
}
@Composable
private fun RpcEndpointRow(
    name: String,
    host: String,
    checked: Boolean,
    probe: AppViewModel.RpcProbe?,
    testing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onCheck: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: (() -> Unit)?
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(bottom = 6.dp)
            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
            .border(
                1.dp,
                Color.White.copy(alpha = if (checked) 0.15f else 0.05f),
                RoundedCornerShape(8.dp)
            )
            .padding(start = 4.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Checkbox(
            checked = checked,
            onCheckedChange = onCheck,
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = PomboColors.Accent,
                uncheckedColor = Color.White.copy(alpha = 0.30f),
                checkmarkColor = Color.Black
            ),
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            color = if (checked) PomboColors.Text else Color.White.copy(alpha = 0.50f),
            fontSize = 13.sp
        )
        Spacer(Modifier.width(6.dp))
        Text(
            host, color = Color.White.copy(alpha = 0.25f), fontSize = 10.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        RpcProbeLabel(probe, testing)
        if (onRemove != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Outlined.Delete, contentDescription = "Remove endpoint",
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(15.dp).clickableNoRipple(onRemove)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RpcMoveArrow(Icons.Filled.KeyboardArrowUp, canMoveUp) { onMove(-1) }
            RpcMoveArrow(Icons.Filled.KeyboardArrowDown, canMoveDown) { onMove(1) }
        }
    }
}

/**
 * One endpoint's verdict. "Blocked in app" is its own state because being
 * alive and answering the WebView's origin are different properties, and only
 * the second one decides whether the bridge can use it.
 */
@Composable
private fun RpcProbeLabel(probe: AppViewModel.RpcProbe?, testing: Boolean) {
    val (text, color) = when {
        probe == null && testing -> "testing" to Color(0xFFFACC15)
        probe == null -> "not tested" to Color.White.copy(alpha = 0.25f)
        probe.reach == com.pombo.android.core.GasEstimator.Reach.DEAD ->
            "no answer" to Color(0xFFF87171)
        probe.reach == com.pombo.android.core.GasEstimator.Reach.LIMITED ->
            "rate limited" to Color(0xFFFACC15)
        probe.reach == com.pombo.android.core.GasEstimator.Reach.REFUSED ->
            "refused" to Color(0xFFFACC15)
        !probe.onPolygon -> "not Polygon" to Color(0xFFFACC15)
        probe.webView == AppViewModel.WebViewProbe.BLOCKED ->
            "blocked in app" to Color(0xFFFACC15)
        else -> "${probe.ms ?: 0} ms" to Color(0xFF4ADE80)
    }
    Text(text, color = color, fontSize = 10.sp, maxLines = 1)
}

@Composable
private fun RpcMoveArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Icon(
        icon, contentDescription = null,
        tint = Color.White.copy(alpha = if (enabled) 0.35f else 0.08f),
        modifier = Modifier
            .size(16.dp)
            .then(if (enabled) Modifier.clickableNoRipple(onClick) else Modifier)
    )
}
/**
 * Security — the web's #settings-panel-security: reveal the private key, and
 * delete the account. Both are two-step and both end in a hold-to-confirm,
 * because both are unrecoverable.
 *
 * The web's first step is "enter your keystore password". This app has no such
 * password (the key is sealed by the Android Keystore, bound to the device
 * lock), so the equivalent proof of ownership is the device credential.
 */
@Composable
private fun SecurityPanel(vm: AppViewModel) {
    val context = LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var revealedKey by remember { mutableStateOf<String?>(null) }
    var keyVisible by remember { mutableStateOf(false) }
    var deleteVerified by remember { mutableStateOf(false) }
    val noDeviceLock = remember { !com.pombo.android.ui.DeviceAuth.canAuthenticate(context) }

    // The unlocked private key renders on this panel — keep it out of
    // screenshots, recordings and the recents thumbnail (M-I1).
    com.pombo.android.ui.SecureFlag()

    // No scroller of its own — the settings pager scrolls every panel.
    Column(Modifier.fillMaxSize()) {
        if (noDeviceLock) {
            Text(
                "This device has no screen lock. Set one up to export your key or " +
                    "delete this account — without it there is no way to confirm it is you.",
                color = PomboColors.Danger.copy(alpha = 0.90f), fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PomboColors.Danger.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                    .border(1.dp, PomboColors.Danger.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            )
            Spacer(Modifier.height(16.dp))
        }

        DangerCard(title = "Private Key", hint = "Anyone with this key has full control of your account") {
            val key = revealedKey
            if (key == null) {
                DangerButton("Unlock Key", enabled = !noDeviceLock) {
                    activity?.let {
                        com.pombo.android.ui.DeviceAuth.authenticate(
                            it, "Unlock private key",
                            "Confirm it is you before the key is shown"
                        ) { ok -> if (ok) revealedKey = vm.exportPrivateKey() }
                    }
                }
            } else {
                Text(
                    if (keyVisible) key else "•".repeat(key.length.coerceAtMost(48)),
                    color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .border(1.dp, PomboColors.Danger.copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { keyVisible = !keyVisible }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (keyVisible) "Hide" else "Show",
                            color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.weight(1f)
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                            .clickableNoRipple { revealedKey = null; keyVisible = false }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("Lock", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp) }
                }
                Spacer(Modifier.height(10.dp))
                HoldToConfirmButton("Hold to Copy (2s)") {
                    // Sensitive-flagged (no preview, no clipboard sync) and
                    // auto-cleared after 60s — the key must not sit in
                    // clipboard history forever.
                    com.pombo.android.ui.SensitiveClipboard.copy(context, key)
                    vm.toast(
                        "Private key copied — clears from the clipboard in 60s",
                        com.pombo.android.ui.ToastKind.WARNING, 6000L
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        DangerCard(title = "Delete Account", hint = "Permanently delete this account and all its data") {
            if (!deleteVerified) {
                DangerButton("Verify", enabled = !noDeviceLock) {
                    activity?.let {
                        com.pombo.android.ui.DeviceAuth.authenticate(
                            it, "Delete account",
                            "Confirm it is you before this account is erased"
                        ) { ok -> deleteVerified = ok }
                    }
                }
            } else {
                Text(
                    "This deletes the key for this account from this device, along with its " +
                        "channels and contacts. Without a copy of the private key it cannot be " +
                        "recovered — not by us, not by anyone.",
                    color = Color(0xFFFCA5A5), fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PomboColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .border(1.dp, PomboColors.Danger.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                HoldToConfirmButton("Hold to Delete (2s)") {
                    deleteVerified = false
                    vm.deleteAccount()
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                        .clickableNoRipple { deleteVerified = false }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White.copy(alpha = 0.70f), fontSize = 14.sp) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Privacy — the web's blocked-peers list with unblock (SettingsUI renderBlockedPeersList). */
@Composable
private fun PrivacyPanel(vm: AppViewModel) {
    // Recomposition trigger: the store is plain state, not a flow.
    var version by remember { mutableStateOf(0) }
    val blocked = remember(version) { vm.blockedPeers.toList().sorted() }

    // No scroller of its own — the settings pager scrolls every panel.
    Column(Modifier.fillMaxSize()) {
        Text(
            "BLOCKED USERS", color = Color.White.copy(alpha = 0.70f),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Messages from these accounts are ignored before they are decrypted.",
            color = Color.White.copy(alpha = 0.30f), fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))
        if (blocked.isEmpty()) {
            Text("No blocked users.", color = Color.White.copy(alpha = 0.30f), fontSize = 14.sp)
        } else {
            blocked.forEach { addr ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(addr, size = 34.dp, cornerRadiusFraction = 0.5)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        shortAddress(addr), color = PomboColors.Text, fontSize = 13.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        Modifier
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                            .clickableNoRipple { vm.unblockPeer(addr); version++ }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) { Text("Unblock", color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Red-tinted card the web uses for both destructive sections. */
@Composable
private fun DangerCard(title: String, hint: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(PomboColors.Danger.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Danger.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(title, color = Color.White.copy(alpha = 0.90f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(hint, color = Color.White.copy(alpha = 0.40f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun DangerButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .background(PomboColors.Danger.copy(alpha = if (enabled) 0.10f else 0.03f), RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Danger.copy(alpha = if (enabled) 0.20f else 0.08f), RoundedCornerShape(12.dp))
            .then(if (enabled) Modifier.clickableNoRipple(onClick) else Modifier)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) Color(0xFFF87171) else Color(0xFFF87171).copy(alpha = 0.35f),
            fontSize = 14.sp
        )
    }
}

/**
 * Hold-to-confirm, matching the web's 2-second press-and-hold on both
 * destructive actions. A tap does nothing: the delay is the confirmation, so
 * neither copying a private key nor deleting an account can happen by accident.
 */
@Composable
private fun HoldToConfirmButton(label: String, onConfirm: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = if (holding) 2000 else 200,
            easing = androidx.compose.animation.core.LinearEasing
        ),
        label = "hold-progress"
    )
    // Fire when the bar actually reaches the end, so the visible progress and
    // the action can never disagree.
    LaunchedEffect(progress, holding) {
        if (holding && progress >= 1f) { holding = false; onConfirm() }
    }
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFDC2626).copy(alpha = 0.90f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        holding = true
                        // Releasing early cancels: tryAwaitRelease returns on
                        // both release and cancellation, and either way the
                        // bar rewinds without firing.
                        tryAwaitRelease()
                        holding = false
                    }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(40.dp)
                .background(Color(0xFFF87171).copy(alpha = 0.40f))
        )
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}


/**
 * "Sync Devices" — the web puts this in the same pill dropdown as the settings
 * panels (#pill-sync-devices-btn), with an 18px #F6851B spinner while running.
 */
@Composable
internal fun SyncDevicesRow(vm: AppViewModel) {
    val syncing by vm.sync.syncing.collectAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple { if (!syncing) vm.syncNow() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (syncing) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color(0xFFF6851B),
                trackColor = Color(0xFFF6851B).copy(alpha = 0.16f),
                strokeWidth = 2.5.dp
            )
        } else {
            Icon(
                Icons.Outlined.Refresh, contentDescription = null,
                tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (syncing) "Syncing your data" else "Sync Devices",
            color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp
        )
    }
}


@Composable
private fun SettingsSection(title: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PomboColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        // The panel's name already sits in the header, so most cards pass no
        // title — repeating the word directly under it reads as an echo.
        if (title != null) {
            Text(title, color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}
