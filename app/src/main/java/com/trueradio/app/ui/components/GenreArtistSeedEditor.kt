package com.trueradio.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trueradio.app.GenreAnchors

/**
 * Lets the user name a few favourite artists for one genre. Shown once per selected genre so the
 * seeds are genre-specific - "my favourite rock artists" and "my favourite jazz artists" describe
 * different vibes and shouldn't be pooled into one list.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GenreArtistSeedEditor(
    genre: String,
    artists: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var input by rememberSaveable(genre) { mutableStateOf("") }
    val atCapacity = artists.size >= GenreAnchors.MAX_PER_GENRE

    fun commit() {
        val name = input.trim()
        if (name.isNotBlank() && !atCapacity) {
            onAdd(name)
            input = ""
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(genre.uppercase(), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${artists.size}/${GenreAnchors.MAX_PER_GENRE}",
                    style = MaterialTheme.typography.labelMedium,
                    // Amber-ish hint until they've added enough to be a useful signal.
                    color = if (artists.size < GenreAnchors.RECOMMENDED_MIN)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }

            if (artists.isEmpty()) {
                Text(
                    "Add ${GenreAnchors.RECOMMENDED_MIN}-${GenreAnchors.MAX_PER_GENRE} artists that define this genre for you. " +
                        "They steer the mix without limiting it to just them.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    artists.forEach { artist ->
                        InputChip(
                            selected = true,
                            onClick = { onRemove(artist) },
                            label = { Text(artist) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove $artist",
                                    modifier = Modifier.width(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(if (atCapacity) "Limit reached" else "Add artist") },
                    enabled = !atCapacity,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { commit() }, enabled = input.isNotBlank() && !atCapacity) {
                    Text("Add")
                }
            }
        }
    }
}
