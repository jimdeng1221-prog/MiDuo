package com.jake.duolauncher

/**
 * Preserve the user's current desktop and append lightly populated pages for
 * exercising long, continuous paging. The edit is committed once by the model,
 * so the whole test layout can be undone in one step.
 */
fun multiPageTestLayout(
    current: HomeLayout,
    candidates: List<Phase22AppCandidate>,
    targetPageCount: Int = 5,
    appsPerNewPage: Int = 6,
): HomeLayout {
    require(targetPageCount > 0)
    require(appsPerNewPage > 0)
    if (current.pageCount >= targetPageCount) return current

    val newPages = current.pageCount until targetPageCount
    val used = (current.slots.filterNotNull() + current.dock.filterNotNull() +
        current.leadingSlots.filterNotNull() + current.folders.flatMap(FolderEntry::appIds)).toSet()
    val available = candidates.asSequence()
        .filterNot(Phase22AppCandidate::isWork)
        .distinctBy(Phase22AppCandidate::id)
        .filter { it.id !in used }
        .iterator()
    val blocked = current.widgetPlacements.flatMapTo(mutableSetOf(), WidgetPlacement::coveredIndices)
    val freeCells = newPages.associateWith { page ->
        (0 until HOME_CELLS).map { homeCellIndex(page, it) }.filterNot(blocked::contains).iterator()
    }
    val slots = current.slots.toMutableList()

    // Round-robin ensures every appended page exists even when only a few apps
    // are available, instead of filling one page before the next is created.
    repeat(appsPerNewPage) {
        newPages.forEach { page ->
            val cells = freeCells.getValue(page)
            if (available.hasNext() && cells.hasNext()) {
                val index = cells.next()
                while (slots.size <= index) slots += null
                slots[index] = available.next().id
            }
        }
    }
    return current.copy(slots = slots.dropLastWhile { it == null })
}
