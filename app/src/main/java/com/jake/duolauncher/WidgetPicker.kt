package com.jake.duolauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.RemoteViews
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

internal data class WidgetCatalogEntry(
    val provider: AppWidgetProviderInfo,
    val providerLabel: String,
    val appLabel: String,
    val description: String,
    val userSerial: Long = 0,
    val profileLabel: String = launcherText("Personal"),
    val isWork: Boolean = false,
)

internal val LocalWidgetPlacementSession = compositionLocalOf<WidgetPickerSession?> { null }

internal data class WidgetPickerSession(
    val provider: AppWidgetProviderInfo?,
    val slot: Int,
    val span: WidgetSpan,
    val pointer: Offset,
    val dragging: Boolean,
    val targetIndex: Int? = null,
    val candidate: WidgetPlacement? = null,
    val builtinId: Int? = null,
)

internal fun widgetCatalog(context: Context, providers: List<AppWidgetProviderInfo>, profile: AppProfile): List<WidgetCatalogEntry> {
    val pm = context.packageManager
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    return providers.map { provider ->
        val packageName = provider.provider.packageName
        val appLabel = runCatching {
            launcherApps.getApplicationInfo(packageName, 0, provider.profile).loadLabel(pm).toString()
        }.recoverCatching {
            if (provider.profile != Process.myUserHandle()) throw it
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }
            .getOrDefault(packageName)
        val providerLabel = provider.loadLabel(pm).toString()
        val description = if (android.os.Build.VERSION.SDK_INT >= 31)
            runCatching { provider.loadDescription(context)?.toString().orEmpty() }.getOrDefault("") else ""
        WidgetCatalogEntry(provider, providerLabel, appLabel, description, profile.userSerial, profile.label, profile.isWork)
    }.sortedWith(compareBy({ it.appLabel.lowercase() }, { it.providerLabel.lowercase() }))
}

private sealed interface CatalogPreview {
    data class Remote(val views: RemoteViews) : CatalogPreview
    data class Picture(val bitmap: Bitmap) : CatalogPreview
    data object Missing : CatalogPreview
}

private object WidgetPreviewCache {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private val values = object : LinkedHashMap<String, CatalogPreview>(16, .75f, true) {}
    private var bytes = 0
    @Synchronized fun get(key: String) = values[key]
    @Synchronized fun put(key: String, value: CatalogPreview) {
        values.remove(key)?.let { bytes -= it.cost }
        values[key] = value; bytes += value.cost
        val iterator = values.entries.iterator()
        while (bytes > MAX_BYTES && iterator.hasNext()) { bytes -= iterator.next().value.cost; iterator.remove() }
    }
    private val CatalogPreview.cost get() = when (this) {
        is CatalogPreview.Picture -> bitmap.allocationByteCount
        is CatalogPreview.Remote -> 64 * 1024
        CatalogPreview.Missing -> 1
    }
}

private suspend fun loadWidgetPreview(context: Context, provider: AppWidgetProviderInfo, span: WidgetSpan): CatalogPreview {
    val density = context.resources.displayMetrics.densityDpi
    val key = "${provider.provider.flattenToString()}|${provider.profile.hashCode()}|$density|${span.width}x${span.height}"
    WidgetPreviewCache.get(key)?.let { return it }
    val loaded = withContext(Dispatchers.IO) {
        val manager = AppWidgetManager.getInstance(context)
        var remote: RemoteViews? = null
        if (android.os.Build.VERSION.SDK_INT >= 35 &&
            provider.generatedPreviewCategories and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0) {
            remote = runCatching { manager.getWidgetPreview(provider.provider, provider.profile,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) }.getOrNull()
        }
        if (remote == null && android.os.Build.VERSION.SDK_INT >= 31 && provider.previewLayout != 0)
            remote = runCatching { RemoteViews(provider.provider.packageName, provider.previewLayout) }.getOrNull()
        remote?.let(CatalogPreview::Remote) ?: run {
            val drawable = runCatching { provider.loadPreviewImage(context, density) }.getOrNull()
                ?: runCatching { provider.loadIcon(context, density) }.getOrNull()
            drawable
        }
    }
    // Drawable.draw may touch theme/view state. Convert it on Compose's UI thread; only resource
    // Provider enumeration and generated-preview IPC belong on the worker dispatcher.
    val result = when (loaded) {
        is CatalogPreview -> loaded
        is Drawable -> CatalogPreview.Picture(loaded.catalogBitmap())
        else -> CatalogPreview.Missing
    }
    WidgetPreviewCache.put(key, result)
    return result
}

