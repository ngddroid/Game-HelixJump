package com.ngstudio.spindledrop.game.render

import com.ngstudio.spindledrop.game.level.Cell
import com.ngstudio.spindledrop.game.level.Physics
import com.ngstudio.spindledrop.game.level.RingKind
import com.ngstudio.spindledrop.game.level.TowerBuilder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One drawable arc inside a ring mesh. Index ranges allow drawing it in chunks (for shatter shards). */
class ArcPart(
    val type: Byte,
    val startDeg: Float,
    val lenDeg: Float,
    val firstIndex: Int,
    val stepPairs: Int,
    val indicesPerStep: Int,
    val startCapFirst: Int,
    val endCapFirst: Int,
    val capCount: Int,
    val closed: Boolean,
) {
    val sweepCount get() = stepPairs * indicesPerStep
}

/** A contiguous index range of one tile type, drawn with a single call. */
class DrawBatch(val type: Byte, val first: Int, val count: Int, val part: ArcPart?)

class RingMesh(val mesh: Mesh, val parts: List<ArcPart>) {
    /** Adjacent parts of the same type merged into one draw (crumble tiles stay separate: each has its own crack state). */
    val batches: List<DrawBatch> = buildList {
        var cur: ArcPart? = null
        var first = 0
        var end = 0
        for (p in parts) {
            val pEnd = p.firstIndex + p.sweepCount + 2 * p.capCount
            val c = cur
            if (c != null && c.type == p.type && p.type != Cell.CRUMBLE && p.firstIndex == end) {
                end = pEnd
            } else {
                if (c != null) add(DrawBatch(c.type, first, end - first, if (c.type == Cell.CRUMBLE) c else null))
                cur = p; first = p.firstIndex; end = pEnd
            }
        }
        cur?.let { add(DrawBatch(it.type, first, end - first, if (it.type == Cell.CRUMBLE) it else null)) }
    }

    fun release() = mesh.release()
}

object MeshFactory {
    /** Vertex layout for surfaces: position(3), normal(3), polar(2) = (angle degrees, radial/vertical param). */
    val SURFACE_LAYOUT = intArrayOf(3, 3, 2)
    val SIMPLE_LAYOUT = intArrayOf(3, 3)

    private const val CORNER_SEGS = 4
    private const val END_RINGS = 3

    private class Profile(val r: FloatArray, val y: FloatArray, val nr: FloatArray, val ny: FloatArray) {
        val size get() = r.size
    }

    private fun roundedRect(ri: Float, ro: Float, y0: Float, y1: Float, rt: Float, rb: Float): Profile {
        val pts = ArrayList<FloatArray>()
        fun corner(cx: Float, cy: Float, rad: Float, a0: Float, a1: Float) {
            for (s in 0..CORNER_SEGS) {
                val a = (a0 + (a1 - a0) * s / CORNER_SEGS) * (PI.toFloat() / 180f)
                val c = cos(a)
                val sn = sin(a)
                pts += floatArrayOf(cx + rad * c, cy + rad * sn, c, sn)
            }
        }
        corner(ro - rb, y0 + rb, rb, -90f, 0f)
        corner(ro - rt, y1 - rt, rt, 0f, 90f)
        corner(ri + rt, y1 - rt, rt, 90f, 180f)
        corner(ri + rb, y0 + rb, rb, 180f, 270f)
        return Profile(
            FloatArray(pts.size) { pts[it][0] }, FloatArray(pts.size) { pts[it][1] },
            FloatArray(pts.size) { pts[it][2] }, FloatArray(pts.size) { pts[it][3] },
        )
    }

    /** Geometry spec for each tile type: (y bottom, y top, inset inner, inset outer). */
    private fun dims(type: Byte): FloatArray {
        val t = Physics.RING_THICKNESS
        return when (type) {
            Cell.HAZARD, Cell.PULSE -> floatArrayOf(-t + 0.02f, 0.03f, 0.0f, 0.0f)
            Cell.BOOST -> floatArrayOf(-t + 0.02f, 0.02f, 0.0f, 0.0f)
            Cell.SHUTTER -> floatArrayOf(-0.11f, -0.01f, 0.05f, 0.05f)
            Cell.GATE -> floatArrayOf(-0.44f, 0.04f, -0.02f, -0.10f)
            Cell.VAULT -> floatArrayOf(-t, 0.0f, 0.0f, -0.04f)
            Cell.FINISH -> floatArrayOf(-0.32f, 0.0f, 0.0f, -0.08f)
            else -> floatArrayOf(-t, 0f, 0f, 0f)
        }
    }

