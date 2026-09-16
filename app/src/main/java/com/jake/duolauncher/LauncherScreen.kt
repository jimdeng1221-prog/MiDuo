@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher

import android.appwidget.AppWidgetProviderInfo
import android.os.UserManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.core.view.ViewCompat
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

internal val Ink: Color
    @Composable get() = LocalDuoPalette.current.ink
internal val Glass: Color
    @Composable get() = LocalDuoPalette.current.glass

private data class WindowCutoutSnapshot(
    val statusColumnCutoutDepthPx: Int = 0,
    val bounds: List<IntRect> = emptyList(),
)

private fun readWindowCutout(context: android.content.Context, view: android.view.View): WindowCutoutSnapshot {
    val platformCutout = context.getSystemService(WindowManager::class.java)
        ?.currentWindowMetrics?.windowInsets?.displayCutout
    val compatCutout = ViewCompat.getRootWindowInsets(view)?.displayCutout
    val bounds = (platformCutout?.boundingRects ?: compatCutout?.boundingRects.orEmpty())
        .map { IntRect(it.left, it.top, it.right, it.bottom) }
    val windowWidthPx = platformCutout?.let {
        context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds?.width()
    }?.takeIf { it > 0 } ?: view.width
    // Only a cutout entering the right-most quarter can obstruct the status column.
    // A centred cover-screen camera therefore leaves the status high, while a top-right
    // inner-screen camera moves it down. This also adapts to Samsung without model constants.
    val statusDepthPx = bounds.filter { windowWidthPx > 0 && it.right > windowWidthPx * .75f }
        .maxOfOrNull { it.bottom.coerceAtLeast(it.height) } ?: 0
    return WindowCutoutSnapshot(statusColumnCutoutDepthPx = statusDepthPx, bounds = bounds)
}

private fun findFreeWidgetIndex(layout: HomeLayout, page: Int, spanX: Int, spanY: Int): Int? {
    val blocked = layout.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }
    for (row in 0..widgetRowsForPage(page) - spanY) for (column in 0..GRID_COLUMNS - spanX) {
        val cells = buildList {
            repeat(spanY) { y -> repeat(spanX) { x -> add(homeCellIndex(page, (row + y) * GRID_COLUMNS + column + x)) } }
        }
        if (cells.none { it in blocked || layout.slotAt(it) != null }) return cells.first()
    }
    return null
}

@Composable
fun DuoTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val palette = if (dark) DarkDuoPalette else LightDuoPalette
    CompositionLocalProvider(LocalDuoPalette provides palette) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF9BC5D7), onPrimary = Color(0xFF12303D),
            surface = Color(0xFF17272E), onSurface = palette.ink, secondary = Color(0xFFD1BE98),
            secondaryContainer = Color(0xFF314852), onSecondaryContainer = palette.ink)
        else lightColorScheme(primary = Color(0xFF30596D), onPrimary = Color.White,
            surface = Color(0xFFF4F7F8), onSurface = palette.ink, secondary = Color(0xFF84775F),
            secondaryContainer = Color(0xFFDCE8ED), onSecondaryContainer = palette.ink), content = content)
    }
}


