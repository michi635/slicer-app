package com.michi635.slicer.model

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * What the app needs to know about a model to show and place it: its vertices (de-duplicated on a fine grid, relative to
 * the centre of the bounding box, as the engine centres objects before rotating them) and its size.
 */
class Mesh(
    /** x, y, z of every distinct vertex, relative to the bounding-box centre. */
    val points: FloatArray,
    val triangles: Int,
    val sizeX: Float,
    val sizeY: Float,
    val sizeZ: Float,
) {
    val pointCount get() = points.size / 3

    /** Footprint on the plate (convex hull, x/y pairs relative to the object's position) for a rotation and scale. */
    fun footprint(rotation: Rotation, scale: Double): FloatArray {
        val m = rotation.matrix()
        val n = pointCount
        val xy = DoubleArray(n * 2)
        for (i in 0 until n) {
            val x = points[3 * i].toDouble()
            val y = points[3 * i + 1].toDouble()
            val z = points[3 * i + 2].toDouble()
            xy[2 * i] = (m[0] * x + m[1] * y + m[2] * z) * scale
            xy[2 * i + 1] = (m[3] * x + m[4] * y + m[5] * z) * scale
        }
        return convexHull(xy)
    }

    /** Height of the object for a rotation and scale. */
    fun height(rotation: Rotation, scale: Double): Double {
        val m = rotation.matrix()
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0 until pointCount) {
            val z = m[6] * points[3 * i] + m[7] * points[3 * i + 1] + m[8] * points[3 * i + 2]
            if (z < lo) lo = z
            if (z > hi) hi = z
        }
        return if (pointCount == 0) 0.0 else (hi - lo) * scale
    }

    companion object {
        /** Reads a binary or ASCII STL file. */
        fun readStl(file: File): Mesh {
            val length = file.length()
            val header = ByteArray(84)
            FileInputStream(file).use { it.read(header) }
            val count = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val collector = PointCollector()
            if (length == 84 + 50 * count && count > 0) {
                readBinary(file, count, collector)
            } else {
                readAscii(file, collector)
            }
            if (collector.triangles == 0) throw IllegalArgumentException("Die Datei enthält keine Dreiecke.")
            return collector.toMesh()
        }

        private fun readBinary(file: File, count: Long, collector: PointCollector) {
            BufferedInputStream(FileInputStream(file), 1 shl 16).use { input ->
                input.skip(84)
                val buf = ByteArray(50)
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                for (t in 0 until count) {
                    var read = 0
                    while (read < 50) {
                        val r = input.read(buf, read, 50 - read)
                        if (r < 0) return
                        read += r
                    }
                    for (v in 0 until 3) {
                        val o = 12 + v * 12
                        collector.add(bb.getFloat(o), bb.getFloat(o + 4), bb.getFloat(o + 8))
                    }
                    collector.triangles++
                }
            }
        }

        private fun readAscii(file: File, collector: PointCollector) {
            var vertices = 0
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (!line.startsWith("vertex")) continue
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 4) continue
                    collector.add(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    if (++vertices % 3 == 0) collector.triangles++
                }
            }
        }
    }
}

/** Collects distinct vertices on a 0.05 mm grid with a primitive open-addressing set. */
private class PointCollector {
    var triangles = 0
    private var keys = LongArray(1 shl 16) { EMPTY }
    private var size = 0
    private var coords = FloatArray(3 * 4096)
    private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE; private var minZ = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE; private var maxY = -Float.MAX_VALUE; private var maxZ = -Float.MAX_VALUE

    fun add(x: Float, y: Float, z: Float) {
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        val key = (q(x) shl 42) or (q(y) shl 21) or q(z)
        if (insert(key)) {
            if (3 * size + 3 > coords.size) coords = coords.copyOf(coords.size * 2)
            coords[3 * size] = x; coords[3 * size + 1] = y; coords[3 * size + 2] = z
            size++
        }
    }

    private fun q(v: Float): Long = ((v * 20.0).roundToLong() + (1L shl 20)) and 0x1FFFFF

    private fun insert(key: Long): Boolean {
        if (size * 2 >= keys.size) grow()
        var i = mix(key) and (keys.size - 1)
        while (true) {
            val k = keys[i]
            if (k == EMPTY) { keys[i] = key; return true }
            if (k == key) return false
            i = (i + 1) and (keys.size - 1)
        }
    }

    private fun grow() {
        val old = keys
        keys = LongArray(old.size * 2) { EMPTY }
        for (k in old) {
            if (k == EMPTY) continue
            var i = mix(k) and (keys.size - 1)
            while (keys[i] != EMPTY) i = (i + 1) and (keys.size - 1)
            keys[i] = k
        }
    }

    private fun mix(k: Long): Int {
        var h = k * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return h.toInt()
    }

    fun toMesh(): Mesh {
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2; val cz = (minZ + maxZ) / 2
        val out = FloatArray(3 * size)
        for (i in 0 until size) {
            out[3 * i] = coords[3 * i] - cx
            out[3 * i + 1] = coords[3 * i + 1] - cy
            out[3 * i + 2] = coords[3 * i + 2] - cz
        }
        return Mesh(out, triangles, maxX - minX, maxY - minY, maxZ - minZ)
    }

    companion object { const val EMPTY = Long.MIN_VALUE }
}

/** Rotation in degrees about X, then Y, then Z, as OrcaSlicer applies an instance rotation. */
data class Rotation(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    fun matrix(): DoubleArray {
        val rx = Math.toRadians(x); val ry = Math.toRadians(y); val rz = Math.toRadians(z)
        val cx = cos(rx); val sx = sin(rx); val cy = cos(ry); val sy = sin(ry); val cz = cos(rz); val sz = sin(rz)
        // Rz * Ry * Rx
        return doubleArrayOf(
            cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
            sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
            -sy, cy * sx, cy * cx,
        )
    }
}

/** Andrew's monotone chain; input and output are x/y pairs. */
fun convexHull(xy: DoubleArray): FloatArray {
    val n = xy.size / 2
    if (n < 3) return FloatArray(xy.size) { xy[it].toFloat() }
    val idx = (0 until n).sortedWith(compareBy<Int>({ xy[2 * it] }, { xy[2 * it + 1] }))
    fun cross(o: Int, a: Int, b: Int) =
        (xy[2 * a] - xy[2 * o]) * (xy[2 * b + 1] - xy[2 * o + 1]) - (xy[2 * a + 1] - xy[2 * o + 1]) * (xy[2 * b] - xy[2 * o])
    val hull = IntArray(2 * n)
    var k = 0
    for (i in idx) {
        while (k >= 2 && cross(hull[k - 2], hull[k - 1], i) <= 0) k--
        hull[k++] = i
    }
    val lower = k + 1
    for (j in idx.indices.reversed()) {
        val i = idx[j]
        while (k >= lower && cross(hull[k - 2], hull[k - 1], i) <= 0) k--
        hull[k++] = i
    }
    val count = k - 1
    val out = FloatArray(2 * count)
    for (i in 0 until count) {
        out[2 * i] = xy[2 * hull[i]].toFloat()
        out[2 * i + 1] = xy[2 * hull[i] + 1].toFloat()
    }
    return out
}
