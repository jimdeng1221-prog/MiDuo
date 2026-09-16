@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow

@Composable
internal fun AppLibrary(
    state: LauncherState, query: String, onQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit, onPin: (String, Boolean) -> Unit, onActions: (AppEntry) -> Unit,
    modifier: Modifier = Modifier, editing: Boolean = false,
    drag: HomeDragState? = null, page: Int? = null,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    onTurnOnWork: (Long) -> Unit = {},
) {
    val glass = !editing
    val palette = LocalDuoPalette.current
    val ink = if (glass) duoGlassContentColor(DuoGlassRole.Elevated) else MaterialTheme.colorScheme.onSurface
    val pinned = remember(state.homeSlots, state.leadingSlots) {
        (state.homeSlots.asSequence() + state.leadingSlots.asSequence()).filterNotNull().toSet()
    }
    val hasWork = state.profiles.any { it.isWork } || state.apps.any { it.isWork }
    var showWork by remember { mutableStateOf(false) }
    val listState = rememberLazyGridState()
    val selectedProfile = if (showWork) state.profiles.firstOrNull { it.isWork } else state.profiles.firstOrNull { it.isPersonal }
    LaunchedEffect(showWork, selectedProfile?.available, selectedProfile?.quiet) {
        listState.scrollToItem(0)
    }
    val visibleApps = remember(state.apps, query, showWork, hasWork) {
        state.apps.filter { (!hasWork || it.isWork == showWork) && it.label.contains(query.trim(), true) }
    }
    val panelShape = RoundedCornerShape(24.dp)
    val panelModifier = if (glass) modifier.duoGlass(DuoGlassRole.Elevated, panelShape) else modifier
    Surface(panelModifier, shape = panelShape,
        color = if (glass) Color.Transparent else MaterialTheme.colorScheme.surface,
        contentColor = ink) {
        Column(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (editing) launcherText("Add to Home") else launcherText("All apps"), Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text("${visibleApps.size}", color = ink, fontSize = 10.sp)
            }
            if (hasWork) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showWork, onClick = { showWork = false }, label = { Text(launcherText("Personal")) })
                FilterChip(selected = showWork, onClick = { showWork = true }, label = { Text(launcherText("Work")) })
            }
            val searchLabel = launcherText("Search apps")
            BasicTextField(query, onQuery, singleLine = true, textStyle = TextStyle(color = ink, fontSize = 12.sp),
                cursorBrush = SolidColor(ink), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .heightIn(min = 44.dp).testTag(if (editing) "pin-search" else "library-search")
                    .semantics { contentDescription = searchLabel },
                decorationBox = { inner ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(13.dp))
                        .background(ink.copy(alpha = .05f)).border(1.dp, ink.copy(alpha = .18f), RoundedCornerShape(13.dp))
                        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text(searchLabel, color = ink.copy(alpha = .7f), fontSize = 12.sp)
                            inner()
                        }
                        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Close, launcherText("Clear search"), Modifier.size(18.dp))
                        }
                    }
                })
            LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.weight(1f).testTag("all-apps-list"), state = listState,
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp)) {
                if (showWork && selectedProfile?.available == false) item("work-paused", span = { GridItemSpan(4) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(launcherFormat(if (selectedProfile.quiet) R.string.ui_profile_paused else R.string.ui_profile_unavailable, launcherText("Work")))
                        if (selectedProfile.quiet) Button(onClick = { onTurnOnWork(selectedProfile.userSerial) },
                            Modifier.padding(top = 10.dp).testTag("turn-on-work")) { Text(launcherText("Turn on work apps")) }
                    }
                }
                if (visibleApps.isEmpty()) item(span = { GridItemSpan(4) }) { Text(if (state.loading) launcherFormat(R.string.ui_loading_apps) else launcherText("No apps found"), Modifier.padding(vertical = 20.dp)) }
                    items(visibleApps, key = { it.id }) { app ->
                        val isPinned = app.id in pinned
                        val launchBounds = remember { android.graphics.Rect() }
                        val dragModifier = if (drag != null) Modifier.dropRegion(drag, DropTarget.Library(app.id), app.id, page) else Modifier
                        val click = { if (editing) onPin(app.id, !isPinned) else onLaunchFrom(app, launchBounds) }
                        Column(Modifier.fillMaxWidth().heightIn(min = 82.dp).then(dragModifier).clip(RoundedCornerShape(14.dp)).testTag("library-app-${app.id}")
                            .then(if (drag == null) Modifier.combinedClickable(onClick = click, onLongClick = { onActions(app) })
                                else Modifier.clickable(onClick = click).semantics { onLongClick(launcherFormat(R.string.app_options)) { onActions(app); true } })
                            .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(app.icon.asImageBitmap(), null, Modifier.size(dockIconSize().dp).testTag("library-icon-${app.id}")
                                .onGloballyPositioned { launchBounds.set(it.boundsInWindow().toAndroidBounds()) }.clip(RoundedCornerShape(10.dp)))
                            Text(app.label, Modifier.fillMaxWidth().padding(top = 5.dp), maxLines = 2,
                                fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                            if (editing) IconButton(onClick = { onPin(app.id, !isPinned) }, Modifier.testTag("pin-${app.id}")) {
                                Icon(if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                                    launcherFormat(if (isPinned) R.string.unpin_app else R.string.pin_app, app.label),
                                    tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    }
            }
        }
    }
}
