package com.jake.duolauncher

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Only our own window is captured, once on entry. No second AppWidgetHostView is created. */
internal suspend fun captureEditorBackdrop(activity: Activity): Bitmap? = suspendCancellableCoroutine { continuation ->
    val view = activity.window.decorView
    if (view.width <= 0 || view.height <= 0) { continuation.resume(null); return@suspendCancellableCoroutine }
    // A small source is enough for the deliberately deep blur and limits memory on foldables.
    val bitmap = Bitmap.createBitmap((view.width / 3).coerceAtLeast(1), (view.height / 3).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(activity.window, bitmap, { result ->
            if (continuation.isActive) continuation.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
        }, Handler(Looper.getMainLooper()))
    } catch (_: IllegalArgumentException) { if (continuation.isActive) continuation.resume(null) }
}

@Composable
internal fun BoxScope.DesktopEditorChrome(page: Int, pageCount: Int, onPage: (Int) -> Unit,
    onChromeHeight: (Int) -> Unit,
    onWidgets: () -> Unit, onWallpaper: () -> Unit, onSettings: () -> Unit, onDone: () -> Unit) {
    Text(stringResource(R.string.editor_title), color = Color.White, fontSize = 24.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(24.dp).testTag("editor-title"))
    Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { onChromeHeight(it.height) }.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)
        .widthIn(max = 520.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            repeat(pageCount) { index ->
                Box(Modifier.size(28.dp).clickable { onPage(index) }.testTag("editor-page-$index"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (index == page) 7.dp else 5.dp)
                        .background(Color.White.copy(alpha = if (index == page) 1f else .4f), CircleShape))
                }
            }
        }
        Text(stringResource(R.string.editor_swipe), color = Color.White.copy(alpha = .75f), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().duoGlass(DuoGlassRole.Dock, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp).testTag("editor-toolbar")) {
            EditorTool(Icons.Outlined.GridView, R.string.editor_widgets, "editor-widgets", onWidgets)
            EditorTool(Icons.Outlined.Image, R.string.editor_wallpaper, "editor-wallpaper", onWallpaper)
            EditorTool(Icons.Outlined.Settings, R.string.editor_settings, "editor-settings", onSettings)
            EditorTool(Icons.Outlined.CheckCircleOutline, R.string.editor_done, "editor-done", onDone)
        }
    }
}

@Composable
private fun RowScope.EditorTool(icon: ImageVector, label: Int, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag(tag)) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            Text(stringResource(label), color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** Lightweight neighbouring-page context. No live provider views or drag targets are duplicated. */
@Composable
internal fun EditorNeighbourPreview(state: LauncherState, page: Int, direction: Int,
    currentScale: Float, currentShift: Float, onClick: () -> Unit) {
    val apps = state.apps.associateBy { it.id }
    val slots = if (page >= state.homePages) state.apps.take(HOME_CELLS).map { it.id }
        else (0 until HOME_CELLS).map { state.layout.slotAt(homeCellIndex(page, it)) }
    Box(Modifier.fillMaxSize().graphicsLayer {
        // Derive both size and position from the live page, including short-window fitting.
        // The selected page must remain the largest at every posture and animation frame.
        val neighbourScale = currentScale * .85f
        scaleX = neighbourScale; scaleY = neighbourScale
        translationX = size.width * ((currentScale + neighbourScale) / 2f + .045f) * direction
        translationY = size.height * currentShift
        alpha = .48f
    }.clip(RoundedCornerShape(28.dp)).border(1.dp, Color.White.copy(alpha = .6f), RoundedCornerShape(28.dp))
        .clickable(onClick = onClick).blur(2.dp).testTag("editor-neighbour-$direction")) {
        DuneWallpaper(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            slots.chunked(GRID_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { id ->
                        val app = apps[id]
                        Column(Modifier.width(62.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (app != null) {
                                Image(app.icon.asImageBitmap(), null, Modifier.size(54.dp))
                                Text(app.label, color = Color.White, fontSize = 10.sp, maxLines = 1)
                            } else Spacer(Modifier.size(54.dp))
                        }
                    }
                }
            }
        }
    }
}
