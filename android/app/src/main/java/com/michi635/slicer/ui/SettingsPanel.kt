@file:OptIn(ExperimentalMaterial3Api::class)

package com.michi635.slicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.michi635.slicer.BuildConfig
import com.michi635.slicer.MAX_SLOTS
import com.michi635.slicer.MainViewModel
import com.michi635.slicer.SUPPORTED_BRANDS
import kotlin.math.roundToInt

/** German names for OrcaSlicer's choice values; anything missing shows OrcaSlicer's own label. */
private val GERMAN = mapOf(
    // infill patterns
    "concentric" to "Konzentrisch", "zig-zag" to "Zickzack", "grid" to "Gitter", "line" to "Linien",
    "cubic" to "Kubisch", "triangles" to "Dreiecke", "tri-hexagon" to "Tri-Hexagon", "gyroid" to "Gyroid",
    "honeycomb" to "Waben", "adaptivecubic" to "Adaptiv kubisch", "alignedrectilinear" to "Ausgerichtet geradlinig",
    "3dhoneycomb" to "3D-Waben", "hilbertcurve" to "Hilbert-Kurve", "archimedeanchords" to "Archimedische Sehnen",
    "octagramspiral" to "Oktagramm-Spirale", "supportcubic" to "Stützkubisch", "lightning" to "Blitz",
    "crosshatch" to "Kreuzschraffur", "quartercubic" to "Viertel-kubisch", "rectilinear" to "Geradlinig",
    // support types
    "normal(auto)" to "Normal (automatisch)", "tree(auto)" to "Baum (automatisch)",
    "normal(manual)" to "Normal (manuell)", "tree(manual)" to "Baum (manuell)",
    // brim
    "auto_brim" to "Automatisch", "brim_ears" to "Mausohren", "painted" to "Gemalt", "outer_only" to "Nur außen",
    "inner_only" to "Nur innen", "outer_and_inner" to "Außen und innen", "no_brim" to "Kein Brim",
    // extra bridge layer
    "disabled" to "Aus", "external_bridge_only" to "Nur äußere Brücken", "internal_bridge_only" to "Nur innere Brücken",
    "apply_to_all" to "Alle Brücken",
)