    /** Builds the mesh for a ring from its (possibly overridden) cells. Gaps produce no geometry. */
    fun ring(cells: ByteArray, kind: RingKind): RingMesh {
        val v = FloatList(16384)
        val idx = IntList(32768)
        val parts = ArrayList<ArcPart>()
        if (kind != RingKind.NORMAL) {
            val code = cells[0]
            parts += buildArc(v, idx, code, 0f, 360f, closed = true)
        } else {
            val types = byteArrayOf(Cell.NORMAL, Cell.HAZARD, Cell.CRUMBLE, Cell.BOOST, Cell.SHUTTER, Cell.PULSE)
            for (t in types) {
                val runs = TowerBuilder.runsOf(cells, t)
                if (runs.size == 1 && runs[0].second == 360) {
                    parts += buildArc(v, idx, t, 0f, 360f, closed = true)
                    continue
                }
                for ((s, len) in runs) {
                    // Hazards are drawn slightly shorter so neighbouring tiles read as separate pieces.
                    val trim = if (t == Cell.NORMAL || t == Cell.SHUTTER) 0.0f else 0.6f
                    if (len < 2) continue
                    parts += buildArc(v, idx, t, s + trim, len - 2 * trim, closed = false)
                }
            }
        }
        return RingMesh(Mesh(v.toArray(), idx.toArray(), SURFACE_LAYOUT), parts)
    }

    /** A thin hazard strip used for sliding hazards (drawn with its own rotation). */
    fun slider(lenDeg: Float): RingMesh {
        val v = FloatList(4096)
        val idx = IntList(8192)
        val part = buildArc(v, idx, Cell.HAZARD, 0f, lenDeg, closed = false,
            override = floatArrayOf(-0.05f, 0.05f, 0.06f, 0.06f))
        return RingMesh(Mesh(v.toArray(), idx.toArray(), SURFACE_LAYOUT), listOf(part))
    }

