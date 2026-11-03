package com.marvis.ai.hologram

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A wireframe: vertices (x, y, z) and edges between them. Normalised to fit a unit sphere. */
class Mesh(val vx: FloatArray, val vy: FloatArray, val vz: FloatArray, val ea: IntArray, val eb: IntArray) {
    val vertexCount get() = vx.size
    val edgeCount get() = ea.size
}

/** Screen-space result of projecting a mesh. Reused every frame so drawing allocates nothing. */
class Projected(n: Int) {
    val x = FloatArray(n)
    val y = FloatArray(n)
    val z = FloatArray(n)
}

class MeshBuilder {
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val zs = ArrayList<Float>()
    private val ea = ArrayList<Int>()
    private val eb = ArrayList<Int>()

    fun v(x: Float, y: Float, z: Float): Int { xs += x; ys += y; zs += z; return xs.size - 1 }
    fun e(i: Int, j: Int) { ea += i; eb += j }
    fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) = e(v(x1, y1, z1), v(x2, y2, z2))

    /** A closed ring of [seg] points around axis 'z' (default), 'y' or 'x'. */
    fun ring(cx: Float, cy: Float, cz: Float, r: Float, seg: Int, axis: Char = 'z'): IntArray {
        val ids = IntArray(seg)
        for (k in 0 until seg) {
            val t = (2 * PI * k / seg).toFloat()
            val c = cos(t) * r
            val s = sin(t) * r
            ids[k] = when (axis) {
                'z' -> v(cx + c, cy + s, cz)
                'y' -> v(cx + c, cy, cz + s)
                else -> v(cx, cy + c, cz + s)
            }
        }
        for (k in 0 until seg) e(ids[k], ids[(k + 1) % seg])
        return ids
    }

    fun box(cx: Float, cy: Float, cz: Float, w: Float, h: Float, d: Float) {
        val hx = w / 2; val hy = h / 2; val hz = d / 2
        val p = IntArray(8)
        var i = 0
        for (sx in floatArrayOf(-1f, 1f)) for (sy in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) {
            p[i++] = v(cx + sx * hx, cy + sy * hy, cz + sz * hz)
        }
        // vertex index = sx*4 + sy*2 + sz (with -1 -> 0, +1 -> 1)
        for (a in 0 until 8) for (bit in intArrayOf(1, 2, 4)) if (a and bit == 0) e(p[a], p[a or bit])
    }

    /** Centres the model and scales it so the farthest vertex sits at distance [radius] from the centre. */
    fun build(radius: Float = 1f): Mesh {
        val n = xs.size
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = minOf(minX, xs[i]); maxX = max(maxX, xs[i])
            minY = minOf(minY, ys[i]); maxY = max(maxY, ys[i])
            minZ = minOf(minZ, zs[i]); maxZ = max(maxZ, zs[i])
        }
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2; val cz = (minZ + maxZ) / 2
        var far = 0.0001f
        for (i in 0 until n) {
            val dx = xs[i] - cx; val dy = ys[i] - cy; val dz = zs[i] - cz
            far = max(far, sqrt(dx * dx + dy * dy + dz * dz))
        }
        val k = radius / far
        return Mesh(
            FloatArray(n) { (xs[it] - cx) * k }, FloatArray(n) { (ys[it] - cy) * k }, FloatArray(n) { (zs[it] - cz) * k },
            ea.toIntArray(), eb.toIntArray())
    }
}

/** Procedurally generated, stylised wireframes. They are original shapes, not copies of any official design. */
object Meshes {

    /** Concentric rings, a ring of coil blocks, spokes and a triangular core. */
    fun reactor(seg: Int = 48): Mesh {
        val b = MeshBuilder()
        val front = b.ring(0f, 0f, 0.12f, 1.0f, seg)
        val back = b.ring(0f, 0f, -0.12f, 1.0f, seg)
        for (k in 0 until seg step 2) b.e(front[k], back[k])
        for (k in 0 until 10) {
            val t = (2 * PI * k / 10).toFloat()
            b.box(cos(t) * 0.86f, sin(t) * 0.86f, 0f, 0.16f, 0.16f, 0.16f)
        }
        b.ring(0f, 0f, 0.06f, 0.70f, seg)
        b.ring(0f, 0f, -0.06f, 0.70f, seg)
        for (k in 0 until 20) {
            val t = (2 * PI * k / 20).toFloat()
            b.line(cos(t) * 0.70f, sin(t) * 0.70f, 0f, cos(t) * 0.50f, sin(t) * 0.50f, 0f)
        }
        b.ring(0f, 0f, 0f, 0.50f, seg)
        val t0 = b.v(0f, 0.36f, 0f); val t1 = b.v(-0.31f, -0.18f, 0f); val t2 = b.v(0.31f, -0.18f, 0f)
        b.e(t0, t1); b.e(t1, t2); b.e(t2, t0)
        val u0 = b.v(0f, -0.20f, 0f); val u1 = b.v(-0.17f, 0.10f, 0f); val u2 = b.v(0.17f, 0.10f, 0f)
        b.e(u0, u1); b.e(u1, u2); b.e(u2, u0)
        b.ring(0f, 0f, 0f, 0.14f, 16)
        return b.build()
    }

