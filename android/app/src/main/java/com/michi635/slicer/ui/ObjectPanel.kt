@file:OptIn(ExperimentalLayoutApi::class)

package com.michi635.slicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.michi635.slicer.MainViewModel
import kotlin.math.roundToInt

/** Tools for the selected object: rotate, tip over, scale, filament slot, copy, delete. */
@Composable
fun ObjectPanel(vm: MainViewModel, modifier: Modifier = Modifier) {
    val o = vm.selected ?: return
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val b = o.bounds()
                    Text(
                        "%.0f × %.0f × %.0f mm · %d %%".format(b[2] - b[0], b[3] - b[1], o.height, (o.scale * 100).roundToInt()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.duplicate(o.id) }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Kopieren") }
                IconButton(onClick = { vm.remove(o.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Entfernen") }
                IconButton(onClick = { vm.selectedId = null }) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.rotateZ(o.id, 90.0) }, label = { Text("↺ 90°") })
                AssistChip(onClick = { vm.rotateZ(o.id, -90.0) }, label = { Text("↻ 90°") })
                AssistChip(onClick = { vm.rotateZ(o.id, 15.0) }, label = { Text("↺ 15°") })
                AssistChip(onClick = { vm.tip(o.id, 'x') }, label = { Text("Kippen X") })
                AssistChip(onClick = { vm.tip(o.id, 'y') }, label = { Text("Kippen Y") })
                if (o.rotation.x != 0.0 || o.rotation.y != 0.0 || o.rotation.z != 0.0) {
                    AssistChip(onClick = { vm.resetRotation(o.id) }, label = { Text("Drehung zurück") })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.setScale(o.id, o.scale / 1.1) }, label = { Text("− 10 %") })
                AssistChip(onClick = { vm.setScale(o.id, 1.0) }, label = { Text("100 %") })
                AssistChip(onClick = { vm.setScale(o.id, o.scale * 1.1) }, label = { Text("+ 10 %") })
                vm.printer?.let { p ->
                    AssistChip(onClick = { vm.setPosition(o.id, p.centerX, p.centerY) }, label = { Text("Mitte") })
                }
            }
            if (vm.slots.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Filament", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    vm.slots.forEachIndexed { i, slot ->
                        ColorDot(parseColor(slot.color), size = if (o.slot == i + 1) 36 else 26) { vm.setObjectSlot(o.id, i + 1) }
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }
}
