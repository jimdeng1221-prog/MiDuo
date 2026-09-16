package com.jake.duolauncher

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.DayOfWeek
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

internal fun standbySafeEdgePx(systemInset: Int, cornerRadius: Int, density: Float): Int =
    maxOf(systemInset, (cornerRadius * .5f + 8f * density).toInt(), (12f * density).toInt())

/** Insets rotate with the physical cover; do not hard-code a left/right camera hole. */
@Composable
private fun standbySafePadding(): PaddingValues {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val drawing = WindowInsets.safeDrawing.asPaddingValues()
    val radius = remember(context, config.orientation, config.screenWidthDp, config.screenHeightDp) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val insets = context.getSystemService(android.view.WindowManager::class.java)?.currentWindowMetrics?.windowInsets
            listOf(android.view.RoundedCorner.POSITION_TOP_LEFT, android.view.RoundedCorner.POSITION_TOP_RIGHT,
                android.view.RoundedCorner.POSITION_BOTTOM_LEFT, android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)
                .maxOf { insets?.getRoundedCorner(it)?.radius ?: 0 }
        } else 0
    }
    return with(density) {
        fun edge(value: androidx.compose.ui.unit.Dp) = standbySafeEdgePx(value.roundToPx(), radius, this.density).toDp()
        PaddingValues(start = edge(drawing.calculateStartPadding(direction)), top = edge(drawing.calculateTopPadding()),
            end = edge(drawing.calculateEndPadding(direction)), bottom = edge(drawing.calculateBottomPadding()))
    }
}

internal fun standbyMonthCells(date: LocalDate): List<Int?> {
    val offset = date.withDayOfMonth(1).dayOfWeek.value % 7
    return List(42) { index -> (index - offset + 1).takeIf { it in 1..date.lengthOfMonth() } }
}

/** Foreground cover experience only. No lockscreen, unlock, mirror or fold effect. */
@Composable
internal fun StandbyScreen(onExit: () -> Unit, safePadding: PaddingValues = standbySafePadding()) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(ZonedDateTime.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = ZonedDateTime.now(); delay(1000) }
        }
    }
    val prefs = remember { context.getSharedPreferences("standby", 0) }
    val pager = rememberPagerState(initialPage = prefs.getInt("page", 0).coerceIn(0, 4), pageCount = { 5 })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.settledPage) { prefs.edit().putInt("page", pager.settledPage).apply() }
    BackHandler(onBack = onExit)
    val titles = listOf(R.string.standby_calendar, R.string.standby_photo, R.string.standby_digital, R.string.standby_widgets, R.string.standby_night)
    val controls = when (pager.currentPage) { 0 -> Color(0xFF173962); 2, 3 -> Color(0xFF122219); 4 -> Color(0xFFFF443B); else -> Color.White }
    val label = stringResource(R.string.standby_title)
    val clockFormat = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    val time = now.format(DateTimeFormatter.ofPattern(clockFormat, locale))
    val date = now.format(DateTimeFormatter.ofPattern(if (locale.language == "zh") "M月d日 EEEE" else "EEEE, MMM d", locale))
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("standby-root")
        .semantics { contentDescription = label }) {
        val short = minOf(maxWidth.value, maxHeight.value)
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("standby-pager"),
            beyondViewportPageCount = 1) { page ->
            Box(Modifier.fillMaxSize().testTag("standby-view-$page"), contentAlignment = Alignment.Center) {
                when (page) {
                    1 -> {
                        DuneWallpaper(Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .24f)))
                    }
                    else -> Box(Modifier.fillMaxSize().background(when (page) {
                        0 -> Color(0xFFF0F0F2); 2 -> Color(0xFF08A575); 3 -> Color(0xFFF3BE42); else -> Color.Black
                    }))
                }
                Box(Modifier.fillMaxSize().padding(safePadding).testTag("standby-safe-content-$page"), contentAlignment = Alignment.Center) {
                when (page) {
                    0 -> StandbyFullCalendar(now.toLocalDate(), locale, time)
                    3 -> Row(Modifier.fillMaxSize().background(Color(0xFFF3BE42)).padding(horizontal = 32.dp, vertical = 48.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                            Text(now.dayOfWeek.getDisplayName(TextStyle.FULL, locale), color = Color(0xFF34260C),
                                fontSize = (short * .042f).sp, fontWeight = FontWeight.Medium)
                            Text(now.dayOfMonth.toString(), color = Color(0xFF231D10),
                                fontSize = (short * .18f).sp, lineHeight = (short * .2f).sp, fontWeight = FontWeight.Bold)
                            Box(Modifier.weight(1f)) { StandbyMonth(now.toLocalDate(), locale, light = true) }
                        }
                        Box(Modifier.weight(1.75f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            StandbyAnalogClock(now, face = Color(0xFF221E14), accent = Color(0xFFF8E5AD))
                        }
                    }
                    4 -> Box(Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 48.dp)) {
                        StandbyAnalogClock(now, face = Color(0xFFEA292D), accent = Color(0xFFEA292D), wide = true)
                    }
                    1 -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date, color = Color.White, fontSize = (short * .047f).sp, fontWeight = FontWeight.Medium)
                            Text(time, color = Color.White, fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.SemiBold, fontSize = (short * .28f).sp,
                                lineHeight = (short * .32f).sp, maxLines = 1)
                        }
                    }
                    else -> Column(Modifier.fillMaxSize().background(Color(0xFF08A575)).padding(horizontal = 38.dp, vertical = 54.dp)) {
                        Text(date, color = Color(0xFF052C22), fontSize = (short * .047f).sp)
                        StandbyDigitalTime(time, Modifier.fillMaxWidth().weight(1f))
                    }
                }
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(safePadding).testTag("standby-safe-controls")) {
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(titles[pager.currentPage]), color = controls.copy(alpha = .66f),
                fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 16.dp))
            IconButton(onClick = onExit, modifier = Modifier.testTag("standby-exit")) {
                Icon(Icons.Rounded.Close, stringResource(R.string.standby_exit), tint = controls.copy(alpha = .8f))
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { index ->
                val pageLabel = stringResource(R.string.standby_page, index + 1, 5)
                Box(Modifier.size(44.dp).clickable(interactionSource = null, indication = null) { scope.launch { pager.animateScrollToPage(index) } }
                    .testTag("standby-page-$index").semantics { contentDescription = pageLabel }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (index == pager.currentPage) 7.dp else 5.dp)
                        .background(controls.copy(alpha = if (index == pager.currentPage) .9f else .35f), CircleShape))
                }
            }
        }
        }
    }
}

