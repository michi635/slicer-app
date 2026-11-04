package com.michi635.slicer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.michi635.slicer.engine.SlicerCore
import com.michi635.slicer.model.Catalog
import com.michi635.slicer.model.Filament
import com.michi635.slicer.model.FilamentSlot
import com.michi635.slicer.model.Mesh
import com.michi635.slicer.model.PlateObject
import com.michi635.slicer.model.PrimeTower
import com.michi635.slicer.model.Printer
import com.michi635.slicer.model.Rotation
import com.michi635.slicer.model.SliceResult
import com.michi635.slicer.model.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Printer models the app offers. */
val SUPPORTED_MODELS = listOf("Anycubic Kobra S1", "Anycubic Kobra 3 Max")

/** Filament brands the app offers. */
val SUPPORTED_BRANDS = listOf("Anycubic", "Bambu Lab", "Extrudr")

const val MAX_SLOTS = 8

data class Busy(val title: String, val percent: Int = -1, val text: String = "", val cancellable: Boolean = false)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val core = SlicerCore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val modelsDir = File(app.filesDir, "models").apply { mkdirs() }
    private val gcodeDir = File(app.filesDir, "gcode").apply { mkdirs() }

    var printers by mutableStateOf<List<Printer>>(emptyList()); private set
    var catalog by mutableStateOf<Catalog?>(null); private set
    var slots by mutableStateOf(loadSlots()); private set
    var overrides by mutableStateOf(loadOverrides()); private set
    var objects by mutableStateOf<List<PlateObject>>(emptyList()); private set
    var selectedId by mutableStateOf<Long?>(null)
    var primeTower by mutableStateOf<PrimeTower?>(null); private set
    var busy by mutableStateOf<Busy?>(null); private set
    var message by mutableStateOf<String?>(null)
    var result by mutableStateOf<SliceResult?>(null)
    var engineVersion by mutableStateOf(""); private set

    private var nextId = 1L

    val printer: Printer? get() = catalog?.let { c -> printers.firstOrNull { it.name == c.printer } }
    val selected: PlateObject? get() = objects.firstOrNull { it.id == selectedId }

    init {
        // Models from the last session are gone (the job files are temporary); start with an empty plate.
        modelsDir.listFiles()?.forEach { it.delete() }
        viewModelScope.launch {
            busy = Busy("Profile laden …")
            val savedPrinter = prefs.getString("printer", null)
            val savedProcess = prefs.getString("process", null)
            if (!loadCatalog(savedPrinter, savedProcess) && savedProcess != null) loadCatalog(savedPrinter, null)
            busy = null
            core.version().optString("version").let { engineVersion = it }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Profiles

    /** Asks the engine for the printer's processes, filaments and preset values. */
    private suspend fun loadCatalog(printerName: String?, processName: String?): Boolean {
        var name = printerName
        if (name == null || (printers.isNotEmpty() && printers.none { it.name == name })) {
            val all = core.describe()
            if (!all.optBoolean("ok")) {
                message = all.optString("error", "Engine nicht verfügbar.")
                return false
            }
            printers = supportedPrinters(all)
            name = printers.firstOrNull { it.name == printerName }?.name
                ?: printers.firstOrNull { it.model == SUPPORTED_MODELS.first() && it.nozzle == 0.4 }?.name
                ?: printers.firstOrNull()?.name
                ?: run { message = "Keine Profile für Kobra S1 / Kobra 3 Max gefunden."; return false }
        }
        val j = core.describe(name, processName)
        if (!j.optBoolean("ok")) {
            message = j.optString("error")
            return false
        }
        printers = supportedPrinters(j)
        val c = Catalog.from(j)
        catalog = c
        prefs.edit().putString("printer", c.printer).putString("process", c.process).apply()
        fixSlots(c, j)
        return true
    }

    private fun supportedPrinters(j: JSONObject): List<Printer> =
        j.getJSONArray("printers").objects().map { Printer.from(it) }
            .filter { it.model in SUPPORTED_MODELS }
            .sortedWith(compareBy({ SUPPORTED_MODELS.indexOf(it.model) }, { it.nozzle }))

    /** Keeps the filament slots valid for the selected printer. */
    private fun fixSlots(c: Catalog, describe: JSONObject) {
        val names = c.filaments.map { it.name }.toSet()
        val defaults = describe.getJSONArray("printers").objects().firstOrNull { it.getString("name") == c.printer }
            ?.optJSONArray("default_filaments")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val fallback = defaults.firstOrNull { it in names } ?: offeredFilaments(c).firstOrNull()?.name ?: return
        val fixed = (if (slots.isEmpty()) listOf(FilamentSlot(fallback, "#FFFFFF")) else slots)
            .map { s -> if (s.preset in names) s else s.copy(preset = equivalent(s.preset, c) ?: fallback) }
        updateSlots(fixed)
    }

    /** The same filament for another printer ("Anycubic PLA @Kobra S1" -> "Anycubic PLA @Kobra 3 Max"). */
    private fun equivalent(preset: String, c: Catalog): String? {
        val base = preset.substringBefore(" @")
        return c.filaments.firstOrNull { it.name.substringBefore(" @") == base }?.name
    }

    fun offeredFilaments(c: Catalog = catalog!!): List<Filament> =
        c.filaments.filter { Filament.brandOf(it) in SUPPORTED_BRANDS }
            .sortedWith(compareBy({ SUPPORTED_BRANDS.indexOf(Filament.brandOf(it)) }, { it.label }))

    fun selectPrinter(name: String) {
        if (name == catalog?.printer) return
        viewModelScope.launch {
            busy = Busy("Drucker wechseln …")
            loadCatalog(name, null)
            primeTower = null
            busy = null
        }
    }

    fun selectProcess(name: String) {
        val c = catalog ?: return
        if (name == c.process) return
        viewModelScope.launch {
            busy = Busy("Profil laden …")
            loadCatalog(c.printer, name)
            busy = null
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Settings

    fun valueOf(key: String): String = overrides[key] ?: catalog?.values?.get(key) ?: ""
    fun isOverridden(key: String) = overrides.containsKey(key)

    fun setValue(key: String, value: String) {
        val preset = catalog?.values?.get(key)
        overrides = if (value == preset) overrides - key else overrides + (key to value)
        saveOverrides()
    }

    fun reset(key: String) {
        overrides = overrides - key
        saveOverrides()
    }

    fun resetAll() {
        overrides = emptyMap()
        saveOverrides()
    }

    private fun loadOverrides(): Map<String, String> {
        val j = runCatching { JSONObject(prefs.getString("overrides", "{}")!!) }.getOrDefault(JSONObject())
        return j.keys().asSequence().associateWith { j.getString(it) }
    }

    private fun saveOverrides() = prefs.edit().putString("overrides", JSONObject(overrides).toString()).apply()

    // -----------------------------------------------------------------------------------------------------------------
    // Filament slots (ACE)

    fun setSlot(index: Int, slot: FilamentSlot) {
        updateSlots(slots.toMutableList().also { it[index] = slot })
    }

    fun addSlot() {
        if (slots.size >= MAX_SLOTS) return
        val colors = listOf("#FFFFFF", "#202020", "#D32F2F", "#1976D2", "#FBC02D", "#388E3C", "#F57C00", "#7B1FA2")
        updateSlots(slots + slots.last().copy(color = colors[slots.size % colors.size]))
    }

    fun removeSlot(index: Int) {
        if (slots.size <= 1) return
        updateSlots(slots.filterIndexed { i, _ -> i != index })
        // Objects on a removed slot move to slot 1; later slots shift down.
        objects = objects.map { o ->
            when {
                o.slot == index + 1 -> o.copy(slot = 1)
                o.slot > index + 1 -> o.copy(slot = o.slot - 1)
                else -> o
            }
        }
    }

    private fun updateSlots(list: List<FilamentSlot>) {
        slots = list
        val a = JSONArray(list.map { JSONObject().put("preset", it.preset).put("color", it.color) })
        prefs.edit().putString("slots", a.toString()).apply()
    }

    private fun loadSlots(): List<FilamentSlot> {
        val a = runCatching { JSONArray(prefs.getString("slots", "[]")!!) }.getOrDefault(JSONArray())
        return a.objects().map { FilamentSlot(it.getString("preset"), it.optString("color", "#FFFFFF")) }
    }

    fun filamentOf(slot: FilamentSlot): Filament? = catalog?.filaments?.firstOrNull { it.name == slot.preset }

    // -----------------------------------------------------------------------------------------------------------------
    // Plate

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = Busy("Modell laden …")
            val added = mutableListOf<PlateObject>()
            val p = printer
            for (uri in uris) {
                val loaded = withContext(Dispatchers.IO) { runCatching { importStl(uri) } }
                loaded.onSuccess { (name, file, mesh) ->
                    val o = PlateObject(nextId++, name, file, mesh, p?.centerX ?: 125.0, p?.centerY ?: 125.0)
                    added += o
                }.onFailure { message = "Datei nicht lesbar: ${it.message}" }
            }
            busy = null
            if (added.isEmpty()) return@launch
            val placeOthers = objects.isNotEmpty() || added.size > 1
            objects = objects + added
            selectedId = added.last().id
            if (placeOthers) arrange()
        }
    }

    private fun importStl(uri: Uri): Triple<String, File, Mesh> {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Modell"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val file = File(modelsDir, "${System.nanoTime()}.stl")
        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalArgumentException("Kein Zugriff auf $name")
        val mesh = runCatching { Mesh.readStl(file) }.getOrElse {
            file.delete()
            throw IllegalArgumentException("$name ist keine gültige STL-Datei.")
        }
        return Triple(name.substringBeforeLast('.'), file, mesh)
    }

    private fun update(id: Long, change: (PlateObject) -> PlateObject) {
        objects = objects.map { if (it.id == id) change(it) else it }
    }

    fun moveSelected(dx: Double, dy: Double) {
        val id = selectedId ?: return
        update(id) { it.copy(x = it.x + dx, y = it.y + dy) }
    }

    fun setPosition(id: Long, x: Double, y: Double) = update(id) { it.copy(x = x, y = y) }
    fun rotateZ(id: Long, degrees: Double) = update(id) { it.copy(rotation = it.rotation.copy(z = norm(it.rotation.z + degrees))) }
    fun tip(id: Long, axis: Char) = update(id) {
        when (axis) {
            'x' -> it.copy(rotation = it.rotation.copy(x = norm(it.rotation.x + 90)))
            else -> it.copy(rotation = it.rotation.copy(y = norm(it.rotation.y + 90)))
        }
    }
    fun resetRotation(id: Long) = update(id) { it.copy(rotation = Rotation()) }
    fun setScale(id: Long, scale: Double) = update(id) { it.copy(scale = scale.coerceIn(0.05, 20.0)) }
    fun setObjectSlot(id: Long, slot: Int) = update(id) { it.copy(slot = slot) }

    fun duplicate(id: Long) {
        val o = objects.firstOrNull { it.id == id } ?: return
        val copy = o.copy(id = nextId++, x = o.x + 10, y = o.y - 10)
        objects = objects + copy
        selectedId = copy.id
        arrange()
    }

    fun remove(id: Long) {
        objects.firstOrNull { it.id == id }?.let { o -> if (objects.count { it.file == o.file } == 1) o.file.delete() }
        objects = objects.filter { it.id != id }
        if (selectedId == id) selectedId = null
    }

    fun clearPlate() {
        objects.forEach { it.file.delete() }
        objects = emptyList()
        selectedId = null
        primeTower = null
    }

    private fun norm(d: Double): Double = ((d % 360) + 360) % 360

    // -----------------------------------------------------------------------------------------------------------------
    // Engine jobs

    private fun job(): JSONObject? {
        val c = catalog ?: return null
        return JSONObject()
            .put("printer", c.printer)
            .put("process", c.process)
            .put("filaments", JSONArray(slots.map { JSONObject().put("preset", it.preset).put("color", it.color) }))
            .put("overrides", JSONObject(overrides as Map<*, *>))
            .put("objects", JSONArray(objects.map { o ->
                JSONObject()
                    .put("path", o.file.path)
                    .put("name", o.name)
                    .put("x", o.x)
                    .put("y", o.y)
                    .put("rotation", JSONArray(listOf(o.rotation.x, o.rotation.y, o.rotation.z)))
                    .put("scale", o.scale)
                    .put("filament", o.slot.coerceIn(1, slots.size))
            }))
    }

    fun arrange() {
        val j = job() ?: return
        if (objects.isEmpty()) return
        viewModelScope.launch {
            busy = Busy("Anordnen …")
            val r = core.arrange(j)
            busy = null
            if (!r.optBoolean("ok")) {
                message = r.optString("error")
                return@launch
            }
            val placed = r.getJSONArray("objects").objects()
            if (placed.size == objects.size) {
                objects = objects.mapIndexed { i, o -> o.copy(x = placed[i].getDouble("x"), y = placed[i].getDouble("y")) }
            }
            primeTower = tower(r.optJSONObject("prime_tower"))
        }
    }

    private fun tower(t: JSONObject?) = t?.let { PrimeTower(it.getDouble("x"), it.getDouble("y"), it.getDouble("width"), it.getDouble("depth")) }

    fun slice() {
        val j = job() ?: return
        if (objects.isEmpty()) {
            message = "Zuerst ein Modell hinzufügen."
            return
        }
        val base = objects.first().name.replace(Regex("[^A-Za-z0-9äöüÄÖÜß_.-]+"), "_").take(40)
        val out = File(gcodeDir, "$base.gcode")
        gcodeDir.listFiles()?.forEach { it.delete() }
        viewModelScope.launch {
            busy = Busy("Slicen …", 0, "", cancellable = true)
            val r = core.slice(j, out) { percent, text -> busy = Busy("Slicen …", percent, text, cancellable = true) }
            busy = null
            if (!r.optBoolean("ok")) {
                message = r.optString("error")
                return@launch
            }
            primeTower = tower(r.optJSONObject("prime_tower"))
            val s = r.getJSONObject("summary")
            val filaments = s.optJSONArray("filaments")?.objects()?.map {
                Triple(it.getInt("id"), it.optDouble("length") / 1000.0, it.optDouble("weight"))
            } ?: emptyList()
            val warnings = r.optJSONArray("warnings")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val gcode = File(r.optString("gcode", out.path))
            result = SliceResult(gcode, gcode.name, s.optDouble("time"), s.optDouble("weight"), s.optInt("layer_count"),
                s.optInt("filament_changes"), filaments, warnings)
        }
    }

    fun cancel() = core.cancel()

    fun saveTo(uri: Uri) {
        val r = result ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        r.gcode.inputStream().use { it.copyTo(out) }
                    } ?: error("no stream")
                }.isSuccess
            }
            message = if (ok) "G-code gespeichert." else "Speichern fehlgeschlagen."
        }
    }

    val logFile get() = core.logFile
}
