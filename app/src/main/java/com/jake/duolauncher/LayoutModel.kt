package com.jake.duolauncher

/** Dock artwork has its own visual scale; changing Home artwork must not shrink it. */
fun dockIconSize() = 44f

/**
 * Place the unfolded left pane so the empty space between both icon grids is
 * centred on the physical hinge. The right Home pane keeps its shared-canvas
 * anchor and the stationary Dock is not involved in this calculation.
 */
fun expandedLeftPaneOrigin(homeWidth: Float, gridWidth: Float, paneLeadingPadding: Float = 16f): Float =
    (homeWidth - gridWidth - 2f * paneLeadingPadding).coerceAtLeast(0f)

fun dockFrameSideInset(reservedWidth: Float) = minOf(2f, ((reservedWidth - 56f) / 2f).coerceAtLeast(0f))
fun dockFrameWidth(reservedWidth: Float) = reservedWidth - 2f * dockFrameSideInset(reservedWidth)

const val DOCK_END_MARGIN_DP = 12f

/** The unfolded workspace is clipped exactly where the stationary Dock glass begins. */
fun expandedWorkspaceWidth(windowWidth: Float, reservedDockWidth: Float): Float =
    (windowWidth - DOCK_END_MARGIN_DP - reservedDockWidth + dockFrameSideInset(reservedDockWidth))
        .coerceAtLeast(1f)

/** Centre the fixed-width Home canvas inside either half of the unfolded workspace. */
fun expandedPaneContentInset(workspaceWidth: Float, contentWidth: Float): Float =
    (workspaceWidth / 2f - contentWidth).coerceAtLeast(0f) / 2f

const val SHARED_HOME_WIDTH_DP = 425f
const val SHARED_SAFE_HEIGHT_DP = 608f
const val HOME_ICON_FOOTPRINT_DP = 54f
const val HOME_ICON_ARTWORK_MAX_DP = 52f
const val EXPANDED_HOME_ICON_ARTWORK_MAX_DP = 60f
// The unfolded display already has a physical hinge break. A generous margin on
// both panes made the two four-column grids read as three separate regions. Keep
// only a small optical inset and let the shared 16dp page gutter form the centre
// breathing room; the cover geometry and stationary Dock remain unchanged.
const val EXPANDED_HOME_CONTENT_SIDE_MARGIN_DP = 4f
const val CUTOUT_VISUAL_GAP_DP = 18f
const val REFERENCE_STATUS_TOP_DP = 56f
const val REFERENCE_CONTENT_TOP_DP = 70f
const val STATUS_RAIL_LIFT_DP = HOME_ICON_FOOTPRINT_DP / 2f

/** Only ordinary Home pages reserve the bottom controls strip. The leading feed scrolls
 * all the way to the workspace edge, just as it can scroll past its initial top inset. */
fun homePaneViewportHeight(page: Int, contentHeight: Float, bottomControlsSpace: Float): Float =
    (contentHeight - if (page == -1) 0f else bottomControlsSpace).coerceAtLeast(0f)

/**
 * The cover is slightly taller than the shared 608dp desktop canvas. Only the
 * leading widgets page is allowed to use those centred top/bottom margins; the
 * ordinary Home surfaces keep the shared geometry used by the unfolded screen.
 */
fun coverLeadingEdgeInset(coverDisplay: Boolean, safeHeight: Float, windowHeight: Float): Float =
    if (!coverDisplay) 0f else ((windowHeight - safeHeight) / 2f).coerceAtLeast(0f)

/** Desktop geometry stays fixed; only the status layer yields to a cutout in its column. */
fun cutoutAwareStatusTopDp(baseTopDp: Float, statusColumnCutoutDepthDp: Float): Float =
    maxOf(baseTopDp, statusColumnCutoutDepthDp + CUTOUT_VISUAL_GAP_DP)

/** Move the complete rail equally on both displays after preserving their cutout delta. */
fun statusRailTopDp(baseTopDp: Float, statusColumnCutoutDepthDp: Float): Float =
    (cutoutAwareStatusTopDp(baseTopDp, statusColumnCutoutDepthDp) - STATUS_RAIL_LIFT_DP)
        .coerceAtLeast(0f)

data class LayoutPreset(
    val iconSize: Float = 48f,
    val rowGap: Float = 8f,
    val dockWidth: Float = 68f,
    val dockPosition: Float = 0.56f,
    val dockAlignToGrid: Boolean = true,
) {
    fun sanitized() = copy(
        iconSize = iconSize.coerceIn(40f, 54f),
        rowGap = rowGap.coerceIn(0f, 28f),
        dockWidth = dockWidth.coerceIn(56f, 84f),
        dockPosition = dockPosition.coerceIn(0.25f, 0.75f),
    )
}

data class HomeGeometry(
    val expanded: Boolean,
    val homeWidth: Float,
    val gridWidth: Float,
    val iconSize: Float,
    val rowHeight: Float,
    val widgetHeight: Float,
    val contentTop: Float,
    val dockTop: Float,
    val dockHeight: Float,
    val dockRowHeight: Float,
)

/**
 * iPhone Duo uses a denser unfolded canvas than the cover. Widen only the Home
 * grid and artwork; Dock dimensions and its vertical anchor stay independent.
 */
fun expandedHomeGeometry(base: HomeGeometry, paneWidth: Float): HomeGeometry {
    if (!base.expanded) return base
    // Additional inner-display width belongs outside the grid, never between its columns.
    val targetGridWidth = minOf(base.gridWidth, (paneWidth - 32f).coerceAtLeast(192f))
    return base.copy(
        gridWidth = targetGridWidth,
        iconSize = minOf(base.iconSize, targetGridWidth / GRID_COLUMNS - 8f),
    )
}