@Composable
private fun StandbyDigitalTime(time: String, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = time }) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(3, 31, 24)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = size.height * 1.2f
        }
        paint.textSize *= minOf(1f, size.width * .97f / paint.measureText(time), size.height * .94f / (paint.descent() - paint.ascent()))
        drawContext.canvas.nativeCanvas.drawText(time, center.x, center.y - (paint.ascent() + paint.descent()) / 2, paint)
    }
}

/** Duo Standing reference: a whole-screen month, not two narrow phone widgets.
 * All geometry is derived from the actual cover viewport (1712 x 1168 on Xiaomi).
 */
@Composable
private fun StandbyFullCalendar(date: LocalDate, locale: Locale, time: String) {
    val description = date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale))
    Canvas(Modifier.fillMaxSize().background(Color(0xFFF0F0F2))
        .testTag("standby-full-calendar").semantics { contentDescription = "$description $time" }) {
        val short = size.minDimension
        val left = size.width * .09f
        val right = size.width * .91f
        val top = 52.dp.toPx().coerceAtLeast(short * .125f)
        val bottom = size.height - 48.dp.toPx()
        val row = (bottom - top) / 8f
        val column = (right - left) / 7f
        val red = android.graphics.Color.rgb(255, 86, 93)
        val blue = android.graphics.Color.rgb(0, 99, 205)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = android.graphics.Paint.Align.CENTER
        }
        fun text(label: String, x: Float, y: Float, height: Float, color: Int) {
            paint.textSize = height; paint.color = color
            drawContext.canvas.nativeCanvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint)
        }
        val number = date.dayOfMonth.toString()
        paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        paint.textSize = row * 7.4f
        if (paint.measureText(number) > (right - left) * .76f) paint.textSize *= (right - left) * .76f / paint.measureText(number)
        val numberSize = paint.textSize
        text(number, size.width / 2f, top + row * 4.5f, numberSize, red)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textAlign = android.graphics.Paint.Align.LEFT
        text(date.format(DateTimeFormatter.ofPattern(if (locale.language == "zh") "M月" else "MMMM", locale)), left, top + row * .5f, row * .57f, red)
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        text(time, right, top + row * .5f, row * .57f, red)
        paint.textAlign = android.graphics.Paint.Align.CENTER
        repeat(7) { day ->
            text(DayOfWeek.of(if (day == 0) 7 else day).getDisplayName(TextStyle.NARROW, locale),
                left + column * (day + .5f), top + row * 1.5f, row * .56f, blue)
        }
        val first = date.withDayOfMonth(1).minusDays((date.withDayOfMonth(1).dayOfWeek.value % 7).toLong())
        repeat(42) { index ->
            val cell = first.plusDays(index.toLong())
            val x = left + column * (index % 7 + .5f)
            val y = top + row * (index / 7 + 2.5f)
            if (cell == date) drawCircle(Color(0xFF073C78), row * .43f, Offset(x, y))
            text(cell.dayOfMonth.toString(), x, y, row * .54f,
                when { cell == date -> android.graphics.Color.WHITE
                    cell.month != date.month -> android.graphics.Color.rgb(157, 189, 218)
                    else -> blue })
        }
    }
}

