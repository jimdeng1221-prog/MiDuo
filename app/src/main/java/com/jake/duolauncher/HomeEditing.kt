package com.jake.duolauncher

const val GRID_COLUMNS = 4
const val GRID_ROWS = 6
const val LEADING_WIDGET_ROWS = 24
fun widgetRowsForPage(page: Int) = if (page == -1) LEADING_WIDGET_ROWS else GRID_ROWS

internal fun homeRenderedRows(page: Int, occupiedRows: Int, editingWidgets: Boolean): Int =
    if (page == -1) {
        if (editingWidgets) LEADING_WIDGET_ROWS
        else maxOf(GRID_ROWS, occupiedRows + 4).coerceAtMost(LEADING_WIDGET_ROWS)
    } else maxOf(GRID_ROWS, occupiedRows)

/** indexOf's -1 is NOT an address: -1 is also a real leading-page cell. */
internal fun shortcutIndexOnPage(page: Int, id: String, slots: List<String?>, leading: List<String?>): Int? =
    if (page == -1) leading.indexOf(id).takeIf { it >= 0 }?.let { homeCellIndex(-1, it) }
    else slots.indexOf(id).takeIf { it >= 0 && homeCellPage(it) == page }
const val HOME_CELLS = GRID_COLUMNS * GRID_ROWS
const val EMPTY_WIDGET = -1
const val CLOCK_WIDGET = -2
const val DATE_WIDGET = -3
const val INFO_WIDGET = -4
const val NEEDS_BINDING_WIDGET = -5
const val MONTH_WIDGET = -6
const val WEATHER_WIDGET = -7
const val AGENDA_WIDGET = -8
const val BATTERY_WIDGET = -9
const val MAP_WIDGET = -10
val BUILTIN_WIDGET_IDS = setOf(CLOCK_WIDGET, DATE_WIDGET, INFO_WIDGET, MONTH_WIDGET,
    WEATHER_WIDGET, AGENDA_WIDGET, BATTERY_WIDGET, MAP_WIDGET)

data class WidgetPlacement(val slot: Int, val id: Int, val page: Int, val column: Int, val row: Int, val spanX: Int, val spanY: Int)
data class WidgetRestore(val slot: Int, val providerComponent: String, val userSerial: Long, val title: String,
    val profileLabel: String, val isWork: Boolean = false, val sourceScope: String? = null)

val DEFAULT_WIDGET_PLACEMENTS = listOf(
    WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2),
    WidgetPlacement(1, DATE_WIDGET, 0, 2, 0, 2, 2),
)

/** Seed only a truly empty leading page. Existing shortcuts and widgets are never moved. */
fun withDefaultLeadingWidgets(layout: HomeLayout): HomeLayout {
    if (layout.leadingSlots.any { it != null } || layout.widgetPlacements.any { it.page == -1 }) return layout
    val usedSlots = layout.widgetPlacements.mapTo(mutableSetOf(), WidgetPlacement::slot)
    fun nextSlot(): Int = generateSequence(0) { it + 1 }.first { it !in usedSlots }.also(usedSlots::add)
    return layout.copy(widgetPlacements = layout.widgetPlacements + listOf(
        WidgetPlacement(nextSlot(), MONTH_WIDGET, -1, 0, 2, 2, 2),
        WidgetPlacement(nextSlot(), WEATHER_WIDGET, -1, 0, 0, 4, 2),
        WidgetPlacement(nextSlot(), BATTERY_WIDGET, -1, 0, 4, 2, 2),
        WidgetPlacement(nextSlot(), AGENDA_WIDGET, -1, 2, 2, 2, 2),
        WidgetPlacement(nextSlot(), MAP_WIDGET, -1, 0, 6, 4, 2),
    ))
}

/** Explicit preset application replaces only the leading page; the caller supplies Undo. */
fun withLeadingDashboard(layout: HomeLayout): HomeLayout {
    val removed = layout.widgetPlacements.filter { it.page == -1 }.map { it.slot }.toSet()
    val removedFolders = layout.leadingSlots.filterNotNull().toSet()
    return withDefaultLeadingWidgets(layout.copy(leadingSlots = List(HOME_CELLS) { null },
        widgetPlacements = layout.widgetPlacements.filter { it.page != -1 },
        widgetRestores = layout.widgetRestores.filter { it.slot !in removed },
        folders = layout.folders.filter { it.id !in removedFolders }))
}