    /** A stylised armoured humanoid built from boxes plus a chest ring. */
    fun armor(): Mesh {
        val b = MeshBuilder()
        b.box(0f, 1.35f, 0f, 0.42f, 0.50f, 0.46f)                 // head
        b.line(-0.21f, 1.30f, 0.23f, 0.21f, 1.30f, 0.23f)         // visor
        b.box(0f, 1.08f, 0f, 0.18f, 0.12f, 0.18f)                 // neck
        b.box(0f, 0.68f, 0f, 0.95f, 0.62f, 0.52f)                 // chest
        b.box(0f, 0.18f, 0f, 0.62f, 0.40f, 0.40f)                 // abdomen
        b.box(0f, -0.12f, 0f, 0.74f, 0.26f, 0.44f)                // pelvis
        for (s in floatArrayOf(-1f, 1f)) {
            b.box(s * 0.62f, 0.92f, 0f, 0.36f, 0.30f, 0.40f)      // shoulder
            b.box(s * 0.78f, 0.50f, 0f, 0.24f, 0.60f, 0.28f)      // upper arm
            b.box(s * 0.82f, -0.08f, 0f, 0.26f, 0.56f, 0.30f)     // forearm
            b.box(s * 0.84f, -0.50f, 0f, 0.18f, 0.22f, 0.14f)     // hand
            b.box(s * 0.22f, -0.58f, 0f, 0.30f, 0.62f, 0.34f)     // thigh
            b.box(s * 0.22f, -1.05f, 0f, 0.28f, 0.50f, 0.32f)     // shin
            b.box(s * 0.22f, -1.38f, 0.06f, 0.30f, 0.16f, 0.48f)  // boot
        }
        b.ring(0f, 0.68f, 0.28f, 0.17f, 20)                       // chest ring
        b.ring(0f, 0.68f, 0.28f, 0.09f, 14)
        return b.build()
    }

    fun globe(): Mesh {
        val b = MeshBuilder()
        for (lat in intArrayOf(-60, -30, 0, 30, 60)) {
            val phi = Math.toRadians(lat.toDouble()).toFloat()
            b.ring(0f, sin(phi), 0f, cos(phi), 36, 'y')
        }
        for (m in 0 until 12) {
            val theta = (PI * m / 12).toFloat()
            var prev = -1
            for (i in 0..24) {
                val phi = (-PI / 2 + PI * i / 24).toFloat()
                val id = b.v(cos(phi) * cos(theta), sin(phi), cos(phi) * sin(theta))
                if (prev >= 0) b.e(prev, id)
                prev = id
            }
        }
        return b.build()
    }

    fun helix(): Mesh {
        val b = MeshBuilder()
        var a0 = -1; var a1 = -1
        for (i in 0..60) {
            val th = i * 0.32f
            val y = i * 0.04f - 1.2f
            val p0 = b.v(cos(th) * 0.6f, y, sin(th) * 0.6f)
            val p1 = b.v(cos(th + PI.toFloat()) * 0.6f, y, sin(th + PI.toFloat()) * 0.6f)
            if (a0 >= 0) { b.e(a0, p0); b.e(a1, p1) }
            if (i % 3 == 0) b.e(p0, p1)
            a0 = p0; a1 = p1
        }
        return b.build()
    }

    fun forKey(key: String): Mesh = when (key) {
        "ARMOR" -> armor()
        "GLOBE" -> globe()
        "HELIX" -> helix()
        else -> reactor()
    }
}

object Holo3D {
    private const val CAMERA = 3.4f

    /** Rotates (yaw around Y, pitch around X, roll around Z), then applies perspective. Writes into [out]. */
    fun project(m: Mesh, yaw: Float, pitch: Float, roll: Float, zoom: Float, cx: Float, cy: Float, scale: Float, out: Projected) {
        val cyw = cos(yaw); val syw = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val cr = cos(roll); val sr = sin(roll)
        for (i in 0 until m.vertexCount) {
            val x = m.vx[i]; val y = m.vy[i]; val z = m.vz[i]
            val x1 = x * cyw + z * syw
            val z1 = -x * syw + z * cyw
            val y2 = y * cp - z1 * sp
            val z2 = y * sp + z1 * cp
            val x3 = x1 * cr - y2 * sr
            val y3 = x1 * sr + y2 * cr
            val persp = CAMERA / (CAMERA + z2)
            out.x[i] = cx + x3 * persp * scale * zoom
            out.y[i] = cy - y3 * persp * scale * zoom
            out.z[i] = z2
        }
    }

    /** Nearer parts glow brighter. z is roughly -1 (near) .. +1 (far). */
    fun depthAlpha(z: Float): Float = (0.55f - z * 0.37f).coerceIn(0.16f, 1f)
}
