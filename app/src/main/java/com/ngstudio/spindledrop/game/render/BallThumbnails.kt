package com.ngstudio.spindledrop.game.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30.*
import android.opengl.Matrix
import android.util.Log
import com.ngstudio.spindledrop.game.theme.BallSkin
import com.ngstudio.spindledrop.game.theme.Catalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders real 3D previews of every ball skin with the in-game shader on a background
 * EGL context, and caches them as PNGs. Used by the shop, onboarding and profile.
 */
object BallThumbnails {
    private const val VERSION = 3
    private const val RENDER = 320
    private const val OUT = 192
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    @Volatile private var started = false

    fun get(id: String): Bitmap? = cache[id]

    fun start(context: Context) {
        if (started) return
        started = true
        val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
        Thread({
            // Load cached files first (fast).
            val missing = ArrayList<BallSkin>()
            for (b in Catalog.balls) {
                val f = File(dir, "v${VERSION}_${b.id}.png")
                val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                if (bmp != null) cache[b.id] = bmp else missing += b
            }
            _version.value++
            if (missing.isNotEmpty()) render(missing, dir)
        }, "BallThumbnails").start()
    }

    private fun render(balls: List<BallSkin>, dir: File) {
        var display: EGLDisplay? = null
        var ctx: EGLContext? = null
        var surf: EGLSurface? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(display, ver, 0, ver, 1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
            if (num[0] == 0) return
            ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            surf = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surf, surf, ctx)

            val prog = Program(Shaders.BALL_VS, Shaders.BALL_FS, "thumb")
            val sphere = MeshFactory.sphere(4)
            val fbo = Gl.gen { n, a, o -> glGenFramebuffers(n, a, o) }
            val tex = Gl.gen { n, a, o -> glGenTextures(n, a, o) }
            glBindTexture(GL_TEXTURE_2D, tex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, RENDER, RENDER, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            val depth = Gl.gen { n, a, o -> glGenRenderbuffers(n, a, o) }
            glBindRenderbuffer(GL_RENDERBUFFER, depth)
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, RENDER, RENDER)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth)
            glBindTexture(GL_TEXTURE_2D, 0)

            val proj = FloatArray(16); val view = FloatArray(16); val vp = FloatArray(16); val model = FloatArray(16)
            Matrix.perspectiveM(proj, 0, 28f, 1f, 0.5f, 20f)
            Matrix.setLookAtM(view, 0, 0f, 0.6f, 4.6f, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            Matrix.setIdentityM(model, 0)
            val buf = ByteBuffer.allocateDirect(RENDER * RENDER * 4).order(ByteOrder.nativeOrder())
            val px = IntArray(RENDER * RENDER)
            val l = floatArrayOf(0.35f, 1.0f, 0.55f).let { val n = kotlin.math.sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]); floatArrayOf(it[0] / n, it[1] / n, it[2] / n) }

            for (b in balls) {
                glViewport(0, 0, RENDER, RENDER)
                glClearColor(0f, 0f, 0f, 0f)
                glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
                glEnable(GL_DEPTH_TEST)
                glEnable(GL_CULL_FACE)
                prog.use()
                prog.v3("uLightDir", l)
                prog.v3("uLightColor", 1f, 0.98f, 0.95f)
                prog.v3("uAmbSky", 0.55f, 0.55f, 0.7f)
                prog.v3("uAmbGround", 0.35f, 0.25f, 0.4f)
                prog.v3("uCamPos", 0f, 0.6f, 4.6f)
                prog.v3("uFogColor", 0f, 0f, 0f)
                prog.v2("uFog", 100f, 200f)
                prog.f("uFocusY", 0f)
                prog.f("uShadowOn", 0f)
                prog.f("uTime", 1.3f)
                prog.m4("uViewProj", vp)
                prog.m4("uLightVP", model)
                prog.m4("uModel", model)
                val rot = FloatArray(9)
                if (b.skin == 10 || b.skin == 19) { rot[0] = 1f; rot[4] = 1f; rot[8] = 1f } else {
                    val m = FloatArray(16); Matrix.setRotateM(m, 0, 25f, 1f, 0.4f, 0f)
                    rot[0] = m[0]; rot[1] = m[1]; rot[2] = m[2]; rot[3] = m[4]; rot[4] = m[5]; rot[5] = m[6]; rot[6] = m[8]; rot[7] = m[9]; rot[8] = m[10]
                }
                glUniformMatrix3fv(prog.loc("uObjRot"), 1, false, rot, 0)
                prog.i("uSkin", b.skin)
                prog.v3("uC1", Color.red(b.c1) / 255f, Color.green(b.c1) / 255f, Color.blue(b.c1) / 255f)
                prog.v3("uC2", Color.red(b.c2) / 255f, Color.green(b.c2) / 255f, Color.blue(b.c2) / 255f)
                prog.v3("uC3", Color.red(b.c3) / 255f, Color.green(b.c3) / 255f, Color.blue(b.c3) / 255f)
                prog.f("uMetal", b.metal); prog.f("uEmis", b.emissive); prog.f("uBlaze", 0f); prog.f("uFlash", 0f)
                prog.f("uForceAlpha", 1f)
                sphere.draw()
                glFinish()
                buf.position(0)
                glReadPixels(0, 0, RENDER, RENDER, GL_RGBA, GL_UNSIGNED_BYTE, buf)
                buf.position(0)
                // RGBA bytes, bottom-up -> ARGB ints, top-down.
                for (y in 0 until RENDER) {
                    val row = (RENDER - 1 - y) * RENDER
                    for (x in 0 until RENDER) {
                        val r = buf.get().toInt() and 0xFF
                        val g = buf.get().toInt() and 0xFF
                        val bb = buf.get().toInt() and 0xFF
                        val a = buf.get().toInt() and 0xFF
                        px[row + x] = (a shl 24) or (r shl 16) or (g shl 8) or bb
                    }
                }
                val big = Bitmap.createBitmap(px, RENDER, RENDER, Bitmap.Config.ARGB_8888)
                val small = Bitmap.createScaledBitmap(big, OUT, OUT, true)
                big.recycle()
                cache[b.id] = small
                runCatching { FileOutputStream(File(dir, "v${VERSION}_${b.id}.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                _version.value++
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
        } catch (t: Throwable) {
            Log.w(Gl.TAG, "Thumbnail rendering failed", t)
        } finally {
            if (display != null) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                surf?.let { EGL14.eglDestroySurface(display, it) }
                ctx?.let { EGL14.eglDestroyContext(display, it) }
            }
        }
    }
}
