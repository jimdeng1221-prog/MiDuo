package com.jake.duolauncher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same Floating material and 48dp controls as app/folder actions, not a modal sheet.
 * No provider ID, layout coordinates or resize values are changed just by opening it. */
@Composable
internal fun CompactWidgetActions(
    anchor: Rect?, canResize: Boolean, canConfigure: Boolean,
    onResize: () -> Unit, onConfigure: () -> Unit, onReplace: () -> Unit,
    onRemove: () -> Unit, onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val density=LocalDensity.current
    val safe=WindowInsets.safeDrawing.asPaddingValues()
    val direction=LocalLayoutDirection.current
    val left=safe.calculateLeftPadding(direction)+12.dp
    val right=safe.calculateRightPadding(direction)+12.dp
    val top=safe.calculateTopPadding()+12.dp
    val bottom=safe.calculateBottomPadding()+12.dp
    val width=if(canConfigure)264.dp else 204.dp
    var measuredHeight by remember { mutableStateOf(84.dp) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("widget-context-overlay")) {
        Box(Modifier.fillMaxSize().clickable(
            interactionSource=remember { MutableInteractionSource() },indication=null,onClick=onClose))
        val actualWidth=minOf(width,(maxWidth-left-right).coerceAtLeast(48.dp))
        val anchorX=anchor?.center?.x?.let { with(density){it.toDp()} } ?: maxWidth/2
        val anchorTop=anchor?.top?.let { with(density){it.toDp()} } ?: maxHeight/2
        val anchorBottom=anchor?.bottom?.let { with(density){it.toDp()} } ?: anchorTop
        val x=(anchorX-actualWidth/2).coerceIn(left,(maxWidth-actualWidth-right).coerceAtLeast(left))
        val above=anchorTop-measuredHeight-10.dp
        val y=(if(above>=top)above else anchorBottom+10.dp)
            .coerceIn(top,(maxHeight-measuredHeight-bottom).coerceAtLeast(top))
        val shape=RoundedCornerShape(28.dp)
        Surface(Modifier.offset(x,y).width(actualWidth)
            .onSizeChanged { measuredHeight=with(density){it.height.toDp()} }
            .duoGlass(DuoGlassRole.Floating,shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha=.32f),shape)
            .testTag("widget-context-menu"),shape=shape,color=Color.Transparent,
            contentColor=MaterialTheme.colorScheme.onSurface) {
            Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                WidgetAction(Icons.Rounded.OpenInFull,stringResource(R.string.legacy_ui_79),"widget-context-resize",canResize,onResize,Modifier.weight(1f))
                if(canConfigure)WidgetAction(Icons.Rounded.Settings,stringResource(R.string.compact_widget_settings),"widget-context-settings",true,onConfigure,Modifier.weight(1f))
                WidgetAction(Icons.Rounded.FindReplace,stringResource(R.string.legacy_ui_9),"widget-context-replace",true,onReplace,Modifier.weight(1f))
                WidgetAction(Icons.Rounded.DeleteOutline,stringResource(R.string.legacy_ui_8),"widget-context-remove",true,onRemove,Modifier.weight(1f),MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun WidgetAction(icon: ImageVector,label: String,tag: String,enabled: Boolean,onClick: ()->Unit,
    modifier: Modifier,tint: Color=LocalContentColor.current) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(48.dp).testTag(tag)) {
            Icon(icon,label,Modifier.size(22.dp),tint=if(enabled)tint else tint.copy(alpha=.38f))
        }
        Text(label,fontSize=10.sp,lineHeight=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,
            color=if(enabled)tint else tint.copy(alpha=.38f))
    }
}
