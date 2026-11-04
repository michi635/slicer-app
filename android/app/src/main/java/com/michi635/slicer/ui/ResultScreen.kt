@file:OptIn(ExperimentalMaterial3Api::class)

package com.michi635.slicer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.michi635.slicer.MainViewModel
import com.michi635.slicer.model.SliceResult
import java.io.File
import kotlin.math.roundToInt

fun formatDuration(seconds: Double): String {
    val total = (seconds / 60).roundToInt()
    val h = total / 60
    val m = total % 60
    return if (h > 0) "$h h $m min" else "$m min"
}

private fun fmt(v: Double, digits: Int = 1) = "%.${digits}f".format(v).replace('.', ',')

fun shareFile(context: Context, file: File, title: String, view: Boolean = false) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = if (view) {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/x.gcode")
    } else {
        Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, title))
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
fun ResultScreen(vm: MainViewModel, r: SliceResult) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) vm.saveTo(uri)
    }
    BackHandler { vm.result = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fertig gesliced") },
                navigationIcon = {
                    IconButton(onClick = { vm.result = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Text(formatDuration(r.timeSeconds), style = MaterialTheme.typography.displayMedium)
                Text("Druckzeit (Schätzung)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column {
                        Text("${fmt(r.weightGrams)} g", style = MaterialTheme.typography.headlineSmall)
                        Text("Filament", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column {
                        Text("${r.layers}", style = MaterialTheme.typography.headlineSmall)
                        Text("Schichten", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.filamentChanges > 0) {
                        Column {
                            Text("${r.filamentChanges}", style = MaterialTheme.typography.headlineSmall)
                            Text("Farbwechsel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.padding(12.dp))
                Button(onClick = { shareFile(context, r.gcode, "G-code an Anycubic-App senden") }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Teilen (z. B. an Anycubic-App)")
                }
                OutlinedButton(onClick = { shareFile(context, r.gcode, "G-code öffnen mit", view = true) }, Modifier.fillMaxWidth()) {
                    Text("Öffnen mit …")
                }
                OutlinedButton(onClick = { save.launch(r.name) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Speichern unter …")
                }
                SectionTitle("Schichtvorschau")
                LayerPreview(r.gcode, vm.printer, vm.slots.map { parseColor(it.color) }, Modifier.fillMaxWidth())

                if (r.filaments.isNotEmpty()) {
                    SectionTitle("Verbrauch je Filament")
                    r.filaments.forEach { (slot, meters, grams) ->
                        val s = vm.slots.getOrNull(slot - 1)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(parseColor(s?.color ?: "#FFFFFF"), size = 22)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Slot $slot · " + (s?.let { vm.filamentOf(it)?.label } ?: ""),
                                Modifier.weight(1f),
                            )
                            Text("${fmt(meters, 2)} m · ${fmt(grams)} g")
                        }
                    }
                }

                if (r.warnings.isNotEmpty()) {
                    SectionTitle("Hinweise")
                    r.warnings.forEach { w ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.padding(12.dp)) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(w, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Text(
                    r.name,
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
