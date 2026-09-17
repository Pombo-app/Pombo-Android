package com.pombo.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pombo.android.AppViewModel
import com.pombo.android.ui.theme.PomboColors

/**
 * The wallet: what this account holds on Polygon, reached from the header
 * beside the bell. Web parity with the wallet modal — balances only. The
 * address, the key and account actions stay with the account.
 *
 * A token's symbol is text its author chose, so it is capped and shown beside
 * the address, which is the only identity a token really has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    val rows by vm.walletRows.collectAsState()
    val addError by vm.walletAddError.collectAsState()
    val address by vm.address.collectAsState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var adding by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshWallet() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PomboColors.SurfaceHigh,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.20f)) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Wallet",
                color = PomboColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Outlined.Refresh, contentDescription = "Refresh balances",
                tint = Color.White.copy(alpha = 0.40f),
                modifier = Modifier.size(18.dp).clickableNoRipple { vm.refreshWallet() }
            )
        }

        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.symbol.ifEmpty { "…" },
                            color = PomboColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (row.kind != AppViewModel.WalletRow.Kind.BASE && row.address != null) {
                            Text(
                                shortToken(row.address),
                                color = Color.White.copy(alpha = 0.30f),
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    when {
                        row.failed -> Text(
                            "Unavailable",
                            color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp
                        )
                        row.amount == null -> Text(
                            "···",
                            color = Color.White.copy(alpha = 0.25f), fontSize = 14.sp
                        )
                        else -> Text(
                            row.amount,
                            color = PomboColors.Text, fontSize = 14.sp
                        )
                    }
                    if (row.kind == AppViewModel.WalletRow.Kind.CUSTOM && row.address != null) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Outlined.Close, contentDescription = "Remove token",
                            tint = Color.White.copy(alpha = 0.30f),
                            modifier = Modifier.size(16.dp)
                                .clickableNoRipple { vm.removeWalletToken(row.address) }
                        )
                    }
                }
            }
        }

        if (!adding) {
            Row(
                Modifier.fillMaxWidth()
                    .clickableNoRipple { vm.clearWalletAddError(); adding = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Add, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.40f), modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Add token", color = Color.White.copy(alpha = 0.40f), fontSize = 13.sp)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f)
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (tokenInput.isEmpty()) {
                        Text(
                            "Token address (0x…)",
                            color = Color.White.copy(alpha = 0.25f),
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    BasicTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it; vm.clearWalletAddError() },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = PomboColors.Text, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(PomboColors.Accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                        .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                        .clickableNoRipple { vm.addWalletToken(tokenInput) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text("Add", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Close, contentDescription = "Cancel",
                    tint = Color.White.copy(alpha = 0.30f),
                    modifier = Modifier.size(18.dp).clickableNoRipple {
                        adding = false; tokenInput = ""; vm.clearWalletAddError()
                    }
                )
            }
        }

        // The panel closes the add row once the token lands, which is the only
        // signal the caller gets that the address was accepted.
        LaunchedEffect(rows) {
            if (adding && tokenInput.isNotEmpty() &&
                rows.any { it.address?.equals(tokenInput.trim(), ignoreCase = true) == true }
            ) {
                adding = false
                tokenInput = ""
            }
        }

        addError?.let {
            Text(
                it,
                color = Color(0xFFF87171), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
            )
        }

        Spacer(Modifier.height(6.dp))
        // Web talks to the injected extension; here the MetaMask app has no
        // usable send deep link (its /send route demands a chain id it then
        // fails to match), so the address goes to the clipboard and the app
        // opens on its own screen.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .background(PomboColors.Accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .border(1.dp, PomboColors.Accent.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                .clickableNoRipple {
                    address?.let { addr ->
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(addr))
                        vm.toast(
                            "Address copied — paste it in MetaMask's Send",
                            ToastKind.SUCCESS, 5000L
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
                tint = Color.White, modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Fund with MetaMask", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** Web surfaces have no ripple; matches the helper the screens already use. */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick
)

private fun shortToken(address: String) =
    "${address.take(6)}…${address.takeLast(4)}"
