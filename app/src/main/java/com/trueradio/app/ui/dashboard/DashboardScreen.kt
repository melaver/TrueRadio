package com.trueradio.app.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trueradio.app.DaySegment
import com.trueradio.app.SecureSettings
import com.trueradio.app.SegmentGenres
import com.trueradio.app.service.RadioServiceState
import com.trueradio.app.ui.theme.RadioPalette
import kotlinx.coroutines.launch

/**
 * Dashboard styled as a 1970s silver-face receiver faceplate: brushed champagne panel, a blue
 * backlit tuning window with an amber needle, and a heavy knob for power.
 *
 * The layout is scrollable rather than weight-distributed, because on short screens a fixed
 * faceplate would either clip the dial or squash the controls - a real faceplate can be any size,
 * a phone screen can't.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    settings: SecureSettings,
    onStartRadio: (String) -> Unit,
    onStopRadio: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onRemix: () -> Unit,
    onForceNews: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val isRunning by RadioServiceState.isRunning.collectAsState()
    val status by RadioServiceState.status.collectAsState()
    val nowPlaying by RadioServiceState.nowPlaying.collectAsState()
    val daySegment by RadioServiceState.daySegment.collectAsState()
    val currentGenre by RadioServiceState.currentGenre.collectAsState()
    val isRateLimited by RadioServiceState.isRateLimited.collectAsState()
    val dailyQuotaExhausted by RadioServiceState.dailyQuotaExhausted.collectAsState()

    val spotifyClientId by settings.spotifyClientId.collectAsState(initial = "")
    val geminiKey by settings.geminiApiKey.collectAsState(initial = "")
    val segmentGenres by settings.segmentGenres.collectAsState(initial = SegmentGenres())

    val dialGenres = remember(segmentGenres, daySegment) {
        segmentGenres.genresFor(daySegment).ifEmpty { SegmentGenres.ALL_GENRES.take(8) }
    }
    val pagerState = rememberPagerState(pageCount = { dialGenres.size })

    // Sync only on a real genre change, never while the user is turning the dial.
    LaunchedEffect(currentGenre) {
        val genre = currentGenre ?: return@LaunchedEffect
        if (pagerState.isScrollInProgress) return@LaunchedEffect
        val index = dialGenres.indexOfFirst { it.equals(genre, ignoreCase = true) }
        if (index >= 0 && index != pagerState.currentPage) pagerState.animateScrollToPage(index)
    }

    Box(
        Modifier
            .fillMaxSize()
            // Walnut cabinet behind the faceplate.
            .background(
                Brush.verticalGradient(listOf(RadioPalette.Walnut, RadioPalette.WalnutDark))
            )
            .padding(horizontal = 10.dp, vertical = 14.dp)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(RadioPalette.brushedMetal)
                .border(1.dp, RadioPalette.ChampagneDark, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FacePlateHeader(onOpenSettings = onOpenSettings)
            Spacer(Modifier.height(14.dp))

            DialWindow(
                daySegment = daySegment,
                genre = currentGenre,
                nowPlaying = nowPlaying,
                status = status,
                isRunning = isRunning,
                tunedIndex = pagerState.currentPage,
                totalGenres = dialGenres.size
            )

            Spacer(Modifier.height(18.dp))
            EngravedLabel("TUNING")
            Spacer(Modifier.height(6.dp))
            GenreDial(genres = dialGenres, pagerState = pagerState)

            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                if (isRunning && nowPlaying != null) {
                    SmallKnobButton(icon = { Icon(Icons.Default.ThumbUp, null, tint = RadioPalette.Champagne) }, label = "LIKE", onClick = onLike)
                }
                PowerKnob(
                    isRunning = isRunning,
                    enabled = spotifyClientId.isNotBlank(),
                    onToggle = {
                        if (isRunning) {
                            onStopRadio()
                        } else {
                            scope.launch {
                                dialGenres.getOrNull(pagerState.currentPage)?.let {
                                    settings.saveTunedGenreOverride(it)
                                }
                                onStartRadio(spotifyClientId)
                            }
                        }
                    }
                )
                if (isRunning && nowPlaying != null) {
                    SmallKnobButton(icon = { Icon(Icons.Default.ThumbDown, null, tint = RadioPalette.Champagne) }, label = "SKIP", onClick = onDislike)
                }
            }

            if (isRunning && nowPlaying != null) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    SmallKnobButton(
                        icon = { Icon(Icons.Default.Refresh, null, tint = RadioPalette.Champagne) },
                        label = "REMIX",
                        onClick = onRemix
                    )
                    SmallKnobButton(
                        icon = { Icon(Icons.Default.Campaign, null, tint = RadioPalette.Champagne) },
                        label = "NEWS",
                        onClick = onForceNews
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            EngravedLabel("OUTPUT LEVEL")
            Spacer(Modifier.height(6.dp))
            VuMeterPanel(isActive = isRunning)

            if (isRateLimited) {
                Spacer(Modifier.height(12.dp))
                AssistChip(
                    onClick = onOpenSettings,
                    label = {
                        Text(
                            if (dailyQuotaExhausted) "Gemini daily quota used up - DJ in offline mode"
                            else "Gemini busy - DJ using offline voice"
                        )
                    }
                )
            }

            if (spotifyClientId.isBlank() || geminiKey.isBlank()) {
                Spacer(Modifier.height(16.dp))
                AssistChip(
                    onClick = onOpenSettings,
                    label = {
                        Text(
                            if (spotifyClientId.isBlank()) "Spotify Client ID missing"
                            else "Gemini key missing - DJ won't speak"
                        )
                    }
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Brand strip along the top of the faceplate, with the settings control at the right. */
@Composable
private fun FacePlateHeader(onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                "TRUERADIO",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Light,
                letterSpacing = 6.sp,
                color = RadioPalette.KnobBlack
            )
            Text(
                "STEREO AI RECEIVER",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = Color(0xFF5A554B)
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = RadioPalette.KnobBlack)
        }
    }
}