/** Live clock geometry (not a screenshot asset): hands reflect current local time. */
@Composable
private fun StandbyAnalogClock(now: ZonedDateTime, face: Color, accent: Color, wide: Boolean = false) {
    val description = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
        val radius = size.minDimension * .47f
        val center = center
        fun point(angle: Float, fraction: Float): Offset {
            val radians = Math.toRadians((angle - 90).toDouble())
            val xRadius = if (wide) size.width * .46f else radius
            return center + Offset(cos(radians).toFloat() * xRadius, sin(radians).toFloat() * radius) * fraction
        }
        repeat(60) { minute ->
            drawLine(face.copy(alpha = if (minute % 5 == 0) .8f else .35f),
                point(minute * 6f, .96f), point(minute * 6f, if (minute % 5 == 0) .89f else .92f),
                strokeWidth = radius * .011f)
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = if (wide) android.graphics.Color.rgb(234, 41, 45) else android.graphics.Color.rgb(34, 30, 20)
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = radius * (if (wide) .28f else .22f); typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        for (hour in 1..12) {
            val p = point(hour * 30f, .76f)
            drawContext.canvas.nativeCanvas.drawText(hour.toString(), p.x, p.y - (paint.ascent() + paint.descent()) / 2, paint)
        }
        drawLine(face, point(now.hour * 30f + now.minute / 2f, -.12f),
            point(now.hour * 30f + now.minute / 2f, .46f), radius * .049f, StrokeCap.Round)
        drawLine(face, point(now.minute * 6f + now.second / 10f, -.15f),
            point(now.minute * 6f + now.second / 10f, .7f), radius * .036f, StrokeCap.Round)
        drawLine(accent, point(now.second * 6f, -.2f), point(now.second * 6f, .92f), radius * .009f)
        drawCircle(accent, radius * .032f)
        drawCircle(Color.Black, radius * .013f)
    }
}

@Composable
private fun StandbyMonth(date: LocalDate, locale: Locale, light: Boolean = false) {
    val cells = remember(date) { standbyMonthCells(date) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val unit = minOf(maxWidth.value / 7f, maxHeight.value / 8f)
        Column(Modifier.align(Alignment.Center).width((unit * 7f).dp)) {
            Text(date.format(DateTimeFormatter.ofPattern(if (locale.language == "zh") "M月" else "MMMM", locale)),
                fontSize = (unit * .44f).sp, color = if (light) Color(0xFF5C3F06) else Color(0xFFFF453A), fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = (unit * .32f).dp))
            Row {
                for (day in 0..6) Box(Modifier.size(unit.dp), contentAlignment = Alignment.Center) {
                    Text(DayOfWeek.of(if (day == 0) 7 else day).getDisplayName(TextStyle.NARROW, locale),
                        color = if (light) Color(0xFF735923) else Color.Gray, fontSize = (unit * .38f).sp)
                }
            }
            cells.chunked(7).forEach { week -> Row {
                week.forEach { day -> Box(Modifier.size(unit.dp), contentAlignment = Alignment.Center) {
                    if (day != null) Box(Modifier.size((unit * .82f).dp)
                        .background(if (day == date.dayOfMonth) if (light) Color(0xFF483715) else Color(0xFFFF453A) else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text(day.toString(), color = if (day == date.dayOfMonth) Color.White else if (light) Color(0xFF302512) else Color(0xFFDADADD),
                            fontSize = (unit * .4f).sp)
                    }
                } }
            } }
        }
    }
}
