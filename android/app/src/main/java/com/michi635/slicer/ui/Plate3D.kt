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
import com.michi635.slicer.MainViewModel
import com.michi635.slicer.model.Mesh
import com.michi635.slicer.model.Printer
import com.michi635.slicer.model.Rotation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What the 3D view draws; built on the UI thread, read on the GL thread. */
class Scene(
    val bed: DoubleArray,          // minX, minY, maxX, maxY
    val objects: List<SceneObject>,
    val tower: DoubleArray?,       // x, y, width, depth
)

class SceneObject(val mesh: Mesh, val model: FloatArray, val color: FloatArray)

/** 3D view of the plate: one finger orbits, two fingers zoom, double tap resets the view. */
@Composable
fun Plate3D(vm: MainViewModel, modifier: Modifier = Modifier) {
    val printer = vm.printer
    val objects = vm.objects
    val slots = vm.slots
    val selectedId = vm.selectedId
    val tower = vm.primeTower
    AndroidView(
        factory = { PlateGLView(it) },
        modifier = modifier,
        update = { view ->
            val colors = slots.map { parseColor(it.color) }
            view.setScene(
                Scene(
                    bed = bedOf(printer),
                    objects = objects.map { o ->
                        val base = colors.getOrElse(o.slot - 1) { Color.White }
                        val c = if (o.id == selectedId) floatArrayOf(
                            base.red * 0.6f + 0.4f, base.green * 0.6f + 0.25f, base.blue * 0.6f
                        ) else floatArrayOf(base.red, base.green, base.blue)
                        SceneObject(o.mesh, modelMatrix(o.mesh, o.x, o.y, o.rotation, o.scale), c)
                    },
                    tower = tower?.let { doubleArrayOf(it.x, it.y, it.width, it.depth) },
                )
            )
        },
    )
}

private fun bedOf(p: Printer?) = doubleArrayOf(p?.bedMinX ?: 0.0, p?.bedMinY ?: 0.0, p?.bedMaxX ?: 250.0, p?.bedMaxY ?: 250.0)