// Extra widget rows use a separate address range; old shortcut addresses stay stable.
private const val LEADING_OVERFLOW_BASE = -100_000
fun homeCellPage(index: Int) = if (index <= LEADING_OVERFLOW_BASE) -1 else Math.floorDiv(index, HOME_CELLS)
fun homeCellLocal(index: Int) = if (index <= LEADING_OVERFLOW_BASE) LEADING_OVERFLOW_BASE - index else Math.floorMod(index, HOME_CELLS)
fun homeCellIndex(page: Int, local: Int): Int {
    require(page >= -1 && local in 0 until widgetRowsForPage(page) * GRID_COLUMNS)
    if (page == -1 && local >= HOME_CELLS) return LEADING_OVERFLOW_BASE - local
    return page * HOME_CELLS + local
}

private fun normalizedLeadingSlots(slots: List<String?>) =
    normalizeHomeSlots(slots.take(HOME_CELLS)).let { it + List(HOME_CELLS - it.size) { null } }

/** Nulls are intentional empty home cells. Only unused trailing cells are removed. */
fun normalizeHomeSlots(slots: List<String?>): List<String?> {
    val seen = mutableSetOf<String>()
    return slots.map { it?.takeIf { id -> id.isNotBlank() && seen.add(id) } }.dropLastWhile { it == null }
}

fun reconcileHomeSlots(slots: List<String?>, installed: Set<String>) =
    normalizeHomeSlots(slots.map { it?.takeIf(installed::contains) })

data class HomeLayout(
    val slots: List<String?>,
    val dock: List<String?>,
    val widgetPlacements: List<WidgetPlacement> = emptyList(),
    val folders: List<FolderEntry> = emptyList(),
    val widgetRestores: List<WidgetRestore> = emptyList(),
    val leadingSlots: List<String?> = List(HOME_CELLS) { null },
) {
    val widgets: List<Int> get() {
        val last = widgetPlacements.maxOfOrNull { it.slot } ?: -1
        return List(maxOf(3, last + 1)) { slot -> placement(slot)?.id ?: EMPTY_WIDGET }
    }
    fun placement(slot: Int) = widgetPlacements.firstOrNull { it.slot == slot }
    fun folder(id: String) = folders.firstOrNull { it.id == id }
    fun widgetRestore(slot: Int) = widgetRestores.firstOrNull { it.slot == slot }
    val pageCount get() = maxOf(homePageCount(slots.size),
        widgetPlacements.filter { it.page >= 0 }.maxOfOrNull { it.page + 1 } ?: 1)
    fun slotAt(index: Int): String? = when (homeCellPage(index)) {
        -1 -> leadingSlots.getOrNull(homeCellLocal(index))
        in 0..Int.MAX_VALUE -> slots.getOrNull(index)
        else -> null
    }
    fun indexOfShortcut(id: String): Int? {
        val leading = leadingSlots.indexOf(id)
        if (leading >= 0) return homeCellIndex(-1, leading)
        return slots.indexOf(id).takeIf { it >= 0 }
    }
    fun slotsForPage(page: Int): List<String?> = when {
        page == -1 -> normalizedLeadingSlots(leadingSlots)
        page >= 0 -> List(HOME_CELLS) { local -> slots.getOrNull(homeCellIndex(page, local)) }
        else -> emptyList()
    }
    fun withSlot(index: Int, value: String?): HomeLayout = if (homeCellPage(index) == -1) {
        if (homeCellLocal(index) >= HOME_CELLS) this
        else copy(leadingSlots = normalizedLeadingSlots(leadingSlots).toMutableList().apply { this[homeCellLocal(index)] = value })
    } else {
        val next = slots.toMutableList().apply { while (size <= index) add(null); this[index] = value }
        copy(slots = next.dropLastWhile { it == null })
    }
}

sealed interface DropTarget {
    data class Home(val index: Int) : DropTarget
    data class Dock(val index: Int) : DropTarget
    data class Library(val id: String) : DropTarget
    data class Widget(val index: Int) : DropTarget
    data class Folder(val id: String) : DropTarget
    data object Remove : DropTarget
}

fun canPlaceInDock(layout: HomeLayout, id: String): Boolean =
    id.isNotBlank() && !isReservedFolderId(id) && layout.folders.none { id in it.appIds } &&
        (id in layout.dock || layout.dock.any { it == null })

