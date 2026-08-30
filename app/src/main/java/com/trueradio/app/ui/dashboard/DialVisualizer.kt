package com.trueradio.app.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trueradio.app.ui.theme.RadioPalette
import kotlin.math.abs
import kotlin.math.sin

/**
 * Spectrum-bar visualizer for the dial window.
 *
 * IMPORTANT - this is procedural, not a real FFT of the music, and that is deliberate. Android's
 * Visualizer API can only read audio from your own app's session; capturing another app's output
 * needs AudioPlaybackCapture (API 29+), which Spotify opts out of via ALLOW_CAPTURE_BY_NONE like
 * most DRM-bearing music apps. So genuine spectrum data for Spotify playback is not obtainable at
 * any permission level. A real Visualizer would also require RECORD_AUDIO - a microphone prompt
 * that would look alarming in a radio app - for a purely decorative meter.
 *
 * The motion is therefore synthesised: each bar is the sum of three sine waves at incommensurable
 * frequencies, so the pattern never visibly repeats, with bar-dependent phase offsets so bars
 * don't move in lockstep. It reads as audio-reactive without claiming to be.
 */
@Composable
fun DialVisualizer(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24
) {
    val transition = rememberInfiniteTransition(label = "spectrum")
    // One slow master phase drives everything; per-bar variety comes from the phase offsets below
    // rather than from many animations, which keeps this to a single recomposition source.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Stable per-bar constants so the pattern is deterministic across recompositions.
    val seeds = remember(barCount) {
        List(barCount) { i ->
            Triple(
                0.7f + (i % 5) * 0.31f,   // frequency multiplier
                i * 0.53f,                 // phase offset
                0.55f + ((i * 37) % 45) / 100f // per-bar amplitude ceiling
            )
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(40.dp)) {
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        if (barWidth <= 0f) return@Canvas

        seeds.forEachIndexed { i, (freq, offset, ceiling) ->
            val level = if (!isActive) {
                0.06f // resting sliver, so the meter reads as present but idle
            } else {
                // Three incommensurable sines: the combined pattern doesn't visibly loop.
                val a = sin(phase * freq + offset)
                val b = sin(phase * freq * 1.7f + offset * 2.1f) * 0.5f
                val c = sin(phase * freq * 0.43f + offset * 0.7f) * 0.35f
                (abs(a + b + c) / 1.85f).coerceIn(0.05f, 1f) * ceiling
            }

            val barHeight = size.height * level
            val x = i * (barWidth + gap)
            // Amber for peaks, blue below - matching the needle/scale relationship in the window.
            val color = if (level > 0.72f) RadioPalette.AmberBright else RadioPalette.DialGlow
            drawRect(
                color = color,
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight)
            )
        }
    }
}
