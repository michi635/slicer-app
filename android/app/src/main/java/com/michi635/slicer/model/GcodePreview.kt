package com.michi635.slicer.model

import java.io.File

/** Extrusion moves of one layer: x0,y0,x1,y1 per segment, and a feature or filament index per segment. */
class PreviewLayer(val z: Float, val segments: FloatArray, val features: ByteArray, val tools: ByteArray) {
    val count get() = features.size
}

/** Feature types as OrcaSlicer writes them in ";TYPE:..." comments. */
enum class Feature(val label: String, val argb: Long) {
    OuterWall("Außenwand", 0xFFFF8C1A),
    InnerWall("Innenwand", 0xFFFFD54F),
    OverhangWall("Überhang", 0xFF3F51FF),
    Infill("Füllung", 0xFFC62828),
    SolidInfill("Massive Füllung", 0xFF9C27B0),
    TopSurface("Oberfläche", 0xFFFF5252),
    BottomSurface("Unterseite", 0xFF7E57C2),
    Bridge("Brücke", 0xFF4FC3F7),
    Support("Stütze", 0xFF43A047),
    SupportInterface("Grenzschicht", 0xFF1B5E20),
    Skirt("Brim / Skirt", 0xFF00ACC1),
    PrimeTower("Reinigungsturm", 0xFF8D6E63),
    Other("Sonstiges", 0xFF9E9E9E);

    companion object {
        fun of(type: String): Feature {
            val t = type.lowercase()
            return when {
                t.contains("outer wall") || t.contains("external perimeter") -> OuterWall
                t.contains("overhang") -> OverhangWall
                t.contains("inner wall") || t.contains("perimeter") -> InnerWall
                t.contains("sparse infill") || t == "internal infill" -> Infill
                t.contains("internal solid") || t.contains("solid infill") -> SolidInfill
                t.contains("top surface") || t.contains("ironing") -> TopSurface
                t.contains("bottom surface") -> BottomSurface
                t.contains("bridge") -> Bridge
                t.contains("support interface") || t.contains("support transition") -> SupportInterface
                t.contains("support") -> Support
                t.contains("skirt") || t.contains("brim") -> Skirt
                t.contains("prime tower") || t.contains("wipe tower") -> PrimeTower
                else -> Other
            }
        }
    }
}

object GcodePreview {
    /** Reads the extrusion moves of a G-code file into layers. */
    fun read(file: File): List<PreviewLayer> {
        val layers = mutableListOf<PreviewLayer>()
        var segs = FloatArrayBuilder()
        var feats = ByteArrayBuilder()
        var tools = ByteArrayBuilder()
        var x = 0f; var y = 0f; var z = 0f
        var layerZ = 0f
        var relativeE = false
        var lastE = 0f
        var feature = Feature.Other
        var tool = 0

        fun closeLayer() {
            if (feats.size > 0) layers += PreviewLayer(layerZ, segs.toArray(), feats.toArray(), tools.toArray())
            segs = FloatArrayBuilder(); feats = ByteArrayBuilder(); tools = ByteArrayBuilder()
        }

        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                if (raw.isEmpty()) continue
                val c0 = raw[0]
                if (c0 == ';') {
                    // OrcaSlicer writes ";TYPE:" / ";LAYER_CHANGE" / ";Z:", for Bambu printers "; FEATURE: " / "; CHANGE_LAYER"
                    when {
                        raw.startsWith(";TYPE:") -> feature = Feature.of(raw.substring(6))
                        raw.startsWith("; FEATURE: ") -> feature = Feature.of(raw.substring(11))
                        raw.startsWith(";LAYER_CHANGE") || raw.startsWith("; CHANGE_LAYER") -> { closeLayer(); layerZ = 0f }
                        raw.startsWith(";Z:") -> raw.substring(3).trim().toFloatOrNull()?.let { layerZ = it }
                        raw.startsWith("; Z_HEIGHT:") -> raw.substring(11).trim().toFloatOrNull()?.let { layerZ = it }
                    }
                    continue
                }
                val code = raw.substringBefore(';').trim()
                if (code.isEmpty()) continue
                when {
                    code == "M83" -> relativeE = true
                    code == "M82" -> relativeE = false
                    code.startsWith("G92") -> {
                        param(code, 'E')?.let { lastE = it }
                    }
                    code[0] == 'T' && code.length <= 3 -> code.substring(1).toIntOrNull()?.let { tool = it }
                    code.startsWith("G1 ") || code.startsWith("G0 ") || code == "G1" || code == "G0" -> {
                        val nx = param(code, 'X') ?: x
                        val ny = param(code, 'Y') ?: y
                        param(code, 'Z')?.let { z = it }
                        val e = param(code, 'E')
                        val extrudes = when {
                            e == null -> false
                            relativeE -> e > 0f
                            else -> (e - lastE > 0f).also { lastE = e }
                        }
                        if (extrudes && (nx != x || ny != y)) {
                            segs.add(x, y, nx, ny)
                            feats.add(feature.ordinal.toByte())
                            tools.add(tool.toByte())
                        }
                        x = nx; y = ny
                        if (layerZ == 0f) layerZ = z
                    }
                }
            }
        }
        closeLayer()
        return layers
    }

    private fun param(code: String, key: Char): Float? {
        var i = code.indexOf(' ')
        while (i >= 0 && i < code.length) {
            while (i < code.length && code[i] == ' ') i++
            if (i >= code.length) break
            val end = code.indexOf(' ', i).let { if (it < 0) code.length else it }
            if (code[i] == key) return code.substring(i + 1, end).toFloatOrNull()
            i = end
        }
        return null
    }
}

private class FloatArrayBuilder {
    private var a = FloatArray(1024)
    var size = 0; private set
    fun add(v0: Float, v1: Float, v2: Float, v3: Float) {
        if (size + 4 > a.size) a = a.copyOf(a.size * 2)
        a[size] = v0; a[size + 1] = v1; a[size + 2] = v2; a[size + 3] = v3
        size += 4
    }
    fun toArray(): FloatArray = a.copyOf(size)
}

private class ByteArrayBuilder {
    private var a = ByteArray(256)
    var size = 0; private set
    fun add(v: Byte) {
        if (size + 1 > a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    fun toArray(): ByteArray = a.copyOf(size)
}
