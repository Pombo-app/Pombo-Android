package com.pombo.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pombo.android.ui.theme.PomboColors

/** The app's on/off switch (web `.pombo-switch`): tinted accent track and a white knob when on. */
@Composable
fun PomboSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(9.dp)
    val track by animateColorAsState(if (checked) PomboColors.Accent.copy(alpha = 0.22f) else PomboColors.Overlay8, label = "switch-track")
    val edge by animateColorAsState(if (checked) PomboColors.Accent.copy(alpha = 0.45f) else PomboColors.Overlay10, label = "switch-edge")
    val knob by animateColorAsState(if (checked) Color.White else PomboColors.Overlay40, label = "switch-knob")
    val shift by animateDpAsState(if (checked) 14.dp else 0.dp, label = "switch-shift")
    Box(
        modifier
            .size(width = 32.dp, height = 18.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(track)
            .border(1.dp, edge, shape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange
            )
    ) {
        Box(
            Modifier.align(Alignment.CenterStart)
                .padding(start = 3.dp)
                .offset(x = shift)
                .size(12.dp)
                .background(knob, CircleShape)
        )
    }
}
