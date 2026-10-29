package com.pombo.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pombo.android.ui.theme.PomboColors

/** What each author badge claims. Mirrored verbatim from the web (ChatAreaUI.js AUTHOR_BADGE_TEXT). */
enum class AuthorBadgeKind(val title: String, val body: String) {
    VERIFIED(
        "Verified author",
        "This message carries a proof bound to the account shown. No one can post under someone else's account."
    ),
    ENS(
        "Verified author · ENS",
        "This message carries a proof bound to the account shown, and the name is that account's ENS name. No one can post under someone else's account."
    ),
    CONTACT(
        "Verified author · Trusted contact",
        "This message carries a proof bound to the account shown, which you marked as a trusted contact. No one can post under someone else's account."
    ),
    FAILED(
        "Could not verify",
        "This message is dated more than 5 minutes ahead of your clock. It may be replayed, or the sender's clock is wrong."
    )
}

/** The badge beside an author's name; a tap explains what it vouches for. */
@Composable
fun AuthorBadge(kind: AuthorBadgeKind) {
    var open by remember { mutableStateOf(false) }
    Box {
        val tap = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { open = true }
        when (kind) {
            AuthorBadgeKind.ENS -> Icon(
                Icons.Filled.CheckCircleOutline, contentDescription = kind.title,
                tint = PomboColors.Success, modifier = tap.size(14.dp)
            )
            AuthorBadgeKind.CONTACT -> Icon(
                PomboIcons.StarOutline, contentDescription = kind.title,
                tint = PomboColors.Success, modifier = tap.size(14.dp)
            )
            AuthorBadgeKind.VERIFIED -> Text(
                "✓", color = PomboColors.Success, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = tap
            )
            AuthorBadgeKind.FAILED -> Text("❌", fontSize = 11.sp, modifier = tap)
        }
        if (open) PomboAnchoredMenu(onDismiss = { open = false }) {
            Column(Modifier.widthIn(max = 260.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(
                    kind.title, color = PomboColors.Overlay90,
                    fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold
                )
                Text(kind.body, color = PomboColors.Overlay70, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}
