package com.ngstudio.spindledrop.game.render

import android.opengl.GLES30.*
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/** Offscreen scene target (optionally multisampled) + bloom chain + final composite to the screen. */
class PostFx {
    private var screenW = 1
    private var screenH = 1
    var renderW = 1
        private set
    var renderH = 1
        private set
    private var samples = 0
    var bloomEnabled = true
        private set

    private var msaaFbo = 0
    private var msaaColor = 0
    private var msaaDepth = 0
    private var sceneFbo = 0
    private var sceneTex = 0
    private var sceneDepth = 0
    private var halfFbo = 0
    private var halfTex = 0
    private var blurFboA = 0
    private var blurTexA = 0
    private var blurFboB = 0
    private var blurTexB = 0
    private var bw = 1
    private var bh = 1
    private var blackTex = 0

    private var skyFbo = 0
    private var skyTex = 0
    private var skyW = 1
    private var skyH = 1
    var fxaa = true
    private lateinit var copy: Program
    private lateinit var bright: Program
    private lateinit var blur: Program
    private lateinit var composite: Program
    private var emptyVao = 0

    fun init() {
        bright = Program(Shaders.FULLSCREEN_VS, Shaders.BRIGHT_FS, "bright")
        blur = Program(Shaders.FULLSCREEN_VS, Shaders.BLUR_FS, "blur")
        composite = Program(Shaders.FULLSCREEN_VS, Shaders.COMPOSITE_FS, "composite")
        copy = Program(Shaders.FULLSCREEN_VS, Shaders.COPY_FS, "copy")
        emptyVao = Gl.gen { n, a, o -> glGenVertexArrays(n, a, o) }
        blackTex = colorTex(1, 1)
    }

