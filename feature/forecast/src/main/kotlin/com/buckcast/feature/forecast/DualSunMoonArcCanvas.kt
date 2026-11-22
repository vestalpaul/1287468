package com.buckcast.feature.forecast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.buckcast.domain.astronomy.LunarDayInfo
import com.buckcast.domain.astronomy.SolarDayInfo
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.sin

/**
 * Custom Compose Canvas plotting 24-hour Solar (Civil Twilight, Legal Light -30m/+30m, Sunrise/Sunset)
 * and Lunar (Moonrise/Moonset) celestial arcs across the local civil day.
 */
@Composable
fun DualSunMoonArcCanvas(
    solar: SolarDayInfo,
    lunar: LunarDayInfo,
    currentTime: ZonedDateTime = ZonedDateTime.now(solar.zoneId),
    modifier: Modifier = Modifier,
) {
    val sunArcColor = MaterialTheme.colorScheme.tertiary
    val legalBandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val moonArcColor = MaterialTheme.colorScheme.secondary
    val horizonColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.onSurface

    val timeFmt = DateTimeFormatter.ofPattern("h:mm a")

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp),
        ) {
            val w = size.width
            val h = size.height
            val horizonY = h * 0.72f
            val sunAmplitude = h * 0.56f
            val moonAmplitude = h * 0.42f

            fun xForTime(dt: ZonedDateTime?): Float? {
                if (dt == null) return null
                val mins = dt.hour * 60f + dt.minute + dt.second / 60f
                return (mins / 1440f).coerceIn(0f, 1f) * w
            }

            // Horizon baseline
            drawLine(
                color = horizonColor,
                start = Offset(0f, horizonY),
                end = Offset(w, horizonY),
                strokeWidth = 2f,
            )

            // Legal shooting light bracket shading
            val legalStartX = xForTime(solar.legalShootingStart)
            val legalEndX = xForTime(solar.legalShootingEnd)
            if (legalStartX != null && legalEndX != null && legalEndX > legalStartX) {
                drawRect(
                    color = legalBandColor,
                    topLeft = Offset(legalStartX, 8f),
                    size = Size(legalEndX - legalStartX, horizonY - 8f),
                )
            }

            // Sun arc between civil dawn & civil dusk
            val dawnX = xForTime(solar.civilDawn) ?: 0f
            val duskX = xForTime(solar.civilDusk) ?: w
            if (duskX > dawnX) {
                val sunPath = Path()
                val steps = 40
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val x = dawnX + (duskX - dawnX) * t
                    val y = horizonY - (sin(t * PI).toFloat() * sunAmplitude)
                    if (i == 0) sunPath.moveTo(x, y) else sunPath.lineTo(x, y)
                }
                drawPath(
                    path = sunPath,
                    color = sunArcColor,
                    style = Stroke(width = 4.5f, cap = StrokeCap.Round),
                )
            }

            // Moon arc (supports same-day rise->set or overnight split)
            val riseX = xForTime(lunar.moonrise)
            val setX = xForTime(lunar.moonset)
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            if (riseX != null && setX != null && setX > riseX) {
                val moonPath = Path()
                val steps = 30
                for (i in 0..steps) {
                    val t = i / steps.toFloat()
                    val x = riseX + (setX - riseX) * t
                    val y = horizonY - (sin(t * PI).toFloat() * moonAmplitude)
                    if (i == 0) moonPath.moveTo(x, y) else moonPath.lineTo(x, y)
                }
                drawPath(
                    path = moonPath,
                    color = moonArcColor,
                    style = Stroke(width = 3f, pathEffect = dashEffect),
                )
            } else {
                // Draw subtle moon transit arc across active sky segment
                val transitX = xForTime(lunar.moonOverhead)
                if (transitX != null) {
                    val left = (transitX - w * 0.22f).coerceAtLeast(0f)
                    val right = (transitX + w * 0.22f).coerceAtMost(w)
                    val moonPath = Path()
                    val steps = 24
                    for (i in 0..steps) {
                        val t = i / steps.toFloat()
                        val x = left + (right - left) * t
                        val y = horizonY - (sin(t * PI).toFloat() * moonAmplitude)
                        if (i == 0) moonPath.moveTo(x, y) else moonPath.lineTo(x, y)
                    }
                    drawPath(
                        path = moonPath,
                        color = moonArcColor.copy(alpha = 0.75f),
                        style = Stroke(width = 3f, pathEffect = dashEffect),
                    )
                }
            }

            // Sunrise & Sunset marker dots
            xForTime(solar.sunrise)?.let { sx ->
                drawCircle(color = sunArcColor, radius = 6f, center = Offset(sx, horizonY))
            }
            xForTime(solar.sunset)?.let { ex ->
                drawCircle(color = sunArcColor, radius = 6f, center = Offset(ex, horizonY))
            }

            // Moonrise & Moonset marker dots
            riseX?.let { mx ->
                drawCircle(color = moonArcColor, radius = 5f, center = Offset(mx, horizonY))
            }
            setX?.let { mx ->
                drawCircle(color = moonArcColor, radius = 5f, center = Offset(mx, horizonY))
            }

            // Current time cursor
            xForTime(currentTime)?.let { nowX ->
                drawLine(
                    color = nowColor,
                    start = Offset(nowX, 6f),
                    end = Offset(nowX, h - 4f),
                    strokeWidth = 2.5f,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val legalStart = solar.legalShootingStart?.let { timeFmt.format(it) } ?: "--"
            val legalEnd = solar.legalShootingEnd?.let { timeFmt.format(it) } ?: "--"
            val moonRiseStr = lunar.moonrise?.let { timeFmt.format(it) } ?: "--"
            val moonSetStr = lunar.moonset?.let { timeFmt.format(it) } ?: "--"
            Text(
                text = "Legal Light: $legalStart – $legalEnd",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Moon: ↑$moonRiseStr ↓$moonSetStr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