private fun Drawable.catalogBitmap(): Bitmap {
    val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: 144
    val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: 144
    val scale = minOf(1f, 720f / sourceWidth, 480f / sourceHeight)
    val w = (sourceWidth * scale).toInt().coerceAtLeast(1)
    val h = (sourceHeight * scale).toInt().coerceAtLeast(1)
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = android.graphics.Canvas(bitmap)
        setBounds(0, 0, w, h)
        draw(canvas)
    }
}

@Composable
internal fun WidgetProviderPreview(entry: WidgetCatalogEntry, span: WidgetSpan, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview by produceState<CatalogPreview?>(null, entry.provider, span, context.resources.displayMetrics.densityDpi) {
        value = loadWidgetPreview(context, entry.provider, span)
    }
    Box(modifier.background(Glass.copy(alpha = .38f)), contentAlignment = Alignment.Center) {
        when (val value = preview) {
            is CatalogPreview.Remote -> AndroidView(factory = { previewContext ->
                object : android.widget.FrameLayout(previewContext) {
                    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean = false
                }.apply {
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    isFocusable = false
                    isClickable = false
                    runCatching { addView(value.views.apply(previewContext, this)) }
                        .onFailure { addView(android.widget.TextView(previewContext).apply { text = entry.providerLabel }) }
                }
            }, modifier = Modifier.fillMaxSize().padding(6.dp))
            is CatalogPreview.Picture -> Image(value.bitmap.asImageBitmap(), null, Modifier.fillMaxSize().padding(8.dp),
                contentScale = ContentScale.Fit)
            CatalogPreview.Missing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(entry.providerLabel, style = MaterialTheme.typography.bodySmall)
                Text("${span.width} × ${span.height}", style = MaterialTheme.typography.labelSmall)
            }
            null -> CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
        }
    }
}

