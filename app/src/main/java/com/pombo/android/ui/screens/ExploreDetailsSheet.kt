package com.pombo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pombo.android.AppViewModel
import com.pombo.android.ExploreChannel
import com.pombo.android.ui.Avatar
import com.pombo.android.ui.ToastKind
import com.pombo.android.ui.theme.PomboColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The type a reader recognises: the gate mode decides for gated channels (web channelTypeLabel). */
internal fun exploreTypeLabel(type: String, gateMode: Int?): String = when {
    type == "password" -> "Password"
    type != "gated" -> "Open"
    gateMode == GateModes.PAID -> "Paid"
    gateMode == GateModes.NONE -> "Closed"
    else -> "Gated"
}

/** Everything the directory knows about a channel the viewer has not joined. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExploreDetailsSheet(
    vm: AppViewModel,
    ch: ExploreChannel,
    image: ByteArray?,
    ensNames: Map<String, String>,
    onDismiss: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val owner = ch.messageStreamId.substringBefore('/').lowercase()
    androidx.compose.runtime.LaunchedEffect(owner) { vm.ensureEns(owner) }
    val ownerName = ensNames[owner]
    val storageDays by androidx.compose.runtime.produceState<Int?>(null, ch.messageStreamId) {
        value = com.pombo.android.core.GraphApi.streamRetention(ch.messageStreamId)
    }
    val copy: (String, String) -> Unit = { value, what ->
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(value))
        vm.toast("$what copied", ToastKind.SUCCESS)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PomboColors.Surface8,
        dragHandle = { BottomSheetDefaults.DragHandle(color = PomboColors.Overlay20) }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = remember(image) {
                        android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size).asImageBitmap()
                    },
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(88.dp).clip(CircleShape)
                )
            } else {
                Avatar(ch.messageStreamId, size = 88.dp, cornerRadiusFraction = 0.5)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                ch.name, color = PomboColors.Overlay90, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
            )
            if (ch.description.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    ch.description, color = PomboColors.Overlay60, fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))

            DetailsRow("Type") { DetailsValue(exploreTypeLabel(ch.type, ch.gateMode)) }
            if (ch.type == "gated" && ch.gateVerb != null) {
                DetailsRow("Access") {
                    DetailsValue(listOfNotNull(ch.gateVerb, ch.gateValue, ch.gateQualifier).joinToString(" "))
                }
            }
            if (ch.type == "gated") {
                val sealed = ch.wireIdentity == "sealed"
                DetailsRow("Identity on the wire") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (sealed) Icons.Outlined.People else Icons.Outlined.Public,
                            contentDescription = null, tint = PomboColors.Overlay50,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        DetailsValue(if (sealed) "Sealed" else "Visible")
                    }
                }
            }
            storageDays?.let { days ->
                DetailsRow("Storage") { DetailsValue(if (days == 1) "1 day" else "$days days") }
            }
            DetailsRow("Owner") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ownerName != null) {
                        DetailsValue(ownerName)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        owner.take(6) + "…" + owner.takeLast(4),
                        color = if (ownerName != null) PomboColors.Overlay40 else PomboColors.Overlay90,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace
                    )
                    CopyIcon { copy(owner, "Address") }
                }
            }
            if (ch.createdAt > 0) {
                DetailsRow("Created") {
                    DetailsValue(SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(ch.createdAt)))
                }
            }
            DetailsRow("Channel ID", last = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ch.messageStreamId, color = PomboColors.Overlay90, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    CopyIcon { copy(ch.messageStreamId, "Channel ID") }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Flag, contentDescription = null,
                    tint = PomboColors.Overlay30, modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Report · Soon", color = PomboColors.Overlay30, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DetailsRow(label: String, last: Boolean = false, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = PomboColors.Overlay40, fontSize = 13.sp)
        Spacer(Modifier.width(16.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) { value() }
    }
    if (!last) HorizontalDivider(color = PomboColors.Overlay5, thickness = 1.dp)
}

@Composable
private fun DetailsValue(text: String) {
    Text(text, color = PomboColors.Overlay90, fontSize = 13.sp, textAlign = TextAlign.End)
}

@Composable
private fun CopyIcon(onClick: () -> Unit) {
    Spacer(Modifier.width(6.dp))
    Icon(
        Icons.Outlined.ContentCopy, contentDescription = "Copy",
        tint = PomboColors.Overlay40,
        modifier = Modifier.size(16.dp).clickableNoRipple(onClick)
    )
}