/** Translate(x, y, on the bed) * Rz * Ry * Rx * Scale, as the engine places an instance. */
private fun modelMatrix(mesh: Mesh, x: Double, y: Double, r: Rotation, scale: Double): FloatArray {
    val m = FloatArray(16)
    Matrix.setIdentityM(m, 0)
    val zMin = mesh.zRange(r, scale)[0]
    Matrix.translateM(m, 0, x.toFloat(), y.toFloat(), (-zMin).toFloat())
    Matrix.rotateM(m, 0, r.z.toFloat(), 0f, 0f, 1f)
    Matrix.rotateM(m, 0, r.y.toFloat(), 0f, 1f, 0f)
    Matrix.rotateM(m, 0, r.x.toFloat(), 1f, 0f, 0f)
    Matrix.scaleM(m, 0, scale.toFloat(), scale.toFloat(), scale.toFloat())
    return m
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PlateGLView(context: Context) : GLSurfaceView(context) {
    private val renderer = PlateRenderer()

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
        setEGLConfigChooser(true)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setScene(scene: Scene) {
        renderer.scene = scene
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

private class PlateRenderer : GLSurfaceView.Renderer {
    @Volatile var scene: Scene? = null

    @Volatile private var azimuth = 20f
    @Volatile private var elevation = 35f
    @Volatile private var distanceFactor = 1.6f

    private var program = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uLight = 0
    private var uUnlit = 0
    private var width = 1
    private var height = 1
    private val buffers = IdentityHashMap<Mesh, IntArray>() // vbo, ibo, index count
    private val cube: Mesh = unitCube()

    fun orbit(dx: Float, dy: Float) {
        azimuth -= dx * 0.4f
        elevation = (elevation + dy * 0.4f).coerceIn(3f, 89f)
    }

    fun zoom(factor: Float) {
        distanceFactor = (distanceFactor / factor).coerceIn(0.3f, 5f)
    }

    fun resetCamera() {
        azimuth = 20f; elevation = 35f; distanceFactor = 1.6f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        buffers.clear() // a new context has none of the old buffers
        program = link(VERTEX, FRAGMENT)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uModel = GLES30.glGetUniformLocation(program, "uModel")
        uColor = GLES30.glGetUniformLocation(program, "uColor")
        uLight = GLES30.glGetUniformLocation(program, "uLight")
        uUnlit = GLES30.glGetUniformLocation(program, "uUnlit")
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0.11f, 0.12f, 0.14f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = scene ?: return
        val bed = s.bed
        val cx = ((bed[0] + bed[2]) / 2).toFloat()
        val cy = ((bed[1] + bed[3]) / 2).toFloat()
        val diag = sqrt(((bed[2] - bed[0]) * (bed[2] - bed[0]) + (bed[3] - bed[1]) * (bed[3] - bed[1]))).toFloat()

        // Camera orbiting the bed centre
        val d = diag * distanceFactor
        val az = Math.toRadians(azimuth.toDouble())
        val el = Math.toRadians(elevation.toDouble())
        val ex = cx + (d * cos(el) * sin(az)).toFloat()
        val ey = cy - (d * cos(el) * cos(az)).toFloat()
        val ez = (d * sin(el)).toFloat()
        val view = FloatArray(16)
        val proj = FloatArray(16)
        val vp = FloatArray(16)
        Matrix.setLookAtM(view, 0, ex, ey, ez, cx, cy, diag * 0.05f, 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, 35f, width.toFloat() / height, 1f, d * 4f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        val light = floatArrayOf(-0.3f, -0.5f, 1f).let { val n = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]); floatArrayOf(it[0] / n, it[1] / n, it[2] / n) }
        GLES30.glUniform3fv(uLight, 1, light, 0)

        // Objects (meshes no longer on the plate free their buffers)
        val live = IdentityHashMap<Mesh, Boolean>()
        s.objects.forEach { live[it.mesh] = true }
        live[cube] = true
        buffers.keys.filter { it !in live }.forEach { m -> buffers.remove(m)?.let { GLES30.glDeleteBuffers(2, it, 0) } }
        GLES30.glUniform1f(uUnlit, 0f)
        for (o in s.objects) drawMesh(o.mesh, o.model, o.color)

        // Prime tower as a grey box
        s.tower?.let { t ->
            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, (t[0] + t[2] / 2).toFloat(), (t[1] + t[3] / 2).toFloat(), 5f)
            Matrix.scaleM(m, 0, t[2].toFloat(), t[3].toFloat(), 10f)
            drawMesh(cube, m, floatArrayOf(0.55f, 0.45f, 0.4f))
        }

        // Bed: a flat plate with a 50 mm grid, unlit
        GLES30.glUniform1f(uUnlit, 1f)
        val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        GLES30.glUniformMatrix4fv(uModel, 1, false, identity, 0)
        drawBed(bed)
    }

    private fun drawMesh(mesh: Mesh, model: FloatArray, color: FloatArray) {
        if (mesh.indices.isEmpty()) return
        val b = buffers.getOrPut(mesh) { upload(mesh) }
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniform3fv(uColor, 1, color, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[0])
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b[1])
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, b[2], GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun upload(mesh: Mesh): IntArray {
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        val vb = ByteBuffer.allocateDirect(mesh.points.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(mesh.points).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.points.size * 4, vb, GLES30.GL_STATIC_DRAW)
        val ib = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        ib.put(mesh.indices).position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        return intArrayOf(ids[0], ids[1], mesh.indices.size)
    }

    private fun drawBed(bed: DoubleArray) {
        val x0 = bed[0].toFloat(); val y0 = bed[1].toFloat(); val x1 = bed[2].toFloat(); val y1 = bed[3].toFloat()
        val plate = floatArrayOf(x0, y0, -0.2f, x1, y0, -0.2f, x1, y1, -0.2f, x0, y0, -0.2f, x1, y1, -0.2f, x0, y1, -0.2f)
        drawArrays(plate, GLES30.GL_TRIANGLES, floatArrayOf(0.24f, 0.26f, 0.30f))
        val lines = ArrayList<Float>()
        var x = x0
        while (x <= x1 + 0.01f) { lines += listOf(x, y0, 0f, x, y1, 0f); x += 50f }
        var y = y0
        while (y <= y1 + 0.01f) { lines += listOf(x0, y, 0f, x1, y, 0f); y += 50f }
        lines += listOf(x1, y0, 0f, x1, y1, 0f, x0, y1, 0f, x1, y1, 0f)
        drawArrays(lines.toFloatArray(), GLES30.GL_LINES, floatArrayOf(0.45f, 0.48f, 0.53f))
    }

    private fun drawArrays(data: FloatArray, mode: Int, color: FloatArray) {
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        GLES30.glUniform3fv(uColor, 1, color, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, fb)
        GLES30.glDrawArrays(mode, 0, data.size / 3)
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
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        return p
    }

    companion object {
        const val VERTEX = """#version 300 es
uniform mat4 uMvp;
uniform mat4 uModel;
layout(location = 0) in vec3 aPos;
out vec3 vWorld;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    gl_Position = uMvp * w;
}
"""
        const val FRAGMENT = """#version 300 es
precision highp float;
in vec3 vWorld;
uniform vec3 uColor;
uniform vec3 uLight;
uniform float uUnlit;
out vec4 fragColor;
void main() {
    if (uUnlit > 0.5) { fragColor = vec4(uColor, 1.0); return; }
    vec3 n = normalize(cross(dFdx(vWorld), dFdy(vWorld)));
    float d = abs(dot(n, uLight));
    fragColor = vec4(uColor * (0.35 + 0.65 * d), 1.0);
}
"""

        /** A 1 x 1 x 1 box centred on the origin. */
        fun unitCube(): Mesh {
            val p = floatArrayOf(
                -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f,
                -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
            )
            val i = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
            return Mesh(p, i, 12, 1f, 1f, 1f)
        }
    }
}
