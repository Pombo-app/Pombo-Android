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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import com.pombo.android.AppViewModel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.theme.PomboColors


@Composable
internal fun ContactsTab(vm: AppViewModel) {
    val ensAvatars by vm.ensAvatars.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val ensNames by vm.ensNames.collectAsState()
    val status by vm.status.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showAdd by remember { mutableStateOf(false) }
    /** Contact whose nickname is being edited (web showEditModal). */
    var editing by remember { mutableStateOf<com.pombo.android.data.Contact?>(null) }

    Column(Modifier.fillMaxSize()) {
        PomboHeader(status) {
            // Same treatment as the Explore header's create button (web
            // #header-create-channel-btn): elongated rounded rectangle, not a
            // circle, white plus on the orange wash.
            Box(
                Modifier
                    .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                    .clickableNoRipple { showAdd = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Add, contentDescription = "Add contact", tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {

        if (contacts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No contacts yet. Add one with their address.", color = PomboColors.TextDim, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts, key = { it.address }) { c ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(PomboColors.Surface, RoundedCornerShape(12.dp))
                            .border(1.dp, PomboColors.Border, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Local nickname wins, then ENS, then the address.
                        LaunchedEffect(c.address) { vm.ensureEns(c.address) }
                        val ens = ensNames[c.address.lowercase()]
                        Avatar(
                            c.address, size = 42.dp, cornerRadiusFraction = 0.5,
                            ensAvatarUrl = ensAvatars[c.address.lowercase()]
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            // Web renderList: nickname (or short address) on top,
                            // then the FULL address in mono at 10px/white-30.
                            Text(
                                c.nickname ?: ens ?: shortAddr(c.address),
                                color = PomboColors.Text, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                c.address,
                                color = Color.White.copy(alpha = 0.30f), fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        // Web: a kebab opening the contact dropdown. "Send DM"
                        // is ours — on the web a contact is reached from the DM
                        // modal, but here this list IS the address book.
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            Icon(
                                Icons.Filled.MoreVert, contentDescription = "Contact options",
                                tint = Color.White.copy(alpha = 0.25f),
                                modifier = Modifier.size(20.dp).clickableNoRipple { menuOpen = true }
                            )
                            if (menuOpen) {
                                com.pombo.android.ui.PomboAnchoredMenu(onDismiss = { menuOpen = false }) {
                                    ContactMenuItem("Edit Contact", Icons.Outlined.Edit) {
                                        menuOpen = false; editing = c
                                    }
                                    ContactMenuItem("Send DM", Icons.Outlined.MailOutline) {
                                        menuOpen = false; vm.startDm(c.address)
                                    }
                                    ContactMenuItem("Copy address", Icons.Outlined.ContentCopy) {
                                        menuOpen = false
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(c.address))
                                        vm.toast("Address copied", com.pombo.android.ui.ToastKind.SUCCESS)
                                    }
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp)
                                            .background(Color.White.copy(alpha = 0.08f))
                                    )
                                    ContactMenuItem("Remove Contact", Icons.Outlined.Close, danger = true) {
                                        menuOpen = false; vm.removeContact(c.address)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (showAdd) AddContactDialog(onDismiss = { showAdd = false }) { addr, name ->
        vm.addContact(addr, name); showAdd = false
    }
    editing?.let { target ->
        EditContactDialog(
            contact = target,
            onDismiss = { editing = null },
            // addContact's re-add semantics (web addTrustedContact): keeps
            // notes/addedAt, refreshes the nickname, renames the DM room.
            onSave = { newName -> vm.addContact(target.address, newName); editing = null }
        )
    }
}

/** Row of the contact kebab dropdown (web contact dropdown items). */
@Composable
private fun ContactMenuItem(
    label: String,
    icon: ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (danger) PomboColors.Danger.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.60f),
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (danger) PomboColors.Danger.copy(alpha = 0.90f) else Color.White.copy(alpha = 0.80f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun EditContactDialog(
    contact: com.pombo.android.data.Contact,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var name by remember { mutableStateOf(contact.nickname ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PomboColors.Surface,
        titleContentColor = PomboColors.Text,
        textContentColor = PomboColors.Text,
        title = { Text("Edit contact") },
        text = {
            Column {
                Text(
                    contact.address,
                    color = PomboColors.TextDim, fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= 18) name = it },
                    placeholder = { Text("Local name (optional)", color = PomboColors.TextDim) },
                    colors = pomboFieldColors(), singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(name.trim().ifEmpty { null }) }) {
                Text("Save", color = PomboColors.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = PomboColors.TextDim)
            }
        }
    )
}

@Composable
private fun AddContactDialog(onDismiss: () -> Unit, onAdd: (String, String?) -> Unit) {
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    PomboDialogFrame("Add contact", onDismiss) {
        // addContact resolves an ENS name before saving, so what is stored is
        // always an address and survives the record changing later.
        PomboFieldLabel("ADDRESS OR ENS")
        PomboDialogField(address, { address = it }, placeholder = "0x… or name.eth")
        Spacer(Modifier.height(12.dp))

        PomboFieldLabel("NICKNAME (OPTIONAL)")
        PomboDialogField(name, { name = it }, placeholder = "e.g. Alice")

        Spacer(Modifier.height(20.dp))
        PomboPrimaryButton("Add", enabled = address.isNotBlank()) {
            onAdd(address, name.ifEmpty { null })
        }
    }
}
