package com.trueradio.app.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.launch

/**
 * Radio-styled dashboard: a "tuning display" panel up top, a swipeable genre dial in the middle,
 * and a heavy mechanical power button below.
 *
 * All live state comes from [RadioServiceState] rather than local UI flags, so the screen stays
 * truthful across rotation and when the service is stopped from its notification.
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
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val isRunning by RadioServiceState.isRunning.collectAsState()
    val status by RadioServiceState.status.collectAsState()
    val nowPlaying by RadioServiceState.nowPlaying.collectAsState()
    val daySegment by RadioServiceState.daySegment.collectAsState()
    val currentGenre by RadioServiceState.currentGenre.collectAsState()

    val spotifyClientId by settings.spotifyClientId.collectAsState(initial = "")
    val geminiKey by settings.geminiApiKey.collectAsState(initial = "")
    val segmentGenres by settings.segmentGenres.collectAsState(initial = SegmentGenres())

    // The dial tunes within the genres configured for the CURRENT daypart, so it always offers
    // choices appropriate to the time of day rather than the entire genre catalogue.
    val dialGenres = remember(segmentGenres, daySegment) {
        segmentGenres.genresFor(daySegment).ifEmpty { SegmentGenres.ALL_GENRES.take(8) }
    }
    val pagerState = rememberPagerState(pageCount = { dialGenres.size })

    // Keep the dial in sync when the service switches genre on its own (hourly rotation), so the
    // needle doesn't sit on a genre that isn't actually playing.
    LaunchedEffect(currentGenre, dialGenres) {
        val index = dialGenres.indexOfFirst { it.equals(currentGenre, ignoreCase = true) }
        if (index >= 0 && index != pagerState.currentPage) pagerState.animateScrollToPage(index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TrueRadio", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            TuningDisplay(
                daySegment = daySegment,
                genre = currentGenre,
                nowPlaying = nowPlaying,
                status = status,
                isRunning = isRunning
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "TUNE",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            GenreDial(genres = dialGenres, pagerState = pagerState)

            Spacer(Modifier.weight(1f))

            PowerButton(
                isRunning = isRunning,
                enabled = spotifyClientId.isNotBlank(),
                onToggle = {
                    if (isRunning) {
                        onStopRadio()
                    } else {
                        // Persist the tuned genre as the daypart's lead choice so the service
                        // builds this hour's mix around what the user actually dialled in.
                        scope.launch {
                            val tuned = dialGenres.getOrNull(pagerState.currentPage)
                            if (tuned != null) {
                                val reordered = listOf(tuned) + dialGenres.filterNot { it == tuned }
                                settings.saveSegmentGenres(
                                    segmentGenres.copy(
                                        bySegment = segmentGenres.bySegment + (daySegment to reordered)
                                    )
                                )
                            }
                            onStartRadio(spotifyClientId)
                        }
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            if (isRunning && nowPlaying != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onLike) {
                        Icon(Icons.Default.ThumbUp, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Like")
                    }
                    FilledTonalButton(onClick = onDislike) {
                        Icon(Icons.Default.ThumbDown, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Skip")
                    }
                    FilledTonalButton(onClick = onRemix) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Remix")
                    }
                }
            }

            if (spotifyClientId.isBlank() || geminiKey.isBlank()) {
                Spacer(Modifier.height(12.dp))
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

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** The amber "readout" panel, styled after a radio's backlit display. */
@Composable
private fun TuningDisplay(
    daySegment: DaySegment,
    genre: String?,
    nowPlaying: String?,
    status: String,
    isRunning: Boolean
) {
    val greeting = when (daySegment) {
        DaySegment.MORNING -> "Good Morning"
        DaySegment.AFTERNOON -> "Good Afternoon"
        DaySegment.EVENING -> "Good Evening"
        DaySegment.NIGHT -> "Late Night"
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    greeting,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                // "ON AIR" indicator, the one unmistakable signal of whether the radio is live.
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isRunning) Color(0xFFD32F2F) else MaterialTheme.colorScheme.outline
                ) {
                    Text(
                        if (isRunning) "ON AIR" else "OFF",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                genre?.uppercase() ?: "— — —",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                nowPlaying ?: if (isRunning) "Waiting for Spotify..." else "Not playing",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
            Spacer(Modifier.height(4.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

/**
 * Swipeable genre dial. Uses HorizontalPager for built-in snapping, so each swipe lands cleanly
 * on one genre the way a detented tuning knob would, rather than free-scrolling between them.
 */
@Composable
private fun GenreDial(
    genres: List<String>,
    pagerState: androidx.compose.foundation.pager.PagerState
) {
    Box(contentAlignment = Alignment.Center) {
        HorizontalPager(
            state = pagerState,
            // Side padding reveals the neighbouring genres, which is what makes it read as a dial
            // you're scrolling through rather than a single-item carousel.
            contentPadding = PaddingValues(horizontal = 90.dp),
            pageSpacing = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val selected = page == pagerState.currentPage
            val scale by animateFloatAsState(if (selected) 1f else 0.8f, label = "dialScale")
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .scale(scale)
                    .height(64.dp)
                    .fillMaxWidth()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        genres[page].uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }
        }
        // Fixed centre needle marking the tuned position.
        Box(
            Modifier
                .height(80.dp)
                .width(2.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** Heavy mechanical power button; presses in and lights up when live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowerButton(isRunning: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    // Depth drops when active, so the control reads as physically pressed in rather than just
    // recoloured.
    val elevation by animateFloatAsState(if (isRunning) 2f else 12f, label = "buttonDepth")
    Surface(
        onClick = onToggle,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier
            .size(150.dp)
            .shadow(elevation.dp, CircleShape)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.background(
                brush = if (isRunning) {
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
                    )
                } else {
                    Brush.radialGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
                    )
                },
                shape = CircleShape
            )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isRunning) "STOP" else "START",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