fun WidgetPlacement.coveredIndices(): Set<Int> {
    if (page < -1) return emptySet()
    return buildSet {
        repeat(spanY) { y -> repeat(spanX) { x ->
            if (row + y < widgetRowsForPage(page)) add(homeCellIndex(page, (row + y) * GRID_COLUMNS + column + x))
        } }
    }
}

private fun WidgetPlacement.valid() =
    slot >= 0 && id != EMPTY_WIDGET && page >= -1 && column >= 0 && row >= 0 &&
        spanX in 1..GRID_COLUMNS && spanY in 1..GRID_ROWS && column + spanX <= GRID_COLUMNS &&
        row + spanY <= widgetRowsForPage(page)

private fun widgetCells(layout: HomeLayout, exceptSlot: Int? = null) = layout.widgetPlacements
    .filter { it.slot != exceptSlot }.flatMapTo(mutableSetOf()) { it.coveredIndices() }

private fun overlaps(a: WidgetPlacement, b: WidgetPlacement) = a.page == b.page &&
    a.column < b.column + b.spanX && b.column < a.column + a.spanX &&
    a.row < b.row + b.spanY && b.row < a.row + a.spanY

/** Builds a non-persistable placement draft when the requested rectangle is available. */
fun widgetCandidate(layout: HomeLayout, slot: Int, targetIndex: Int, spanX: Int, spanY: Int): WidgetPlacement? {
    val page = homeCellPage(targetIndex)
    if (slot < 0 || page !in -1..layout.pageCount) return null
    val local = homeCellLocal(targetIndex)
    val candidate = WidgetPlacement(slot, EMPTY_WIDGET, page,
        local % GRID_COLUMNS, local / GRID_COLUMNS, spanX, spanY)
    if (spanX !in 1..GRID_COLUMNS || spanY !in 1..GRID_ROWS ||
        candidate.column + spanX > GRID_COLUMNS || candidate.row + spanY > widgetRowsForPage(page)) return null
    if (layout.widgetPlacements.any { it.slot != slot && overlaps(it, candidate) }) return null
    if (candidate.coveredIndices().any { layout.slotAt(it) != null }) return null
    return candidate
}

