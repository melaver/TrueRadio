package com.trueradio.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Adds artists one at a time as removable chips.
 *
 * Replaces an earlier comma-separated text field. That parsed on every keystroke, which meant a
 * half-typed name was briefly stored as an artist, and it gave no clear affordance for removing
 * one entry - you had to edit around commas. Chips make each entry a discrete object with an
 * obvious delete target, and the keyboard's Done action commits without reaching for a button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LikedArtistsEditor(
    artists: List<String>,
    maxArtists: Int,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    label: String = "Add an artist",
    placeholder: String = "e.g. Radiohead",
    modifier: Modifier = Modifier
) {
    var input by rememberSaveable { mutableStateOf("") }
    val atCapacity = artists.size >= maxArtists

    fun commit() {
        val name = input.trim()
        if (name.isNotBlank() && !atCapacity) {
            onAdd(name)
            input = "" // cleared so the next name can be typed straight away
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(if (atCapacity) "Limit reached ($maxArtists)" else label) },
                placeholder = { Text(placeholder) },
                enabled = !atCapacity,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.weight(1f)
            )
            FilledIconButton(
                onClick = { commit() },
                enabled = input.isNotBlank() && !atCapacity
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add artist")
            }
        }

        if (artists.isEmpty()) {
            Text(
                "No artists yet. These steer the mix toward your taste without limiting it to " +
                    "only these artists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
            Text(
                "${artists.size}/$maxArtists · tap a chip to remove it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
