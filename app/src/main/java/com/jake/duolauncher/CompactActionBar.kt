package com.jake.duolauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Same two-icon glass treatment for shortcuts inside folders. */
@Composable
internal fun CompactAppActionBar(onInfo: () -> Unit, onRemove: () -> Unit, tag: String) {
    val shape = RoundedCornerShape(28.dp)
    Surface(Modifier.width(116.dp).duoGlass(DuoGlassRole.Floating, shape)
        .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape).testTag(tag),
        color = Color.Transparent, shape = shape) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onInfo, modifier = Modifier.size(48.dp).testTag("$tag-info")) {
                Icon(Icons.Rounded.Info, launcherText("App info"))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp).testTag("$tag-remove")) {
                Icon(Icons.Rounded.RemoveCircleOutline, launcherText("Remove from Home"),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