/** Small engraved-looking caption, as printed on a metal faceplate. */
@Composable
private fun EngravedLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 4.sp,
        color = Color(0xFF5A554B)
    )
}

/**
 * The blue backlit tuning window: horizontal frequency scale, amber needle, and the readout.
 * The needle position tracks the dial rather than being decorative, so the window and the dial
 * always agree about where you're tuned.
 */
@Composable
private fun DialWindow(
    daySegment: DaySegment,
    genre: String?,
    nowPlaying: String?,
    status: String,
    isRunning: Boolean,
    tunedIndex: Int,
    totalGenres: Int
) {
    val greeting = when (daySegment) {
        DaySegment.MORNING -> "MORNING"
        DaySegment.AFTERNOON -> "AFTERNOON"
        DaySegment.EVENING -> "EVENING"
        DaySegment.NIGHT -> "LATE NIGHT"
    }
    // Needle animates between stations rather than jumping, like a real tuning pointer.
    val needleFraction by animateFloatAsState(
        targetValue = if (totalGenres <= 1) 0.5f else tunedIndex / (totalGenres - 1f),
        label = "needle"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(RadioPalette.dialWindow)
            .border(2.dp, RadioPalette.ChampagneDark, RoundedCornerShape(4.dp))
            .padding(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                greeting,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 3.sp,
                color = RadioPalette.DialGlow
            )
            // Pilot lamp: amber when live, dark when off.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (isRunning) RadioPalette.AmberBright else Color(0xFF2A3F52),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isRunning) "ON AIR" else "STANDBY",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 2.sp,
                    color = if (isRunning) RadioPalette.AmberBright else Color(0xFF5E7A88)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Frequency scale with tick marks and the amber needle.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
        ) {
            val w = size.width
            val h = size.height
            val ticks = 40
            repeat(ticks + 1) { i ->
                val x = w * i / ticks
                val major = i % 5 == 0
                drawLine(
                    color = if (major) RadioPalette.DialGlow else RadioPalette.DialGlow.copy(alpha = 0.4f),
                    start = Offset(x, 0f),
                    end = Offset(x, if (major) h * 0.45f else h * 0.25f),
                    strokeWidth = if (major) 2f else 1f
                )
            }
            val needleX = w * needleFraction
            drawLine(
                color = RadioPalette.AmberBright,
                start = Offset(needleX, 0f),
                end = Offset(needleX, h),
                strokeWidth = 4f
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            genre?.uppercase() ?: "— — —",
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 3.sp,
            color = RadioPalette.AmberBright
        )
        Spacer(Modifier.height(4.dp))
        Text(
            nowPlaying ?: if (isRunning) "TUNING..." else "NO SIGNAL",
            style = MaterialTheme.typography.bodyMedium,
            color = RadioPalette.ChampagneLight,
            maxLines = 2
        )
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = RadioPalette.DialGlow,
            maxLines = 2
        )
    }
}