    private fun buildArc(
        v: FloatList, idx: IntList, type: Byte, startDeg: Float, lenDeg: Float, closed: Boolean,
        override: FloatArray? = null,
    ): ArcPart {
        val d = override ?: dims(type)
        val ri = Physics.RING_INNER + d[2]
        val ro = Physics.RING_OUTER - d[3]
        val y0 = d[0]
        val y1 = d[1]
        val thick = y1 - y0
        val rt = min(0.085f, thick * 0.42f)
        val rb = min(0.05f, thick * 0.3f)
        val prof = roundedRect(ri, ro, y0, y1, rt, rb)
        val pCount = prof.size
        val lenRad = lenDeg * (PI.toFloat() / 180f)
        val phi0 = startDeg * (PI.toFloat() / 180f)
        val steps = max(2, ceil(lenDeg / 3.2f).toInt())
        val eb = if (closed) 0f else min(0.06f, 0.35f * lenRad * ri)
        val ringsAlong = if (closed) steps + 1 else steps + 1 + 2 * END_RINGS
        val base = v.size / 8
        val span = ro - ri

        fun emit(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, angDeg: Float, radial: Float) {
            v.add3(px, py, pz); v.add3(nx, ny, nz); v.add(angDeg); v.add(radial)
        }

        // Vertex rings along the sweep.
        for (k in 0 until ringsAlong) {
            for (j in 0 until pCount) {
                val pr = prof.r[j]
                val py = prof.y[j]
                val nr = prof.nr[j]
                val ny = prof.ny[j]
                var r = pr
                var y = py
                var phi: Float
                var nx: Float
                var nyy: Float
                var nz: Float
                if (closed) {
                    phi = phi0 + lenRad * k / steps
                    nx = nr * cos(phi); nyy = ny; nz = nr * sin(phi)
                } else {
                    val endOff = eb / max(0.05f, pr)
                    when {
                        k < END_RINGS -> {
                            // Start rounding: u goes 1 -> (1/END_RINGS)
                            val u = (END_RINGS - k).toFloat() / END_RINGS
                            val a = u * (PI.toFloat() / 2f)
                            val inset = eb * (1f - cos(a))
                            val fwd = eb * sin(a)
                            r = pr - nr * inset
                            y = py - ny * inset
                            phi = phi0 + endOff - fwd / max(0.05f, r)
                            val ca = cos(a); val sa = sin(a)
                            nx = nr * cos(phi) * ca + sin(phi) * sa
                            nyy = ny * ca
                            nz = nr * sin(phi) * ca - cos(phi) * sa
                        }
                        k >= END_RINGS + steps + 1 -> {
                            val u = (k - (END_RINGS + steps)).toFloat() / END_RINGS
                            val a = u * (PI.toFloat() / 2f)
                            val inset = eb * (1f - cos(a))
                            val fwd = eb * sin(a)
                            r = pr - nr * inset
                            y = py - ny * inset
                            phi = phi0 + lenRad - endOff + fwd / max(0.05f, r)
                            val ca = cos(a); val sa = sin(a)
                            nx = nr * cos(phi) * ca - sin(phi) * sa
                            nyy = ny * ca
                            nz = nr * sin(phi) * ca + cos(phi) * sa
                        }
                        else -> {
                            val s = (k - END_RINGS).toFloat() / steps
                            phi = phi0 + endOff + (lenRad - 2f * endOff) * s
                            nx = nr * cos(phi); nyy = ny; nz = nr * sin(phi)
                        }
                    }
                }
                val nl = sqrt(nx * nx + nyy * nyy + nz * nz).coerceAtLeast(1e-5f)
                emit(r * cos(phi), y, r * sin(phi), nx / nl, nyy / nl, nz / nl,
                    phi * (180f / PI.toFloat()), (r - ri) / span)
            }
        }
        val firstIndex = idx.size
        for (k in 0 until ringsAlong - 1) {
            val a0 = base + k * pCount
            val b0 = base + (k + 1) * pCount
            for (j in 0 until pCount) {
                val j1 = (j + 1) % pCount
                val a = a0 + j
                val b = a0 + j1
                val c = b0 + j1
                val dd = b0 + j
                idx.add3(a, b, dd)
                idx.add3(b, c, dd)
            }
        }
        val ips = pCount * 6
        var startCap = idx.size
        var endCap = idx.size
        var capCount = 0
        if (!closed) {
            // Start cap (faces -tangent): fan from centroid of the first ring.
            startCap = idx.size
            val c0 = v.size / 8
            addCapCenter(v, base, pCount, -1f)
            for (j in 0 until pCount) {
                val j1 = (j + 1) % pCount
                idx.add3(c0, base + j1, base + j)
            }
            val lastRing = base + (ringsAlong - 1) * pCount
            endCap = idx.size
            val c1 = v.size / 8
            addCapCenter(v, lastRing, pCount, 1f)
            for (j in 0 until pCount) {
                val j1 = (j + 1) % pCount
                idx.add3(c1, lastRing + j, lastRing + j1)
            }
            capCount = pCount * 3
        }
        return ArcPart(type, startDeg, lenDeg, firstIndex, ringsAlong - 1, ips, startCap, endCap, capCount, closed)
    }

    private fun addCapCenter(v: FloatList, first: Int, count: Int, dir: Float) {
        var cx = 0f; var cy = 0f; var cz = 0f; var ang = 0f; var rad = 0f
        for (j in 0 until count) {
            val o = (first + j) * 8
            cx += v.data[o]; cy += v.data[o + 1]; cz += v.data[o + 2]
            ang += v.data[o + 6]; rad += v.data[o + 7]
        }
        cx /= count; cy /= count; cz /= count; ang /= count; rad /= count
        val phi = ang * (PI.toFloat() / 180f)
        // Tangent of increasing angle is (-sin, 0, cos).
        val tx = -sin(phi) * dir
        val tz = cos(phi) * dir
        v.add3(cx, cy, cz); v.add3(tx, 0f, tz); v.add(ang); v.add(rad)
    }