@Composable
fun LauncherScreen(
    state: LauncherState, model: LauncherModel, widgets: WidgetController, homeRequests: Int,
    onLaunch: (AppEntry) -> Unit, onMakeDefault: () -> Unit, onAppInfo: (AppEntry) -> Unit,
    isDefaultHome: Boolean, deviceStatus: DeviceStatus, onStatusMode: (Boolean) -> Unit, onWallpaperPreview: () -> Unit,
    searchRequests: Int = 0,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    onGoogleSearch: (android.graphics.Rect?) -> Boolean = { false },
    appearance: AppearanceState = AppearanceState(),
    onAppearanceMode: (AppearanceMode) -> Unit = {},
    onAppearanceManual: (String, Double, Double) -> Unit = { _, _, _ -> },
    onAppearanceDeviceLocation: () -> Unit = {},
    onAppearanceClear: () -> Unit = {},
    showFirstRun: Boolean = false,
    onFinishFirstRun: () -> Unit = {},
    onShadeSetup: () -> Unit = {},
) {
    val license = (androidx.activity.compose.LocalActivity.current as MainActivity).licensing
    val savedSheet = rememberSaveable { mutableStateOf("") }
    var sheet by remember(savedSheet, license) { LicensedSheetState(savedSheet) { license.activated } }
    var dockSlot by rememberSaveable { mutableIntStateOf(0) }
    var widgetSlot by rememberSaveable { mutableIntStateOf(0) }
    var widgetTargetIndex by rememberSaveable { mutableIntStateOf(Int.MIN_VALUE) }
    var widgetExactTarget by rememberSaveable { mutableStateOf(false) }
    var widgetPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var widgetProfileSerial by rememberSaveable { mutableStateOf<Long?>(null) }
    var widgetSession by remember { mutableStateOf<WidgetPickerSession?>(null) }
    var widgetPlacementMessage by remember { mutableStateOf<String?>(null) }
    var emptyCellIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var desktopEditing by rememberSaveable { mutableStateOf(false) }
    var editorBackdrop by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var resizeSlot by remember { mutableStateOf<Int?>(null) }
    var resizeWidth by rememberSaveable { mutableIntStateOf(1) }
    var resizeHeight by rememberSaveable { mutableIntStateOf(1) }
    var resizeConstraints by remember { mutableStateOf<WidgetSpanConstraints?>(null) }
    var resizePitchX by remember { mutableFloatStateOf(1f) }
    var resizePitchY by remember { mutableFloatStateOf(1f) }
    var resizeTopPitch by remember { mutableFloatStateOf(1f) }
    var resizeAppPitch by remember { mutableFloatStateOf(1f) }
    val savedSelection = rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by remember(savedSelection, license) {
        LicensedSelectionState(savedSelection, { license.activated }, { sheet = "activation" })
    }
    var appMoveMenu by rememberSaveable { mutableStateOf(false) }
    var appMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    var customizationPage by rememberSaveable { mutableStateOf(CustomizationPage.OVERVIEW) }
    LaunchedEffect(selectedId) { if (selectedId == null) appMoveMenu = false }
    LaunchedEffect(sheet) { if (sheet.isEmpty()) customizationPage = CustomizationPage.OVERVIEW }
    var openFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var createFolderFirstId by rememberSaveable { mutableStateOf<String?>(null) }
    // Cover positions: components=0, Home 1=1, Home 2=2, ... . Expanded positions
    // identify the right home: components + Home 1=0, Home 1 + Home 2=1, ... .
    var savedPhysicalPage by rememberSaveable(key = "duo.physical.spread.start.v3") {
        mutableIntStateOf(1)
    }
    var savedPagePosture by rememberSaveable(key = "duo.physical.spread.posture.v3") {
        // A fresh session starts conceptually from cover Home 1. If the app first
        // composes while open, posture remapping produces components + Home 1.
        mutableIntStateOf(1)
    }
    var lastHomePage by rememberSaveable { mutableIntStateOf(0) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var pinQuery by rememberSaveable { mutableStateOf("") }
    val launcherActivity = androidx.activity.compose.LocalActivity.current as MainActivity
    val launcherRootView = LocalView.current.rootView
    LaunchedEffect(emptyCellIndex) {
        if (emptyCellIndex != null) {
            if (!desktopEditing) editorBackdrop = captureEditorBackdrop(launcherActivity)
            desktopEditing = true
            emptyCellIndex = null
        }
    }
    val windowSize = LocalWindowInfo.current.containerSize
    val windowDensity = LocalDensity.current
    var editorChromeHeight by remember { mutableIntStateOf(0) }
    val editorHeight = windowSize.height.toFloat().coerceAtLeast(1f)
    val editorTop = with(windowDensity) { 72.dp.toPx() }
    val editorBottom = editorHeight - editorChromeHeight - with(windowDensity) { 8.dp.toPx() }
    val editorTargetScale = minOf(.68f, ((editorBottom - editorTop) / editorHeight).coerceAtLeast(.25f))
    val editorHalfHeight = editorHeight * editorTargetScale / 2f
    val editorTargetCenter = (editorHeight * .435f).coerceIn(editorTop + editorHalfHeight,
        maxOf(editorTop + editorHalfHeight, editorBottom - editorHalfHeight))
    val editorScale by animateFloatAsState(if (desktopEditing) editorTargetScale else 1f,
        animationSpec = LauncherMotion.settle, label = "editor-scale")
    val editorShift by animateFloatAsState(if (desktopEditing) editorTargetCenter / editorHeight - .5f else 0f,
        animationSpec = LauncherMotion.settle, label = "editor-shift")
    // The leading component page is a pager page on the cover and the left pane of
    // the first unfolded spread, so only the cover needs a physical page before Home.
    val windowCutout = readWindowCutout(launcherActivity, launcherRootView)
    val appsById = remember(state.apps) { state.apps.associateBy { it.id } }
    val drag = remember { HomeDragState() }
    LaunchedEffect(launcherActivity.activationRequests.intValue) {
        if (launcherActivity.activationRequests.intValue > 0 && !license.activated) {
            drag.clear(); openFolderId = null; selectedId = null
            renameFolderId = null; createFolderFirstId = null
            sheet = "activation"
        }
    }
    LaunchedEffect(drag.moved) { if (drag.moved) selectedId = null }
    val haptic = LocalHapticFeedback.current
    val glassBackdrop = rememberHazeState()
    val editorGlassBackdrop = rememberHazeState()
    val workspaceBackdrop = rememberHazeState()
    val liveDesktopBackdrop = rememberHazeState()
    SideEffect {
        // Never flatten soft-light glass to Haze's fallback tint during motion.
        glassBackdrop.blurEnabled = duoGlassBackdropEnabled()
    }
    val homePages = state.homePages
    val pendingNewPage = widgets.pendingPlacement?.page == homePages
    val visibleHomePages = homePages + if (drag.active || widgetSession != null || pendingNewPage) 1 else 0
    var expandedWorkspace by remember { mutableStateOf(false) }
    val firstHome = if (with(windowDensity) { windowSize.width.toDp().value } >= 650f) 0 else 1
    val pageCount = visibleHomePages + 1
    val initialPhysicalPage = remapPhysicalPageForPosture(
        savedPhysicalPage,
        previousFirstHome = savedPagePosture.takeIf { it >= 0 } ?: firstHome,
        nextFirstHome = firstHome,
        pageCount = pageCount + firstHome,
    )
    val nativePager = rememberPagerState(
        initialPage = initialPhysicalPage,
        pageCount = { pageCount + firstHome },
    )
    val pager = remember(nativePager, firstHome) { LauncherPager(nativePager, firstHome) }
    var previousFirstHome by remember {
        mutableIntStateOf(restoredPagePosture(savedPagePosture, firstHome))
    }
    LaunchedEffect(firstHome) {
        if (firstHome != previousFirstHome) {
            val sourcePage = savedPhysicalPage
            val targetPage = remapPhysicalPageForPosture(
                sourcePage, previousFirstHome, firstHome, nativePager.pageCount)
            savedPhysicalPage = targetPage
            savedPagePosture = firstHome
            nativePager.scrollToPage(targetPage)
            previousFirstHome = firstHome
        } else {
            savedPagePosture = firstHome
        }
    }
    fun leaveTemporaryWidgetPage() {
        val persistedPages = model.state.value.homePages
        if (pager.currentPage >= persistedPages)
            pager.requestScrollToPage((persistedPages - 1).coerceAtLeast(0))
    }
    var priorPendingPlacement by remember { mutableStateOf<WidgetPlacement?>(null) }
    LaunchedEffect(widgets.pendingPlacement, state.layout) {
        val pending = widgets.pendingPlacement
        if (pending != null) priorPendingPlacement = pending
        else priorPendingPlacement?.let { prior ->
            if (model.placement(prior.slot) == null && prior.page >= homePages) leaveTemporaryWidgetPage()
            priorPendingPlacement = null
        }
    }
    val pageGestures = remember(nativePager) { PageGestureLimits(nativePager) }
    var edgeVisual by remember { mutableStateOf(WorkspaceEdgeVisual.Rest) }
    val edgeTranslation by animateFloatAsState(
        targetValue = edgeVisual.translationPx,
        animationSpec = if (edgeVisual.progress > 0f) snap() else LauncherMotion.edgeReturn,
        label = "workspace edge translation",
    )
    val edgeScale by animateFloatAsState(
        targetValue = edgeVisual.scale,
        animationSpec = if (edgeVisual.progress > 0f) snap() else LauncherMotion.settle,
        label = "workspace edge scale",
    )
    var homeReturning by remember { mutableStateOf(false) }
    LaunchedEffect(homeRequests) {
        if (homeRequests > 0) {
            homeReturning = true
            withFrameNanos { }
            homeReturning = false
        }
    }
    val homeReturnScale by animateFloatAsState(
        targetValue = if (homeReturning) .965f else 1f,
        animationSpec = if (homeReturning) snap() else LauncherMotion.settle,
        label = "home return scale",
    )
    val homeReturnAlpha by animateFloatAsState(
        targetValue = if (homeReturning) .88f else 1f,
        animationSpec = if (homeReturning) snap() else LauncherMotion.settle,
        label = "home return alpha",
    )
    SideEffect { pageGestures.editing = drag.active || widgetSession != null || resizeSlot != null }
    val pageFling = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(nativePager, pagerSnapDistance = pageGestures)
    val scope = rememberCoroutineScope()
    var previousHomePages by remember { mutableIntStateOf(homePages) }
    var previousEditRevision by remember { mutableIntStateOf(state.editRevision) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(pager, homePages) {
        snapshotFlow { nativePager.settledPage to drag.active }.distinctUntilChanged().collect { (physicalPage, moving) ->
            if (!moving) {
                savedPhysicalPage = physicalPage
                savedPagePosture = firstHome
                val logicalPage = physicalPage - firstHome
                if (logicalPage in 0 until homePages) lastHomePage = logicalPage
            }
        }
    }
    LaunchedEffect(homePages, state.editRevision) {
        if (homePages != previousHomePages && !drag.active) {
            // Pin edits in the library keep the library selected; a completed drop stays on home.
            if (state.editRevision == previousEditRevision) {
                if (pager.currentPage == previousHomePages) pager.scrollToPage(homePages)
                else if (pager.currentPage >= pageCount) pager.scrollToPage(homePages - 1)
            } else if (pager.currentPage >= homePages) pager.scrollToPage(homePages - 1)
        }
        previousHomePages = homePages
        previousEditRevision = state.editRevision
    }
    LaunchedEffect(pager.settledPage) { if (pager.settledPage != homePages) focus.clearFocus() }
    LaunchedEffect(state.verticalStatus) { onStatusMode(state.verticalStatus) }
    LaunchedEffect(homeRequests) { if (homeRequests > 0) {
        // An app can pause Home after the destination is visible but before its settle completes.
        val page = pager.currentPage.takeIf { it in 0 until homePages }
            ?: lastHomePage.coerceIn(0, homePages - 1)
        drag.clear(); widgetSession = null; resizeSlot = null; sheet = ""; widgetPackage = null
        widgetExactTarget = false; widgetPlacementMessage = null; selectedId = null; appMoveMenu = false
        openFolderId = null; createFolderFirstId = null; emptyCellIndex = null; desktopEditing = false
        focus.clearFocus(); keyboard?.hide()
        pager.animateScrollToPage(page)
    } }
    LaunchedEffect(searchRequests) { if (searchRequests > 0) { drag.clear(); widgetSession = null; resizeSlot = null; sheet = ""; widgetPackage = null; widgetExactTarget = false; selectedId = null
        if (!onGoogleSearch(null)) pager.animateScrollToPage(homePages)
    } }
    val widgetPickerBack = {
        if (widgetSession != null) {
            leaveTemporaryWidgetPage(); widgetSession = null; widgetPlacementMessage = null
        } else {
            sheet = ""; widgetPackage = null; widgetExactTarget = false; widgetPlacementMessage = null
        }
    }
    BackHandler(enabled = sheet == "widgets") { widgetPickerBack() }
    BackHandler(enabled = sheet.isEmpty()) { if (resizeSlot != null) resizeSlot = null else if (drag.active) {
        val destination = if (drag.source?.target is DropTarget.Library) homePages else drag.originPage.coerceAtMost(homePages - 1)
        drag.clear(); scope.launch { pager.scrollToPage(destination) }
    } else if (selectedId != null) selectedId = null else if (desktopEditing) desktopEditing = false else {
        focus.clearFocus()
        scope.launch { nativePager.animateScrollToPage(minOf(1, nativePager.pageCount - 1)) }
    } }
    val openLeadingPage = { scope.launch { pager.animateScrollToPage(if (firstHome > 0) -1 else 0) }; Unit }
    val openLibrary = { scope.launch { pager.animateScrollToPage(homePages) }; Unit }

    val dragWindowPage = if (expandedWorkspace && (drag.active || widgetSession != null)) pager.settledPage else pager.currentPage
    val eligibleDragPages = remember(expandedWorkspace, dragWindowPage, visibleHomePages) {
        if (expandedWorkspace && dragWindowPage in 0 until visibleHomePages) {
            setOfNotNull((dragWindowPage - 1).takeIf { it >= -1 }, dragWindowPage)
        } else setOf(dragWindowPage)
    }
    val rawTarget = if (drag.active) drag.destination(drag.pointer, eligibleDragPages)?.target else null
    val target = if (rawTarget is DropTarget.Home && drag.source?.target is DropTarget.Widget) {
        val slot = (drag.source!!.target as DropTarget.Widget).index
        model.placement(slot)?.let {
            DropTarget.Home(adjustedWidgetDropIndex(rawTarget.index, it, drag.source!!.bounds, drag.origin))
        } ?: rawTarget
    } else rawTarget
    val blockedDock = drag.moved && target is DropTarget.Dock &&
        if (drag.source?.folderId != null) state.dock.none { it == null }
        else drag.source?.appId?.let { !canPlaceInDock(state.layout, it) } == true
    val insertionTarget = target.takeIf { drag.moved && !blockedDock }
    val widgetRawTarget = widgetSession?.let { session -> drag.regions.values.firstOrNull {
        it.target is DropTarget.Home && it.page in eligibleDragPages && it.bounds.contains(session.pointer)
    }?.target as? DropTarget.Home }
    val widgetDraft = widgetSession?.let { session -> session.candidate ?: widgetRawTarget?.let { cell ->
        widgetCandidate(state.layout, session.slot, session.targetIndex ?: cell.index, session.span.width, session.span.height)
    } ?: session.targetIndex?.let { widgetCandidate(state.layout, session.slot, it, session.span.width, session.span.height) } }
    val dropHomePage = if (pager.currentPage >= visibleHomePages)
        lastHomePage.coerceIn(0, homePages - 1) else pager.currentPage.coerceIn(0, homePages)
    val previewLayout = remember(state.layout, drag.source, insertionTarget, drag.moved) {
        val id = drag.source?.appId
        when {
            id != null && insertionTarget is DropTarget.Home -> dropApp(state.layout, id, insertionTarget)
            id != null && insertionTarget is DropTarget.Dock -> dropApp(state.layout, id, insertionTarget)
            drag.source?.target is DropTarget.Widget && insertionTarget is DropTarget.Home ->
                moveWidget(state.layout, (drag.source!!.target as DropTarget.Widget).index, insertionTarget.index)
            else -> state.layout
        }
    }
    val edgeWidth = with(LocalDensity.current) { 30.dp.toPx() }
    val edgePointer = widgetSession?.takeIf { it.dragging }?.pointer ?: drag.pointer
    val edgeActive = (drag.active && drag.moved) || widgetSession?.dragging == true
    val edge = if (!edgeActive) 0 else dragEdgeDirection(edgePointer, drag.rootBounds, edgeWidth)
    LaunchedEffect(edgeActive, edge) {
        if (edge != 0) while (drag.active || widgetSession?.dragging == true) {
            delay(650)
            val next = (pager.currentPage + edge).coerceIn(-1, homePages)
            if ((!drag.active && widgetSession?.dragging != true) || next == pager.currentPage) break
            // Do not key this effect on currentPage: it changes halfway through the
            // animation and would cancel the turn before the inner grid is visible.
            // Once the hold commits a turn, finish its animation while the finger moves
            // into the incoming page. Leaving the edge cancels only the next hold timer.
            scope.launch { pager.animateScrollToPage(next) }.join()
        }
    }
    fun finishDrag(cancelled: Boolean) {
        val source = drag.source ?: return
        val moved = drag.moved
        val rawDestination = if (moved && !cancelled) drag.destination(drag.pointer, eligibleDragPages)?.target else null
        val destination = if (rawDestination is DropTarget.Home && source.target is DropTarget.Widget) {
            model.placement(source.target.index)?.let {
                DropTarget.Home(adjustedWidgetDropIndex(rawDestination.index, it, source.bounds, drag.origin))
            }
                ?: rawDestination
        } else rawDestination
        val changed = when {
            source.folderId != null && destination is DropTarget.Folder ->
                model.addAppToFolder(destination.id, source.appId ?: "")
            source.folderId != null && destination != null && source.appId != null ->
                model.removeAppFromFolder(source.folderId, source.appId, destination)
            destination == DropTarget.Remove -> model.removePlacement(source.target)
            destination is DropTarget.Home && source.target is DropTarget.Widget -> model.moveWidgetTo(source.target.index, destination.index)
            destination != null && source.appId != null -> model.applyDrop(source.appId, destination)
            else -> false
        }
        val returnToLibrary = source.target is DropTarget.Library && source.folderId == null && !changed
        val destinationHomePage = (destination as? DropTarget.Home)?.index?.let(::homeCellPage)
        val currentWindow = pager.settledPage.coerceIn(0, visibleHomePages - 1)
        val page = when (destination) {
            is DropTarget.Home -> if (expandedWorkspace && homeCellPage(destination.index) in eligibleDragPages) currentWindow else destinationHomePage!!
            is DropTarget.Dock -> dropHomePage
            is DropTarget.Widget -> 0
            else -> if (source.target is DropTarget.Library) pager.currentPage else drag.originPage
        }
        scope.launch {
            // Let a new home page compose before removing the temporary drop page.
            withFrameNanos { }
            drag.clear()
            withFrameNanos { }
            pager.scrollToPage(if (returnToLibrary) model.state.value.homePages else page.coerceIn(-1, model.state.value.homePages - 1))
            if (!moved && !cancelled) {
                if (source.target is DropTarget.Widget) { widgetSlot = source.target.index; sheet = "widgetActions" }
                else if (source.appId?.let(::isFolderId) == true) selectedId = source.appId
                else if (source.folderId == null) selectedId = source.appId
            }
        }
    }

    val folderBackdropBlur by animateDpAsState(
        targetValue = if (openFolderId != null) 24.dp else 0.dp,
        label = "folder backdrop blur",
    )
    Box(Modifier.fillMaxSize().testTag("launcher-root").homeDragInput(drag,
        canStart = launcherActivity::requireFeatureLicense,
        enabled = sheet.isEmpty() && !showFirstRun && selectedId == null && resizeSlot == null && pager.currentPage >= -1,
        page = pager.currentPage, eligiblePages = eligibleDragPages, onStart = {
            focus.clearFocus(); keyboard?.hide(); haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (drag.source?.appId != null) {
                appMenuAnchor = drag.pointer
                selectedId = drag.source?.appId
            }
            if (drag.source?.folderId != null) openFolderId = null
            if (drag.source?.target is DropTarget.Library) scope.launch {
                withFrameNanos { }
                pager.scrollToPage(lastHomePage.coerceIn(0, homePages - 1))
            }
        },
        onFinish = { cancelled -> finishDrag(cancelled) })) {
        DuneWallpaper(Modifier.fillMaxSize().hazeSource(glassBackdrop).blur(folderBackdropBlur))
        if (desktopEditing) {
            Box(Modifier.fillMaxSize().hazeSource(editorGlassBackdrop)) {
            editorBackdrop?.let { bitmap ->
                Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().blur(28.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)))
            }
        }
        CompositionLocalProvider(LocalDuoGlassBackdrop provides if (desktopEditing) editorGlassBackdrop else glassBackdrop,
            LocalWidgetPlacementSession provides widgetSession) {
        BoxWithConstraints(Modifier.fillMaxSize().blur(folderBackdropBlur).graphicsLayer {
            scaleX = homeReturnScale
            scaleY = homeReturnScale
            alpha = homeReturnAlpha
        }) {
            val wide = maxWidth.value >= 650f
            val preset = state.sharedPreset
            val density = LocalDensity.current
            val contentHeight = minOf(maxHeight, SHARED_SAFE_HEIGHT_DP.dp)
            val isCoverDisplay = launcherActivity.panelOrientation.panel.value == FoldPanel.COVER
            val foldTransition = rememberFoldTransition(!isCoverDisplay, launcherActivity.foldPreviewRequests.intValue,
                enabled = launcherActivity.licensing.foldAnimationEnabled)
            val statusCutoutDepthDp = with(density) { windowCutout.statusColumnCutoutDepthPx.toDp().value }
            val inLibrary = pager.currentPage == visibleHomePages
            val labelHeightDp = with(density) { 14.sp.toDp().value } + 6f
            // The temporary "Set as home" prompt must never squeeze or move the desktop.
            // It overlays the reserved bottom controls until the launcher becomes default.
            val homeBottomSpaceDp = 44f
            val statusTop = statusRailTopDp(REFERENCE_STATUS_TOP_DP, statusCutoutDepthDp)
            val expandedPagerWidth = expandedWorkspaceWidth(maxWidth.value, preset.dockWidth).dp
            val baseGeometry = homeGeometry(maxWidth.value, contentHeight.value, preset, state.labels,
                labelHeight = labelHeightDp, inLibrary = inLibrary, homeBottomSpace = homeBottomSpaceDp)
                .copy(expanded = !isCoverDisplay && maxWidth.value >= 650f)
            val geometry = expandedHomeGeometry(baseGeometry, expandedPagerWidth.value / 2f)
            SideEffect {
                resizePitchX = with(density) { (geometry.gridWidth / GRID_COLUMNS).dp.toPx() } * editorScale
                resizePitchY = with(density) { minOf((geometry.widgetHeight + 18f) / 2f, geometry.rowHeight).dp.toPx() } * editorScale
                resizeTopPitch = with(density) { ((geometry.widgetHeight + 18f) / 2f).dp.toPx() } * editorScale
                resizeAppPitch = with(density) { geometry.rowHeight.dp.toPx() } * editorScale
            }
            LaunchedEffect(geometry.gridWidth, geometry.widgetHeight, geometry.rowHeight) { resizeSlot = null }
            SideEffect { expandedWorkspace = geometry.expanded }
            LaunchedEffect(geometry.expanded) {
                if (!geometry.expanded) {
                    val sessionTargetsLeading = widgetSession?.let { session ->
                        session.candidate?.page == -1 || session.targetIndex?.let(::homeCellPage) == -1
                    } == true
                    val savedTargetLeading = widgetTargetIndex != Int.MIN_VALUE && homeCellPage(widgetTargetIndex) == -1
                    if (sessionTargetsLeading || savedTargetLeading) {
                        widgetSession = null
                        widgetTargetIndex = Int.MIN_VALUE
                        widgetExactTarget = false
                        widgetPackage = null
                        widgetProfileSerial = null
                        widgetPlacementMessage = null
                        sheet = ""
                    }
                    val dragTouchesLeading = drag.source?.page == -1 ||
                        ((target as? DropTarget.Home)?.index?.let(::homeCellPage) == -1)
                    if (dragTouchesLeading) {
                        drag.clear()
                    }
                }
            }
            val windowHeightDp = maxHeight.value
            val windowWidth = maxWidth
            val coverLeadingInset = coverLeadingEdgeInset(
                isCoverDisplay, contentHeight.value, windowHeightDp)
            val coverPagerHeight = contentHeight + (coverLeadingInset * 2f).dp
            val pagerWidth = expandedPagerWidth
            // Once the fixed Dock is removed, both panes own exactly half of the
            // remaining workspace. Their shared Home canvas is centred per half.
            val panelWidth = if (geometry.expanded) (expandedPagerWidth.value / 2f).dp else maxWidth - geometry.homeWidth.dp
            val paneContentInset = expandedPaneContentInset(
                expandedPagerWidth.value, geometry.gridWidth + 16f).dp
            val homeStride = panelWidth
            val bottomSpace = 44.dp
            val workspaceMotion = if (geometry.expanded) remember(firstHome, visibleHomePages, pagerWidth, homeStride, density) {
                WorkspacePageMotion(firstHome, visibleHomePages, with(density) { pagerWidth.toPx() }, with(density) { homeStride.toPx() })
            } else null
            val dockScroll = rememberScrollState()
            var gestureOriginInRoot by remember { mutableStateOf(Offset.Zero) }
            var gestureOriginInWindow by remember { mutableStateOf(Offset.Zero) }
            val pagerInputEnabled = pager.currentPage in -firstHome..visibleHomePages && !drag.active &&
                widgetSession == null && resizeSlot == null && sheet.isEmpty() && !showFirstRun && selectedId == null &&
                openFolderId == null && emptyCellIndex == null && createFolderFirstId == null &&
                launcherActivity.backups.preview == null && !launcherActivity.backups.pickerPending &&
                !launcherActivity.backgrounds.pickerPending && widgets.setupStatus == null &&
                widgets.reconfigureWidgetId == null
            Box(Modifier.fillMaxSize().onGloballyPositioned {
                gestureOriginInRoot = it.boundsInRoot().topLeft
                gestureOriginInWindow = it.boundsInWindow().topLeft
            }.onePageGestures(
                nativePager,
                pageGestures,
                motion = workspaceMotion,
                enabled = pagerInputEnabled,
                // Positive IDs are provider-owned Android views. Leave their vertical
                // stream untouched so scrollable widgets retain native gesture handling.
                // A dock that is already scrolled also gets first use of a downward drag.
                canStartDownwardSwipe = { point ->
                    val leadingGesture = startsInExpandedLeadingPane(geometry.expanded, point.x,
                        with(density) { panelWidth.toPx() }, workspaceMotion?.offset(
                            nativePager.currentPage + nativePager.currentPageOffsetFraction) ?: 0f)
                    if (desktopEditing || leadingGesture || pager.currentPage !in 0 until visibleHomePages) false else {
                        val region = drag.hit(point + gestureOriginInRoot, eligibleDragPages)
                        val rootOnScreen = IntArray(2).also(launcherRootView::getLocationOnScreen)
                        val screenPoint = point + gestureOriginInWindow +
                            Offset(rootOnScreen[0].toFloat(), rootOnScreen[1].toFloat())
                        !(region?.target is DropTarget.Dock && dockScroll.value > 0) &&
                            !nativeWidgetConsumesVerticalGesture(launcherRootView, screenPoint)
                    }
                },
                onDownwardSwipe = launcherActivity::openSystemShade,
                onEdgeVisual = { edgeVisual = it },
            )) {
            if (desktopEditing) {
                for (direction in listOf(-1, 1)) {
                    val neighbour = nativePager.currentPage + direction
                    if (neighbour in 0 until nativePager.pageCount) EditorNeighbourPreview(state,
                        neighbour - firstHome, direction, editorScale, editorShift) {
                        scope.launch { nativePager.animateScrollToPage(neighbour) }
                    }
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = editorScale; scaleY = editorScale
                translationY = size.height * editorShift
                shape = RoundedCornerShape(if (desktopEditing) 28.dp else 0.dp)
                clip = desktopEditing
            }.then(if (desktopEditing) Modifier.border(1.5.dp, Color.White.copy(alpha = .65f), RoundedCornerShape(28.dp)) else Modifier)
                .testTag("editor-live-desktop").hazeSource(liveDesktopBackdrop)) {
            if (desktopEditing) DuneWallpaper(Modifier.fillMaxSize().hazeSource(workspaceBackdrop))
            val pagerDescription = when {
                geometry.expanded && nativePager.currentPage == 0 -> launcherFormat(R.string.ui_widget_and_home)
                pager.currentPage == visibleHomePages -> launcherText("All apps")
                geometry.expanded -> launcherFormat(R.string.ui_home_pages, nativePager.currentPage, nativePager.currentPage + 1)
                pager.currentPage == -1 -> launcherText("Widgets")
                else -> launcherFormat(R.string.ui_home_page_count, pager.currentPage + 1, visibleHomePages)
            }
            val pagerModifier = Modifier.width(pagerWidth).then(
                if (coverLeadingInset > 0f) Modifier
                    .height(coverPagerHeight)
                else Modifier.fillMaxHeight()
            )
                .graphicsLayer {
                    translationX = edgeTranslation
                    scaleX = edgeScale
                    scaleY = edgeScale
                }
                .testTag("app-pager")
                .semantics { stateDescription = pagerDescription }
            val coverBackdrop = rememberHazeState()
            val innerProjecting = geometry.expanded && foldTransition.reveal.value < .999f && android.os.Build.VERSION.SDK_INT >= 33
            Box(Modifier.fillMaxSize().foldCoverPane(
                progress = foldTransition.coverFold,
                enabled = { !geometry.expanded },
            ).foldInnerDesktop(foldTransition.reveal, geometry.expanded)) {
            if (!geometry.expanded || innerProjecting) DuneWallpaper(Modifier.fillMaxSize().hazeSource(coverBackdrop))
            CompositionLocalProvider(LocalDuoGlassBackdrop provides
                if (geometry.expanded && !innerProjecting) (if (desktopEditing) workspaceBackdrop else glassBackdrop) else coverBackdrop) {
            if (geometry.expanded) {
                Box(Modifier.width(pagerWidth).fillMaxHeight().clipToBounds()) {
                Box(pagerModifier) {
                    // PagerState remains the source of truth for snapping, accessibility
                    // state, and programmatic page requests.
                    HorizontalPager(nativePager, Modifier.fillMaxSize(), userScrollEnabled = false, overscrollEffect = null,
                        key = { if (it < firstHome) "widgets" else if (it - firstHome == visibleHomePages) "library" else "home-${it - firstHome}" }) { }
                    ExpandedWorkspace(
                        nativePager = nativePager, motion = workspaceMotion!!, firstHome = firstHome,
                        visibleHomePages = visibleHomePages, panelWidth = panelWidth,
                        paneContentInset = paneContentInset, wallpaperWidth = windowWidth,
                        contentHeight = contentHeight, bottomSpace = bottomSpace, geometry = geometry,
                        foldProgress = foldTransition.reveal,
                        state = state, deviceStatus = deviceStatus,
                        previewSlots = previewLayout.slots, previewLeadingSlots = previewLayout.leadingSlots,
                        previewWidgetPlacements = previewLayout.widgetPlacements, appsById = appsById,
                        widgets = widgets, drag = drag, target = target, insertionTarget = insertionTarget,
                        libraryQuery = libraryQuery, onLibraryQuery = { libraryQuery = it },
                        onLaunch = onLaunch, onLaunchFrom = onLaunchFrom, onPinned = model::setPinned,
                        onTurnOnWork = { model.turnOnWork(it) },
                        onActions = { selectedId = it.id }, onWidget = { widgetSlot = it; sheet = "widgetActions" },
                        onFolder = { openFolderId = it },
                        onEmptyWidget = { emptyCellIndex = it },
                        onRefresh = model::refresh,
                    )
                }
                }
            } else {
                Box(Modifier.width(pagerWidth).height(coverPagerHeight).clipToBounds()) {
                HorizontalPager(nativePager, pagerModifier,
                    overscrollEffect = null,
                    // Keep adjacent Home panes attached so ordinary back-and-forth paging does
                    // not synchronously inflate provider RemoteViews inside the gesture frame.
                    // The component page is two physical positions before Home 2. Retain
                    // both Home neighbors to avoid reinflating provider RemoteViews.
                    beyondViewportPageCount = if (firstHome > 0) 2 else 1,
                    userScrollEnabled = !drag.active && resizeSlot == null, flingBehavior = pageFling,
                    key = { if (it < firstHome) "widgets" else if (it - firstHome == visibleHomePages) "library" else "home-${it - firstHome}" }) { physicalPage ->
                    val page = physicalPage - firstHome
                    if (page == -1) {
                        HomePagePane(page, state, previewLayout.slots, previewLayout.leadingSlots,
                            previewLayout.widgetPlacements, appsById, geometry, coverPagerHeight,
                            bottomSpace, widgets, drag, target, insertionTarget, showLargeWidget = true,
                            deviceStatus = deviceStatus, onLaunch = onLaunchFrom,
                            onActions = { selectedId = it.id },
                            onWidget = { widgetSlot = it; sheet = "widgetActions" },
                            onFolder = { openFolderId = it },
                            onEmptyWidget = { emptyCellIndex = it }, onRefresh = model::refresh,
                            contentTopInset = coverLeadingInset.dp)
                    } else if (page == visibleHomePages) {
                        Box(Modifier.fillMaxWidth().height(contentHeight)) {
                            AppLibrary(state, libraryQuery, { libraryQuery = it }, onLaunch, model::setPinned,
                                onActions = { selectedId = it.id }, modifier = Modifier.libraryHomeBounds(geometry, bottomSpace),
                                drag = drag, page = visibleHomePages, onLaunchFrom = onLaunchFrom, onTurnOnWork = { model.turnOnWork(it) })
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().height(contentHeight).testTag("home-surface")) {
                            HomePagePane(page, state, previewLayout.slots, previewLayout.leadingSlots, previewLayout.widgetPlacements, appsById, geometry, contentHeight,
                                bottomSpace, widgets, drag, target, insertionTarget, showLargeWidget = false,
                                deviceStatus = deviceStatus,
                                onLaunch = onLaunchFrom, onActions = { selectedId = it.id },
                                onWidget = { widgetSlot = it; sheet = "widgetActions" },
                                onFolder = { openFolderId = it },
                                onEmptyWidget = { emptyCellIndex = it },
                                onRefresh = model::refresh)
                        }
                    }
                }
            }
            }
            if (state.verticalStatus) StatusRail(deviceStatus,
                Modifier.align(Alignment.TopEnd).padding(end = 12.dp)
                    .offset(y = (statusTop + coverLeadingInset).dp)
                    .width(preset.dockWidth.dp),
                compact = contentHeight < 500.dp, iconSize = 39.dp)
            val dockFrameInset = dockFrameSideInset(preset.dockWidth)
            val dockFrameWidth = dockFrameWidth(preset.dockWidth)
            // Pull both glass edges inward by 2dp while keeping the Dock icons on the
            // same vertical centre line as Status and Search.
            val dockShape = RoundedCornerShape(20.dp)
            Surface(Modifier.align(Alignment.TopEnd).padding(end = (12f + dockFrameInset).dp)
                .offset(y = (geometry.dockTop + coverLeadingInset).dp)
                .width(dockFrameWidth.dp).height(geometry.dockHeight.dp)
                // One backdrop layer for the complete stationary Dock. Slots and icons
                // remain ordinary content, so its geometry and scrolling are unchanged.
                .duoGlass(DuoGlassRole.Dock, dockShape).testTag("dock"),
                shape = dockShape, color = Color.Transparent,
                contentColor = duoGlassContentColor(DuoGlassRole.Dock)) {
                Column(Modifier.padding(vertical = 8.dp).verticalScroll(dockScroll)) {
                    DockAppColumn(state.dock, previewLayout.dock, appsById, geometry.dockRowHeight,
                        dockIconSize(), drag, insertionTarget,
                        onLaunch = onLaunchFrom, onChoose = { dockSlot = it; sheet = "dock" })
                }
            }
            val pageNavigationModifier = if (geometry.expanded) {
                Modifier.align(Alignment.BottomStart).width(pagerWidth)
                    .padding(bottom = 16.dp + coverLeadingInset.dp)
            } else {
                // On the cover, retain the approved centre of the icon field left of Dock.
                Modifier.align(Alignment.BottomStart).width(pagerWidth)
                    .padding(start = 16.dp, bottom = 16.dp + coverLeadingInset.dp)
            }
            val pageNavigationProgress by remember(nativePager, firstHome, geometry.expanded) {
                derivedStateOf {
                    coverPageNavigationProgress(
                        geometry.expanded,
                        firstHome,
                        nativePager.currentPage + nativePager.currentPageOffsetFraction,
                    )
                }
            }
            val pageNavigationVisible by remember(nativePager, firstHome, geometry.expanded) {
                derivedStateOf {
                    showPageNavigation(
                        geometry.expanded,
                        firstHome,
                        nativePager.settledPage,
                        nativePager.isScrollInProgress,
                    )
                }
            }
            val pagerWidthPx = with(density) { pagerWidth.toPx() }
            val expandedNavigationOffset by remember(nativePager, geometry.expanded) {
                derivedStateOf {
                    expandedPageNavigationOffsetFraction(
                        geometry.expanded,
                        nativePager.currentPage + nativePager.currentPageOffsetFraction,
                    )
                }
            }
            if (pageNavigationVisible && !desktopEditing) Column(pageNavigationModifier
                .graphicsLayer {
                    translationX = pagerWidthPx * if (geometry.expanded) {
                        expandedNavigationOffset
                    } else {
                        pageNavigationProgress
                    }
                }
                .testTag("page-navigation"), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (!drag.active) IconButton(onClick = openLeadingPage, Modifier.size(32.dp).testTag("widgets-page-link")) {
                        Icon(Icons.Rounded.Widgets, launcherText("Widgets page"),
                            tint = Color.White.copy(alpha = if (geometry.expanded && nativePager.currentPage == 0 || pager.currentPage == -1) 1f else .65f),
                            modifier = Modifier.size(17.dp))
                    }
                    val selectedHomeIndicator = pager.currentPage.takeIf { it in 0 until visibleHomePages }
                    if (visibleHomePages <= 6) repeat(visibleHomePages) { index ->
                        val targetPhysicalPage = index + firstHome
                        Box(Modifier.size(28.dp).clip(CircleShape).clickable {
                            scope.launch { nativePager.animateScrollToPage(targetPhysicalPage) }
                        }
                            .semantics { contentDescription = if (index == homePages) launcherText("New home page") else launcherFormat(R.string.ui_home_page, index + 1) }, contentAlignment = Alignment.Center) {
                            if (index == homePages) Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(14.dp))
                            else Box(Modifier.size(if (index == selectedHomeIndicator) 6.dp else 4.dp)
                                .background(Color.White.copy(alpha = if (index == selectedHomeIndicator) 1f else .4f), CircleShape))
                        }
                    } else Text("${minOf(pager.currentPage + 1, homePages)} / $homePages", color = Color.White, fontSize = 12.sp)
                    IconButton(onClick = openLibrary, Modifier.size(32.dp).testTag("library-page-link")) {
                        Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, launcherText("All apps page"), tint = Color.White.copy(alpha = if (pager.currentPage == homePages) 1f else .6f), modifier = Modifier.size(17.dp))
                    }
                }
            }
            if (!inLibrary && !drag.active) Column(Modifier.align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 16.dp + coverLeadingInset.dp)
                .width(preset.dockWidth.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val controlSize = dockIconSize().dp
                if (pager.currentPage == -1) CircleControl(Icons.Rounded.ArrowForward, launcherText("Back to home"), "widgets-home", controlSize) { scope.launch { pager.animateScrollToPage(0) } }
                val searchBounds = remember { android.graphics.Rect() }
                Box(Modifier.onGloballyPositioned { searchBounds.set(it.boundsInWindow().toAndroidBounds()) }) {
                    CircleControl(Icons.Rounded.Search, androidx.compose.ui.res.stringResource(R.string.search_system), "search", controlSize) {
                        if (!onGoogleSearch(searchBounds)) openLibrary()
                    }
                }
            }
            } // Pane backdrop provider.
            } // Cover projection.
            } // Live desktop only: modal windows and drop feedback remain in root coordinates.
            if (desktopEditing && sheet.isEmpty() && !drag.active && selectedId == null) {
                DesktopEditorChrome(nativePager.currentPage, nativePager.pageCount,
                    onChromeHeight = { editorChromeHeight = it },
                    onPage = { scope.launch { nativePager.animateScrollToPage(it) } },
                    onWidgets = {
                        widgetTargetIndex = homeCellIndex(pager.currentPage.coerceIn(-1, homePages - 1), 0)
                        widgetExactTarget = false; widgetSlot = model.nextWidgetSlot()
                        widgetPackage = null; widgetProfileSerial = null; sheet = "widgets"
                    }, onWallpaper = { sheet = "settings:wallpaper" },
                    onSettings = { sheet = "settings" }, onDone = { desktopEditing = false })
            }
            if (sheet == "widgetActions") {
                val placement = model.placement(widgetSlot)
                if (placement == null) LaunchedEffect(widgetSlot) { sheet = "" }
                else {
                    val constraints = widgets.manager.getAppWidgetInfo(placement.id)?.let { widgets.sizing(it, geometry.widgetGridSizing()) }
                    val minWidth = constraints?.minimum?.width ?: 2
                    val minHeight = constraints?.minimum?.height ?: 2
                    val maxWidth = minOf(GRID_COLUMNS - placement.column, constraints?.maximum?.width ?: GRID_COLUMNS)
                    val maxHeight = minOf(widgetRowsForPage(placement.page) - placement.row, constraints?.maximum?.height ?: GRID_ROWS)
                    val canResize = placement.page >= -1 && placement.row in 0 until widgetRowsForPage(placement.page) &&
                        !(placement.id >= 0 && constraints == null) && minWidth <= maxWidth && minHeight <= maxHeight &&
                        (constraints == null || constraints.canResizeHorizontally || constraints.canResizeVertically)
                    CompositionLocalProvider(LocalDuoGlassBackdrop provides liveDesktopBackdrop) {
                        CompactWidgetActions(anchor = drag.regions[DropTarget.Widget(widgetSlot)]?.bounds,
                            canResize = canResize, canConfigure = widgets.canReconfigure(placement.id),
                            onResize = {
                                resizeSlot = widgetSlot; resizeWidth = placement.spanX; resizeHeight = placement.spanY
                                resizeConstraints = constraints; sheet = ""
                            }, onConfigure = { widgets.reconfigure(placement.id); sheet = "" },
                            onReplace = {
                                widgetPackage = null
                                widgetProfileSerial = widgets.manager.getAppWidgetInfo(placement.id)?.profile?.let {
                                    launcherActivity.getSystemService(UserManager::class.java).getSerialNumberForUser(it)
                                }?.takeIf { it >= 0 }
                                widgetExactTarget = false; sheet = "widgets"
                            }, onRemove = { widgets.remove(widgetSlot); sheet = "" }, onClose = { sheet = "" })
                    }
                }
            }
            if (sheet.isNotEmpty() && sheet != "widgets" && sheet != "widgetActions") {
                val activeCustomizationPage = if (sheet == "settings:wallpaper") CustomizationPage.WALLPAPER else customizationPage
                ModalBottomSheet(onDismissRequest = {
                    customizationPage = CustomizationPage.OVERVIEW
                    sheet = ""; widgetPackage = null; widgetExactTarget = false
                }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
                    containerColor = MaterialTheme.colorScheme.surface) {
                    ModalDialogBackHandler {
                        if ((sheet == "settings" || sheet == "settings:wallpaper") &&
                            activeCustomizationPage != CustomizationPage.OVERVIEW) {
                            customizationPage = CustomizationPage.OVERVIEW
                            sheet = "settings"
                        } else {
                            customizationPage = CustomizationPage.OVERVIEW
                            sheet = ""; widgetPackage = null; widgetExactTarget = false
                        }
                    }
                    when (sheet) {
                        "activation" -> Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)
                            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LicenseActivationPanel(license, onActivated = { sheet = "settings" })
                            TextButton(onClick = { sheet = "settings" }) { Text(androidx.compose.ui.res.stringResource(R.string.miduo_back)) }
                            TextButton(onClick = { launcherActivity.showRestoreSystemHome() }) {
                                Text(androidx.compose.ui.res.stringResource(R.string.restore_system_home))
                            }
                        }
                        "dock" -> AppPicker(state.apps, dockSlot,
                            onSelect = {
                                if (canPlaceInDock(state.layout, it.id)) {
                                    model.applyDrop(it.id, DropTarget.Dock(dockSlot)); sheet = ""
                                }
                            },
                            onClear = { model.removePlacement(DropTarget.Dock(dockSlot)) },
                            onLongClick = { selectedId = it.id; sheet = "" },
                            canSelect = { canPlaceInDock(state.layout, it.id) },
                            blockedHint = if (state.dock.none { it == null }) launcherText("Dock full • Move an app out first") else null)
                        "pins" -> Column(Modifier.fillMaxHeight(.9f).imePadding()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { sheet = "" }) { Text(launcherText("Done")) }
                            }
                            AppLibrary(state, pinQuery, { pinQuery = it }, onLaunch, model::setPinned,
                                onActions = { selectedId = it.id; sheet = "" }, editing = true, modifier = Modifier.weight(1f).fillMaxWidth(),
                                onTurnOnWork = { model.turnOnWork(it) })
                        }
                        "settings", "settings:wallpaper" -> SimpleLauncherSettings(state, model, isDefaultHome,
                            wallpaper = sheet == "settings:wallpaper",
                            onMakeDefault = { sheet = ""; onMakeDefault() },
                            onClose = { customizationPage = CustomizationPage.OVERVIEW; sheet = "" },
                            onWidgets = { widgetSlot = model.nextWidgetSlot(); widgetTargetIndex = homeCellIndex(pager.currentPage.coerceIn(-1, homePages - 1), 0); widgetPackage = null; widgetProfileSerial = null; widgetExactTarget = false; sheet = "widgets" },
                            onWallpaper = { sheet = "settings:wallpaper" },
                            onExport = { sheet = ""; launcherActivity.backups.startExport() },
                            onImport = { sheet = ""; launcherActivity.backups.startImport() },
                            onShadeSetup = { sheet = ""; onShadeSetup() },
                            backgrounds = launcherActivity.backgrounds,
                            onSystemWallpaper = { sheet = ""; onWallpaperPreview() })
                    }
                }
            }
            if (showFirstRun) {
                ModalBottomSheet(
                    onDismissRequest = onFinishFirstRun,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("first-run-setup"),
                ) {
                    FirstRunSetupSheet(
                        isDefaultHome = isDefaultHome,
                        onMakeDefault = onMakeDefault,
                        onAddWidget = {
                            onFinishFirstRun()
                            widgetSlot = model.nextWidgetSlot()
                            widgetTargetIndex = pager.currentPage.coerceIn(0, homePages - 1) * HOME_CELLS
                            widgetPackage = null
                            widgetProfileSerial = null
                            widgetExactTarget = false
                            sheet = "widgets"
                        },
                        onExplore = onFinishFirstRun,
                        onSkip = onFinishFirstRun,
                    )
                }
            }
            if (sheet == "widgets") {
                val catalogProfiles = remember(state.profiles) { state.profiles.filter { it.isPersonal || it.isWork } }
                val selectedProfile = catalogProfiles.firstOrNull { it.userSerial == widgetProfileSerial }
                    ?: catalogProfiles.firstOrNull { it.isPersonal } ?: AppProfile(0, launcherText("Personal"), true, false, false, true, true)
                val userManager = remember(launcherActivity) { launcherActivity.getSystemService(UserManager::class.java) }
                val providers = remember(widgetPackage, selectedProfile, sheet, state.apps) {
                    val user = userManager.getUserForSerialNumber(selectedProfile.userSerial)
                    if (user == null || !selectedProfile.available || !selectedProfile.unlocked || selectedProfile.quiet) emptyList()
                    else runCatching { widgetPackage?.let { widgets.providersForPackage(it, user) }
                        ?: widgets.providers(user) }.getOrDefault(emptyList()).filter { provider ->
                        provider.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 &&
                            provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
                    }
                }
                val catalog by produceState<List<WidgetCatalogEntry>?>(null, providers, selectedProfile.userSerial, sheet) {
                    value = withContext(Dispatchers.IO) { widgetCatalog(launcherActivity, providers, selectedProfile) }
                }
                val topPitch = (geometry.widgetHeight + 18f) / 2f
                val pickerSizing = remember(geometry) { geometry.widgetGridSizing() }
                val footprint: (AppWidgetProviderInfo) -> WidgetSpan? = { provider ->
                    widgets.sizing(provider, pickerSizing)?.takeIf { it.minimumFitsGrid }?.preferred
                }
                VisualWidgetPicker(catalog, catalogProfiles.ifEmpty { listOf(selectedProfile) }, selectedProfile,
                    onSelectProfile = { widgetProfileSerial = it.userSerial; widgetPlacementMessage = null },
                    onTurnOnWork = { model.turnOnWork(it) }, hiddenForDrag = widgetSession != null,
                    footprint = footprint,
                    onBack = widgetPickerBack,
                    onTap = { provider ->
                        footprint(provider)?.let { preferredSpan ->
                            val existing = model.placement(widgetSlot)
                            val constraints = widgets.sizing(provider, pickerSizing)
                            val span = existing?.let { placement ->
                                WidgetSpan(placement.spanX, placement.spanY).takeIf {
                                    constraints != null && it.width in constraints.minimum.width..constraints.maximum.width &&
                                        it.height in constraints.minimum.height..constraints.maximum.height
                                }
                            } ?: preferredSpan
                            val special = existing?.takeIf { it.page > 0 && it.row + it.spanY > GRID_ROWS }
                            if (special != null) {
                                widgetSession = WidgetPickerSession(provider, widgetSlot,
                                    WidgetSpan(special.spanX, special.spanY), Offset.Zero,
                                    dragging = false, candidate = special)
                                widgetPlacementMessage = null
                                scope.launch { pager.scrollToPage(special.page.coerceAtLeast(0).coerceAtMost(homePages - 1)) }
                                return@let
                            }
                            val requestedIndex = existing?.let { homeCellIndex(it.page, it.row * GRID_COLUMNS + it.column) }
                                ?: widgetTargetIndex.takeUnless { it == Int.MIN_VALUE } ?: 0
                            val requestedPage = homeCellPage(requestedIndex).coerceIn(-1, homePages)
                            val availablePages = -1..homePages
                            val autoPages = (listOf(requestedPage) + availablePages.filter { it != requestedPage })
                            val freeIndex = if (existing != null || widgetExactTarget) requestedIndex.takeIf {
                                widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null
                            } else autoPages.asSequence().flatMap { page ->
                                (0 until widgetRowsForPage(page) * GRID_COLUMNS).asSequence().map { homeCellIndex(page, it) }
                            }.firstOrNull { widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null }
                            val targetIndex = freeIndex ?: requestedIndex
                            widgetSession = WidgetPickerSession(provider, widgetSlot, span, Offset.Zero,
                                dragging = false, targetIndex = targetIndex)
                            widgetPlacementMessage = if (freeIndex == null)
                                launcherText("There isn’t room for this size. Choose another page or move an item first.") else null
                            scope.launch { pager.scrollToPage(homeCellPage(targetIndex).coerceIn(-1, homePages)) }
                        }
                    },
                    onBuiltin = builtin@{ builtinId ->
                        val existing = model.placement(widgetSlot)
                        val special = existing?.takeIf { it.page > 0 && it.row + it.spanY > GRID_ROWS }
                        val span = existing?.let { WidgetSpan(it.spanX, it.spanY) } ?: WidgetSpan(if (builtinId == WEATHER_WIDGET) 4 else 2, 2)
                        if (special != null) {
                            widgetSession = WidgetPickerSession(null, widgetSlot, span, Offset.Zero,
                                dragging = false, candidate = special, builtinId = builtinId)
                            widgetPlacementMessage = null
                            scope.launch { pager.scrollToPage(special.page.coerceAtLeast(0).coerceAtMost(homePages - 1)) }
                            return@builtin
                        }
                        val requested = existing?.let {
                            homeCellIndex(it.page, it.row * GRID_COLUMNS + it.column)
                        } ?: widgetTargetIndex.takeUnless { it == Int.MIN_VALUE } ?: 0
                        val requestedPage = homeCellPage(requested).coerceIn(-1, homePages)
                        val availablePages = -1..homePages
                        val candidates = if (model.placement(widgetSlot) != null || widgetExactTarget) sequenceOf(requested)
                            else (listOf(requestedPage) + availablePages.filter { it != requestedPage }).asSequence()
                                .flatMap { page -> (0 until widgetRowsForPage(page) * GRID_COLUMNS).asSequence().map { homeCellIndex(page, it) } }
                        val free = candidates.firstOrNull {
                            widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null
                        }
                        widgetSession = WidgetPickerSession(null, widgetSlot, span, Offset.Zero,
                            dragging = false, targetIndex = free ?: requested, builtinId = builtinId)
                        widgetPlacementMessage = if (free == null)
                            launcherText("There isn’t room for this card. Choose another page or move an item first.") else null
                        scope.launch { pager.scrollToPage(homeCellPage(free ?: requested).coerceIn(-1, homePages)) }
                    },
                    onBuiltinDragStart = { builtinId, point ->
                        widgetSession = WidgetPickerSession(null, widgetSlot, WidgetSpan(if (builtinId == WEATHER_WIDGET) 4 else 2, 2), point,
                            dragging = true, builtinId = builtinId)
                        widgetPlacementMessage = null
                    },
                    onDragStart = { provider, point ->
                        footprint(provider)?.let { span ->
                            widgetSession = WidgetPickerSession(provider, widgetSlot, span, point, dragging = true)
                            widgetPlacementMessage = null
                            val destination = widgetTargetIndex.takeUnless { it == Int.MIN_VALUE }
                                ?.let(::homeCellPage) ?: lastHomePage
                            scope.launch { pager.scrollToPage(destination.coerceIn(-1, homePages - 1)) }
                        }
                    },
                    onDrag = { point -> widgetSession = widgetSession?.copy(pointer = point) },
                    onDrop = {
                        val session = widgetSession
                        // Resolve from the final pointer, not the previous rendered frame.
                        // A quick release may arrive before Compose has drawn its preview.
                        val finalCell = session?.let { selected -> drag.regions.values.firstOrNull {
                            it.target is DropTarget.Home && it.page in eligibleDragPages && it.bounds.contains(selected.pointer)
                        }?.target as? DropTarget.Home }
                        val finalDraft = session?.candidate ?: finalCell?.let { cell -> session?.let {
                            widgetCandidate(state.layout, it.slot, cell.index, it.span.width, it.span.height)
                        } }
                        if (session != null && finalDraft != null) {
                            session.provider?.let { widgets.add(finalDraft, it, pickerSizing) }
                                ?: session.builtinId?.let { widgets.setBuiltin(finalDraft.copy(id = it)) }
                            widgetSession = null; sheet = ""; widgetPackage = null
                        } else {
                            leaveTemporaryWidgetPage(); widgetSession = null
                            widgetPlacementMessage = launcherText("There isn’t room there. Try another space or page.")
                        }
                    },
                    onCancelDrag = {
                        if (widgetSession != null) {
                            leaveTemporaryWidgetPage(); widgetSession = null
                        }
                    })
                widgetSession?.let { session ->
                    val placementDensity = LocalDensity.current
                    val sessionEntry = session.provider?.let { selected -> catalog?.firstOrNull {
                        it.provider.provider == selected.provider && it.provider.profile == selected.profile } }
                    // Legacy overflow replacements are locked to their existing view
                    // bounds and may begin below the canonical six-row grid. They have
                    // no Home-cell address; specialAnchor below is their visual anchor.
                    val candidateIndex = widgetDraft?.takeIf { session.candidate == null }
                        ?.let { homeCellIndex(it.page, it.row * GRID_COLUMNS + it.column) }
                    val visualIndex = candidateIndex ?: widgetRawTarget?.index ?: session.targetIndex
                    val specialAnchor = session.candidate?.let { drag.regions[DropTarget.Widget(session.slot)]?.bounds }
                    val anchor = specialAnchor ?: visualIndex?.let { drag.regions[DropTarget.Home(it)]?.bounds }
                    Box(Modifier.fillMaxSize().testTag("widget-placement-mode")
                        .then(if (!session.dragging && session.candidate == null) Modifier.pointerInput(session.slot, session.span) {
                            detectTapGestures { local ->
                                val point = local + drag.rootOrigin
                                val cell = drag.regions.values.firstOrNull {
                                    it.target is DropTarget.Home && it.page in eligibleDragPages && it.bounds.contains(point)
                                }?.target as? DropTarget.Home
                                cell?.let { widgetSession = session.copy(pointer = point, targetIndex = it.index) }
                            }
                        } else Modifier)) {
                        Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                            .background(Glass.copy(alpha = .97f), RoundedCornerShape(22.dp))
                            .testTag("widget-placement-toolbar"), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = widgetPickerBack) { Text(launcherText("Back to widgets")) }
                            if (session.candidate != null) Text(launcherText("Replace here"), color = Ink,
                                modifier = Modifier.testTag("widget-replacement-locked"))
                            val targetPage = homeCellPage(session.targetIndex ?: 0)
                            if (!session.dragging && session.candidate == null) IconButton(
                                enabled = targetPage > -1, onClick = {
                                val local = homeCellLocal(session.targetIndex ?: 0)
                                val page = targetPage - 1
                                widgetSession = session.copy(targetIndex = homeCellIndex(page, local.coerceAtMost(HOME_CELLS - 1)))
                                scope.launch { pager.animateScrollToPage(page) }
                            }) { Icon(Icons.Rounded.ChevronLeft, launcherText("Previous home page")) }
                            Text("${session.span.width} × ${session.span.height}", color = Ink)
                            if (!session.dragging && session.candidate == null) IconButton(enabled = targetPage < homePages, onClick = {
                                val local = homeCellLocal(session.targetIndex ?: 0)
                                val page = (targetPage + 1).coerceAtMost(homePages)
                                widgetSession = session.copy(targetIndex = homeCellIndex(page, local.coerceAtMost(HOME_CELLS - 1)))
                                scope.launch { pager.animateScrollToPage(page) }
                            }) { Icon(Icons.Rounded.ChevronRight, launcherText("Next home page")) }
                            if (!session.dragging) TextButton(enabled = widgetDraft != null, onClick = {
                                widgetDraft?.let { draft ->
                                    val contentSize = specialAnchor?.let { bounds -> with(placementDensity) {
                                        WidgetContentSize(bounds.width.toDp().value / editorScale, bounds.height.toDp().value / editorScale)
                                    } }
                                    session.provider?.let { widgets.add(draft, it, pickerSizing, contentSize) }
                                        ?: session.builtinId?.let { widgets.setBuiltin(draft.copy(id = it)) }
                                    widgetSession = null; sheet = ""; widgetPackage = null
                                }
                            }, modifier = Modifier.testTag("widget-placement-apply")) { Text(launcherText("Place")) }
                            TextButton(onClick = { leaveTemporaryWidgetPage(); widgetSession = null; sheet = ""; widgetPackage = null },
                                modifier = Modifier.testTag("widget-placement-cancel")) { Text(launcherText("Cancel")) }
                        }
                        if (anchor != null) {
                            val density = LocalDensity.current
                            val cellWidthPx = with(density) { (geometry.gridWidth / GRID_COLUMNS).dp.toPx() } * editorScale
                            fun pickerRowTop(row: Int): Float = (if (row <= 2) row * with(density) { topPitch.dp.toPx() }
                                else with(density) { (geometry.widgetHeight + 18f + (row - 2) * geometry.rowHeight).dp.toPx() }) * editorScale
                            val candidateRow = homeCellLocal(visualIndex ?: 0) / GRID_COLUMNS
                            val previewWidth = specialAnchor?.let { with(density) { it.width.toDp() } }
                                ?: with(density) { (cellWidthPx * session.span.width - 10.dp.toPx() * editorScale).toDp() }
                            val previewHeight = specialAnchor?.let { with(density) { it.height.toDp() } }
                                ?: with(density) { (pickerRowTop(candidateRow + session.span.height) -
                                    pickerRowTop(candidateRow) - 18.dp.toPx() * editorScale).coerceAtLeast(48.dp.toPx() * editorScale).toDp() }
                            val previewX = if (specialAnchor != null) anchor.left
                                else anchor.left + with(density) { 5.dp.toPx() } * editorScale
                            Surface(Modifier.offset { IntOffset(previewX.roundToInt(), anchor.top.roundToInt()) }
                                .size(previewWidth, previewHeight).testTag("widget-placement-preview")
                                .semantics { stateDescription = if (widgetDraft != null) launcherText("Ready to place") else launcherText("No room here") },
                                color = if (widgetDraft != null) Glass.copy(alpha = .82f) else Color(0xFFE7B6B6).copy(alpha = .9f),
                                shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(3.dp,
                                    if (widgetDraft != null) Color.White else Color(0xFFFF6B6B))) {
                                Box(Modifier.fillMaxSize()) {
                                    if (sessionEntry != null) WidgetProviderPreview(sessionEntry, session.span,
                                        Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                                    else Column(Modifier.align(Alignment.Center).padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(session.provider?.loadLabel(launcherActivity.packageManager)?.toString()
                                            ?: when (session.builtinId) {
                                                CLOCK_WIDGET -> launcherText("Clock")
                                                DATE_WIDGET -> launcherText("Date")
                                                else -> launcherText("Widget panel")
                                            }, color = Ink,
                                            textAlign = TextAlign.Center)
                                        Text("${session.span.width} × ${session.span.height}", color = Ink)
                                    }
                                    if (widgetDraft == null) Box(Modifier.matchParentSize()
                                        .background(Color(0xFFB83B3B).copy(alpha = .34f)), contentAlignment = Alignment.Center) {
                                        Text(launcherText("No room here"), color = Color.White, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        } else if (session.dragging) {
                            Surface(Modifier.offset { IntOffset((session.pointer.x - 90.dp.toPx()).roundToInt(),
                                (session.pointer.y - 60.dp.toPx()).roundToInt()) }.size(180.dp, 120.dp)
                                .testTag("widget-placement-preview").semantics { stateDescription = launcherText("No room here") },
                                color = Color(0xFFE7B6B6).copy(alpha = .9f), shape = RoundedCornerShape(24.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (sessionEntry != null) WidgetProviderPreview(sessionEntry, session.span,
                                        Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                                    Box(Modifier.matchParentSize().background(Color(0xFFB83B3B).copy(alpha = .34f)),
                                        contentAlignment = Alignment.Center) { Text(launcherText("No room here"), color = Color.White) }
                                }
                            }
                        }
                    }
                }
                widgetPlacementMessage?.let { message ->
                    Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                        color = Glass, shape = RoundedCornerShape(18.dp)) { Text(message, Modifier.padding(16.dp), color = Ink) }
                }
            }
        }
        DisplayCutoutTouchGuards(windowCutout, Modifier.matchParentSize())
        if (drag.active) {
            var ghostRaised by remember(drag.source?.target) { mutableStateOf(false) }
            LaunchedEffect(drag.source?.target) { ghostRaised = true }
            val ghostScale by animateFloatAsState(
                if (ghostRaised) 1.06f else .94f,
                animationSpec = LauncherMotion.lift,
                label = "drag ghost lift",
            )
            if (drag.moved) {
                if (pager.currentPage > 0) Box(Modifier.align(Alignment.CenterStart).width(6.dp).height(112.dp)
                    .background(Color.White.copy(alpha = if (edge < 0) .9f else .3f), RoundedCornerShape(6.dp)).testTag("drag-edge-left"))
                if (pager.currentPage < homePages) Box(Modifier.align(Alignment.CenterEnd).width(6.dp).height(112.dp)
                    .background(Color.White.copy(alpha = if (edge > 0) .9f else .3f), RoundedCornerShape(6.dp)).testTag("drag-edge-right"))
            }
            appsById[drag.source?.appId]?.let { app ->
                val size = 66.dp
                val px = with(LocalDensity.current) { size.toPx() }
                Image(app.icon.asImageBitmap(), "Moving ${app.label}", Modifier
                    .size(size).graphicsLayer {
                        translationX = drag.pointer.x - drag.rootOrigin.x - px / 2
                        translationY = drag.pointer.y - drag.rootOrigin.y - px * .65f
                        scaleX = ghostScale
                        scaleY = ghostScale
                    }.shadow(16.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).testTag("drag-ghost"))
            }
            drag.source?.appId?.let { state.layout.folder(it) }?.let { folder ->
                Surface(Modifier.size(84.dp).graphicsLayer {
                    translationX = drag.pointer.x - drag.rootOrigin.x - 42.dp.toPx()
                    translationY = drag.pointer.y - drag.rootOrigin.y - 52.dp.toPx()
                    scaleX = ghostScale
                    scaleY = ghostScale
                }
                    .shadow(16.dp, RoundedCornerShape(20.dp)).testTag("folder-drag-ghost"),
                    color = Glass.copy(alpha = .96f), shape = RoundedCornerShape(20.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(folder.title, color = Ink, textAlign = TextAlign.Center) }
                }
            }
            drag.source?.widgetId?.let { id ->
                val width = 144.dp; val height = 108.dp
                val x = with(LocalDensity.current) { width.toPx() }
                val y = with(LocalDensity.current) { height.toPx() }
                Surface(Modifier.size(width, height).graphicsLayer {
                    translationX = drag.pointer.x - x / 2
                    translationY = drag.pointer.y - y * .65f
                    scaleX = ghostScale
                    scaleY = ghostScale
                }.shadow(16.dp, RoundedCornerShape(24.dp)).testTag("drag-ghost"),
                    color = Glass.copy(alpha = .95f), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Widgets, null, tint = Ink)
                        Spacer(Modifier.height(8.dp))
                        Text(remember(id, widgets) { widgetLabel(id, widgets) }, color = Ink, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
            if (blockedDock) Surface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding()
                    .padding(top = 10.dp, start = 20.dp, end = 100.dp),
                color = Glass.copy(alpha = .96f), shape = RoundedCornerShape(18.dp)
            ) {
                Text(launcherText("Dock full • Move an app out first"),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = Ink, fontSize = 13.sp)
            }
            if (drag.moved && drag.source?.target !is DropTarget.Library &&
                drag.source?.appId?.let(::isFolderId) != true) Surface(
                // Keep removal in the right-side control area that is vacated during a drag.
                // A centered target overlaps the expanded workspace's right-hand first cell.
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 12.dp)
                    .width(state.sharedPreset.dockWidth.dp).height(64.dp)
                    .dropRegion(drag, DropTarget.Remove).testTag("remove-drop-target"),
                color = if (target == DropTarget.Remove) Color(0xFFB33B3B) else Glass.copy(alpha = .96f), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxSize().padding(vertical = 6.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                    Text(launcherText("Remove"), fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        resizeSlot?.let { slot ->
            val placement = model.placement(slot)
            val bounds = drag.regions[DropTarget.Widget(slot)]?.bounds
            if (placement != null && bounds != null) {
                val minW = resizeConstraints?.minimum?.width ?: 2
                val minH = resizeConstraints?.minimum?.height ?: 2
                val maxW = minOf(GRID_COLUMNS - placement.column, resizeConstraints?.maximum?.width ?: GRID_COLUMNS)
                val maxH = minOf(widgetRowsForPage(placement.page) - placement.row, resizeConstraints?.maximum?.height ?: GRID_ROWS)
                val feasible = placement.page >= -1 && placement.row in 0 until widgetRowsForPage(placement.page) &&
                    !(placement.id >= 0 && resizeConstraints == null) && minW <= maxW && minH <= maxH
                val candidate = resizeWidget(state.layout, slot, resizeWidth, resizeHeight)
                val valid = feasible && ((resizeWidth == placement.spanX && resizeHeight == placement.spanY) || candidate != state.layout)
                val widthPx = (bounds.width + (resizeWidth - placement.spanX) * resizePitchX).coerceAtLeast(resizePitchX)
                val density = LocalDensity.current
                fun resizeRowTop(row: Int) = if (row <= 2) row * resizeTopPitch else 2 * resizeTopPitch + (row - 2) * resizeAppPitch
                val heightPx = (resizeRowTop(placement.row + resizeHeight) - resizeRowTop(placement.row) -
                    with(density) { 18.dp.toPx() }).coerceAtLeast(resizePitchY)
                Box(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                    .border(3.dp, if (valid) Color.White else Color(0xFFFF6B6B), RoundedCornerShape(24.dp))
                    .testTag("widget-resize-preview-$slot")) {
                    Box(Modifier.align(Alignment.BottomEnd).offset(12.dp, 12.dp).size(44.dp)
                        .background(if (valid) Color.White else Color(0xFFFF6B6B), CircleShape)
                        .testTag("widget-resize-handle-$slot")
                        .pointerInput(slot, resizeConstraints) {
                            var dx = 0f; var dy = 0f; var startWidth = resizeWidth; var startHeight = resizeHeight
                            detectDragGestures(onDragStart = {
                                dx = 0f; dy = 0f; startWidth = resizeWidth; startHeight = resizeHeight
                            }, onDrag = { change, amount ->
                                change.consume(); dx += amount.x; dy += amount.y
                                if (feasible && resizeConstraints?.canResizeHorizontally != false)
                                    resizeWidth = (startWidth + (dx / resizePitchX).roundToInt()).coerceIn(minW, maxW)
                                if (feasible && resizeConstraints?.canResizeVertically != false)
                                    resizeHeight = (startHeight + (dy / resizePitchY).roundToInt()).coerceIn(minH, maxH)
                            })
                        }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.OpenInFull, launcherText("Drag to resize widget"), tint = Ink, modifier = Modifier.size(22.dp))
                    }
                    Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                        .background(Glass.copy(alpha = .96f), RoundedCornerShape(20.dp))) {
                        TextButton(onClick = { resizeSlot = null }) { Text(launcherText("Cancel")) }
                        TextButton(enabled = valid, onClick = {
                            model.resizeWidget(slot, resizeWidth, resizeHeight); resizeSlot = null
                        }) { Text(launcherText("Apply")) }
                    }
                    if (!feasible) Text(launcherText("Move this widget into the six-row grid before resizing."),
                        color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .65f)).padding(8.dp))
                }
            }
        }
        selectedId?.let { id ->
            val app = appsById[id]
            val folder = state.layout.folder(id)
            val parentFolder = state.folders.firstOrNull { id in it.appIds }
            val pinned = state.layout.indexOfShortcut(id) != null || id in state.dock || parentFolder != null
            if (app != null || folder != null) {
            Box(Modifier.fillMaxSize().testTag("app-context-overlay")) {
                if (!drag.active) Box(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) { appMoveMenu = false; selectedId = null })
                val menuWidth = 116.dp
                val menuX = with(LocalDensity.current) { appMenuAnchor.x.toDp() - menuWidth / 2 }
                    .coerceIn(16.dp, (with(LocalDensity.current) { windowSize.width.toDp() } - menuWidth - 16.dp).coerceAtLeast(16.dp))
                val menuY = with(LocalDensity.current) { appMenuAnchor.y.toDp() - 100.dp }
                    .coerceIn(32.dp, (with(LocalDensity.current) { windowSize.height.toDp() } - 80.dp).coerceAtLeast(32.dp))
                CompositionLocalProvider(LocalDuoGlassBackdrop provides liveDesktopBackdrop) {
                Surface(Modifier.offset(x = menuX, y = menuY).width(menuWidth)
                    .duoGlass(DuoGlassRole.Floating, RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f), RoundedCornerShape(28.dp)).testTag("app-context-menu"),
                    color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(28.dp)) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        if (folder != null) renameFolderId = id else app?.let(onAppInfo)
                        selectedId = null
                    }, modifier = Modifier.size(48.dp).testTag("context-primary")) {
                        Icon(if (folder != null) Icons.Rounded.Edit else Icons.Rounded.Info,
                            if (folder != null) stringResource(R.string.compact_rename_folder) else launcherText("App info"))
                    }
                    IconButton(enabled = pinned || folder != null, onClick = {
                        if (folder != null) model.dissolveFolder(id)
                        else if (parentFolder != null) model.removeAppFromFolder(parentFolder.id, id, DropTarget.Remove)
                        else model.setPinned(id, false)
                        selectedId = null
                    }, modifier = Modifier.size(48.dp).testTag("context-remove")) {
                        Icon(Icons.Rounded.RemoveCircleOutline,
                            if (folder != null) stringResource(R.string.compact_dissolve_folder) else launcherText("Remove from Home"),
                            tint = if (pinned || folder != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
                    }
                }
                }
                }
            }
        }
        }
        renameFolderId?.let { id ->
            val folder = state.layout.folder(id)
            if (folder != null) {
                var title by remember(id) { mutableStateOf(folder.title) }
                AlertDialog(onDismissRequest = { renameFolderId = null },
                    title = { Text(stringResource(R.string.compact_rename_folder)) },
                    text = { OutlinedTextField(title, { title = it }, singleLine = true,
                        modifier = Modifier.testTag("compact-folder-name"), label = { Text(launcherText("Folder name")) }) },
                    confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                        model.renameFolder(id, title); renameFolderId = null
                    }) { Text(launcherText("Done")) } },
                    dismissButton = { TextButton(onClick = { renameFolderId = null }) { Text(launcherText("Cancel")) } })
            }
        }
        createFolderFirstId?.let { firstId ->
            val first = appsById[firstId]
            AlertDialog(onDismissRequest = { createFolderFirstId = null }, title = { Text(launcherFormat(R.string.ui_create_folder, first?.label.orEmpty())) },
                text = { LazyColumn(Modifier.heightIn(max = 420.dp).testTag("folder-app-picker")) {
                    items(state.apps.filter { it.id != firstId && it.available }, key = { it.id }) { second ->
                        TextButton(onClick = {
                            val preferredPage = state.layout.indexOfShortcut(firstId)?.let(::homeCellPage)
                                ?.takeIf { it >= 0 || expandedWorkspace } ?: lastHomePage.coerceIn(0, homePages - 1)
                            val blocked = state.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }
                            val targetIndex = (0 until HOME_CELLS).map { homeCellIndex(preferredPage, it) }
                                .firstOrNull { it !in blocked && state.layout.slotAt(it) in listOf(null, firstId, second.id) }
                            if (targetIndex != null) model.createFolder(firstId, second.id, targetIndex)
                            createFolderFirstId = null
                        }, modifier = Modifier.fillMaxWidth().testTag("folder-app-${second.id}")) {
                            Text(second.label, Modifier.fillMaxWidth())
                        }
                    }
                } }, confirmButton = { TextButton(onClick = { createFolderFirstId = null }) { Text(launcherText("Cancel")) } })
        }
        launcherActivity.backups.preview?.let { preview ->
            LayoutRestorePreview(preview, onRestore = {
                launcherActivity.backups.applyImport(); sheet = ""
            }, onCancel = launcherActivity.backups::cancelImport)
        }
        if (launcherActivity.backups.pickerPending) AlertDialog(onDismissRequest = {},
            title = { Text(launcherText("Layout document")) },
            text = { Text(launcherText("The system document picker is still open. Return to it to finish, or cancel this operation.")) },
            confirmButton = { TextButton(onClick = { launcherActivity.backups.resumePendingPicker() },
                modifier = Modifier.testTag("backup-picker-resume")) { Text(launcherText("Resume")) } },
            dismissButton = { TextButton(onClick = launcherActivity.backups::cancelImport,
                modifier = Modifier.testTag("backup-picker-cancel")) { Text(launcherText("Cancel")) } })
        if (launcherActivity.backgrounds.pickerPending && !launcherActivity.backgrounds.loading) AlertDialog(
            onDismissRequest = {}, title = { Text(launcherText("Background photo")) },
            text = { Text(launcherText("The photo picker was interrupted. Resume choosing a photo, or cancel and keep the current background.")) },
            confirmButton = { TextButton(onClick = launcherActivity.backgrounds::choosePhoto,
                modifier = Modifier.testTag("background-picker-resume")) { Text(launcherText("Resume")) } },
            dismissButton = { TextButton(onClick = launcherActivity.backgrounds::cancelPendingSelection,
                modifier = Modifier.testTag("background-picker-cancel")) { Text(launcherText("Cancel")) } })
        (launcherActivity.backups.errorMessage ?: launcherActivity.backups.successMessage)?.let { message ->
            AlertDialog(onDismissRequest = launcherActivity.backups::clearMessage,
                title = { Text(if (launcherActivity.backups.errorMessage != null) launcherText("Layout backup problem") else launcherText("Layout backup")) },
                text = { Text(message) }, confirmButton = { TextButton(onClick = launcherActivity.backups::clearMessage) { Text(launcherText("OK")) } })
        }
        widgets.failureMessage?.let { message ->
            AlertDialog(onDismissRequest = widgets::clearFailure, title = { Text(launcherText("Widget not added")) },
                text = { Text(message, Modifier.testTag("widget-bind-error")) },
                confirmButton = { TextButton(onClick = widgets::clearFailure) { Text(launcherText("OK")) } })
        }
        if (widgets.pendingPlacement != null && widgets.setupStatus != null) {
            AlertDialog(onDismissRequest = {}, title = { Text(launcherText("Finish widget setup")) },
                text = { Text(launcherText("The widget is waiting at its chosen spot. Finish setup to add it, or cancel to remove the placeholder.")) },
                confirmButton = { Button(onClick = widgets::finishPendingSetup,
                    modifier = Modifier.semantics { contentDescription = launcherText("Continue widget setup") }) { Text(launcherText("Finish setup")) } },
                dismissButton = { TextButton(onClick = { leaveTemporaryWidgetPage(); widgets.cancelPendingSetup() },
                    modifier = Modifier.semantics { contentDescription = launcherText("Cancel widget setup") }) { Text(launcherText("Cancel")) } })
        }
        widgets.reconfigureWidgetId?.let {
            AlertDialog(onDismissRequest = {}, title = { Text(launcherText("Widget settings")) },
                text = { Text(launcherText("Widget settings were interrupted. Resume configuration, or cancel and keep the widget unchanged.")) },
                confirmButton = { Button(onClick = widgets::finishPendingReconfigure,
                    modifier = Modifier.testTag("widget-reconfigure-resume")) { Text(launcherText("Resume")) } },
                dismissButton = { TextButton(onClick = widgets::cancelPendingReconfigure,
                    modifier = Modifier.testTag("widget-reconfigure-cancel")) { Text(launcherText("Cancel")) } })
        }
        FoldProjectionEdges(
            progress = foldTransition.coverFold,
            side = FoldShadeSide.RIGHT,
            cover = true,
            enabled = { !geometry.expanded },
        )
        }
        openFolderId?.let { id ->
            state.folders.firstOrNull { it.id == id }?.let { folder ->
                val blocked = state.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }
                val destinationPages = (if (expandedWorkspace) listOf(-1) else emptyList()) + (0 until homePages)
                val homeDestinations = destinationPages.mapNotNull { destinationPage ->
                    (0 until HOME_CELLS).map { homeCellIndex(destinationPage, it) }
                        .firstOrNull { it !in blocked && state.layout.slotAt(it) == null }
                }
                FolderPanel(folder, appsById, drag, pager.currentPage, homeDestinations,
                    dockVacancies = state.dock.indices.filter { state.dock[it] == null },
                    onDismiss = { openFolderId = null }, onRename = { model.renameFolder(id, it) },
                    onLaunch = onLaunchFrom,
                    onAppInfo = onAppInfo,
                    onMoveOut = { appId, destination ->
                        if (model.removeAppFromFolder(id, appId, destination)) openFolderId = model.folder(id)?.id
                    })
            } ?: LaunchedEffect(id) { openFolderId = null }
        }
        }
    }
}

