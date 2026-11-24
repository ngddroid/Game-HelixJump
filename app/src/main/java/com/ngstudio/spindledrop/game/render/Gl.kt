package com.ngstudio.spindledrop.game.render

import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

object Gl {
    const val TAG = "SpindleGL"

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    fun intBuffer(data: IntArray): IntBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
            put(data); position(0)
        }

    fun compile(type: Int, src: String, name: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            Log.e(TAG, "Shader compile failed ($name, ${if (type == GL_VERTEX_SHADER) "vs" else "fs"}): $log")
            val lines = src.lines()
            lines.forEachIndexed { i, l -> Log.e(TAG, "${i + 1}: $l") }
            glDeleteShader(s)
            throw RuntimeException("Shader compile failed: $name: $log")
        }
        return s
    }

    fun program(vs: String, fs: String, name: String): Int {
        val v = compile(GL_VERTEX_SHADER, vs, name)
        val f = compile(GL_FRAGMENT_SHADER, fs, name)
        val p = glCreateProgram()
        glAttachShader(p, v)
        glAttachShader(p, f)
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        glDeleteShader(v)
        glDeleteShader(f)
        if (ok[0] == 0) {
            val log = glGetProgramInfoLog(p)
            Log.e(TAG, "Program link failed ($name): $log")
            throw RuntimeException("Program link failed: $name: $log")
        }
        return p
    }

    fun gen(f: (Int, IntArray, Int) -> Unit): Int {
        val a = IntArray(1)
        f(1, a, 0)
        return a[0]
    }

    fun checkError(where: String) {
        val e = glGetError()
        if (e != GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
    }
}

/** Thin wrapper over a GL program with cached uniform locations. */
class Program(vs: String, fs: String, val name: String) {
    val id: Int = Gl.program(vs, fs, name)
    private val locs = HashMap<String, Int>()

    fun use() = glUseProgram(id)
    fun loc(n: String): Int = locs.getOrPut(n) { glGetUniformLocation(id, n) }

    fun f(n: String, v: Float) { val l = loc(n); if (l >= 0) glUniform1f(l, v) }
    fun i(n: String, v: Int) { val l = loc(n); if (l >= 0) glUniform1i(l, v) }
    fun v2(n: String, x: Float, y: Float) { val l = loc(n); if (l >= 0) glUniform2f(l, x, y) }
    fun v3(n: String, x: Float, y: Float, z: Float) { val l = loc(n); if (l >= 0) glUniform3f(l, x, y, z) }
    fun v3(n: String, c: FloatArray) { val l = loc(n); if (l >= 0) glUniform3f(l, c[0], c[1], c[2]) }
    fun v4(n: String, x: Float, y: Float, z: Float, w: Float) { val l = loc(n); if (l >= 0) glUniform4f(l, x, y, z, w) }
    fun m4(n: String, m: FloatArray) { val l = loc(n); if (l >= 0) glUniformMatrix4fv(l, 1, false, m, 0) }
    fun release() = glDeleteProgram(id)
}

/**
 * Indexed triangle mesh with interleaved float attributes.
 * [layout] lists the component count of each attribute, bound to locations 0..n-1.
 */
class Mesh(vertices: FloatArray, indices: IntArray, layout: IntArray, dynamic: Boolean = false) {
    val vao = Gl.gen { n, a, o -> glGenVertexArrays(n, a, o) }
    val vbo = Gl.gen { n, a, o -> glGenBuffers(n, a, o) }
    val ibo = Gl.gen { n, a, o -> glGenBuffers(n, a, o) }
    var indexCount = indices.size
        private set

    init {
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, vertices.size * 4, Gl.floatBuffer(vertices), if (dynamic) GL_DYNAMIC_DRAW else GL_STATIC_DRAW)
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, Gl.intBuffer(indices), GL_STATIC_DRAW)
        val stride = layout.sum() * 4
        var off = 0
        for ((loc, comps) in layout.withIndex()) {
            glEnableVertexAttribArray(loc)
            glVertexAttribPointer(loc, comps, GL_FLOAT, false, stride, off)
            off += comps * 4
        }
        glBindVertexArray(0)
    }

    fun draw(firstIndex: Int = 0, count: Int = indexCount) {
        if (count <= 0) return
        glBindVertexArray(vao)
        glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_INT, firstIndex * 4)
    }

    fun release() {
        glDeleteVertexArrays(1, intArrayOf(vao), 0)
        glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
    }
}

/** Growable float list without boxing. */
class FloatList(cap: Int = 1024) {
    var data = FloatArray(cap)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun add3(a: Float, b: Float, c: Float) { add(a); add(b); add(c) }
    fun clear() { size = 0 }
    fun toArray(): FloatArray = data.copyOf(size)
}

class IntList(cap: Int = 1024) {
    var data = IntArray(cap)
    var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun add3(a: Int, b: Int, c: Int) { add(a); add(b); add(c) }
    fun clear() { size = 0 }
    fun toArray(): IntArray = data.copyOf(size)
}