@Composable
fun SettingsPanel(vm: MainViewModel, modifier: Modifier = Modifier) {
    val catalog = vm.catalog
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (catalog == null) {
            Text("Profile werden geladen …", Modifier.padding(16.dp))
            return@Column
        }

        SectionTitle("Drucker und Qualität")
        SettingRow("Drucker", false, {}) {
            Choice(vm.printer?.label ?: catalog.printer, vm.printers, { it.label }, { vm.selectPrinter(it.name) })
        }
        SettingRow("Qualität", false, {}) {
            val current = catalog.processes.firstOrNull { it.name == catalog.process }
            Choice(current?.label ?: catalog.process, catalog.processes, { it.label }, { vm.selectProcess(it.name) })
        }

        FilamentSection(vm)
        ColorChangeSection(vm)

        SectionTitle("Füllung")
        PercentSlider(vm, "sparse_infill_density", "Fülldichte")
        EnumRow(vm, "sparse_infill_pattern", "Füllmuster")
        BoolRow(vm, "infill_combination", "Füllung kombinieren")

        SectionTitle("Wände")
        IntRow(vm, "wall_loops", "Wandlinien", 1..10)

        SectionTitle("Stützen")
        BoolRow(vm, "enable_support", "Stützen erzeugen")
        if (vm.valueOf("enable_support") == "1") {
            EnumRow(vm, "support_type", "Art")
            BoolRow(vm, "support_on_build_plate_only", "Nur ab Bauplatte")
            SlotRow(vm, "support_filament", "Stützmaterial")
            SlotRow(vm, "support_interface_filament", "Material Grenzschicht")
        }

        SectionTitle("Haftung")
        EnumRow(vm, "brim_type", "Brim")
        IntRow(vm, "skirt_loops", "Skirt-Linien", 0..5)

        SectionTitle("Spezial")
        BoolRow(vm, "spiral_mode", "Vasenmodus")
        if (vm.valueOf("spiral_mode") == "1") {
            Text(
                "Für Lampenschirme: eine Wand, keine Füllung, keine Decke. Nur für ein einzelnes Objekt.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoolRow(vm, "thick_bridges", "Dicke Brücken")
        EnumRow(vm, "enable_extra_bridge_layer", "Zusätzliche Brückenschicht")

        SectionTitle("Fuzzy Skin (raue Oberfläche)")
        FuzzySkinRows(vm)

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { vm.resetAll() }, enabled = vm.overrides.isNotEmpty()) { Text("Alle Einstellungen zurücksetzen") }
        }
        Text(
            "App ${BuildConfig.VERSION_NAME} · Engine OrcaSlicer ${vm.engineVersion}",
            Modifier.padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun choiceLabel(vm: MainViewModel, key: String, value: String): String {
    GERMAN[value]?.let { return it }
    return vm.catalog?.options?.get(key)?.choices?.firstOrNull { it.first == value }?.second ?: value
}

@Composable
private fun EnumRow(vm: MainViewModel, key: String, label: String) {
    val choices = vm.catalog?.options?.get(key)?.choices ?: return
    if (choices.isEmpty()) return
    val value = vm.valueOf(key)
    SettingRow(label, vm.isOverridden(key), { vm.reset(key) }) {
        Choice(choiceLabel(vm, key, value), choices, { choiceLabel(vm, key, it.first) }, { vm.setValue(key, it.first) })
    }
}

@Composable
private fun BoolRow(vm: MainViewModel, key: String, label: String) {
    SettingRow(label, vm.isOverridden(key), { vm.reset(key) }) {
        Switch(checked = vm.valueOf(key) == "1", onCheckedChange = { vm.setValue(key, if (it) "1" else "0") })
    }
}

@Composable
private fun IntRow(vm: MainViewModel, key: String, label: String, range: IntRange) {
    val value = vm.valueOf(key).toIntOrNull() ?: range.first
    SettingRow(label, vm.isOverridden(key), { vm.reset(key) }) {
        Stepper(value, range) { vm.setValue(key, it.toString()) }
    }
}

@Composable
private fun PercentSlider(vm: MainViewModel, key: String, label: String) {
    val value = vm.valueOf(key).removeSuffix("%").toFloatOrNull() ?: 15f
    var dragging by remember { mutableStateOf(false) }
    var live by remember { mutableFloatStateOf(value) }
    val shown = if (dragging) live else value
    Column {
        SettingRow("$label: ${shown.roundToInt()} %", vm.isOverridden(key), { vm.reset(key) }) {}
        Slider(
            value = shown,
            onValueChange = { dragging = true; live = it },
            onValueChangeFinished = { dragging = false; vm.setValue(key, "${(live / 5).roundToInt() * 5}%") },
            valueRange = 0f..100f,
            steps = 19,
        )
    }
}

/** OrcaSlicer's fuzzy skin areas; "disabled_fuzzy" is its off, "none" (only painted areas) makes no sense here. */
private val FUZZY = listOf(
    "disabled_fuzzy" to "Aus",
    "external" to "Nur außen (Außenkontur)",
    "hole" to "Nur innen (Löcher und Öffnungen)",
    "all" to "Außen und innen",
    "allwalls" to "Alle Wände (auch innere)",
)

@Composable
private fun FuzzySkinRows(vm: MainViewModel) {
    if (vm.catalog?.options?.containsKey("fuzzy_skin") != true) return
    val value = vm.valueOf("fuzzy_skin").let { if (it == "none" || it.isEmpty()) "disabled_fuzzy" else it }
    fun label(v: String) = FUZZY.firstOrNull { it.first == v }?.second ?: v
    SettingRow("Bereich", vm.isOverridden("fuzzy_skin"), { vm.reset("fuzzy_skin") }) {
        Choice(label(value), FUZZY, { it.second }, { vm.setValue("fuzzy_skin", it.first) })
    }
    if (value != "disabled_fuzzy") {
        FloatRow(vm, "fuzzy_skin_thickness", "Stärke (mm)", 0.05, 1.0, 0.05)
        FloatRow(vm, "fuzzy_skin_point_distance", "Punktabstand (mm)", 0.2, 3.0, 0.1)
        Text(
            "Stärke: wie weit die Wand nach innen und außen ausschlägt. Punktabstand: kleiner = feinere Körnung. " +
                "Die erste Schicht bleibt glatt, damit das Teil haftet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

@Composable
private fun FloatRow(vm: MainViewModel, key: String, label: String, min: Double, max: Double, step: Double) {
    val value = vm.valueOf(key).toDoubleOrNull() ?: min
    fun set(v: Double) = vm.setValue(key, fmt((v / step).roundToInt() * step))
    SettingRow(label, vm.isOverridden(key), { vm.reset(key) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { set(value - step) }, enabled = value > min + 1e-6) { Text("−") }
            Text(fmt(value).replace('.', ','), Modifier.width(44.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            TextButton(onClick = { set(value + step) }, enabled = value < max - 1e-6) { Text("+") }
        }
    }
}

/** Colour changes by height: from a height on, the whole plate continues with another ACE slot. */
@Composable
private fun ColorChangeSection(vm: MainViewModel) {
    var adding by remember { mutableStateOf(false) }
    SectionTitle("Farbwechsel nach Höhe")
    if (vm.slots.size < 2) {
        Text(
            "Ab einer Höhe mit einer anderen Farbe weiterdrucken. Dafür oben mindestens zwei Slots anlegen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    vm.colorChanges.forEachIndexed { i, change ->
        val slot = vm.slots.getOrNull(change.slot - 1)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorDot(parseColor(slot?.color ?: "#888888"))
            Spacer(Modifier.width(12.dp))
            Text(
                "ab ${fmt(change.z).replace('.', ',')} mm: Slot ${change.slot}" + (slot?.let { s -> vm.filamentOf(s)?.label }?.let { " · $it" } ?: ""),
                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { vm.removeColorChange(i) }) { Icon(Icons.Filled.Close, contentDescription = "Farbwechsel entfernen") }
        }
    }
    TextButton(onClick = { adding = true }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text("Farbwechsel hinzufügen")
    }
    Text(
        "Gilt für die ganze Platte: Darunter wird mit dem Slot der Objekte gedruckt, ab der Höhe mit dem gewählten. " +
            "Alle Objekte müssen denselben Slot haben. Beim Wechsel entsteht ein Reinigungsturm.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (adding) {
        val height = vm.plateHeight
        var text by remember { mutableStateOf("") }
        var slot by remember { mutableIntStateOf(2) }
        val z = text.replace(',', '.').toDoubleOrNull()
        val valid = z != null && z > 0.0 && (height <= 0.0 || z < height)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Farbwechsel") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Ab Höhe (mm)") },
                        singleLine = true,
                        isError = text.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = {
                            Text(if (height > 0.0) "Höchstes Objekt: ${fmt(height).replace('.', ',')} mm" else "Noch kein Objekt auf der Platte")
                        },
                    )
                    Spacer(Modifier.padding(4.dp))
                    fun name(i: Int) = "Slot $i" + (vm.slots.getOrNull(i - 1)?.let { s -> vm.filamentOf(s)?.label }?.let { " · $it" } ?: "")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(parseColor(vm.slots.getOrNull(slot - 1)?.color ?: "#888888"))
                        Spacer(Modifier.width(12.dp))
                        Choice(name(slot), (1..vm.slots.size).toList(), { name(it) }, { slot = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.addColorChange(z!!, slot); adding = false }, enabled = valid) { Text("Hinzufügen") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Abbrechen") } },
        )
    }
}

/** A filament slot setting: 0 = like the object, 1..n = ACE slot. */
@Composable
private fun SlotRow(vm: MainViewModel, key: String, label: String) {
    val value = vm.valueOf(key).toIntOrNull() ?: 0
    val options = listOf(0) + (1..vm.slots.size).toList()
    fun name(i: Int) = if (i == 0) "Wie Objekt" else "Slot $i · ${vm.slots.getOrNull(i - 1)?.let { s -> vm.filamentOf(s)?.label } ?: ""}"
    SettingRow(label, vm.isOverridden(key), { vm.reset(key) }) {
        Choice(name(value), options, { name(it) }, { vm.setValue(key, it.toString()) })
    }
}

@Composable
private fun FilamentSection(vm: MainViewModel) {
    val catalog = vm.catalog ?: return
    var pickFilament by remember { mutableStateOf<Int?>(null) }
    var pickColor by remember { mutableStateOf<Int?>(null) }
    SectionTitle(if (vm.slots.size > 1) "Filamente (ACE)" else "Filament")
    vm.slots.forEachIndexed { i, slot ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", Modifier.width(20.dp), style = MaterialTheme.typography.labelLarge)
            ColorDot(parseColor(slot.color)) { pickColor = i }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { pickFilament = i }, modifier = Modifier.weight(1f)) {
                val f = vm.filamentOf(slot)
                Text(
                    f?.let { "${com.michi635.slicer.model.Filament.brandOf(it)} ${it.label}" } ?: slot.preset,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (vm.slots.size > 1) {
                IconButton(onClick = { vm.removeSlot(i) }) { Icon(Icons.Filled.Close, contentDescription = "Slot entfernen") }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
    }
    if (vm.slots.size < MAX_SLOTS) {
        TextButton(onClick = { vm.addSlot() }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Slot hinzufügen (Mehrfarbe mit ACE)")
        }
    }

    pickFilament?.let { index ->
        FilamentPickerDialog(
            filaments = vm.offeredFilaments(catalog),
            brands = SUPPORTED_BRANDS,
            current = vm.slots[index].preset,
            onDismiss = { pickFilament = null },
            onPick = { f ->
                val slot = vm.slots[index]
                val color = if (vm.slots.size == 1 && f.color.isNotBlank()) f.color else slot.color
                vm.setSlot(index, slot.copy(preset = f.name, color = color))
                pickFilament = null
            },
        )
    }
    pickColor?.let { index ->
        ColorPickerDialog(vm.slots[index].color, onDismiss = { pickColor = null }) { hex ->
            vm.setSlot(index, vm.slots[index].copy(color = hex))
            pickColor = null
        }
    }
}