/**
 * Keep the wallpaper edge-to-edge while making the physical camera voids inert.
 * The platform bounds update with fold posture and rotation, so this also covers
 * non-Xiaomi devices without model-specific coordinates.
 */
@Composable
private fun DisplayCutoutTouchGuards(cutout: WindowCutoutSnapshot, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(modifier) {
        cutout.bounds.filterNot { it.width == 0 || it.height == 0 }.forEachIndexed { index, rect ->
            Box(Modifier
                .offset { IntOffset(rect.left, rect.top) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .pointerInput(rect) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
                .testTag("cutout-touch-guard-$index")
                .semantics { contentDescription = launcherFormat(R.string.cutout_safety) })
        }
    }
}

@Composable
private fun ProjectedExpandedPane(
    modifier: Modifier,
    foldProgress: State<Float>,
    isLeft: () -> Boolean,
    wallpaperWidthPx: Float,
    viewportLeftPx: () -> Float,
    content: @Composable BoxScope.() -> Unit,
) {
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        // Page/dock geometry is for layout only. The projection is rooted at the
        // full desktop above, so pagination cannot relocate the physical hinge.
        Box(modifier.clipToBounds(), content = content)
        return
    }
    val projectedBackdrop = rememberHazeState()
    val rootBackdrop = LocalDuoGlassBackdrop.current
    val projecting = isLeft() && foldProgress.value < .999f
    Box(modifier.clipToBounds()) {
        Box(Modifier.fillMaxSize().foldLeftPane(foldProgress, isLeft)) {
            // Ordinary paging and edge resistance move content over the stationary
            // root wallpaper. Only a folding pane needs wallpaper in its texture.
            if (projecting) DuneWallpaperViewport(
                fullWidthPx = wallpaperWidthPx,
                viewportLeftPx = viewportLeftPx,
                modifier = Modifier.fillMaxSize().clipToBounds().hazeSource(projectedBackdrop),
            )
            // Sampling a source outside a non-affine RenderEffect makes Haze's
            // source coordinates disagree with the projected layer. Sample the
            // local, unprojected wallpaper first, then project the complete pane.
            CompositionLocalProvider(LocalDuoGlassBackdrop provides
                if (projecting) projectedBackdrop else rootBackdrop) { content() }
        }
        FoldProjectionEdges(
            progress = foldProgress,
            side = FoldShadeSide.LEFT,
            enabled = isLeft,
        )
    }
}

