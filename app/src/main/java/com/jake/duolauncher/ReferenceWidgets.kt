package com.jake.duolauncher

import android.graphics.PathMeasure
import android.graphics.Typeface
import android.icu.util.ChineseCalendar
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
private fun widgetNow(): ZonedDateTime {
    val now by produceState(ZonedDateTime.now()) {
        while (true) { value = ZonedDateTime.now(); delay(1000) }
    }
    return now
}

/** Native, live typography and minute ticks, not a flattened screenshot of a clock. */
@Composable
internal fun ReferenceClockCard(onClick: () -> Unit) {
    val now = widgetNow()
    val format = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    val label = now.format(DateTimeFormatter.ofPattern(format))
    Surface(Modifier.fillMaxSize().clickable(onClick = onClick).testTag("reference-clock"),
        shape = RoundedCornerShape(22), color = Color.White, contentColor = Color.Black) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth.value, maxHeight.value)
            Canvas(Modifier.fillMaxSize().padding((side * .055f).dp)
                .semantics { contentDescription = label }) {
                val inset = size.minDimension * .03f
                val path = android.graphics.Path().apply {
                    addRoundRect(inset, inset, size.width - inset, size.height - inset,
                        size.minDimension * .17f, size.minDimension * .17f, android.graphics.Path.Direction.CW)
                }
                val measure = PathMeasure(path, true)
                val position = FloatArray(2)
                val tangent = FloatArray(2)
                repeat(60) { tick ->
                    measure.getPosTan(measure.length * tick / 60f, position, tangent)
                    val length = size.minDimension * .035f
                    val start = Offset(position[0], position[1])
                    drawLine(if (tick <= now.minute) Color.Black else Color(0xFFBBBBBB), start,
                        start + Offset(-tangent[1], tangent[0]) * length,
                        strokeWidth = size.minDimension * .010f, cap = StrokeCap.Round)
                }
                // Measure the live string, including wide digits and 12/24-hour forms.
                // A fixed sp size inherited an unrelated line height and clipped 00:51.
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = size.minDimension * .46f
                }
                val available = size.width * .78f
                val measured = paint.measureText(label)
                if (measured > available) paint.textSize *= available / measured
                drawContext.canvas.nativeCanvas.drawText(label, size.width / 2f,
                    size.height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
            }
        }
    }
}

internal fun lunarDayName(day: Int): String = when (day) {
    10 -> "初十"
    20 -> "二十"
    30 -> "三十"
    else -> listOf("初", "十", "廿")[(day - 1) / 10] + "一二三四五六七八九"[(day - 1) % 10]
}

@Composable
internal fun ReferenceCalendarCard(onClick: () -> Unit) {
    val now = widgetNow()
    val locale = LocalConfiguration.current.locales[0]
    val chinese = locale.language == "zh"
    val lunar = remember(now.toLocalDate(), now.zone) {
        ChineseCalendar().apply { timeInMillis = now.toInstant().toEpochMilli() }
    }
    val lunarMonth = lunar.get(android.icu.util.Calendar.MONTH) + 1
    val lunarDay = lunar.get(android.icu.util.Calendar.DAY_OF_MONTH)
    val lunarText = if (chinese) {
        (if (lunar.get(ChineseCalendar.IS_LEAP_MONTH) == 1) "闰" else "") +
            listOf("正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")[lunarMonth - 1] +
            "月" + lunarDayName(lunarDay)
    } else "Lunar\n$lunarMonth/$lunarDay"
    Surface(Modifier.fillMaxSize().clickable(onClick = onClick).testTag("reference-calendar"),
        shape = RoundedCornerShape(22), color = Color.White, contentColor = Color.Black) {
        BoxWithConstraints {
            val side = minOf(maxWidth.value, maxHeight.value)
            Column(Modifier.fillMaxSize().padding((side * .10f).dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy((side * .035f).dp)) {
                    Text(now.format(DateTimeFormatter.ofPattern(if (chinese) "EEE" else "EEE", locale)),
                        color = Color(0xFFFF3B46), fontSize = (side * .10f).sp, fontWeight = FontWeight.Bold)
                    Text(now.format(DateTimeFormatter.ofPattern(if (chinese) "M月" else "MMM", locale)),
                        color = Color(0xFF88888C), fontSize = (side * .10f).sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    Text(now.dayOfMonth.toString(), fontSize = (side * .49f).sp,
                        lineHeight = (side * .53f).sp, fontWeight = FontWeight.Bold, letterSpacing = (-side * .025f).sp)
                    Spacer(Modifier.width((side * .025f).dp))
                    Text(if (chinese) lunarText.toCharArray().joinToString("\n") else lunarText,
                        fontSize = (side * if (chinese) .08f else .052f).sp,
                        lineHeight = (side * if (chinese) .085f else .075f).sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Explicitly labelled sample; no location lookup, network request or invented live weather. */
@Composable
internal fun ReferenceWeatherCard(onClick: () -> Unit) {
    val now = widgetNow()
    val example = stringResource(R.string.weather_example)
    Surface(Modifier.fillMaxSize().clickable(onClick = onClick).testTag("reference-weather")
        .semantics { contentDescription = example }, shape = RoundedCornerShape(18),
        color = Color(0xFF30374F), contentColor = Color.White) {
        BoxWithConstraints {
            val unit = minOf(maxHeight.value, maxWidth.value / 2f)
            Column(Modifier.fillMaxSize().padding((unit * .10f).dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(stringResource(R.string.weather_location), fontSize = (unit * .085f).sp,
                            lineHeight = (unit * .105f).sp, fontWeight = FontWeight.Medium)
                        Text("25°", fontSize = (unit * .27f).sp, fontWeight = FontWeight.Light, lineHeight = (unit * .29f).sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(example, color = Color(0xFFCAD6F4), fontSize = (unit * .065f).sp, lineHeight = (unit * .085f).sp)
                        Icon(Icons.Rounded.NightsStay, null, Modifier.size((unit * .12f).dp))
                        Text(stringResource(R.string.weather_clear), fontSize = (unit * .075f).sp, lineHeight = (unit * .095f).sp)
                        Text(stringResource(R.string.weather_high_low), fontSize = (unit * .075f).sp, lineHeight = (unit * .095f).sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(25, 24, 24, 23, 23, 22).forEachIndexed { index, temperature ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy((unit * .04f).dp)) {
                            Text(stringResource(R.string.weather_hour, (now.hour + index + 1) % 24),
                                color = Color(0xFFCDD0DF), fontSize = (unit * .067f).sp, lineHeight = (unit * .085f).sp)
                            Icon(if (index < 4) Icons.Rounded.NightsStay else Icons.Rounded.Cloud,
                                null, Modifier.size((unit * .10f).dp))
                            Text("$temperature°", fontSize = (unit * .075f).sp, lineHeight = (unit * .095f).sp,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