/** Advance beta14/beta15 defaults once; later explicit/imported values stay untouched. */
fun upgradeArtworkDefault(preset: LayoutPreset, defaultsRevision: Int): LayoutPreset =
    if ((defaultsRevision == 0 && preset.iconSize == 44f) ||
        (defaultsRevision == 1 && preset.iconSize == 46f)) preset.copy(iconSize = 48f) else preset

/** Advance old defaults without changing individually tuned values. */
fun upgradePreset(preset: LayoutPreset, schema: Int, expanded: Boolean): LayoutPreset = when {
    schema < 2 -> preset.copy(
        iconSize = if (preset.iconSize == if (expanded) 58f else 54f) 54f else preset.iconSize,
        rowGap = if (preset.rowGap == 12f) 8f else preset.rowGap,
        dockWidth = if (preset.dockWidth == 64f) 68f else preset.dockWidth,
    )
    schema == 2 && preset.iconSize == 60f -> preset.copy(iconSize = 54f)
    else -> preset
}

fun homeGeometry(width: Float, height: Float, preset: LayoutPreset, labels: Boolean, statusHeight: Float = 0f, labelHeight: Float = 20f, inLibrary: Boolean = false, homeBottomSpace: Float = 44f): HomeGeometry {
    val p = preset.sanitized()
    val expanded = width >= 650f
    // The cover and inner right-hand Home pane share one physical layout canvas.
    // Wider windows reveal the leading pane instead of resizing the Home pane.
    val homeWidth = minOf(SHARED_HOME_WIDTH_DP, width)
    val gridWidth = (homeWidth - p.dockWidth - 44f).coerceAtLeast(192f)
    // Artwork can shrink without changing the approved grid footprint, row rhythm,
    // Dock span, or the position of any surrounding element.
    val iconFootprint = minOf(HOME_ICON_FOOTPRINT_DP, (gridWidth / 4f - 10f).coerceAtLeast(32f))
    val icon = minOf(p.iconSize, HOME_ICON_ARTWORK_MAX_DP, iconFootprint)
        .coerceAtMost(minOf(EXPANDED_HOME_ICON_ARTWORK_MAX_DP, gridWidth / 4f - 8f))
    // Keep the same icon rhythm when labels are hidden; allow larger system text to fit.
    val row = maxOf(48f, iconFootprint + if (labels) maxOf(20f, labelHeight) else 20f) + p.rowGap
    val widget = minOf(176f, gridWidth / 2f - 10f).coerceAtLeast(88f)
    // The reference Home leaves roughly eight percent of its height above the first
    // cards. Keep that optical margin when four rows fit; only compress on short windows.
    val contentTop = minOf(REFERENCE_CONTENT_TOP_DP,
        (height - widget - 18f - 4f * row - homeBottomSpace).coerceAtLeast(8f))
    // Search reclaims the redundant bottom controls' space for all four dock apps.
    // Extremely short windows still scroll rather than reduce touch targets below 48dp.
    val topLimit = maxOf(8f, statusHeight)
    val bottomReserve = if (inLibrary) 12f else 124f
    // Outer dock edges span the first through third icon images, excluding the last label.
    val desiredHeight = if (p.dockAlignToGrid) 2f * row + iconFootprint else 256f
    val dockHeight = minOf(desiredHeight, (height - topLimit - bottomReserve).coerceAtLeast(76f))
    val dockRowHeight = ((dockHeight - 16f) / 4f).coerceAtLeast(48f)
    // Use the home position as the anchor, so removing library buttons does not
    // move a low-positioned dock on ordinary page swipes. Move up only to fit.
    val homeDockHeight = minOf(desiredHeight, (height - topLimit - 124f).coerceAtLeast(76f))
    val homeDockTop = (if (p.dockAlignToGrid) contentTop + widget + 18f else height * p.dockPosition - homeDockHeight / 2f)
        .coerceIn(topLimit, maxOf(topLimit, height - homeDockHeight - 124f))
    val dockTop = homeDockTop.coerceIn(topLimit, maxOf(topLimit, height - dockHeight - bottomReserve))
    return HomeGeometry(expanded, homeWidth, gridWidth, icon, row, widget, contentTop, dockTop, dockHeight, dockRowHeight)
}

/** Keep stored order stable across installs, removals and configuration changes. */
fun reconcileOrder(saved: List<String>, installed: List<String>): List<String> {
    val present = installed.toSet()
    return (saved.filter { it in present } + installed).distinct()
}

/** Installing an app must never create a home-screen pin. */
fun reconcilePins(saved: List<String>, installed: List<String>): List<String> {
    val available = installed.toSet()
    return saved.filter { it in available }.distinct()
}

fun migrateHomePins(legacy: List<String>, installed: List<String>, suggested: List<String>): List<String> {
    val surviving = reconcilePins(legacy, installed)
    val oldSet = surviving.toSet()
    val wasReordered = surviving.isNotEmpty() && surviving != installed.filter { it in oldSet }
    return if (wasReordered) surviving.take(16) else reconcilePins(suggested, installed).take(16)
}

fun homePageCount(cellCount: Int) = maxOf(1, (cellCount + HOME_CELLS - 1) / HOME_CELLS)

fun moveApp(order: List<String>, id: String, offset: Int): List<String> {
    val from = order.indexOf(id)
    if (from < 0) return order
    val to = (from + offset).coerceIn(0, order.lastIndex)
    return order.toMutableList().apply { add(to, removeAt(from)) }
}