@Composable
internal fun VisualWidgetPicker(
    entries: List<WidgetCatalogEntry>?,
    profiles: List<AppProfile>,
    selectedProfile: AppProfile,
    onSelectProfile: (AppProfile) -> Unit,
    onTurnOnWork: (Long) -> Unit,
    hiddenForDrag: Boolean,
    footprint: (AppWidgetProviderInfo) -> WidgetSpan?,
    onBack: () -> Unit,
    onTap: (AppWidgetProviderInfo) -> Unit,
    onBuiltin: (Int) -> Unit,
    onBuiltinDragStart: (Int, Offset) -> Unit,
    onDragStart: (AppWidgetProviderInfo, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancelDrag: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val latestDragStart by rememberUpdatedState(onDragStart)
    val latestBuiltinDragStart by rememberUpdatedState(onBuiltinDragStart)
    val latestDrag by rememberUpdatedState(onDrag)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestCancelDrag by rememberUpdatedState(onCancelDrag)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val words = query.trim().lowercase()
    val filtered = remember(entries, words) { entries.orEmpty().filter { entry ->
        words.isEmpty() || listOf(entry.appLabel, entry.providerLabel, entry.description,
            entry.provider.provider.packageName).any { it.lowercase().contains(words) }
    } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    Surface(Modifier.fillMaxWidth().fillMaxHeight(.56f).alpha(if (hiddenForDrag) 0f else 1f)
        .then(if (hiddenForDrag) Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false } else Modifier)
        .duoGlass(DuoGlassRole.Screen, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
        .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
        .testTag("visual-widget-picker"),
        color = Color.Transparent, contentColor = duoGlassContentColor(DuoGlassRole.Screen)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, launcherText("Back")) }
                Text(launcherText("Widgets"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 10.dp)
                .testTag("widget-catalog-search"), singleLine = true, placeholder = { Text(launcherText("Search widgets")) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) })
            if (profiles.any { it.isWork }) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { profile ->
                    FilterChip(selected = profile.userSerial == selectedProfile.userSerial,
                        onClick = { onSelectProfile(profile) }, label = { Text(launcherText(profile.label)) })
                }
            }
            if (!selectedProfile.available || !selectedProfile.unlocked || selectedProfile.quiet) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(launcherFormat(if (selectedProfile.quiet) R.string.ui_profile_paused
                        else R.string.ui_profile_unavailable, launcherText(selectedProfile.label)))
                    if (selectedProfile.isWork) Button(onClick = { onTurnOnWork(selectedProfile.userSerial) },
                        modifier = Modifier.padding(top = 12.dp)) { Text(launcherText("Turn on")) }
                }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("widget-catalog-list"), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                if (entries == null) item("catalog-loading") {
                    Box(Modifier.fillParentMaxSize().padding(40.dp), contentAlignment = Alignment.TopCenter) {
                        CircularProgressIndicator()
                    }
                }
                if (words.isEmpty() && selectedProfile.isPersonal) {
                    item("duo-widgets") { Text("MiDuo", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
                    items(listOf(CLOCK_WIDGET to launcherText("Clock"), DATE_WIDGET to launcherText("Calendar"), INFO_WIDGET to launcherText("Widget panel"),
                        BATTERY_WIDGET to launcherText("Battery ring"), WEATHER_WIDGET to launcherText("Weather"), AGENDA_WIDGET to launcherText("Today’s agenda")),
                        key = { "builtin-${it.first}" }) { (id, label) ->
                        val shape = RoundedCornerShape(22.dp)
                        val interaction = remember { MutableInteractionSource() }
                        var builtinOrigin by remember { mutableStateOf(Offset.Zero) }
                        Surface(Modifier.fillMaxWidth().testTag("widget-builtin-$id")
                            .duoGlass(DuoGlassRole.Card, shape, interaction, backdrop = false)
                            .onGloballyPositioned { builtinOrigin = it.boundsInRoot().topLeft }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { point ->
                                        focusManager.clearFocus(); keyboard?.hide()
                                        latestBuiltinDragStart(id, builtinOrigin + point)
                                    },
                                    onDrag = { change, _ -> change.consume(); latestDrag(builtinOrigin + change.position) },
                                    onDragEnd = { latestDrop() }, onDragCancel = { latestCancelDrag() })
                            }
                            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                                focusManager.clearFocus(); keyboard?.hide(); onBuiltin(id)
                            }, color = Color.Transparent, contentColor = duoGlassContentColor(DuoGlassRole.Card),
                            shape = shape) {
                            Column(Modifier.padding(16.dp)) {
                                Text(label, style = MaterialTheme.typography.titleMedium)
                                Text(launcherText("Tap to place · Hold to drag"), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                filtered.groupBy { it.appLabel }.forEach { (app, group) ->
                    item("header-$app") { Text(app, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp)) }
                    items(group, key = { it.provider.provider.flattenToString() }) { entry ->
                        val span = footprint(entry.provider)
                        var origin by remember { mutableStateOf(Offset.Zero) }
                        val shape = RoundedCornerShape(22.dp)
                        val interaction = remember { MutableInteractionSource() }
                        Surface(modifier = Modifier.fillMaxWidth()
                            .testTag("widget-provider-${entry.provider.provider.flattenToString()}${if (entry.isWork) "-profile-${entry.userSerial}" else ""}")
                            .duoGlass(DuoGlassRole.Card, shape, interaction, backdrop = false)
                            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
                            .pointerInput(entry.provider) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { point ->
                                        focusManager.clearFocus(); keyboard?.hide()
                                        latestDragStart(entry.provider, origin + point)
                                    },
                                    onDrag = { change, _ -> change.consume(); latestDrag(origin + change.position) },
                                    onDragEnd = { latestDrop() }, onDragCancel = { latestCancelDrag() })
                            }.clickable(enabled = span != null, interactionSource = interaction,
                                indication = LocalIndication.current, onClick = {
                                focusManager.clearFocus(); keyboard?.hide(); onTap(entry.provider)
                            }),
                            color = Color.Transparent, contentColor = duoGlassContentColor(DuoGlassRole.Card),
                            shape = shape) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Column(Modifier.padding(bottom = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(entry.providerLabel, style = MaterialTheme.typography.titleMedium)
                                        if (entry.isWork) AssistChip(onClick = {}, label = { Text(entry.profileLabel) },
                                            modifier = Modifier.padding(start = 8.dp))
                                    }
                                    if (entry.description.isNotBlank()) Text(entry.description, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2)
                                    Text(span?.let { "${it.width} × ${it.height}" } ?: launcherText("Doesn’t fit this layout"),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (span == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                    Text(launcherText("Tap to place · Hold to drag"), style = MaterialTheme.typography.bodySmall)
                                }
                                val ratio = span?.let { it.width.toFloat() / it.height } ?: 1.5f
                                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    val boundedWidth = minOf(maxWidth, 220.dp * ratio)
                                    val boundedHeight = boundedWidth / ratio
                                    val previewTag = "widget-preview-${entry.provider.provider.flattenToString()}${if (entry.isWork) "-profile-${entry.userSerial}" else ""}"
                                    Box(Modifier.width(boundedWidth).height(boundedHeight).clip(RoundedCornerShape(16.dp))
                                        .testTag(previewTag)) {
                                        WidgetProviderPreview(entry, span ?: WidgetSpan(2, 2), Modifier.fillMaxSize())
                                    }
                                }
                            }
                        }
                    }
                }
                if (entries != null && filtered.isEmpty()) item { Text(launcherText("No widgets found"), Modifier.padding(20.dp)) }
            }
        }
    }
    }
}
