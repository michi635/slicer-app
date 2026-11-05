package com.michi635.slicer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.michi635.slicer.model.Feature
import com.michi635.slicer.model.PreviewLayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The extrusion moves of all layers, ready for the GPU: one 28-byte record per segment
 * (x0, y0, x1, y1, top z, layer height as floats; feature and filament index as bytes), in layer order,
 * so a range of layers is a contiguous range of records.
 */
class Toolpaths(
    val data: ByteBuffer,
    /** Index of the first segment of each layer, plus the total count at the end. */
    val layerStart: IntArray,
    /** minX, minY, minZ, maxX, maxY, maxZ of everything extruded. */
    val bounds: FloatArray,
) {
    val layerCount get() = layerStart.size - 1

    companion object {
        const val STRIDE = 28

        fun of(layers: List<PreviewLayer>): Toolpaths {
            val total = layers.sumOf { it.count }
            val buf = ByteBuffer.allocateDirect(total.coerceAtLeast(1) * STRIDE).order(ByteOrder.nativeOrder())
            val start = IntArray(layers.size + 1)
            val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var n = 0
            var prevZ = 0f
            layers.forEachIndexed { li, layer ->
                start[li] = n
                val h = (layer.z - prevZ).let { if (it in 0.02f..1.0f) it else 0.2f }
                prevZ = layer.z
                val s = layer.segments
                for (i in 0 until layer.count) {
                    val x0 = s[4 * i]; val y0 = s[4 * i + 1]; val x1 = s[4 * i + 2]; val y1 = s[4 * i + 3]
                    buf.putFloat(x0).putFloat(y0).putFloat(x1).putFloat(y1).putFloat(layer.z).putFloat(h)
                    buf.put(layer.features[i]).put(layer.tools[i]).put(0).put(0)
                    if (x0 < b[0]) b[0] = x0; if (x1 < b[0]) b[0] = x1
                    if (y0 < b[1]) b[1] = y0; if (y1 < b[1]) b[1] = y1
                    if (x0 > b[3]) b[3] = x0; if (x1 > b[3]) b[3] = x1
                    if (y0 > b[4]) b[4] = y0; if (y1 > b[4]) b[4] = y1
                    if (layer.z - h < b[2]) b[2] = layer.z - h
                    if (layer.z > b[5]) b[5] = layer.z
                    n++
                }
            }
            start[layers.size] = n
            buf.position(0)
            if (n == 0) { b[0] = 0f; b[1] = 0f; b[2] = 0f; b[3] = 1f; b[4] = 1f; b[5] = 1f }
            return Toolpaths(buf, start, b)
        }
    }
}

/** What the toolpath view shows; changed from the UI thread, read on the GL thread. */
class ToolpathView(
    val paths: Toolpaths,
    val bed: DoubleArray,
    /** The layer the slider points at (0-based). */
    val layer: Int,
    /** true: only that layer; false: everything up to and including it. */
    val onlyLayer: Boolean,
    val byFilament: Boolean,
    val toolColors: List<Color>,
)

/** 3D view of the sliced toolpaths: one finger orbits, two fingers zoom, double tap resets the view. */
@Composable
fun Gcode3D(view: ToolpathView, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ToolpathGLView(it) },
        modifier = modifier,
        update = { it.show(view) },
    )
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ToolpathGLView(context: Context) : GLSurfaceView(context) {
    private val renderer = ToolpathRenderer()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            renderer.resetCamera()
            requestRender()
            return true
        }
    })
    private var lastX = 0f
    private var lastY = 0f

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun show(view: ToolpathView) {
        renderer.view = view
        requestRender()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    renderer.orbit(event.x - lastX, event.y - lastY)
                    requestRender()
                }
                lastX = event.x; lastY = event.y
            }
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}

private class ToolpathRenderer : GLSurfaceView.Renderer {
    @Volatile var view: ToolpathView? = null

    @Volatile private var azimuth = 25f
    @Volatile private var elevation = 35f
    @Volatile private var distanceFactor = 1f

    private var program = 0
    private var flatProgram = 0
    private var corners = 0
    private var instances = 0
    private var uploaded: Toolpaths? = null
    private var width = 1
    private var height = 1

    fun orbit(dx: Float, dy: Float) {
        azimuth -= dx * 0.4f
        elevation = (elevation + dy * 0.4f).coerceIn(-10f, 89f)
    }

    fun zoom(factor: Float) {
        distanceFactor = (distanceFactor / factor).coerceIn(0.15f, 6f)
    }