@Composable
private fun ExpandedWorkspace(
    nativePager: androidx.compose.foundation.pager.PagerState,
    motion: WorkspacePageMotion,
    firstHome: Int,
    visibleHomePages: Int,
    panelWidth: Dp,
    paneContentInset: Dp,
    wallpaperWidth: Dp,
    contentHeight: Dp,
    bottomSpace: Dp,
    geometry: HomeGeometry,
    foldProgress: State<Float>,
    state: LauncherState,
    deviceStatus: DeviceStatus,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    previewWidgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    insertionTarget: DropTarget?,
    libraryQuery: String,
    onLibraryQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit,
    onPinned: (String, Boolean) -> Unit,
    onTurnOnWork: (Long) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    val density = LocalDensity.current
    val viewportWidth = motion.pageWidth
    val stride = motion.homeStride
    val initialHomeOrigin = with(density) { (panelWidth + paneContentInset).toPx() }
    val homePaneWidth = with(density) { (geometry.gridWidth + 16f).dp.toPx() }
    val paneInsetPx = with(density) { paneContentInset.toPx() }
    val wallpaperWidthPx = with(density) { wallpaperWidth.toPx() }
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val visibleHomes by remember(nativePager, motion, firstHome, visibleHomePages, initialHomeOrigin, homePaneWidth) {
        derivedStateOf(structuralEqualityPolicy()) {
            val physicalPosition = nativePager.currentPage + nativePager.currentPageOffsetFraction
            val scroll = motion.offset(physicalPosition)
            val intersectingHomes = (0 until visibleHomePages).filter { page ->
                val start = initialHomeOrigin + page * stride
                start + homePaneWidth > scroll && start < scroll + viewportWidth
            }
            val nearestLogicalPage = nativePager.currentPage - firstHome
            // Keep the initial Home pair cached while the leading component page is current.
            // This avoids rebuilding provider RemoteViews midway through the first swipe.
            val retentionAnchor = nearestLogicalPage.coerceAtLeast(0)
            (intersectingHomes + (retentionAnchor - 1..retentionAnchor + 1))
                .filter { it in 0 until visibleHomePages }.distinct().sorted()
        }
    }
    val place: Modifier.(Float) -> Modifier = { x ->
        graphicsLayer {
            val physicalPosition = nativePager.currentPage + nativePager.currentPageOffsetFraction
            translationX = x - motion.offset(physicalPosition)
        }
    }
    val leadingX = initialHomeOrigin - stride
    val showLeading by remember(nativePager, motion, firstHome, panelWidth, leadingX, homePaneWidth) {
        derivedStateOf(structuralEqualityPolicy()) {
            val physicalPosition = nativePager.currentPage + nativePager.currentPageOffsetFraction
            val scroll = motion.offset(physicalPosition)
            panelWidth.value > 0f && physicalPosition - firstHome < 1f &&
                leadingX - scroll + homePaneWidth > 0f
        }
    }
    val libraryPhysicalPage = firstHome + visibleHomePages
    val showLibrary by remember(nativePager, libraryPhysicalPage) {
        derivedStateOf(structuralEqualityPolicy()) {
            nativePager.currentPage + nativePager.currentPageOffsetFraction >= libraryPhysicalPage - 1.25f
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds().testTag("expanded-workspace")) {
        if (showLeading) {
            key("expanded-leading-home") {
                val plateX = leadingX - paneInsetPx
                ProjectedExpandedPane(
                    modifier = Modifier.place(plateX).width(panelWidth).fillMaxHeight()
                        .testTag("expanded-leading-home"),
                    foldProgress = foldProgress,
                    isLeft = { true },
                    wallpaperWidthPx = wallpaperWidthPx,
                    viewportLeftPx = {
                        val position = nativePager.currentPage + nativePager.currentPageOffsetFraction
                        plateX - motion.offset(position)
                    },
                ) {
                    Box(Modifier.offset(x = paneContentInset).width((geometry.gridWidth + 16f).dp).fillMaxHeight()) {
                        HomePagePane(
                            -1, state, previewSlots, previewLeadingSlots, previewWidgetPlacements, appsById, geometry, contentHeight, bottomSpace,
                            widgets, drag, target, insertionTarget, showLargeWidget = true,
                            deviceStatus = deviceStatus,
                            onLaunch = onLaunchFrom, onActions = onActions, onWidget = onWidget,
                            onFolder = onFolder, onEmptyWidget = onEmptyWidget, onRefresh = onRefresh,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }

        visibleHomes.forEach { page ->
            key("expanded-home-$page") {
                stateHolder.SaveableStateProvider("expanded-home-$page") {
                    val contentX = initialHomeOrigin + page * stride
                    val plateX = contentX - paneInsetPx
                    val isLeft = {
                        val position = nativePager.currentPage + nativePager.currentPageOffsetFraction
                        contentX - motion.offset(position) < initialHomeOrigin - stride * .5f
                    }
                    ProjectedExpandedPane(
                        modifier = Modifier.place(plateX).width(panelWidth).fillMaxHeight(),
                        foldProgress = foldProgress,
                        isLeft = isLeft,
                        wallpaperWidthPx = wallpaperWidthPx,
                        viewportLeftPx = {
                            val position = nativePager.currentPage + nativePager.currentPageOffsetFraction
                            plateX - motion.offset(position)
                        },
                    ) {
                        Box(Modifier.offset(x = paneContentInset).width((geometry.gridWidth + 16f).dp).fillMaxHeight()) {
                            HomePagePane(
                                page, state, previewSlots, previewLeadingSlots, previewWidgetPlacements, appsById, geometry, contentHeight, bottomSpace,
                                widgets, drag, target, insertionTarget, showLargeWidget = page > 0,
                                deviceStatus = deviceStatus,
                                onLaunch = onLaunchFrom, onActions = onActions, onWidget = onWidget,
                                onFolder = onFolder,
                                onEmptyWidget = onEmptyWidget,
                                onRefresh = onRefresh,
                            )
                        }
                    }
                }
            }
        }

        if (showLibrary) {
            key("library-pane") {
                // All Apps follows the final home by exactly one pane. At the
                // terminal stop that home remains on the left and this list is right.
                Box(Modifier.place(initialHomeOrigin + visibleHomePages * stride)
                    .width((geometry.gridWidth + 16f).dp).fillMaxHeight()) {
                    AppLibrary(state, libraryQuery, onLibraryQuery, onLaunch, onPinned,
                        onActions = onActions,
                        modifier = Modifier.libraryHomeBounds(geometry, bottomSpace),
                        drag = drag, page = visibleHomePages, onLaunchFrom = onLaunchFrom, onTurnOnWork = onTurnOnWork)
                }
            }
        }
    }
}

// Match HomePagePane's grid inset and usable vertical region on both displays.
// Padding wraps the library surface, keeping its header and touch targets below
// the same safe top edge as the home widgets/icons instead of near the cutout.
private fun Modifier.libraryHomeBounds(geometry: HomeGeometry, bottomSpace: Dp): Modifier =
    fillMaxSize().padding(start = 16.dp, top = geometry.contentTop.dp, bottom = bottomSpace + 8.dp)
        .testTag("library-page")

@Composable
private fun HomePagePane(
    page: Int,
    state: LauncherState,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    previewWidgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    geometry: HomeGeometry,
    contentHeight: Dp,
    bottomSpace: Dp,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    insertionTarget: DropTarget?,
    showLargeWidget: Boolean,
    deviceStatus: DeviceStatus,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit = {},
    onRefresh: () -> Unit,
    contentTopInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val homeScroll = rememberScrollState()
    var paneBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val pageStart = homeCellIndex(page, 0)
    val backgroundTarget = (pageStart until pageStart + HOME_CELLS).firstOrNull { index ->
        state.layout.slotAt(index) == null && state.widgetPlacements.none { index in it.coveredIndices() }
    } ?: pageStart
    val verticalEdge = with(LocalDensity.current) { 64.dp.toPx() }
    val verticalStep = with(LocalDensity.current) { 12.dp.toPx() }
    val picker = LocalWidgetPlacementSession.current
    val pickerPointer = rememberUpdatedState(picker?.takeIf { it.dragging }?.pointer)
    val moving = drag.active || picker?.dragging == true
    LaunchedEffect(moving, page, paneBounds) {
        while (moving) {
            val pointer = pickerPointer.value ?: drag.pointer
            val amount = widgetAutoScrollDelta(pointer, paneBounds, verticalEdge, verticalStep)
            if (amount != 0f) homeScroll.scrollBy(amount)
            delay(16)
        }
    }
    val placementTarget = picker?.targetIndex
    val placementDensity = LocalDensity.current
    LaunchedEffect(placementTarget, picker?.dragging, page) {
        if (picker?.dragging == false && placementTarget != null && homeCellPage(placementTarget) == page) {
            val row = homeCellLocal(placementTarget) / GRID_COLUMNS
            val top = if (row <= 2) row * (geometry.widgetHeight + 18f) / 2f
                else geometry.widgetHeight + 18f + (row - 2) * geometry.rowHeight
            homeScroll.animateScrollTo(with(placementDensity) { top.dp.roundToPx() })
        }
    }
    Box(modifier.testTag("home-page-$page")
        .semantics {
            onLongClick(launcherText("Home options")) {
                if (!drag.active) onEmptyWidget(backgroundTarget)
                !drag.active
            }
        }
        .onGloballyPositioned { paneBounds = it.boundsInRoot() }
        .width((geometry.gridWidth + 16f).dp)
        .height(homePaneViewportHeight(page, contentHeight.value, bottomSpace.value).dp)) {
        Box(Modifier.width(16.dp).fillMaxHeight().testTag("home-options-margin-$page")
            .pointerInput(backgroundTarget, drag.active) {
                detectTapGestures(onLongPress = {
                    if (!drag.active) onEmptyWidget(backgroundTarget)
                })
            })
        Column(Modifier.offset(x = 16.dp).width(geometry.gridWidth.dp).fillMaxHeight()
            .testTag("home-scroll-$page")
            .verticalScroll(homeScroll)
            .padding(top = geometry.contentTop.dp + contentTopInset, bottom = 8.dp)) {
            SharedHomeGrid(page, state.homeSlots, state.leadingSlots, previewSlots, previewLeadingSlots, previewWidgetPlacements,
                appsById, geometry, state.labels, widgets, drag, target, deviceStatus,
                folders = state.folders, onLaunch = onLaunch, onActions = onActions, onWidget = onWidget,
                onFolder = onFolder, onEmptyWidget = onEmptyWidget)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
            if (state.error != null) Text(state.error, color = Color.White,
                modifier = Modifier.clickable(onClick = onRefresh).padding(12.dp))
        }
    }
}

@Composable
private fun CircleControl(icon: ImageVector, label: String, tag: String, visualSize: Dp, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick = action, interactionSource = interaction,
        modifier = Modifier.size(visualSize.coerceAtLeast(48.dp)).testTag(tag)) {
        Box(Modifier.size(visualSize).testTag("$tag-visual")
            .duoGlass(DuoGlassRole.Control, CircleShape, interaction), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (tag == "search") Color.White else duoGlassContentColor(DuoGlassRole.Control), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SharedHomeGrid(
    page: Int,
    savedSlots: List<String?>,
    savedLeadingSlots: List<String?>,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    widgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    geometry: HomeGeometry,
    labels: Boolean,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    deviceStatus: DeviceStatus,
    folders: List<FolderEntry>,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit,
) {
    val rowHeight = geometry.rowHeight
    val iconSize = geometry.iconSize
    val pageStart = homeCellIndex(page, 0)
    val pageRange = pageStart until pageStart + HOME_CELLS
    fun savedAt(index: Int) = if (page == -1) savedLeadingSlots.getOrNull(homeCellLocal(index)) else savedSlots.getOrNull(index)
    fun previewAt(index: Int) = if (page == -1) previewLeadingSlots.getOrNull(homeCellLocal(index)) else previewSlots.getOrNull(index)
    fun savedIndexOf(id: String) = shortcutIndexOnPage(page, id, savedSlots, savedLeadingSlots)
    fun previewIndexOf(id: String) = shortcutIndexOnPage(page, id, previewSlots, previewLeadingSlots)
    val draggedId = drag.source?.appId
    val homeTarget = (target as? DropTarget.Home)?.index
    val source = drag.source?.target as? DropTarget.Home
    val draggedPreviewIndex = draggedId?.let(::previewIndexOf)
    val hiddenIndex = when {
        !drag.active || !drag.moved -> null
        homeTarget != null -> draggedPreviewIndex
        source != null && target !is DropTarget.Dock -> draggedPreviewIndex
        else -> null
    }
    val dimDragged = drag.active && !drag.moved && source != null
    val pending = widgets.pendingPlacement?.takeIf { it.page == page }
    val pendingIsReplacement = pending != null && widgetPlacements.any { it.slot == pending.slot }
    val pageWidgets = widgetPlacements.filter { it.page == page } + listOfNotNull(pending?.takeUnless { pendingIsReplacement })
    val editingWidgets = drag.source?.target is DropTarget.Widget || LocalWidgetPlacementSession.current != null
    val renderedRows = homeRenderedRows(page, pageWidgets.maxOfOrNull { it.row + it.spanY } ?: 0, editingWidgets)
    val topPitch = (geometry.widgetHeight + 18f) / 2f
    fun rowTop(row: Int) = if (row <= 2) row * topPitch else geometry.widgetHeight + 18f + (row - 2) * rowHeight
    BoxWithConstraints(Modifier.fillMaxWidth().height(rowTop(renderedRows).dp)) {
        val density = LocalDensity.current
        val cellWidth = maxWidth / 4
        val cellWidthPx = with(density) { cellWidth.toPx() }
        val rowHeightPx = with(density) { rowHeight.dp.toPx() }

        repeat(if (page == -1) renderedRows * GRID_COLUMNS else HOME_CELLS) { localIndex ->
            val globalIndex = homeCellIndex(page, localIndex)
            val cell = DropTarget.Home(globalIndex)
            val savedId = savedAt(globalIndex)
            val savedApp = appsById[savedId]
            val savedFolder = folders.firstOrNull { it.id == savedId }
            val previewId = previewAt(globalIndex)
            val highlighted = drag.active && target == cell
            val gap = hiddenIndex == globalIndex
            val cellInteraction = remember(globalIndex) { MutableInteractionSource() }
            val row = localIndex / GRID_COLUMNS
            val cellHeight = rowTop(row + 1) - rowTop(row)
            Box(Modifier.offset(x = cellWidth * (localIndex % GRID_COLUMNS), y = rowTop(row).dp)
                .width(cellWidth).height(cellHeight.dp).testTag("home-cell-$globalIndex")
                .dropRegion(drag, cell, savedApp?.id ?: savedFolder?.id, page)
                .combinedClickable(interactionSource = cellInteraction, indication = null,
                    onClick = { savedFolder?.let { onFolder(it.id) } },
                    onLongClick = { if (savedId == null && !drag.active) onEmptyWidget(globalIndex) })
                .background(if (highlighted) Glass.copy(alpha = .25f) else Color.Transparent, RoundedCornerShape(16.dp))
                .border(if (highlighted) 2.dp else 0.dp,
                    if (highlighted) Color.White.copy(alpha = .8f) else Color.Transparent, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.TopCenter) {
                if (drag.active && drag.source?.appId != null && (gap || previewId == null)) Box(
                    Modifier.size(iconSize.dp).testTag(if (gap) "drag-gap-home-$globalIndex" else "empty-home-slot-$globalIndex")
                        .background(Glass.copy(alpha = if (gap) .16f else .08f), RoundedCornerShape(18.dp))
                        .border(if (gap) 2.dp else 1.dp, Color.White.copy(alpha = if (gap) .55f else .3f), RoundedCornerShape(18.dp)))
            }
        }

        val ids = (if (page == -1) savedLeadingSlots + previewLeadingSlots
            else savedSlots.slicePage(pageRange) + previewSlots.slicePage(pageRange)).filterNotNull().distinct()
        ids.forEach { id ->
            val savedIndex = savedIndexOf(id)
            val previewIndex = previewIndexOf(id)
            val renderIndex = previewIndex?.takeIf { it in pageRange } ?: savedIndex?.takeIf { it in pageRange } ?: return@forEach
            val app = appsById[id] ?: return@forEach
            key(id) {
                val localIndex = renderIndex - pageStart
                val row = localIndex / GRID_COLUMNS
                var entered by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { entered = true }
                val entryScale by animateFloatAsState(
                    if (entered) 1f else .9f,
                    animationSpec = LauncherMotion.settle,
                    label = "home insertion scale $id",
                )
                val entryAlpha by animateFloatAsState(
                    if (entered) 1f else 0f,
                    animationSpec = LauncherMotion.settle,
                    label = "home insertion alpha $id",
                )
                val animatedOffset by animateIntOffsetAsState(
                    IntOffset(((localIndex % GRID_COLUMNS) * cellWidthPx).roundToInt(), with(density) { rowTop(row).dp.toPx() }.roundToInt()),
                    animationSpec = LauncherMotion.placement,
                    label = "home insertion $id",
                )
                val visible = previewIndex != null && previewIndex in pageRange && renderIndex != hiddenIndex
                val opacity by animateFloatAsState(
                    if (dimDragged && id == draggedId) .28f else 1f,
                    label = "home insertion visibility $id",
                )
                Box(Modifier.width(cellWidth).height(rowHeight.dp).graphicsLayer {
                    translationX = animatedOffset.x.toFloat()
                    translationY = animatedOffset.y.toFloat()
                    scaleX = entryScale
                    scaleY = entryScale
                }
                    .alpha(opacity * entryAlpha).testTag("home-app-$id"), contentAlignment = Alignment.TopCenter) {
                    if (visible) AppTile(app, iconSize, labels,
                        onClick = { onLaunch(app, it) }, onLongClick = { onActions(app) })
                }
            }
        }
        folders.forEach { folder ->
            val savedIndex = savedIndexOf(folder.id)
            val previewIndex = previewIndexOf(folder.id)
            if (previewIndex == null || previewIndex == hiddenIndex) return@forEach
            val renderIndex = previewIndex?.takeIf { it in pageRange } ?: savedIndex?.takeIf { it in pageRange } ?: return@forEach
            val localIndex = renderIndex - pageStart
            val row = localIndex / GRID_COLUMNS
            val animatedOffset by animateIntOffsetAsState(
                IntOffset(((localIndex % GRID_COLUMNS) * cellWidthPx).roundToInt(),
                    with(density) { rowTop(row).dp.toPx() }.roundToInt()),
                animationSpec = LauncherMotion.placement,
                label = "folder insertion ${folder.id}",
            )
            FolderTile(folder, appsById, iconSize, labels, drag, page,
                Modifier.width(cellWidth).height(rowHeight.dp).graphicsLayer {
                    translationX = animatedOffset.x.toFloat()
                    translationY = animatedOffset.y.toFloat()
                }
                    .testTag("home-folder-${folder.id}"), onClick = { onFolder(folder.id) })
        }
        pageWidgets.forEach { placement ->
            key("widget-${placement.slot}") {
                val x = cellWidth * placement.column + (cellWidth - iconSize.dp) / 2
                val width = (cellWidth * (placement.spanX - 1) + iconSize.dp).coerceAtLeast(1.dp)
                val y = rowTop(placement.row)
                val height = (rowTop(placement.row + placement.spanY - 1) - y + iconSize).coerceAtLeast(1f)
                val widgetWidth by animateDpAsState(
                    width,
                    animationSpec = LauncherMotion.size,
                    label = "widget width ${placement.slot}",
                )
                val widgetHeight by animateDpAsState(
                    height.dp,
                    animationSpec = LauncherMotion.size,
                    label = "widget height ${placement.slot}",
                )
                val widgetOffset by animateIntOffsetAsState(
                    IntOffset(with(density) { x.toPx() }.roundToInt(), with(density) { y.dp.toPx() }.roundToInt()),
                    animationSpec = LauncherMotion.placement,
                    label = "widget placement ${placement.slot}",
                )
                var widgetEntered by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { widgetEntered = true }
                val widgetScale by animateFloatAsState(
                    if (widgetEntered) 1f else .94f,
                    animationSpec = LauncherMotion.settle,
                    label = "widget insertion scale ${placement.slot}",
                )
                val widgetAlpha by animateFloatAsState(
                    if (widgetEntered) 1f else 0f,
                    animationSpec = LauncherMotion.settle,
                    label = "widget insertion alpha ${placement.slot}",
                )
                val widgetModifier = Modifier.width(widgetWidth).height(widgetHeight).graphicsLayer {
                    translationX = widgetOffset.x.toFloat()
                    translationY = widgetOffset.y.toFloat()
                    scaleX = widgetScale
                    scaleY = widgetScale
                    alpha = widgetAlpha
                }
                if (placement == pending) Surface(widgetModifier
                    .testTag("widget-pending-${placement.slot}").semantics(mergeDescendants = true) {
                        contentDescription = launcherFormat(R.string.pending_widget, widgets.pendingProvider?.shortClassName ?: launcherText("Widgets"))
                    }, color = Glass.copy(alpha = .72f),
                    shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(2.dp, Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(8.dp)); Text(launcherText("Finish widget setup"), color = Ink)
                    }
                } else MovableWidget(placement.id, placement.slot, widgets, drag, target,
                    widgetModifier,
                    page = page, deviceStatus = deviceStatus, wide = placement.spanX >= GRID_COLUMNS) {
                    onWidget(placement.slot)
                }
                if (labels || page == -1) {
                    val name = remember(placement.id, widgets) { widgetLabel(placement.id, widgets) }
                    Text(name, color = Color.White, fontSize = 11.sp, lineHeight = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.width(widgetWidth).graphicsLayer {
                            translationX = widgetOffset.x.toFloat()
                            translationY = widgetOffset.y + with(density) { widgetHeight.toPx() + 4.dp.toPx() }
                            alpha = widgetAlpha
                        }
                            .alpha(if (drag.source?.target == DropTarget.Widget(placement.slot)) .3f else 1f)
                            .testTag("widget-label-${placement.slot}"))
                }
            }
        }
    }
}

@Composable
private fun DockAppColumn(
    savedDock: List<String?>,
    previewDock: List<String?>,
    appsById: Map<String, AppEntry>,
    rowHeight: Float,
    iconSize: Float,
    drag: HomeDragState,
    target: DropTarget?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onChoose: (Int) -> Unit,
) {
    val draggedId = drag.source?.appId
    val dockTarget = (target as? DropTarget.Dock)?.index
    val source = drag.source?.target as? DropTarget.Dock
    val draggedPreviewIndex = previewDock.indexOf(draggedId)
    val hiddenIndex = when {
        !drag.active || !drag.moved -> null
        dockTarget != null -> draggedPreviewIndex.takeIf { it >= 0 }
        source != null && target !is DropTarget.Home -> draggedPreviewIndex.takeIf { it >= 0 }
        else -> null
    }
    val dimDragged = drag.active && !drag.moved && source != null
    val launchBounds = remember(savedDock.size) { List(savedDock.size) { android.graphics.Rect() } }
    val interactions = remember(savedDock.size) { List(savedDock.size) { MutableInteractionSource() } }
    val slotScales = savedDock.indices.map { index ->
        val pressed by interactions[index].collectIsPressedAsState()
        val scale by animateFloatAsState(
            if (pressed) .92f else 1f,
            animationSpec = LauncherMotion.lift,
            label = "dock press $index",
        )
        scale
    }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.dp.toPx() }
    Box(Modifier.fillMaxWidth().height((rowHeight * savedDock.size).dp)) {
        savedDock.indices.forEach { index ->
            val cell = DropTarget.Dock(index)
            val savedApp = appsById[savedDock[index]]
            val previewId = previewDock.getOrNull(index)
            val highlighted = drag.active && target == cell
            val gap = hiddenIndex == index
            Box(Modifier.fillMaxWidth().height(rowHeight.dp).offset(y = (rowHeight * index).dp)
                .background(if (highlighted) Color.White.copy(alpha = .3f) else Color.Transparent, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                when {
                    gap -> Box(Modifier.size(iconSize.dp).testTag("drag-gap-dock-$index")
                        .background(Glass.copy(alpha = .16f), RoundedCornerShape(14.dp))
                        .border(2.dp, Color.White.copy(alpha = .55f), RoundedCornerShape(14.dp)))
                    previewId == null -> Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(rowHeight.dp).offset(y = (rowHeight * index).dp)
                .testTag("dock-slot-$index").dropRegion(drag, cell, savedApp?.id)
                .semantics(mergeDescendants = true) { contentDescription = savedApp?.label ?: "Choose dock app ${index + 1}" }
                .combinedClickable(interactionSource = interactions[index], indication = LocalIndication.current, role = Role.Button, onClick = {
                    if (savedApp != null) onLaunch(savedApp, launchBounds[index]) else onChoose(index)
                }, onLongClick = null)
                .semantics { onLongClick(launcherFormat(R.string.choose_dock_app)) { onChoose(index); true } })
        }

        val ids = (savedDock + previewDock).filterNotNull().distinct()
        ids.forEach { id ->
            val savedIndex = savedDock.indexOf(id)
            val previewIndex = previewDock.indexOf(id)
            val renderIndex = previewIndex.takeIf { it >= 0 } ?: savedIndex.takeIf { it >= 0 } ?: return@forEach
            val app = appsById[id] ?: return@forEach
            key(id) {
                val animatedOffset by animateIntOffsetAsState(
                    IntOffset(0, (renderIndex * rowHeightPx).roundToInt()),
                    animationSpec = LauncherMotion.placement,
                    label = "dock insertion $id")
                val visible = previewIndex >= 0 && renderIndex != hiddenIndex
                val opacity by animateFloatAsState(
                    if (!visible) 0f else if (dimDragged && id == draggedId) .28f else 1f,
                    label = "dock insertion visibility $id",
                )
                Box(Modifier.fillMaxWidth().height(rowHeight.dp).graphicsLayer {
                    translationY = animatedOffset.y.toFloat()
                }.alpha(opacity)
                    .testTag("dock-app-$id"), contentAlignment = Alignment.Center) {
                    Image(app.icon.asImageBitmap(), null, Modifier.size(iconSize.dp).testTag("dock-icon-$id")
                        .onGloballyPositioned { if (savedIndex >= 0) launchBounds[savedIndex].set(it.boundsInWindow().toAndroidBounds()) }
                        .graphicsLayer { scaleX = slotScales[renderIndex]; scaleY = slotScales[renderIndex] }
                        .clip(RoundedCornerShape(11.dp)))
                }
            }
        }
    }
}

private fun <T> List<T>.slicePage(range: IntRange): List<T> =
    if (isEmpty() || range.first >= size) emptyList() else subList(range.first, minOf(range.last + 1, size))

@Composable
private fun FolderTile(folder: FolderEntry, apps: Map<String, AppEntry>, size: Float, labels: Boolean,
    drag: HomeDragState, page: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
        .semantics(mergeDescendants = true) {
        contentDescription = launcherFormat(R.string.folder_summary, folder.title, folder.appIds.size)
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape((size * .24f).dp)
        Box(Modifier.size(size.dp).duoGlass(DuoGlassRole.Control, shape, interaction)
            .dropRegion(drag, DropTarget.Folder(folder.id), page = page, folderId = folder.id)
            .testTag("folder-drop-${folder.id}")) {
            folder.appIds.take(4).forEachIndexed { index, id ->
                apps[id]?.let { app ->
                    Image(app.icon.asImageBitmap(), null, Modifier.align(when (index) {
                        0 -> Alignment.TopStart; 1 -> Alignment.TopEnd; 2 -> Alignment.BottomStart; else -> Alignment.BottomEnd
                    }).padding(5.dp).size((size * .38f).dp).clip(RoundedCornerShape(6.dp)))
                }
            }
        }
        if (labels) HomeShortcutLabel(folder.title)
    }
}

@Composable
private fun AppTile(app: AppEntry, size: Float, labels: Boolean, modifier: Modifier = Modifier, onClick: (android.graphics.Rect) -> Unit, onLongClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) .92f else 1f,
        animationSpec = LauncherMotion.lift,
        label = "app press",
    )
    val iconSize by animateDpAsState(size.dp, label = "icon size")
    val bounds = remember { android.graphics.Rect() }
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = app.label }
        .clickable(interactionSource = interaction, indication = null,
            role = Role.Button, onClick = { onClick(bounds) })
        .semantics { onLongClick(launcherFormat(R.string.app_options)) { onLongClick(); true } }.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Image(app.icon.asImageBitmap(), null, Modifier.size(iconSize).onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) }
            .graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape((size * .24f).dp)))
        if (labels) HomeShortcutLabel(app.label)
    }
}

@Composable
private fun HomeShortcutLabel(text: String) {
    Text(text, color = Color.White, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 1f), 3f)),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp))
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val interaction = remember { MutableInteractionSource() }
    Surface(modifier.fillMaxSize().duoGlass(DuoGlassRole.Card, shape, interaction)
        .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        color = Color.Transparent, contentColor = duoGlassContentColor(DuoGlassRole.Card), shape = shape) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween, content = content)
    }
}

