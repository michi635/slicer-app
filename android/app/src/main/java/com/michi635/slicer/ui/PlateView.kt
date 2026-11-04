package com.michi635.slicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.michi635.slicer.MainViewModel
import com.michi635.slicer.model.Printer
import kotlin.math.min

/** Maps bed millimetres to canvas pixels (y up on the bed, down on screen). */
private class PlateTransform(size: Size, printer: Printer?, padding: Float) {
    val minX = printer?.bedMinX ?: 0.0
    val minY = printer?.bedMinY ?: 0.0
    val maxX = printer?.bedMaxX ?: 250.0
    val maxY = printer?.bedMaxY ?: 250.0
    val scale: Float = min((size.width - 2 * padding) / (maxX - minX).toFloat(), (size.height - 2 * padding) / (maxY - minY).toFloat())
        .coerceAtLeast(0.01f)
    private val ox = (size.width - (maxX - minX).toFloat() * scale) / 2
    private val oy = (size.height - (maxY - minY).toFloat() * scale) / 2

    fun toScreen(x: Double, y: Double) = Offset(ox + ((x - minX) * scale).toFloat(), oy + ((maxY - y) * scale).toFloat())
    fun toBedX(px: Float) = minX + (px - ox) / scale
    fun toBedY(py: Float) = maxY - (py - oy) / scale
}

/** Top view of the build plate: tap selects an object, dragging moves it. */
@Composable
fun PlateView(vm: MainViewModel, modifier: Modifier = Modifier) {
    val printer = vm.printer
    val objects = vm.objects
    val selectedId = vm.selectedId
    val slots = vm.slots
    val tower = vm.primeTower
    val currentPrinter by rememberUpdatedState(printer)
    val currentObjects by rememberUpdatedState(objects)

    val bedColor = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val outline = MaterialTheme.colorScheme.outline
    val highlight = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val density = androidx.compose.ui.platform.LocalDensity.current
    val padding = with(density) { 12.dp.toPx() }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val t = PlateTransform(Size(size.width.toFloat(), size.height.toFloat()), currentPrinter, padding)
                    val bx = t.toBedX(pos.x); val by = t.toBedY(pos.y)
                    vm.selectedId = currentObjects.lastOrNull { it.contains(bx, by) }?.id
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val t = PlateTransform(Size(size.width.toFloat(), size.height.toFloat()), currentPrinter, padding)
                        val hit = currentObjects.lastOrNull { it.contains(t.toBedX(pos.x), t.toBedY(pos.y)) }
                        if (hit != null) vm.selectedId = hit.id
                    },
                    onDrag = { change, drag ->
                        if (vm.selectedId != null) {
                            change.consume()
                            val t = PlateTransform(Size(size.width.toFloat(), size.height.toFloat()), currentPrinter, padding)
                            vm.moveSelected(drag.x / t.scale.toDouble(), -drag.y / t.scale.toDouble())
                        }
                    },
                )
            }
    ) {
        val t = PlateTransform(size, printer, padding)
        val bedTopLeft = t.toScreen(t.minX, t.maxY)
        val bedSize = Size(((t.maxX - t.minX) * t.scale).toFloat(), ((t.maxY - t.minY) * t.scale).toFloat())
        drawRoundRect(bedColor, bedTopLeft, bedSize, CornerRadius(8f, 8f))

        // 50 mm grid
        var gx = t.minX + 50
        while (gx < t.maxX) {
            drawLine(gridColor, t.toScreen(gx, t.minY), t.toScreen(gx, t.maxY), 1f)
            gx += 50
        }
        var gy = t.minY + 50
        while (gy < t.maxY) {
            drawLine(gridColor, t.toScreen(t.minX, gy), t.toScreen(t.maxX, gy), 1f)
            gy += 50
        }
        drawRoundRect(outline, bedTopLeft, bedSize, CornerRadius(8f, 8f), style = Stroke(2f))

        // Prime tower of the last slice or arrangement
        tower?.let {
            val tl = t.toScreen(it.x, it.y + it.depth)
            drawRect(outline.copy(alpha = 0.35f), tl, Size((it.width * t.scale).toFloat(), (it.depth * t.scale).toFloat()))
            drawRect(outline, tl, Size((it.width * t.scale).toFloat(), (it.depth * t.scale).toFloat()), style = Stroke(2f))
        }

        for (o in objects) {
            val f = o.footprint
            if (f.size < 6) continue
            val path = Path()
            for (i in 0 until f.size / 2) {
                val p = t.toScreen(o.x + f[2 * i], o.y + f[2 * i + 1])
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            val slot = slots.getOrNull(o.slot - 1)
            val fill = slot?.let { parseColor(it.color) } ?: Color.White
            drawPath(path, fill.copy(alpha = 0.9f))
            val b = o.bounds()
            val outside = printer != null &&
                (b[0] < printer.bedMinX - 0.01 || b[1] < printer.bedMinY - 0.01 || b[2] > printer.bedMaxX + 0.01 ||
                    b[3] > printer.bedMaxY + 0.01 || o.height > printer.height)
            val stroke = when {
                outside -> error
                o.id == selectedId -> highlight
                else -> Color.Black.copy(alpha = 0.6f)
            }
            drawPath(path, stroke, style = Stroke(if (o.id == selectedId || outside) 5f else 2f))
        }
    }
}
