package com.trueradio.app.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trueradio.app.ui.theme.RadioPalette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Twin VU meters in their own lit panel, as on a period receiver.
 *
 * Procedural, not a real level reading - Android's Visualizer can only read audio from your own
 * app's session, and Spotify opts out of AudioPlaybackCapture like most DRM-bearing music apps,
 * so genuine levels for the music aren't obtainable at any permission level. A real Visualizer
 * would also demand RECORD_AUDIO, which is an alarming prompt for a decorative meter.
 *
 * The needles are driven by summed sines at incommensurable frequencies so the sweep never
 * visibly loops, with the two channels offset from each other - real stereo material rarely has
 * both channels peaking in perfect unison, and mirrored needles look obviously fake.
 */
@Composable
fun VuMeterPanel(isActive: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "vu")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vuPhase"
    )

    fun level(offset: Float): Float {
        if (!isActive) return 0f
        val a = sin(phase * 1.0f + offset)
        val b = sin(phase * 2.3f + offset * 1.6f) * 0.45f
        val c = sin(phase * 0.61f + offset * 0.4f) * 0.3f
        return (abs(a + b + c) / 1.75f).coerceIn(0f, 1f)
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(RadioPalette.dialWindow)
            .border(2.dp, RadioPalette.ChampagneDark, RoundedCornerShape(4.dp))
            .padding(vertical = 10.dp, horizontal = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VuMeter(label = "L", level = level(0f), modifier = Modifier.weight(1f))
            VuMeter(label = "R", level = level(2.4f), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun VuMeter(label: String, level: Float, modifier: Modifier = Modifier) {
    // Needle lags the target slightly, the way a real meter's ballistics do - an instantaneous
    // needle reads as digital, which is the opposite of the intent here.
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = level,
        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
        label = "needle"
    )

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(78.dp)
        ) {
            drawVuMeter(animated)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            color = RadioPalette.DialGlow
        )
    }
}

/**
 * Draws the arc scale, tick marks, red overload zone and needle.
 *
 * The pivot sits below the visible area so the needle sweeps the top arc only, which is how a
 * real VU meter is laid out - putting the pivot inside the box would waste most of the panel on
 * the needle's lower half.
 */
private fun DrawScope.drawVuMeter(level: Float) {
    val w = size.width
    val h = size.height
    val pivot = Offset(w / 2f, h * 1.15f)
    val radius = h * 1.0f

    // Sweep from -52° to +52° measured from vertical.
    val startDeg = -52.0
    val endDeg = 52.0

    fun pointAt(deg: Double, r: Float): Offset {
        val rad = Math.toRadians(deg - 90)
        return Offset(
            pivot.x + (r * cos(rad)).toFloat(),
            pivot.y + (r * sin(rad)).toFloat()
        )
    }

    // Arc scale
    drawArc(
        color = RadioPalette.DialGlow,
        startAngle = (startDeg - 90).toFloat(),
        sweepAngle = (endDeg - startDeg).toFloat(),
        useCenter = false,
        topLeft = Offset(pivot.x - radius, pivot.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = 1.5f)
    )

    // Overload arc, top third of the scale.
    val redStart = startDeg + (endDeg - startDeg) * 0.72
    drawArc(
        color = RadioPalette.Amber,
        startAngle = (redStart - 90).toFloat(),
        sweepAngle = (endDeg - redStart).toFloat(),
        useCenter = false,
        topLeft = Offset(pivot.x - radius, pivot.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = 2.5f)
    )

    // Tick marks
    val ticks = 9
    repeat(ticks) { i ->
        val deg = startDeg + (endDeg - startDeg) * i / (ticks - 1)
        val major = i % 2 == 0
        val outer = pointAt(deg, radius)
        val inner = pointAt(deg, radius - if (major) 9f else 5f)
        drawLine(
            color = if (deg >= redStart) RadioPalette.Amber else RadioPalette.DialGlow,
            start = inner,
            end = outer,
            strokeWidth = if (major) 1.8f else 1f
        )
    }

    // Needle
    val needleDeg = startDeg + (endDeg - startDeg) * level
    drawLine(
        color = RadioPalette.AmberBright,
        start = pointAt(needleDeg, radius * 0.28f),
        end = pointAt(needleDeg, radius - 6f),
        strokeWidth = 2.5f
    )
    // Pivot hub, clipped by the panel edge - only its top half is visible, as on real hardware.
    drawCircle(
        color = RadioPalette.ChampagneDark,
        radius = 5f,
        center = pointAt(needleDeg, radius * 0.28f)
    )
}
