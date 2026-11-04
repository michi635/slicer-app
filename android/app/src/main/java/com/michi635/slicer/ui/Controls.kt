@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.michi635.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.michi635.slicer.model.Filament

/** A label on the left, a control on the right, and a reset button when the value differs from the preset. */
@Composable
fun SettingRow(
    label: String,
    overridden: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (overridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        content()
        if (overridden) {
            IconButton(onClick = onReset) { Icon(Icons.Filled.RestartAlt, contentDescription = "Auf Profilwert zurücksetzen") }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/** A button showing the current choice; tapping opens a menu. */
@Composable
fun <T> Choice(
    current: String,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

/** − value + */
@Composable
fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (value > range.first) onChange(value - 1) }, enabled = value > range.first) { Text("−") }
        Text("$value", Modifier.width(32.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { if (value < range.last) onChange(value + 1) }, enabled = value < range.last) { Text("+") }
    }
}

@Composable
fun ColorDot(color: Color, size: Int = 28, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    )
}

@Composable
fun SectionTitle(text: String) {
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

val PALETTE = listOf(
    "#FFFFFF", "#F5F5DC", "#BDBDBD", "#616161", "#202020",
    "#D32F2F", "#F06292", "#7B1FA2", "#3949AB", "#1976D2",
    "#4FC3F7", "#00897B", "#388E3C", "#9CCC65", "#FBC02D",
    "#FFB300", "#F57C00", "#8D6E63", "#C0A060", "#FFD54F",
)

@Composable
fun ColorPickerDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Farbe") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PALETTE.forEach { hex ->
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(parseColor(hex))
                            .border(if (hex.equals(current, true)) 3.dp else 1.dp,
                                if (hex.equals(current, true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onPick(hex) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

/** Filament choice by brand. */
@Composable
fun FilamentPickerDialog(
    filaments: List<Filament>,
    brands: List<String>,
    current: String,
    onDismiss: () -> Unit,
    onPick: (Filament) -> Unit,
) {
    val currentBrand = filaments.firstOrNull { it.name == current }?.let { Filament.brandOf(it) } ?: brands.first()
    var brand by remember { mutableStateOf(currentBrand) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filament") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    brands.forEach { b ->
                        FilterChip(selected = b == brand, onClick = { brand = b }, label = { Text(b) })
                    }
                }
                val list = filaments.filter { Filament.brandOf(it) == brand }
                if (list.isEmpty()) {
                    Text("Keine Profile für $brand.", Modifier.padding(16.dp))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.name }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(f) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(f.label, style = MaterialTheme.typography.bodyLarge,
                                    color = if (f.name == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text("${f.type} · ${f.temperature} °C", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