@Composable
private fun currentTime(): LocalDateTime {
    val time by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now(); delay(1000) } }
    return time
}

@Composable
private fun ClockCard(onClick: () -> Unit) {
    val time = currentTime()
    val format = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    GlassCard(onClick = onClick) {
        val contentColor = LocalContentColor.current
        Icon(Icons.Rounded.Schedule, launcherText("Clock widget; tap to replace"), tint = contentColor, modifier = Modifier.size(20.dp))
        Text(time.format(DateTimeFormatter.ofPattern(format)), color = contentColor, fontWeight = FontWeight.Light, fontSize = 30.sp, maxLines = 1)
        Text(launcherText("Local time"), color = contentColor.copy(alpha = .76f), fontSize = 11.sp)
    }
}

@Composable
private fun DateCard(onClick: () -> Unit) {
    val date = currentTime()
    GlassCard(onClick = onClick) {
        val contentColor = LocalContentColor.current
        Text(date.format(DateTimeFormatter.ofPattern("EEEE")), color = contentColor, fontSize = 12.sp, maxLines = 1)
        Text(date.dayOfMonth.toString(), color = contentColor, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 42.sp)
        Text(date.format(DateTimeFormatter.ofPattern("MMMM")), color = contentColor.copy(alpha = .76f), fontSize = 12.sp)
    }
}

