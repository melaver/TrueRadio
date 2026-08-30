package com.trueradio.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trueradio.app.GenreAnchors

/**
 * Names a few favourite artists for one genre.
 *
 * Deliberately a single comma-separated field rather than an add-button-plus-chips flow: typing
 * "Radiohead, Portishead, Massive Attack" in one go is far less tapping than three add cycles,
 * which matters when the user is filling this in for several genres during onboarding. Parsing
 * happens on every keystroke, so there's no separate save action to forget.
 */
@Composable
fun GenreArtistSeedEditor(
    genre: String,
    artists: List<String>,
    onArtistsChanged: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    // Local text state so the field doesn't fight the user mid-typing (e.g. deleting a trailing
    // comma would otherwise be undone by re-rendering from the parsed list).
    var text by remember(genre) { mutableStateOf(artists.joinToString(", ")) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    onArtistsChanged(
                        value.split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinctBy { it.lowercase() }
                            .take(GenreAnchors.MAX_PER_GENRE)
                    )
                },
                label = { Text(genre.uppercase()) },
                placeholder = { Text("e.g. Radiohead, Portishead, Massive Attack") },
                supportingText = {
                    Text("${GenreAnchors.RECOMMENDED_MIN}-${GenreAnchors.MAX_PER_GENRE} artists, comma separated. They steer the mix without limiting it to just them.")
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
