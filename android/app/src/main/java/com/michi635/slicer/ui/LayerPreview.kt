@file:OptIn(ExperimentalLayoutApi::class)

package com.michi635.slicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.michi635.slicer.model.Feature
import com.michi635.slicer.model.GcodePreview
import com.michi635.slicer.model.PreviewLayer
import com.michi635.slicer.model.Printer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/** Layer-by-layer view of the sliced G-code from above, coloured by feature or by filament. */
@Composable
fun LayerPreview(gcode: File, printer: Printer?, slotColors: List<Color>, modifier: Modifier = Modifier) {
    var layers by remember(gcode) { mutableStateOf<List<PreviewLayer>?>(null) }
    var failed by remember(gcode) { mutableStateOf(false) }
    LaunchedEffect(gcode) {
        val read = withContext(Dispatchers.IO) { runCatching { GcodePreview.read(gcode) }.getOrNull() }
        if (read == null) failed = true else layers = read
    }
    val list = layers
    Column(modifier) {
        if (list == null) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!failed) CircularProgressIndicator(Modifier.width(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(if (failed) "Vorschau nicht verfügbar." else "Vorschau wird geladen …")
            }
            return@Column
        }
        if (list.isEmpty()) {
            Text("Keine Schichten im G-code gefunden.", Modifier.padding(16.dp))
            return@Column
        }
        var index by remember(list) { mutableIntStateOf(list.size - 1) }
        var byFilament by remember { mutableStateOf(false) }
        val layer = list[index.coerceIn(0, list.size - 1)]
        val bedColor = MaterialTheme.colorScheme.surfaceVariant
        val outline = MaterialTheme.colorScheme.outline

        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val minX = printer?.bedMinX ?: 0.0; val minY = printer?.bedMinY ?: 0.0
            val maxX = printer?.bedMaxX ?: 250.0; val maxY = printer?.bedMaxY ?: 250.0
            val pad = 8f
            val s = min((size.width - 2 * pad) / (maxX - minX), (size.height - 2 * pad) / (maxY - minY)).toFloat()
            val ox = (size.width - ((maxX - minX) * s).toFloat()) / 2
            val oy = (size.height - ((maxY - minY) * s).toFloat()) / 2
            fun sx(x: Float) = ox + ((x - minX) * s).toFloat()
            fun sy(y: Float) = oy + ((maxY - y) * s).toFloat()
            val bedSize = Size(((maxX - minX) * s).toFloat(), ((maxY - minY) * s).toFloat())
            drawRoundRect(bedColor, Offset(ox, oy), bedSize, CornerRadius(8f, 8f))
            drawRoundRect(outline, Offset(ox, oy), bedSize, CornerRadius(8f, 8f), style = Stroke(2f))

            // The layer below, faint, for orientation
            if (index > 0) {
                val below = list[index - 1]
                val seg = below.segments
                for (i in 0 until below.count) {
                    drawLine(outline.copy(alpha = 0.25f), Offset(sx(seg[4 * i]), sy(seg[4 * i + 1])),
                        Offset(sx(seg[4 * i + 2]), sy(seg[4 * i + 3])), strokeWidth = 1f)
                }
            }
            val width = (0.45f * s).coerceIn(1f, 6f)
            val seg = layer.segments
            val features = Feature.entries
            for (i in 0 until layer.count) {
                val color = if (byFilament) {
                    slotColors.getOrElse(layer.tools[i].toInt()) { Color.Gray }
                } else {
                    Color(features[layer.features[i].toInt()].argb)
                }
                drawLine(color, Offset(sx(seg[4 * i]), sy(seg[4 * i + 1])), Offset(sx(seg[4 * i + 2]), sy(seg[4 * i + 3])),
                    strokeWidth = width, cap = StrokeCap.Round)
            }
        }
        Text(
            "Schicht ${index + 1} von ${list.size} · Höhe ${"%.2f".format(layer.z).replace('.', ',')} mm",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = index.toFloat(),
            onValueChange = { index = it.roundToInt().coerceIn(0, list.size - 1) },
            valueRange = 0f..(list.size - 1).coerceAtLeast(1).toFloat(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !byFilament, onClick = { byFilament = false }, label = { Text("Nach Art") })
            FilterChip(selected = byFilament, onClick = { byFilament = true }, label = { Text("Nach Filament") })
        }
        if (!byFilament) {
            val used = layer.features.map { it.toInt() }.toSet()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                Feature.entries.filter { it.ordinal in used }.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(Color(f.argb), size = 12)
                        Spacer(Modifier.width(4.dp))
                        Text(f.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
