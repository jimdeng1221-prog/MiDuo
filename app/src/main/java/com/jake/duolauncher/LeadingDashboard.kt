package com.jake.duolauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val DEFAULT_NATIVE_PROVIDERS = mapOf(
    MONTH_WIDGET to listOf("com.android.calendar/com.android.calendar.widget.SmallMonthWidgetProvider"),
    WEATHER_WIDGET to listOf("com.moji.mjweather/com.moji.mjweather.CMojiWidget4x2"),
    AGENDA_WIDGET to listOf("com.ss.android.lark/com.ss.android.lark.main.widget.calendar.CalendarSmallWidgetProvider"),
    MAP_WIDGET to listOf("com.google.android.apps.maps/com.google.android.apps.gmm.widget.traffic.TrafficWidgetProvider",
        "com.baidu.BaiduMap/com.baidu.baidumaps.desktopwidget.MapWidgetProvider"),
)

internal fun dashboardWidgetTitle(id: Int) = when (id) {
    MONTH_WIDGET -> launcherText("Calendar")
    WEATHER_WIDGET -> launcherText("Weather")
    AGENDA_WIDGET -> launcherText("Today’s agenda")
    BATTERY_WIDGET -> launcherText("Device battery")
    MAP_WIDGET -> launcherText("Map")
    else -> launcherText("Widgets")
}

@Composable
internal fun DashboardPlaceholder(id: Int, available: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val interaction = remember { MutableInteractionSource() }
    Surface(Modifier.fillMaxSize().duoGlass(DuoGlassRole.Card, shape, interaction)
        .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
        color = Color.Transparent, contentColor = duoGlassContentColor(DuoGlassRole.Card), shape = shape) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            val contentColor = LocalContentColor.current
            Icon(when (id) {
                MONTH_WIDGET -> Icons.Rounded.CalendarMonth
                WEATHER_WIDGET -> Icons.Rounded.WbSunny
                AGENDA_WIDGET -> Icons.Rounded.EventNote
                else -> Icons.Rounded.Map
            }, null, tint = contentColor.copy(alpha = .74f), modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(10.dp))
            Text(dashboardWidgetTitle(id), color = contentColor, fontWeight = FontWeight.Medium)
            Text(if (available) launcherText("Tap to add an app widget") else launcherText("Tap to choose a widget"),
                color = contentColor.copy(alpha = .68f), fontSize = 11.sp)
        }
    }
}

@Composable
internal fun BatteryRingCard(status: DeviceStatus, onClick: () -> Unit) {
    val level = status.battery
    val green = Color(0xFF6AB854)
    val shape = RoundedCornerShape(24.dp)
    val interaction = remember { MutableInteractionSource() }
    Surface(Modifier.fillMaxSize().duoGlass(DuoGlassRole.Card, shape, interaction)
        .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).semantics {
        contentDescription = launcherFormat(R.string.ui_battery_status, level?.let { "$it%" } ?: launcherFormat(R.string.ui_unavailable),
            if (status.charging) launcherText("Charging") else "")
    }, color = Color.Transparent, contentColor = duoGlassContentColor(DuoGlassRole.Card), shape = shape) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            val contentColor = LocalContentColor.current
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                    val stroke = Stroke(5.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(green.copy(alpha = .17f), -90f, 360f, false, style = stroke)
                    if (level != null) drawArc(green, -90f, level.coerceIn(0, 100) * 3.6f, false, style = stroke)
                }
                Icon(if (status.charging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.Smartphone,
                    null, tint = green, modifier = Modifier.size(25.dp))
            }
            Text(level?.let { "$it%" } ?: "—", color = contentColor, fontSize = 26.sp,
                fontWeight = FontWeight.Medium, lineHeight = 30.sp)
            Text(if (status.charging) launcherText("This device · Charging") else launcherText("This device battery"),
                color = contentColor.copy(alpha = .68f), fontSize = 10.sp, lineHeight = 12.sp)
        }
    }
}