/** Moves use insertion order and transfer shortcuts between Home and the dock. */
fun dropApp(layout: HomeLayout, id: String, target: DropTarget): HomeLayout {
    if (id.isBlank() || layout.folders.any { id in it.appIds }) return layout
    return when (target) {
        is DropTarget.Home -> {
            val blocked = widgetCells(layout)
            val targetPage = homeCellPage(target.index)
            if (targetPage !in -1..layout.pageCount || target.index in blocked) return layout
            if (targetPage == -1) {
                if (homeCellLocal(target.index) >= HOME_CELLS) return layout
                val cells = normalizedLeadingSlots(layout.leadingSlots).toMutableList()
                val targetLocal = homeCellLocal(target.index)
                val sourceLocal = cells.indexOf(id)
                cells.indices.filter { it != sourceLocal && cells[it] == id }.forEach { cells[it] = null }
                val blockedLocal = blocked.filter { homeCellPage(it) == -1 }.mapTo(mutableSetOf(), ::homeCellLocal)
                if (targetLocal in blockedLocal) return layout
                if (sourceLocal == targetLocal) return layout
                if (sourceLocal >= 0) {
                    if (cells[targetLocal] == null) {
                        cells[sourceLocal] = null
                        cells[targetLocal] = id
                        return layout.copy(leadingSlots = cells,
                            slots = layout.slots.map { it?.takeUnless(id::equals) }.dropLastWhile { it == null },
                            dock = layout.dock.map { it?.takeUnless(id::equals) })
                    }
                    val usable = (minOf(sourceLocal, targetLocal)..maxOf(sourceLocal, targetLocal)).filterNot { it in blockedLocal }
                    val from = usable.indexOf(sourceLocal); val to = usable.indexOf(targetLocal)
                    if (from < 0 || to < 0) return layout
                    if (from < to) for (position in from until to) cells[usable[position]] = cells[usable[position + 1]]
                    else for (position in from downTo to + 1) cells[usable[position]] = cells[usable[position - 1]]
                    cells[targetLocal] = id
                } else if (cells[targetLocal] == null) cells[targetLocal] = id else {
                    val later = (targetLocal + 1 until HOME_CELLS).firstOrNull { it !in blockedLocal && cells[it] == null }
                    val earlier = (targetLocal - 1 downTo 0).firstOrNull { it !in blockedLocal && cells[it] == null }
                    val vacancy = later ?: earlier ?: return layout
                    val usable = (minOf(vacancy, targetLocal)..maxOf(vacancy, targetLocal)).filterNot { it in blockedLocal }
                    if (vacancy > targetLocal) for (position in usable.lastIndex downTo 1) cells[usable[position]] = cells[usable[position - 1]]
                    else for (position in 0 until usable.lastIndex) cells[usable[position]] = cells[usable[position + 1]]
                    cells[targetLocal] = id
                }
                return layout.copy(leadingSlots = cells,
                    slots = layout.slots.map { it?.takeUnless(id::equals) }.dropLastWhile { it == null },
                    dock = layout.dock.map { it?.takeUnless(id::equals) })
            }
            val slots = layout.slots.toMutableList()
            val source = slots.indexOf(id)
            slots.indices.filter { it != source && slots[it] == id }.forEach { slots[it] = null }
            while (slots.size <= target.index) slots.add(null)
            val occupied = slots[target.index] != null
            when {
                source == target.index -> Unit
                source >= 0 && !occupied -> { slots[source] = null; slots[target.index] = id }
                source >= 0 -> {
                    val usable = (minOf(source, target.index)..maxOf(source, target.index)).filterNot { it in blocked }
                    val from = usable.indexOf(source)
                    val to = usable.indexOf(target.index)
                    if (from < 0 || to < 0) return layout
                    if (from < to) for (position in from until to) slots[usable[position]] = slots[usable[position + 1]]
                    else for (position in from downTo to + 1) slots[usable[position]] = slots[usable[position - 1]]
                    slots[target.index] = id
                }
                else -> {
                    if (!occupied) slots[target.index] = id else {
                        var vacancy = target.index + 1
                        val limit = HOME_CELLS * (layout.pageCount + 1)
                        while (vacancy < limit && (vacancy in blocked || slots.getOrNull(vacancy) != null)) vacancy++
                        if (vacancy >= limit) return layout
                        while (slots.size <= vacancy) slots.add(null)
                        val usable = (target.index..vacancy).filterNot { it in blocked }
                        for (position in usable.lastIndex downTo 1) slots[usable[position]] = slots[usable[position - 1]]
                        slots[target.index] = id
                    }
                }
            }
            layout.copy(slots = slots.dropLastWhile { it == null }, dock = layout.dock.map { it?.takeUnless { dockId -> dockId == id } },
                leadingSlots = normalizedLeadingSlots(layout.leadingSlots).map { it?.takeUnless(id::equals) })
        }
        is DropTarget.Dock -> {
            if (target.index !in layout.dock.indices || !canPlaceInDock(layout, id)) return layout
            val dock = layout.dock.toMutableList()
            val source = dock.indexOf(id)
            dock.indices.filter { it != source && dock[it] == id }.forEach { dock[it] = null }
            val occupied = dock[target.index] != null
            when {
                source == target.index -> Unit
                source >= 0 && !occupied -> { dock[source] = null; dock[target.index] = id }
                source >= 0 -> { dock.removeAt(source); dock.add(target.index, id) }
                !occupied -> dock[target.index] = id
                else -> {
                    val later = (target.index + 1 until dock.size).firstOrNull { dock[it] == null }
                    val earlier = (target.index - 1 downTo 0).firstOrNull { dock[it] == null }
                    when {
                        later != null -> { for (i in later downTo target.index + 1) dock[i] = dock[i - 1]; dock[target.index] = id }
                        earlier != null -> { for (i in earlier until target.index) dock[i] = dock[i + 1]; dock[target.index] = id }
                        else -> return layout
                    }
                }
            }
            layout.copy(slots = layout.slots.map { it?.takeUnless { app -> app == id } }.dropLastWhile { it == null }, dock = dock,
                leadingSlots = normalizedLeadingSlots(layout.leadingSlots).map { it?.takeUnless(id::equals) })
        }
        else -> layout
    }
}

fun placeWidget(layout: HomeLayout, placement: WidgetPlacement): HomeLayout {
    if (!placement.valid()) return replaceWidgetAtSameFootprint(layout, placement)
    if (placement.id == NEEDS_BINDING_WIDGET && layout.widgetRestore(placement.slot) == null) return layout
    val without = layout.widgetPlacements.filterNot { it.slot == placement.slot }
    if (without.any { overlaps(it, placement) }) return layout
    if (placement.coveredIndices().any { layout.slotAt(it) != null }) return layout
    return layout.copy(widgetPlacements = (without + placement).sortedBy { it.slot },
        widgetRestores = if (placement.id == NEEDS_BINDING_WIDGET) layout.widgetRestores
            else layout.widgetRestores.filterNot { it.slot == placement.slot })
}