    fun resetCamera() {
        azimuth = 25f; elevation = 35f; distanceFactor = 1f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context has none of the old objects
        uploaded = null
        program = link(PATH_VERTEX, PATH_FRAGMENT)
        flatProgram = link(FLAT_VERTEX, FLAT_FRAGMENT)
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        corners = ids[0]; instances = ids[1]
        val fb = ByteBuffer.allocateDirect(CORNERS.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(CORNERS).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, corners)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, CORNERS.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0.11f, 0.12f, 0.14f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val v = view ?: return
        val p = v.paths
        if (uploaded !== p) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instances)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p.data.capacity(), p.data, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            uploaded = p
        }

        // Camera orbiting the centre of what was printed
        val b = p.bounds
        val cx = (b[0] + b[3]) / 2; val cy = (b[1] + b[4]) / 2; val cz = (b[2] + b[5]) / 2
        val size = sqrt((b[3] - b[0]) * (b[3] - b[0]) + (b[4] - b[1]) * (b[4] - b[1]) + (b[5] - b[2]) * (b[5] - b[2]))
        val d = max(size, 40f) * 1.6f * distanceFactor
        val az = Math.toRadians(azimuth.toDouble())
        val el = Math.toRadians(elevation.toDouble())
        val ex = cx + (d * cos(el) * sin(az)).toFloat()
        val ey = cy - (d * cos(el) * cos(az)).toFloat()
        val ez = cz + (d * sin(el)).toFloat()
        val viewM = FloatArray(16); val proj = FloatArray(16); val vp = FloatArray(16)
        Matrix.setLookAtM(viewM, 0, ex, ey, ez, cx, cy, cz, 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, 35f, width.toFloat() / height, max(d * 0.01f, 0.5f), d * 6f)
        Matrix.multiplyMM(vp, 0, proj, 0, viewM, 0)

        drawToolpaths(v, vp)
        drawBed(v.bed, vp)
    }

    private fun drawToolpaths(v: ToolpathView, vp: FloatArray) {
        val p = v.paths
        if (p.layerCount == 0) return
        val layer = v.layer.coerceIn(0, p.layerCount - 1)
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, vp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uWidth"), 0.45f)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uLight"), 1, LIGHT, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uByTool"), if (v.byFilament) 1 else 0)
        val features = FloatArray(16 * 3)
        Feature.entries.forEachIndexed { i, f ->
            val c = Color(f.argb)
            features[3 * i] = c.red; features[3 * i + 1] = c.green; features[3 * i + 2] = c.blue
        }
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uFeature"), 16, features, 0)
        val tools = FloatArray(16 * 3) { 0.6f }
        v.toolColors.take(16).forEachIndexed { i, c ->
            tools[3 * i] = c.red; tools[3 * i + 1] = c.green; tools[3 * i + 2] = c.blue
        }
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uTool"), 16, tools, 0)
        val uShade = GLES30.glGetUniformLocation(program, "uShade")

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, corners)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribDivisor(0, 0)

        if (v.onlyLayer) {
            GLES30.glUniform1f(uShade, 1f)
            drawRange(p.layerStart[layer], p.layerStart[layer + 1])
        } else {
            // Layers below slightly darker, so the current one stands out
            GLES30.glUniform1f(uShade, 0.8f)
            drawRange(0, p.layerStart[layer])
            GLES30.glUniform1f(uShade, 1f)
            drawRange(p.layerStart[layer], p.layerStart[layer + 1])
        }
        for (a in 1..3) {
            GLES30.glVertexAttribDivisor(a, 0)
            GLES30.glDisableVertexAttribArray(a)
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Segments [from, to): the instance attributes start at the first one (no base instance in GLES 3.0). */
    private fun drawRange(from: Int, to: Int) {
        if (to <= from) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instances)
        val offset = from * Toolpaths.STRIDE
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, Toolpaths.STRIDE, offset)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, Toolpaths.STRIDE, offset + 16)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_UNSIGNED_BYTE, false, Toolpaths.STRIDE, offset + 24)
        GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, CORNERS.size / 4, to - from)
    }

    private fun drawBed(bed: DoubleArray, vp: FloatArray) {
        GLES30.glUseProgram(flatProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(flatProgram, "uMvp"), 1, false, vp, 0)
        val uColor = GLES30.glGetUniformLocation(flatProgram, "uColor")
        val x0 = bed[0].toFloat(); val y0 = bed[1].toFloat(); val x1 = bed[2].toFloat(); val y1 = bed[3].toFloat()
        val plate = floatArrayOf(x0, y0, -0.05f, x1, y0, -0.05f, x1, y1, -0.05f, x0, y0, -0.05f, x1, y1, -0.05f, x0, y1, -0.05f)
        drawArrays(plate, GLES30.GL_TRIANGLES, uColor, floatArrayOf(0.24f, 0.26f, 0.30f))
        val lines = ArrayList<Float>()
        var x = x0
        while (x <= x1 + 0.01f) { lines += listOf(x, y0, 0f, x, y1, 0f); x += 10f }
        var y = y0
        while (y <= y1 + 0.01f) { lines += listOf(x0, y, 0f, x1, y, 0f); y += 10f }
        drawArrays(lines.toFloatArray(), GLES30.GL_LINES, uColor, floatArrayOf(0.32f, 0.34f, 0.39f))
    }

    private fun drawArrays(data: FloatArray, mode: Int, uColor: Int, color: FloatArray) {
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        GLES30.glUniform3fv(uColor, 1, color, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, fb)
        GLES30.glDrawArrays(mode, 0, data.size / 3)
        GLES30.glDisableVertexAttribArray(0)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        return p
    }

    companion object {
        private val LIGHT = floatArrayOf(-0.3f, -0.5f, 1f).let { val n = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]); floatArrayOf(it[0] / n, it[1] / n, it[2] / n) }

        /**
         * One extrusion as an open box: top face and both side faces, 6 vertices each.
         * Per corner: along (0 = start, 1 = end), across (-0.5..0.5 of the width), down (0 = top, -1 = bottom
         * of the layer), end extension (-1 at the start, +1 at the end: half a width, so corners close).
         */
        private val CORNERS: FloatArray = run {
            val out = ArrayList<Float>()
            fun c(u: Float, v: Float, w: Float) { out += listOf(u, v, w, if (u == 0f) -1f else 1f) }
            // top
            c(0f, -0.5f, 0f); c(1f, -0.5f, 0f); c(1f, 0.5f, 0f)
            c(0f, -0.5f, 0f); c(1f, 0.5f, 0f); c(0f, 0.5f, 0f)
            // sides
            for (v in floatArrayOf(-0.5f, 0.5f)) {
                c(0f, v, 0f); c(1f, v, 0f); c(1f, v, -1f)
                c(0f, v, 0f); c(1f, v, -1f); c(0f, v, -1f)
            }
            out.toFloatArray()
        }

        const val PATH_VERTEX = """#version 300 es
uniform mat4 uMvp;
uniform float uWidth;
uniform vec3 uFeature[16];
uniform vec3 uTool[16];
uniform int uByTool;
layout(location = 0) in vec4 aCorner;
layout(location = 1) in vec4 aSeg;
layout(location = 2) in vec2 aZ;
layout(location = 3) in vec2 aInfo;
out vec3 vWorld;
out vec3 vColor;
void main() {
    vec2 p0 = aSeg.xy;
    vec2 d = aSeg.zw - aSeg.xy;
    float len = length(d);
    vec2 dir = len > 1e-5 ? d / len : vec2(1.0, 0.0);
    vec2 across = vec2(-dir.y, dir.x);
    vec2 xy = p0 + d * aCorner.x + dir * aCorner.w * uWidth * 0.45 + across * aCorner.y * uWidth;
    vWorld = vec3(xy, aZ.x + aCorner.z * aZ.y * 0.95);
    int f = clamp(int(aInfo.x + 0.5), 0, 15);
    int t = clamp(int(aInfo.y + 0.5), 0, 15);
    vColor = uByTool == 1 ? uTool[t] : uFeature[f];
    gl_Position = uMvp * vec4(vWorld, 1.0);
}
"""
        const val PATH_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vColor;
uniform vec3 uLight;
uniform float uShade;
out vec4 fragColor;
void main() {
    vec3 n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
    float d = abs(dot(n, uLight));
    fragColor = vec4(vColor * (0.4 + 0.6 * d) * uShade, 1.0);
}
"""
        const val FLAT_VERTEX = """#version 300 es
uniform mat4 uMvp;
layout(location = 0) in vec3 aPos;
void main() { gl_Position = uMvp * vec4(aPos, 1.0); }
"""
        const val FLAT_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec3 uColor;
out vec4 fragColor;
void main() { fragColor = vec4(uColor, 1.0); }
"""
    }
}
