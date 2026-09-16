package com.jake.duolauncher

import android.appwidget.AppWidgetHostView
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.compose.ui.geometry.Offset

/**
 * Reports whether a DOWN in absolute screen pixels belongs to vertically scrollable
 * provider content in one of this launcher's native widget host views.
 */
internal fun nativeWidgetConsumesVerticalGesture(root: View, screenPoint: Offset): Boolean {
    fun contains(view: View): Boolean {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return screenPoint.x >= location[0] && screenPoint.x < location[0] + view.width &&
            screenPoint.y >= location[1] && screenPoint.y < location[1] + view.height
    }

    fun isVerticalContainer(view: View): Boolean {
        if (view is HorizontalScrollView) return false
        if (view.canScrollVertically(-1) || view.canScrollVertically(1)) return true
        if (view is ScrollView || view is AbsListView) return true
        // RemoteViews can supply AndroidX containers without linking their classes into
        // the launcher. Recognize the well-known vertical container types by hierarchy.
        var type: Class<*>? = view.javaClass
        while (type != null) {
            when (type.simpleName) {
                "NestedScrollView", "RecyclerView", "ListView", "GridView" -> return true
                "HorizontalScrollView", "ViewPager", "ViewPager2" -> return false
            }
            type = type.superclass
        }
        return false
    }

    fun providerConsumes(view: View): Boolean {
        if (!contains(view)) return false
        if (isVerticalContainer(view)) return true
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                if (providerConsumes(view.getChildAt(index))) return true
            }
        }
        return false
    }

    fun findHost(view: View): Boolean {
        if (!contains(view)) return false
        if (view is AppWidgetHostView) {
            for (index in view.childCount - 1 downTo 0) {
                if (providerConsumes(view.getChildAt(index))) return true
            }
            return false
        }
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                if (findHost(view.getChildAt(index))) return true
            }
        }
        return false
    }

    return findHost(root)
}