/** Rebinds retained legacy overflow without making that footprint newly placeable. */
fun replaceWidgetAtSameFootprint(layout: HomeLayout, placement: WidgetPlacement): HomeLayout {
    val existing = layout.placement(placement.slot) ?: return layout
    val sameFootprint = placement.page == existing.page && placement.column == existing.column &&
        placement.row == existing.row && placement.spanX == existing.spanX && placement.spanY == existing.spanY
    val retainedSpecial = existing.page > 0 && existing.slot / 3 == existing.page && existing.slot % 3 == 2 &&
        existing.column == 0 && existing.row == GRID_ROWS && existing.spanX == GRID_COLUMNS && existing.spanY == 4
    if (!sameFootprint || !retainedSpecial || placement.id == EMPTY_WIDGET ||
        (placement.id == NEEDS_BINDING_WIDGET && layout.widgetRestore(placement.slot) == null)) return layout
    return layout.copy(widgetPlacements = layout.widgetPlacements.map { if (it.slot == placement.slot) placement else it },
        widgetRestores = if (placement.id == NEEDS_BINDING_WIDGET) layout.widgetRestores
            else layout.widgetRestores.filterNot { it.slot == placement.slot })
}

fun moveWidget(layout: HomeLayout, slot: Int, index: Int): HomeLayout {
    val old = layout.placement(slot) ?: return layout
    val page = homeCellPage(index)
    if (page !in -1..layout.pageCount) return layout
    val local = homeCellLocal(index)
    return placeWidget(layout, old.copy(page = page, column = local % GRID_COLUMNS,
        row = local / GRID_COLUMNS, spanY = old.spanY.coerceAtMost(GRID_ROWS)))
}

fun resizeWidget(layout: HomeLayout, slot: Int, spanX: Int, spanY: Int): HomeLayout {
    val old = layout.placement(slot) ?: return layout
    return placeWidget(layout, old.copy(spanX = spanX, spanY = spanY))
}

/** Remove only the shortcut/placement, never the installed app or widget binding. */
fun removePlacement(layout: HomeLayout, source: DropTarget): HomeLayout = when (source) {
    is DropTarget.Home -> if (layout.slotAt(source.index)?.let(::isFolderId) == true) layout
        else layout.withSlot(source.index, null)
    is DropTarget.Dock -> layout.copy(dock = layout.dock.mapIndexed { i, id -> if (i == source.index) null else id })
    is DropTarget.Widget -> layout.copy(widgetPlacements = layout.widgetPlacements.filterNot { it.slot == source.index },
        widgetRestores = layout.widgetRestores.filterNot { it.slot == source.index })
    else -> layout
}

fun pinHomeApp(slots: List<String?>, id: String, pinned: Boolean, blocked: Set<Int> = emptySet()): List<String?> {
    if (!pinned) return normalizeHomeSlots(slots.map { if (it == id) null else it })
    if (id in slots) return slots
    val gap = slots.indices.firstOrNull { slots[it] == null && it !in blocked }
    if (gap != null) return slots.toMutableList().apply { set(gap, id) }
    var index = slots.size
    while (index in blocked) index++
    return slots + List(index - slots.size) { null } + id
}

fun migrateSchema5Apps(slots: List<String?>): List<String?> {
    if (slots.isEmpty()) return emptyList()
    val result = MutableList(((slots.lastIndex / 16) + 1) * HOME_CELLS) { null as String? }
    slots.forEachIndexed { index, id -> result[index / 16 * HOME_CELLS + 8 + index % 16] = id }
    return normalizeHomeSlots(result)
}

fun migrateSchema5Widgets(widgets: List<Int>): List<WidgetPlacement> = buildList {
    widgets.forEachIndexed { slot, id ->
        if (id == EMPTY_WIDGET) return@forEachIndexed
        val page = slot / 3
        when (slot % 3) {
            0 -> add(WidgetPlacement(slot, id, page, 0, 0, 2, 2))
            1 -> add(WidgetPlacement(slot, id, page, 2, 0, 2, 2))
            2 -> if (page == 0) add(WidgetPlacement(slot, id, -1, 0, 0, 4, 6))
                else add(WidgetPlacement(slot, id, page, 0, 6, 4, 4))
        }
    }
}
