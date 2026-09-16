package com.jake.duolauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun StatusRail(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    iconSize: Dp = 40.dp,
    locationInUse: Boolean = false,
) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_050L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val format = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm"
    val timeFormatter = remember(format) { DateTimeFormatter.ofPattern(format) }
    val timeText = now.format(timeFormatter)
    val description = listOfNotNull(
        if (locationInUse) "Location in use" else null,
        now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, $format")),
        status.battery?.let { "Battery $it percent${if (status.charging) ", charging" else ""}" } ?: "Battery unavailable",
        if (status.wifiConnected) "Wi-Fi connected${status.wifiLevel?.let { ", signal $it of 4" } ?: ""}" else "Wi-Fi disconnected",
        if (status.airplane) "Airplane mode" else listOfNotNull(
            status.cellularTechnology.label?.let { "$it cellular network" },
            status.cellularLevel?.let { "signal $it of 4" },
        ).takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Cellular signal unavailable",
    ).joinToString(". ")
    val fontScale = LocalDensity.current.fontScale
    val labelStyle = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .3f), Offset(0f, 1f), 3f))
    val wifiVisual = wifiSignalVisual(status.wifiConnected, status.wifiLevel)
    val cellularVisual = cellularSignalVisual(status.cellularLevel, status.airplane)
    BoxWithConstraints(modifier.testTag("status-rail").semantics(mergeDescendants = true) { contentDescription = description }) {
        val availableWidth = (maxWidth - 4.dp).coerceAtLeast(28.dp)
        val visualSize = minOf(iconSize, availableWidth, if (compact) 40.dp else 52.dp)
        val timeSize = minOf(16f, visualSize.value / (2.7f * fontScale)).sp
        val detailSize = minOf(11f, availableWidth.value / (3.45f * fontScale)).sp
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Compact windows omit the reserve so status stays clear of the fixed dock.
            if (!compact) Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                // Callers currently leave this false; the slot waits for a truthful activity signal.
                if (locationInUse) Icon(Icons.Rounded.LocationOn, null, tint = Color.White,
                    modifier = Modifier.size(18.dp))
            }
            Box(Modifier.width(visualSize).testTag("status-time"), contentAlignment = Alignment.Center) {
                Text(timeText, modifier = Modifier.wrapContentWidth(unbounded = true),
                    color = Color.White, fontSize = timeSize, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = labelStyle)
            }
            Box(Modifier.size(visualSize).testTag("status-connectivity-ring"), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    val w = size.width
                    val center = Offset(w / 2, w / 2)
                    val radius = w * .44f
                    val ringWidth = w * .072f
                    val stroke = Stroke(width = ringWidth, cap = StrokeCap.Round)
                    val arcSize = Size(radius * 2, radius * 2)
                    val topLeft = Offset(center.x - radius, center.y - radius)
                    drawArc(Color.Black.copy(alpha = .15f), 150f, 240f, false, topLeft, arcSize,
                        style = Stroke(width = ringWidth * 1.25f, cap = StrokeCap.Round))
                    drawArc(Color.White.copy(alpha = .32f), 150f, 240f, false, topLeft, arcSize, style = stroke)
                    status.battery?.let {
                        drawArc(Color.White, 150f, 240f * it / 100, false, topLeft, arcSize, style = stroke)
                    }
                    val activeDots = (cellularVisual as? CellularSignalVisual.Available)?.activeDots ?: 0
                    for (i in 0..4) {
                        val angle = Math.toRadians((130 - i * 20).toDouble())
                        val lit = i < activeDots
                        val dotCenter = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
                        drawCircle(Color.Black.copy(alpha = .14f), w * .052f, dotCenter)
                        drawCircle(Color.White.copy(alpha = if (lit) 1f else .3f), w * .043f, dotCenter)
                    }
                }
                if (wifiVisual is WifiSignalVisual.Connected) {
                    Canvas(Modifier.matchParentSize()) {
                        val w = size.width
                        // A compact, optically centred fan: the shared arc origin sits below
                        // centre so the visible arches and dot balance around the ring centre.
                        val origin = Offset(w / 2f, w * .615f)
                        val radii = listOf(w * .10f, w * .185f, w * .27f)
                        radii.forEachIndexed { index, radius ->
                            val wifiBounds = Offset(origin.x - radius, origin.y - radius)
                            val wifiSize = Size(radius * 2f, radius * 2f)
                            drawArc(Color.Black.copy(alpha = .18f), 220f, 100f, false, wifiBounds, wifiSize,
                                style = Stroke(w * .068f, cap = StrokeCap.Round))
                            drawArc(Color.White.copy(alpha = signalAlpha(wifiVisual.elements[index + 1])),
                                220f, 100f, false, wifiBounds, wifiSize,
                                style = Stroke(w * .052f, cap = StrokeCap.Round))
                        }
                        drawCircle(Color.Black.copy(alpha = .18f), w * .047f, origin)
                        drawCircle(Color.White.copy(alpha = signalAlpha(wifiVisual.elements[0])), w * .038f, origin)
                    }
                } else if (status.airplane) {
                    Icon(Icons.Rounded.AirplanemodeActive, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(18.dp))
                } else if (status.cellularTechnology.label != null) {
                    Text(status.cellularTechnology.label, color = Color.White, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.graphicsLayer { scaleX = .88f }, style = labelStyle)
                } else if (status.cellularLevel != null) {
                    Icon(Icons.Rounded.SignalCellularAlt, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(17.dp))
                } else {
                    Text("—", color = Color.White.copy(alpha = .72f), fontSize = 12.sp,
                        textAlign = TextAlign.Center, style = labelStyle)
                }
            }
            if (!compact) Text(status.battery?.let { "$it%${if (status.charging) " +" else ""}" } ?: "—",
                color = Color.White.copy(alpha = .94f), fontSize = detailSize, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = labelStyle)
        }
    }
}

private fun signalAlpha(emphasis: SignalElementEmphasis): Float = when (emphasis) {
    SignalElementEmphasis.DIM -> .3f
    SignalElementEmphasis.NEUTRAL -> .62f
    SignalElementEmphasis.LIT -> 1f
}