@Composable
private fun ExpandedCard(onClick: () -> Unit) {
    val date = currentTime()
    GlassCard(onClick = onClick) {
        Column {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE")), color = Color.White, fontSize = 22.sp)
            Text(date.format(DateTimeFormatter.ofPattern("MMMM d")), color = Color.White.copy(alpha = .8f), fontSize = 16.sp)
        }
        Column {
            Icon(Icons.Rounded.Widgets, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
            Text(launcherText("A little more room."), color = Color.White, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(12.dp))
            Text(launcherText("Add a calendar, photos, or another widget."), color = Color.White.copy(alpha = .85f), fontSize = 14.sp)
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onClick) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(launcherText("Add widget")) }
        }
    }
}

@Composable
private fun DeviceOverviewCard(status: DeviceStatus, onClick: () -> Unit) {
    val network = when {
        status.airplane -> "Airplane mode"
        status.wifiConnected -> "Wi-Fi connected"
        status.cellularTechnology.label != null -> status.cellularTechnology.label
        status.cellularLevel != null -> "Mobile network"
        else -> "No connection"
    }
    val networkIcon = when {
        status.airplane -> Icons.Rounded.AirplanemodeActive
        status.wifiConnected -> Icons.Rounded.Wifi
        else -> Icons.Rounded.SignalCellularAlt
    }
    GlassCard(onClick = onClick) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(launcherText("Device overview"), color = Color.White, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(network ?: "Mobile network", color = Color.White.copy(alpha = .78f),
                    fontSize = 12.sp)
            }
            Icon(networkIcon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (status.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryFull,
                null, tint = Color.White, modifier = Modifier.size(24.dp))
            Text(status.battery?.let { "$it%" } ?: launcherFormat(R.string.ui_battery_unavailable), color = Color.White,
                fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (status.charging) Text(launcherText("Charging"), color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun WidgetSlot(id: Int, slot: Int, controller: WidgetController, modifier: Modifier, onAdd: () -> Unit, fallback: @Composable () -> Unit) {
    var restoreMessage by remember(slot) { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(24.dp)).testTag("widget-slot-$slot")) {
        val displayedContentSize = WidgetContentSize(maxWidth.value, maxHeight.value)
        if (id in DEFAULT_NATIVE_PROVIDERS && id != WEATHER_WIDGET && id != MONTH_WIDGET) {
            val provider = remember(id) { controller.defaultProvider(id) }
            LaunchedEffect(id, slot) { controller.bindDefaultWidget(slot, displayedContentSize) }
            DashboardPlaceholder(id, provider != null) {
                if (!controller.bindDefaultWidget(slot, displayedContentSize, interactive = true)) onAdd()
            }
            return@BoxWithConstraints
        }
        if (id == NEEDS_BINDING_WIDGET) {
            val restore = controller.restoreDescriptor(slot)
            val restoreShape = RoundedCornerShape(24.dp)
            Surface(Modifier.fillMaxSize().duoGlass(DuoGlassRole.Elevated, restoreShape)
                .testTag("widget-restore-$slot"), color = Color.Transparent,
                contentColor = duoGlassContentColor(DuoGlassRole.Elevated), shape = restoreShape) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(restore?.title ?: "Saved widget", color = Ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(restore?.profileLabel ?: "Unavailable profile", color = Ink.copy(alpha = .72f),
                        style = MaterialTheme.typography.bodySmall)
                    restoreMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                    Row {
                        TextButton(onClick = {
                            if (!controller.rebindRestoredWidget(slot, contentSize = displayedContentSize))
                                restoreMessage = "That provider or profile isn’t available. Choose a replacement."
                        },
                            modifier = Modifier.testTag("widget-restore-reconnect-$slot")) { Text(launcherText("Reconnect")) }
                        TextButton(onClick = onAdd, modifier = Modifier.testTag("widget-restore-replace-$slot")) { Text(launcherText("Replace")) }
                    }
                }
            }
            return@BoxWithConstraints
        }
        val info = remember(id) { if (id >= 0) controller.manager.getAppWidgetInfo(id) else null }
        if (info == null) fallback()
        else {
            key(id) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { context ->
                        val nativeView = controller.host.createView(context, id, info)
                        if (info.provider.flattenToString() == "com.moji.mjweather/com.moji.mjweather.CMojiWidget4x2")
                            FittedNativeWidget(context, nativeView, minimumHeightDp = 200f)
                        else nativeView
                    }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun widgetLabel(id: Int, controller: WidgetController) = when (id) {
    CLOCK_WIDGET -> launcherText("Clock")
    DATE_WIDGET -> launcherText("Calendar")
    INFO_WIDGET -> launcherText("Widget panel")
    EMPTY_WIDGET -> launcherText("Add widget")
    in BUILTIN_WIDGET_IDS -> dashboardWidgetTitle(id)
    else -> controller.label(id)
}

@Composable
private fun MovableWidget(id: Int, slot: Int, controller: WidgetController, drag: HomeDragState,
    target: DropTarget?, modifier: Modifier, page: Int, deviceStatus: DeviceStatus,
    wide: Boolean, onAdd: () -> Unit) {
    val cell = DropTarget.Widget(slot)
    WidgetSlot(id, slot, controller, modifier.dropRegion(drag, cell, page = page, widgetId = id)
        .alpha(if (drag.source?.target == cell) .3f else 1f)
        .border(if (drag.active && target == cell) 2.dp else 0.dp,
            if (drag.active && target == cell) Color.White else Color.Transparent, RoundedCornerShape(24.dp))
        .semantics { onLongClick(launcherFormat(R.string.move_replace_widget)) { onAdd(); true } }, onAdd) {
        when (id) {
            CLOCK_WIDGET -> ReferenceClockCard(onAdd)
            DATE_WIDGET, MONTH_WIDGET -> ReferenceCalendarCard(onAdd)
            WEATHER_WIDGET -> ReferenceWeatherCard(onAdd)
            BATTERY_WIDGET -> BatteryRingCard(deviceStatus, onAdd)
            INFO_WIDGET -> if (wide) DeviceOverviewCard(deviceStatus, onAdd) else GlassCard(onClick = onAdd) {
                Icon(Icons.Rounded.Widgets, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Text(launcherText("Your widgets"), color = Color.White, fontSize = 15.sp, maxLines = 1)
                Text(launcherText("Tap to choose"), color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
            }
            else -> {
                val emptyShape = RoundedCornerShape(24.dp)
                val interaction = remember { MutableInteractionSource() }
                Surface(Modifier.fillMaxSize().duoGlass(DuoGlassRole.Card, emptyShape, interaction)
                    .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onAdd),
                    color = Color.Transparent, contentColor = duoGlassContentColor(DuoGlassRole.Card), shape = emptyShape) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Add, null)
                    Text(if (id >= 0) "Widget unavailable" else launcherText("Add widget"), fontSize = 12.sp)
                }
            }
            }
        }
    }
}

@Composable
private fun AppPicker(apps: List<AppEntry>, dockSlot: Int?, onSelect: (AppEntry) -> Unit, onClear: () -> Unit,
    onLongClick: (AppEntry) -> Unit, canSelect: (AppEntry) -> Boolean = { true }, blockedHint: String? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) { apps.filter { it.label.contains(query.trim(), ignoreCase = true) } }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).padding(horizontal = 20.dp).imePadding()) {
        Text(if (dockSlot == null) "Your apps" else "Dock position ${dockSlot + 1}", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("search-field"),
            placeholder = { Text(launcherText("Search apps")) }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, launcherText("Clear search")) } }, shape = RoundedCornerShape(20.dp))
        if (dockSlot != null) TextButton(onClick = onClear) { Text(launcherText("Leave this position empty")) }
        if (blockedHint != null) Text(blockedHint, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp).testTag("dock-full-guidance"))
        LazyColumn(Modifier.weight(1f)) {
            if (filtered.isEmpty()) item { Text(launcherText("No apps found"), Modifier.padding(vertical = 24.dp)) }
            items(filtered, key = { it.id }) { app ->
                val enabled = canSelect(app)
                Row(Modifier.fillMaxWidth().testTag("picker-app-${app.id}")
                    .combinedClickable(enabled = enabled, onClick = { onSelect(app) }, onLongClick = { onLongClick(app) })
                    .alpha(if (enabled) 1f else .45f)
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(app.icon.asImageBitmap(), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                    Text(app.label, Modifier.padding(start = 16.dp).weight(1f), maxLines = 2)
                    if (dockSlot != null && enabled) Icon(Icons.Rounded.Add, "Choose ${app.label}")
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(state: LauncherState, initiallyWide: Boolean, model: LauncherModel, isDefaultHome: Boolean,
    onMakeDefault: () -> Unit, onClose: () -> Unit, onEditPins: () -> Unit, onWidget: (Int) -> Unit,
    onAddWidget: (Int) -> Unit, onRemoveWidget: (Int) -> Unit, onWallpaperPreview: () -> Unit,
    onExportLayout: () -> Unit, onImportLayout: () -> Unit,
    appearance: AppearanceState, onAppearanceMode: (AppearanceMode) -> Unit,
    onAppearanceManual: (String, Double, Double) -> Unit, onAppearanceDeviceLocation: () -> Unit,
    onAppearanceClear: () -> Unit,
    backgrounds: LauncherBackgroundController,
    homePage: Int = 0) {
    var wide by rememberSaveable { mutableStateOf(initiallyWide) }
    val p = state.sharedPreset
    Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Make it yours", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close customization") }
        }
        Button(onClick = onMakeDefault, modifier = Modifier.fillMaxWidth().testTag("default-home-settings")) {
            Text(if (isDefaultHome) "Change home app" else "Set as home app")
        }
        TextButton(onClick = onEditPins, modifier = Modifier.fillMaxWidth()) { Text("Choose home apps") }
        if (state.canUndoEdit) TextButton(onClick = { model.undoEdit(); onClose() }, modifier = Modifier.fillMaxWidth()) {
            Text("Undo last layout change")
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("The cover and inner Home use the same layout.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingSlider("App icon size", "${p.iconSize.toInt()} dp", p.iconSize, 40f..54f) { model.setPreset(p.copy(iconSize = it)) }
        SettingSlider("Space between rows", "${p.rowGap.toInt()} dp", p.rowGap, 0f..28f) { model.setPreset(p.copy(rowGap = it)) }
        SettingSlider("Dock width", "${p.dockWidth.toInt()} dp", p.dockWidth, 56f..84f) { model.setPreset(p.copy(dockWidth = it)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Align dock with app rows", Modifier.weight(1f))
            Switch(p.dockAlignToGrid, { model.setPreset(p.copy(dockAlignToGrid = it)) })
        }
        if (!p.dockAlignToGrid) SettingSlider("Dock height on screen", "${(p.dockPosition * 100).toInt()}%", p.dockPosition, .25f.. .75f) { model.setPreset(p.copy(dockPosition = it)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show app names", Modifier.weight(1f)); Switch(state.labels, model::setLabels, Modifier.testTag("label-switch"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Status at upper right", Modifier.weight(1f)); Switch(state.verticalStatus, model::setVerticalStatus, Modifier.testTag("status-switch"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Search button opens Google", Modifier.weight(1f))
            Switch(state.googleSearch, model::setGoogleSearch, Modifier.testTag("google-search-switch"))
        }
        Text("Opens Google’s search screen. All apps keeps local app search.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { model.setPreset(LayoutPreset()) }) { Text("Reset this layout") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        TextButton(onClick = onWallpaperPreview, modifier = Modifier.fillMaxWidth().testTag("wallpaper-preview")) {
            Icon(Icons.Rounded.Wallpaper, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Apply matching wallpaper")
        }
        Text("Preview the current launcher background in Android’s wallpaper picker, then choose where to apply it.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Launcher background", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Button(onClick = backgrounds::choosePhoto, enabled = !backgrounds.loading,
            modifier = Modifier.fillMaxWidth().testTag("background-choose")) { Text("Choose background photo") }
        if (backgrounds.photoSelected) OutlinedButton(onClick = backgrounds::reset,
            modifier = Modifier.fillMaxWidth().testTag("background-reset")) { Text("Reset to Duo dunes") }
        if (backgrounds.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("background-loading"))
        (backgrounds.errorMessage ?: backgrounds.successMessage)?.let { message ->
            TextButton(onClick = backgrounds::clearMessage, Modifier.fillMaxWidth().testTag("background-message")) { Text(message) }
        }
        Text("The selected photo stays on this device and is not included in layout backups.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        AppearanceSettings(appearance, onAppearanceMode, onAppearanceManual, onAppearanceDeviceLocation, onAppearanceClear)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text(launcherText("Layout backup"), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExportLayout, modifier = Modifier.weight(1f).testTag("layout-export")) { Text(launcherText("Save")) }
            OutlinedButton(onClick = onImportLayout, modifier = Modifier.weight(1f).testTag("layout-import")) { Text(launcherText("Restore")) }
        }
        Text("Restore always shows a review before changing Home.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Widgets · Page ${homePage + 1}", style = MaterialTheme.typography.titleMedium)
        state.widgetPlacements.filter { it.page == homePage }.forEach { placement ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${placement.spanX} × ${placement.spanY} widget · row ${placement.row + 1}", Modifier.weight(1f))
                IconButton(onClick = { onRemoveWidget(placement.slot) },
                    modifier = Modifier.semantics { contentDescription = "Remove widget" }) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                }
                TextButton(onClick = { onWidget(placement.slot) }) { Text(launcherText("Replace")) }
            }
        }
        if (wide) state.widgetPlacements.filter { it.page == -1 }.forEach { placement ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Unfolded-only page", Modifier.weight(1f))
                IconButton(onClick = { onRemoveWidget(placement.slot) },
                    modifier = Modifier.semantics { contentDescription = "Remove widget from Unfolded-only page" }) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                }
                TextButton(onClick = { onWidget(placement.slot) }) { Text(launcherText("Replace")) }
            }
        }
        TextButton(onClick = { onAddWidget(homePage) }, Modifier.fillMaxWidth()) { Text("Add widget to this page") }
        Text("Hold and drag an app to move it. Pause at the screen edge to turn pages. Release without moving for options.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun SettingSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Row { Text(label, Modifier.weight(1f)); Text(valueLabel, color = MaterialTheme.colorScheme.secondary) }
        Slider(value, onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
    }
}

