@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

internal const val FOLDER_PAGE_CAPACITY = 9

internal fun folderPageCount(appCount: Int): Int =
    ((appCount.coerceAtLeast(1) - 1) / FOLDER_PAGE_CAPACITY) + 1

@Composable
internal fun FolderPanel(
    folder: FolderEntry, apps: Map<String, AppEntry>, drag: HomeDragState, page: Int,
    homeDestinations: List<Int>, dockVacancies: List<Int>, onDismiss: () -> Unit,
    onRename: (String) -> Unit, onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onMoveOut: (String, DropTarget) -> Unit,
    onAppInfo: (AppEntry) -> Unit = {},
) {
    var title by rememberSaveable(folder.id) { mutableStateOf(folder.title) }
    var editing by rememberSaveable(folder.id) { mutableStateOf(false) }
    var shown by remember(folder.id) { mutableStateOf(false) }
    LaunchedEffect(folder.id) { shown = true }
    BackHandler { if (editing) editing = false else onDismiss() }
    DisposableEffect(drag, folder.id) {
        drag.activeSourceScope = folder.id
        onDispose { if (drag.activeSourceScope == folder.id) drag.activeSourceScope = null }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .14f))
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = launcherFormat(R.string.folder_close),
            onClick = onDismiss,
        )
        .imePadding().testTag("folder-panel"),
        contentAlignment = Alignment.Center) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            // Match Apple's folder interaction: a stable 3x3 glass surface with horizontally
            // paged groups. App count never changes the popup's dimensions.
            val columns = 3
            val panelSize = minOf(288.dp, maxWidth - 32.dp, maxHeight - 32.dp)
            val pageCount = folderPageCount(folder.appIds.size)
            val pagerState = rememberPagerState(pageCount = { pageCount })
            LaunchedEffect(pageCount) {
                if (pagerState.currentPage >= pageCount) pagerState.scrollToPage(pageCount - 1)
            }
            AnimatedVisibility(
                visible = shown,
                enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .94f),
            ) {
                val panelShape = RoundedCornerShape(34.dp)
                Surface(Modifier.size(panelSize)
                    .duoGlass(DuoGlassRole.Elevated, panelShape)
                    .background(Color.White.copy(alpha = .055f), panelShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .testTag("folder-panel-content"),
                    color = Color.Transparent, contentColor = duoGlassContentColor(DuoGlassRole.Elevated),
                    shape = panelShape) {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
                    if (editing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(title, { title = it }, Modifier.weight(1f).testTag("folder-name"),
                                singleLine = true, label = { Text(launcherText("Folder name"), fontSize = 11.sp) },
                                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, lineHeight = 19.sp,
                                    fontWeight = FontWeight.Normal))
                            TextButton(onClick = {
                                title.trim().takeIf(String::isNotEmpty)?.let(onRename)
                                editing = false
                            }) { Text(launcherText("Done"), fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            val contentColor = LocalContentColor.current
                            Text(folder.title, Modifier.fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { title = folder.title; editing = true }
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                                .semantics { contentDescription = launcherFormat(R.string.compact_rename_folder) }
                                .testTag("folder-rename"),
                                color = contentColor, fontSize = 17.sp, lineHeight = 21.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("folder-pager"),
                        beyondViewportPageCount = 1,
                        key = { it },
                    ) { folderPage ->
                        val pageAppIds = folder.appIds.drop(folderPage * FOLDER_PAGE_CAPACITY)
                            .take(FOLDER_PAGE_CAPACITY)
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize().padding(top = 6.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            userScrollEnabled = false,
                        ) {
                            items(pageAppIds, key = { it }) { appId ->
                                apps[appId]?.let { app ->
                                    FolderChild(app, folder.id, drag, page,
                                        onLaunch = onLaunch, onMoveOut = onMoveOut, onAppInfo = onAppInfo)
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().height(18.dp).testTag("folder-page-indicator"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(pageCount) { index ->
                            val selected = pagerState.currentPage == index
                            Box(Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (selected) 6.dp else 5.dp)
                                .clip(CircleShape)
                                .background(LocalContentColor.current.copy(alpha = if (selected) .82f else .28f))
                                .testTag("folder-page-dot-$index"))
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun FolderChild(
    app: AppEntry, folderId: String, drag: HomeDragState, page: Int,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onMoveOut: (String, DropTarget) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val contentColor = LocalContentColor.current
    Box(Modifier.fillMaxWidth().height(68.dp).testTag("folder-child-${app.id}")) {
        Column(Modifier.fillMaxSize()
            .dropRegion(drag, DropTarget.Library(app.id), app.id, page,
                folderId = folderId, scope = folderId)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (app.available) launcherFormat(R.string.folder_open_app, app.label) else null,
                onLongClickLabel = launcherFormat(R.string.folder_app_actions, app.label),
                onClick = { if (app.available) onLaunch(app, null) },
                onLongClick = { if (activity?.requireFeatureLicense() == true) menu = true },
            )
            .semantics { contentDescription = app.label + if (app.available) "" else ", " + launcherFormat(R.string.ui_unavailable) }
            .padding(horizontal = 3.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Image(app.icon.asImageBitmap(), null,
                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            Text(app.label, Modifier.fillMaxWidth().padding(top = 4.dp),
                color = contentColor, fontSize = 10.sp, lineHeight = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .3f), Offset(0f, 1f), 2f)))
            if (app.isWork || !app.available) Text(
                if (app.available) launcherText(app.profileLabel) else launcherFormat(R.string.ui_unavailable),
                color = contentColor.copy(alpha = .62f), fontSize = 8.sp, lineHeight = 9.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menu) Popup(alignment = Alignment.TopCenter,
            offset = IntOffset(0, with(LocalDensity.current) { (-68).dp.roundToPx() }),
            onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
            CompactAppActionBar(
                onInfo = { menu = false; onAppInfo(app) },
                onRemove = { menu = false; onMoveOut(app.id, DropTarget.Remove) },
                tag = "folder-child-menu")
        }
    }
}