    /** Unit icosphere (normals = positions). */
    fun sphere(subdiv: Int = 3): Mesh {
        val t = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
        val verts = ArrayList<FloatArray>()
        fun addV(x: Float, y: Float, z: Float): Int {
            val l = sqrt(x * x + y * y + z * z)
            verts += floatArrayOf(x / l, y / l, z / l)
            return verts.size - 1
        }
        listOf(
            floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
            floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t), floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
            floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f), floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f),
        ).forEach { addV(it[0], it[1], it[2]) }
        var faces = mutableListOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
            intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
            intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
        )
        repeat(subdiv) {
            val cache = HashMap<Long, Int>()
            fun mid(a: Int, b: Int): Int {
                val key = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
                return cache.getOrPut(key) {
                    val va = verts[a]; val vb = verts[b]
                    addV((va[0] + vb[0]) / 2, (va[1] + vb[1]) / 2, (va[2] + vb[2]) / 2)
                }
            }
            val nf = ArrayList<IntArray>(faces.size * 4)
            for (f in faces) {
                val a = mid(f[0], f[1]); val b = mid(f[1], f[2]); val c = mid(f[2], f[0])
                nf += intArrayOf(f[0], a, c); nf += intArrayOf(f[1], b, a); nf += intArrayOf(f[2], c, b); nf += intArrayOf(a, b, c)
            }
            faces = nf
        }
        val data = FloatArray(verts.size * 6)
        for ((i, p) in verts.withIndex()) {
            data[i * 6] = p[0]; data[i * 6 + 1] = p[1]; data[i * 6 + 2] = p[2]
            data[i * 6 + 3] = p[0]; data[i * 6 + 4] = p[1]; data[i * 6 + 5] = p[2]
        }
        val ind = IntArray(faces.size * 3)
        for ((i, f) in faces.withIndex()) { ind[i * 3] = f[0]; ind[i * 3 + 1] = f[1]; ind[i * 3 + 2] = f[2] }
        return Mesh(data, ind, SIMPLE_LAYOUT)
    }

    /** Tower pillar: open cylinder of radius 1 and height 1 (centred), polar = (angle deg, y). */
    fun cylinder(segments: Int = 56): Mesh {
        val v = FloatList()
        val idx = IntList()
        for (s in 0..segments) {
            val a = 2f * PI.toFloat() * s / segments
            val c = cos(a); val sn = sin(a)
            for (yy in 0..1) {
                val y = yy - 0.5f
                v.add3(c, y, sn); v.add3(c, 0f, sn); v.add(a * 180f / PI.toFloat()); v.add(y)
            }
        }
        for (s in 0 until segments) {
            val a = s * 2; val b = a + 1; val c = a + 2; val d = a + 3
            // Outward-facing winding.
            idx.add3(a, b, c); idx.add3(b, d, c)
        }
        return Mesh(v.toArray(), idx.toArray(), SURFACE_LAYOUT)
    }

    /** Coin: a lathe around Y (flat faces up/down), rendered with the ball shader. */
    fun coin(): Mesh {
        val prof = roundedRect(0.0001f, 0.15f, -0.035f, 0.035f, 0.03f, 0.03f)
        val v = FloatList()
        val idx = IntList()
        val seg = 28
        val p = prof.size
        for (s in 0..seg) {
            val a = 2f * PI.toFloat() * s / seg
            val c = cos(a); val sn = sin(a)
            for (j in 0 until p) {
                v.add3(prof.r[j] * c, prof.y[j], prof.r[j] * sn)
                v.add3(prof.nr[j] * c, prof.ny[j], prof.nr[j] * sn)
            }
        }
        for (s in 0 until seg) for (j in 0 until p) {
            val j1 = (j + 1) % p
            val a = s * p + j; val b = s * p + j1; val c = (s + 1) * p + j1; val d = (s + 1) * p + j
            idx.add3(a, b, d); idx.add3(b, c, d)
        }
        return Mesh(v.toArray(), idx.toArray(), SIMPLE_LAYOUT)
    }

    /** Unit quad in XY (-0.5..0.5), position only (stored as 3+3 for layout compatibility). */
    fun quad(): Mesh {
        val d = floatArrayOf(
            -0.5f, -0.5f, 0f, 0f, 0f, 1f,
            0.5f, -0.5f, 0f, 0f, 0f, 1f,
            0.5f, 0.5f, 0f, 0f, 0f, 1f,
            -0.5f, 0.5f, 0f, 0f, 0f, 1f,
        )
        return Mesh(d, intArrayOf(0, 1, 2, 0, 2, 3), SIMPLE_LAYOUT)
    }
}