/** Swipeable genre selector; snapping gives it the detented feel of a real tuning control. */
@Composable
private fun GenreDial(genres: List<String>, pagerState: PagerState) {
    Box(contentAlignment = Alignment.Center) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 88.dp),
            pageSpacing = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val selected = page == pagerState.currentPage
            Box(
                Modifier
                    .height(52.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (selected) RadioPalette.DialBlue else RadioPalette.ChampagneDark.copy(alpha = 0.5f)
                    )
                    .border(
                        1.dp,
                        if (selected) RadioPalette.AmberBright else RadioPalette.KnobRim,
                        RoundedCornerShape(3.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    genres[page].uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 1.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) RadioPalette.AmberBright else RadioPalette.KnobBlack,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

/** Large machined power knob with a pointer indicator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerKnob(isRunning: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (isRunning) 30f else -30f, label = "knobRotation")
    val depth by animateFloatAsState(if (isRunning) 3f else 10f, label = "knobDepth")

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                onClick = onToggle,
                enabled = enabled,
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier
                    .size(128.dp)
                    .shadow(depth.dp, CircleShape)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .background(
                            Brush.radialGradient(
                                0.0f to Color(0xFF3A3733),
                                0.75f to RadioPalette.KnobBlack,
                                1.0f to Color(0xFF0E0D0C)
                            ),
                            CircleShape
                        )
                        .border(3.dp, RadioPalette.KnobRim, CircleShape)
                ) {
                    // Pointer line, rotating between the two detent positions.
                    Canvas(Modifier.size(112.dp)) {
                        val cx = size.width / 2
                        val cy = size.height / 2
                        val rad = Math.toRadians(rotation.toDouble() - 90)
                        val len = size.minDimension / 2 * 0.72f
                        drawLine(
                            color = if (isRunning) RadioPalette.AmberBright else RadioPalette.KnobRim,
                            start = Offset(cx, cy),
                            end = Offset(
                                cx + (len * kotlin.math.cos(rad)).toFloat(),
                                cy + (len * kotlin.math.sin(rad)).toFloat()
                            ),
                            strokeWidth = 5f
                        )
                    }
                    Text(
                        if (isRunning) "ON" else "OFF",
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 3.sp,
                        color = if (isRunning) RadioPalette.AmberBright else RadioPalette.Champagne,
                        modifier = Modifier.offset(y = 26.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        EngravedLabel("POWER")
    }
}

/** Smaller secondary control styled to match the main knob. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmallKnobButton(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Transparent,
            modifier = Modifier
                .size(56.dp)
                .shadow(4.dp, CircleShape)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF3A3733), RadioPalette.KnobBlack)
                        ),
                        CircleShape
                    )
                    .border(2.dp, RadioPalette.KnobRim, CircleShape)
            ) { icon() }
        }
        Spacer(Modifier.height(4.dp))
        EngravedLabel(label)
    }
}
