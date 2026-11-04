package com.michi635.slicer.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A printer profile from the engine. */
data class Printer(
    val name: String,
    val model: String,
    val bedMinX: Double, val bedMinY: Double, val bedMaxX: Double, val bedMaxY: Double,
    val height: Double,
    val nozzle: Double,
) {
    val bedWidth get() = bedMaxX - bedMinX
    val bedDepth get() = bedMaxY - bedMinY
    val centerX get() = (bedMinX + bedMaxX) / 2
    val centerY get() = (bedMinY + bedMaxY) / 2

    /** "Kobra S1 · 0,4 mm" */
    val label: String
        get() = model.removePrefix("Anycubic ") + " · " + "%.1f".format(nozzle).replace('.', ',') + " mm"

    companion object {
        fun from(j: JSONObject): Printer {
            val bed = j.getJSONArray("bed")
            return Printer(
                j.getString("name"), j.optString("model"),
                bed.getDouble(0), bed.getDouble(1), bed.getDouble(2), bed.getDouble(3),
                j.optDouble("height", 250.0), j.optDouble("nozzle", 0.4),
            )
        }
    }
}

data class Process(val name: String, val layerHeight: Double) {
    /** "0,20 mm Standard" */
    val label: String get() = name.substringBefore(" @").replace("mm", " mm").replace('.', ',')
}

data class Filament(val name: String, val vendor: String, val type: String, val color: String, val temperature: Int) {
    /** "PLA+" from "Anycubic PLA+ @Anycubic Kobra S1 0.4 nozzle" */
    val label: String
        get() {
            val base = name.substringBefore(" @")
            val brand = brandOf(this)
            return base.removePrefix("$brand ").removePrefix("Bambu ").ifEmpty { base }
        }

    companion object {
        fun brandOf(f: Filament): String = when {
            f.vendor.startsWith("Bambu") || f.name.startsWith("Bambu") -> "Bambu Lab"
            f.vendor.equals("Extrudr", true) || f.name.startsWith("Extrudr") -> "Extrudr"
            f.vendor.equals("Anycubic", true) || f.name.startsWith("Anycubic") -> "Anycubic"
            else -> f.vendor.ifEmpty { "Generic" }
        }
    }
}

/** What the engine knows about the selected printer and process. */
data class Catalog(
    val printer: String,
    val process: String,
    val processes: List<Process>,
    val filaments: List<Filament>,
    /** Preset values of the settings the app shows, serialized as OrcaSlicer does ("15%", "1", "grid"). */
    val values: Map<String, String>,
    val options: Map<String, OptionInfo>,
) {
    companion object {
        fun from(j: JSONObject): Catalog {
            val sel = j.getJSONObject("selected")
            val processes = j.getJSONArray("processes").objects().map { Process(it.getString("name"), it.optDouble("layer_height")) }
                .sortedBy { it.layerHeight }
            val filaments = j.getJSONArray("filaments").objects().map {
                Filament(it.getString("name"), it.optString("vendor"), it.optString("type"), it.optString("color"), it.optInt("temperature"))
            }
            val values = j.getJSONObject("values").let { v -> v.keys().asSequence().associateWith { v.getString(it) } }
            val options = j.getJSONObject("options").let { o -> o.keys().asSequence().associateWith { OptionInfo.from(o.getJSONObject(it)) } }
            return Catalog(sel.getString("printer"), sel.getString("process"), processes, filaments, values, options)
        }
    }
}

data class OptionInfo(val label: String, val type: String, val choices: List<Pair<String, String>>) {
    companion object {
        fun from(j: JSONObject) = OptionInfo(
            j.optString("label"), j.optString("type"),
            j.optJSONArray("choices")?.objects()?.map { it.getString("value") to it.getString("label") } ?: emptyList(),
        )
    }
}

/** One slot of the ACE (or the single spool without ACE). */
data class FilamentSlot(val preset: String, val color: String)

/** An object on the plate. x/y is the centre of its bounding box on the bed, in mm. */
data class PlateObject(
    val id: Long,
    val name: String,
    val file: File,
    val mesh: Mesh,
    val x: Double,
    val y: Double,
    val rotation: Rotation = Rotation(),
    val scale: Double = 1.0,
    val slot: Int = 1,
) {
    /** Cached footprint, recomputed only when rotation or scale change. */
    val footprint: FloatArray by lazy { mesh.footprint(rotation, scale) }
    val height: Double by lazy { mesh.height(rotation, scale) }

    fun contains(px: Double, py: Double): Boolean {
        val f = footprint
        val n = f.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = f[2 * i] + x; val yi = f[2 * i + 1] + y
            val xj = f[2 * j] + x; val yj = f[2 * j + 1] + y
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun bounds(): DoubleArray {
        val f = footprint
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until f.size / 2) {
            minX = minOf(minX, f[2 * i] + x); maxX = maxOf(maxX, f[2 * i] + x)
            minY = minOf(minY, f[2 * i + 1] + y); maxY = maxOf(maxY, f[2 * i + 1] + y)
        }
        return doubleArrayOf(minX, minY, maxX, maxY)
    }
}

data class PrimeTower(val x: Double, val y: Double, val width: Double, val depth: Double)

data class SliceResult(
    val gcode: File,
    val name: String,
    val timeSeconds: Double,
    val weightGrams: Double,
    val layers: Int,
    val filamentChanges: Int,
    /** Per used filament slot: slot number (1-based), length in m, weight in g. */
    val filaments: List<Triple<Int, Double, Double>>,
    val warnings: List<String>,
)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
