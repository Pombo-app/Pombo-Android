package com.pombo.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pombo.android.ui.theme.PomboColors

/** The states of a diagnose-and-repair card (web repairStatusCard.js). */
enum class RepairCardState { IDLE, LOADING, HEALTHY, ISSUES, FAILED, REPAIRING, REPAIRED }

/**
 * The status card shared by the diagnose-and-repair panels (DM inbox,
 * Channel Health): one icon and colour per state.
 */
@Composable
fun RepairStatusCard(state: RepairCardState, title: String, detail: String, onCheckAgain: (() -> Unit)? = null) {
    val accent = when (state) {
        RepairCardState.IDLE -> PomboColors.Overlay20
        RepairCardState.LOADING -> PomboColors.Overlay40
        RepairCardState.HEALTHY, RepairCardState.REPAIRED -> PomboColors.Success
        RepairCardState.ISSUES -> Color(0xFFFACC15)
        RepairCardState.FAILED -> PomboColors.Danger
        RepairCardState.REPAIRING -> PomboColors.Accent
    }
    Row(
        Modifier.fillMaxWidth()
            .background(PomboColors.Overlay3, RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(32.dp).background(accent.copy(alpha = 0.10f), CircleShape), contentAlignment = Alignment.Center) {
            when (state) {
                RepairCardState.LOADING, RepairCardState.REPAIRING ->
                    CircularProgressIndicator(color = accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                RepairCardState.HEALTHY, RepairCardState.REPAIRED ->
                    Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                RepairCardState.ISSUES ->
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                RepairCardState.FAILED ->
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                RepairCardState.IDLE ->
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = PomboColors.Overlay70, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(detail, color = PomboColors.Overlay30, fontSize = 12.sp, lineHeight = 16.sp)
            if (onCheckAgain != null) {
                Spacer(Modifier.height(6.dp))
                Text("Check again", color = PomboColors.Overlay50, fontSize = 12.sp, modifier = Modifier.clickableNoRipple(onCheckAgain))
            }
        }
    }
}