    fun resize(w: Int, h: Int, scale: Float, wantSamples: Int, bloom: Boolean, useFxaa: Boolean) {
        release()
        fxaa = useFxaa
        screenW = w; screenH = h
        renderW = max(1, (w * scale).toInt()); renderH = max(1, (h * scale).toInt())
        val maxS = IntArray(1); glGetIntegerv(GL_MAX_SAMPLES, maxS, 0)
        samples = min(wantSamples, maxS[0])
        bloomEnabled = bloom

        sceneTex = colorTex(renderW, renderH)
        sceneFbo = Gl.gen { n, a, o -> glGenFramebuffers(n, a, o) }
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sceneTex, 0)
        if (samples == 0) {
            sceneDepth = Gl.gen { n, a, o -> glGenRenderbuffers(n, a, o) }
            glBindRenderbuffer(GL_RENDERBUFFER, sceneDepth)
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, renderW, renderH)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, sceneDepth)
        }
        checkFbo("scene")
        if (samples > 0) {
            msaaFbo = Gl.gen { n, a, o -> glGenFramebuffers(n, a, o) }
            glBindFramebuffer(GL_FRAMEBUFFER, msaaFbo)
            msaaColor = Gl.gen { n, a, o -> glGenRenderbuffers(n, a, o) }
            glBindRenderbuffer(GL_RENDERBUFFER, msaaColor)
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, renderW, renderH)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, msaaColor)
            msaaDepth = Gl.gen { n, a, o -> glGenRenderbuffers(n, a, o) }
            glBindRenderbuffer(GL_RENDERBUFFER, msaaDepth)
            glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_DEPTH_COMPONENT24, renderW, renderH)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, msaaDepth)
            if (!checkFbo("msaa")) {
                // Fall back to no MSAA.
                samples = 0
                glDeleteFramebuffers(1, intArrayOf(msaaFbo), 0); msaaFbo = 0
                glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo)
                sceneDepth = Gl.gen { n, a, o -> glGenRenderbuffers(n, a, o) }
                glBindRenderbuffer(GL_RENDERBUFFER, sceneDepth)
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, renderW, renderH)
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, sceneDepth)
            }
        }
        skyW = max(1, renderW / 2); skyH = max(1, renderH / 2)
        skyTex = colorTex(skyW, skyH)
        skyFbo = fboFor(skyTex)
        if (bloom) {
            val hw = max(1, renderW / 2); val hh = max(1, renderH / 2)
            halfTex = colorTex(hw, hh)
            halfFbo = fboFor(halfTex)
            bw = max(1, renderW / 4); bh = max(1, renderH / 4)
            blurTexA = colorTex(bw, bh); blurFboA = fboFor(blurTexA)
            blurTexB = colorTex(bw, bh); blurFboB = fboFor(blurTexB)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    private fun colorTex(w: Int, h: Int): Int {
        val t = Gl.gen { n, a, o -> glGenTextures(n, a, o) }
        glBindTexture(GL_TEXTURE_2D, t)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return t
    }

    private fun fboFor(tex: Int): Int {
        val f = Gl.gen { n, a, o -> glGenFramebuffers(n, a, o) }
        glBindFramebuffer(GL_FRAMEBUFFER, f)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        checkFbo("bloom")
        return f
    }

    private fun checkFbo(name: String): Boolean {
        val s = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (s != GL_FRAMEBUFFER_COMPLETE) {
            Log.w(Gl.TAG, "FBO $name incomplete: 0x${Integer.toHexString(s)}")
            return false
        }
        return true
    }

    /** Low-resolution target for the (expensive, smooth) procedural sky. */
    fun beginSky() {
        glBindFramebuffer(GL_FRAMEBUFFER, skyFbo)
        glViewport(0, 0, skyW, skyH)
    }

    /** Draws the low-res sky into the current scene target (call right after [beginScene]). */
    fun drawSkyIntoScene() {
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        glDisable(GL_BLEND)
        copy.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, skyTex)
        copy.i("uTex", 0)
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindVertexArray(0)
        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_CULL_FACE)
    }

    fun beginScene(clearR: Float, clearG: Float, clearB: Float) {
        glBindFramebuffer(GL_FRAMEBUFFER, if (samples > 0) msaaFbo else sceneFbo)
        glViewport(0, 0, renderW, renderH)
        glClearColor(clearR, clearG, clearB, 0f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
    }

    fun endScene(bloomStrength: Float, vignette: Float, saturation: Float, flash: Float, flashR: Float, flashG: Float, flashB: Float, chroma: Float) {
        if (samples > 0) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, msaaFbo)
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sceneFbo)
            glBlitFramebuffer(0, 0, renderW, renderH, 0, 0, renderW, renderH, GL_COLOR_BUFFER_BIT, GL_NEAREST)
            // Depth is not needed after the scene pass: tell the tiler not to write it back.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0)
        }
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glDisable(GL_CULL_FACE)
        glBindVertexArray(emptyVao)
        var bloomTex = blackTex
        if (bloomEnabled) {
            // Bright pass + downsample to half.
            glBindFramebuffer(GL_FRAMEBUFFER, halfFbo)
            glViewport(0, 0, max(1, renderW / 2), max(1, renderH / 2))
            bright.use()
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sceneTex)
            bright.i("uTex", 0)
            bright.v2("uTexel", 1f / renderW, 1f / renderH)
            glDrawArrays(GL_TRIANGLES, 0, 3)
            // Blur at quarter resolution (half -> A horizontal, A -> B vertical, x2).
            blur.use()
            blur.i("uTex", 0)
            glViewport(0, 0, bw, bh)
            var src = halfTex
            repeat(2) { iter ->
                glBindFramebuffer(GL_FRAMEBUFFER, blurFboA)
                glBindTexture(GL_TEXTURE_2D, src)
                blur.v2("uDir", (1.0f + iter) / bw, 0f)
                glDrawArrays(GL_TRIANGLES, 0, 3)
                glBindFramebuffer(GL_FRAMEBUFFER, blurFboB)
                glBindTexture(GL_TEXTURE_2D, blurTexA)
                blur.v2("uDir", 0f, (1.0f + iter) / bh)
                glDrawArrays(GL_TRIANGLES, 0, 3)
                src = blurTexB
            }
            bloomTex = blurTexB
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenW, screenH)
        composite.use()
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sceneTex)
        composite.i("uScene", 0)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, bloomTex)
        composite.i("uBloomTex", 1)
        composite.f("uBloom", if (bloomEnabled) bloomStrength else 0f)
        composite.f("uVignette", vignette)
        composite.f("uSaturation", saturation)
        composite.f("uFlash", flash)
        composite.v3("uFlashColor", flashR, flashG, flashB)
        composite.f("uChroma", chroma)
        composite.f("uFxaa", if (fxaa) 1f else 0f)
        composite.v2("uPx", 1f / renderW, 1f / renderH)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glActiveTexture(GL_TEXTURE0)
        glBindVertexArray(0)
    }

    fun release() {
        val fbos = intArrayOf(msaaFbo, sceneFbo, halfFbo, blurFboA, blurFboB, skyFbo).filter { it != 0 }.toIntArray()
        if (fbos.isNotEmpty()) glDeleteFramebuffers(fbos.size, fbos, 0)
        val rbs = intArrayOf(msaaColor, msaaDepth, sceneDepth).filter { it != 0 }.toIntArray()
        if (rbs.isNotEmpty()) glDeleteRenderbuffers(rbs.size, rbs, 0)
        val texs = intArrayOf(sceneTex, halfTex, blurTexA, blurTexB, skyTex).filter { it != 0 }.toIntArray()
        if (texs.isNotEmpty()) glDeleteTextures(texs.size, texs, 0)
        skyFbo = 0; skyTex = 0
        msaaFbo = 0; sceneFbo = 0; halfFbo = 0; blurFboA = 0; blurFboB = 0
        msaaColor = 0; msaaDepth = 0; sceneDepth = 0; sceneTex = 0; halfTex = 0; blurTexA = 0; blurTexB = 0
    }
}

/** Directional-light shadow map with hardware depth comparison (PCF). */
class ShadowMap(val size: Int) {
    val tex: Int = Gl.gen { n, a, o -> glGenTextures(n, a, o) }
    val fbo: Int

    init {
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL)
        fbo = Gl.gen { n, a, o -> glGenFramebuffers(n, a, o) }
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, tex, 0)
        glDrawBuffers(1, intArrayOf(GL_NONE), 0)
        glReadBuffer(GL_NONE)
        val s = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        if (s != GL_FRAMEBUFFER_COMPLETE) Log.w(Gl.TAG, "Shadow FBO incomplete 0x${Integer.toHexString(s)}")
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    fun begin() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, size, size)
        glClear(GL_DEPTH_BUFFER_BIT)
    }

    fun release() {
        glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        glDeleteTextures(1, intArrayOf(tex), 0)
    }
}
