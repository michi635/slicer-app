@file:OptIn(ExperimentalMaterial3Api::class)

package com.michi635.slicer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.michi635.slicer.MainViewModel

@Composable
fun SlicerApp(vm: MainViewModel) {
    val result = vm.result
    if (result != null) {
        ResultScreen(vm, result)
    } else {
        PlateScreen(vm)
    }
    BusyDialog(vm)
    MessageDialog(vm)
}

@Composable
private fun PlateScreen(vm: MainViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addFiles(uris) }
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Slicer")
                        vm.printer?.let {
                            Text(it.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.arrange() }, enabled = vm.objects.isNotEmpty()) {
                        Icon(Icons.Filled.GridView, contentDescription = "Anordnen")
                    }
                    IconButton(onClick = { vm.clearPlate() }, enabled = vm.objects.isNotEmpty()) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Platte leeren")
                    }
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Modell hinzufügen")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("STL öffnen") }
                    Button(
                        onClick = { vm.slice() },
                        enabled = vm.objects.isNotEmpty() && vm.catalog != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("Slicen") }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            if (maxWidth >= 720.dp) {
                // Tablet: plate on the left, settings on the right
                Row(Modifier.fillMaxSize()) {
                    PlatePane(vm, Modifier.weight(1f).fillMaxHeight()) { picker.launch(arrayOf("*/*")) }
                    VerticalDivider()
                    SettingsPanel(vm, Modifier.width(400.dp).fillMaxHeight())
                }
            } else {
                // Phone: two tabs
                Column(Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Platte") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Einstellungen") })
                    }
                    if (tab == 0) {
                        PlatePane(vm, Modifier.weight(1f).fillMaxWidth()) { picker.launch(arrayOf("*/*")) }
                    } else {
                        SettingsPanel(vm, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatePane(vm: MainViewModel, modifier: Modifier, onOpen: () -> Unit) {
    Column(modifier.padding(12.dp)) {
        if (vm.objects.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PlateView(vm, Modifier.weight(1f).fillMaxWidth())
                Text("Noch kein Modell auf der Platte.", Modifier.padding(top = 12.dp))
                TextButton(onClick = onOpen) { Text("STL-Datei öffnen") }
            }
        } else {
            PlateView(vm, Modifier.weight(1f).fillMaxWidth())
            Text(
                "Tippen wählt ein Objekt, Ziehen verschiebt es.",
                Modifier.padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ObjectPanel(vm)
        }
    }
}

@Composable
private fun BusyDialog(vm: MainViewModel) {
    val busy = vm.busy ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(busy.title) },
        text = {
            Column {
                if (busy.percent >= 0) {
                    LinearProgressIndicator(progress = { busy.percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (busy.text.isNotEmpty()) {
                    Spacer(Modifier.padding(4.dp))
                    Text(busy.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (busy.cancellable) TextButton(onClick = { vm.cancel() }) { Text("Abbrechen") }
        },
    )
}

@Composable
private fun MessageDialog(vm: MainViewModel) {
    val message = vm.message ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { vm.message = null },
        title = { Text("Hinweis") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
        dismissButton = {
            if (vm.logFile.exists() && vm.logFile.length() > 0) {
                TextButton(onClick = { shareFile(context, vm.logFile, "Protokoll teilen") }) { Text("Protokoll teilen") }
            }
        },
    )
}
