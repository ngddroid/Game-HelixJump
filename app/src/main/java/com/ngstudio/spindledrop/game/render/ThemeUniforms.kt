package com.ngstudio.spindledrop.game.render

import com.ngstudio.spindledrop.game.theme.Ambient
import com.ngstudio.spindledrop.game.theme.WorldTheme
import com.ngstudio.spindledrop.game.theme.rgb

/** Theme colours as float arrays, blendable for smooth world transitions (Endless mode). */
class ThemeUniforms {
    val skyTop = FloatArray(3); val skyBottom = FloatArray(3); val glow = FloatArray(3)
    val fog = FloatArray(3); val far = FloatArray(3); val mid = FloatArray(3); val near = FloatArray(3)
    val cityLights = FloatArray(3)
    val pillarA = FloatArray(3); val pillarB = FloatArray(3)
    val ringTop = FloatArray(3); val ringSide = FloatArray(3); val accent = FloatArray(3)
    val hazard = FloatArray(3); val hazardGlow = FloatArray(3)
    val crumble = FloatArray(3); val boost = FloatArray(3); val gate = FloatArray(3)
    val light = FloatArray(3); val ambSky = FloatArray(3); val ambGround = FloatArray(3)
    var sunX = 0.5f; var sunY = 0.8f; var sunSize = 0.08f
    var stars = 0f; var clouds = 0f; var gloss = 40f; var saturation = 1f
    var pattern = 1; var shape = 0
    var ambient = Ambient.SPARKLE

    private val all get() = listOf(skyTop, skyBottom, glow, fog, far, mid, near, cityLights, pillarA, pillarB, ringTop, ringSide, accent,
        hazard, hazardGlow, crumble, boost, gate, light, ambSky, ambGround)

    fun set(t: WorldTheme) {
        copy(skyTop, t.skyTop.rgb()); copy(skyBottom, t.skyBottom.rgb()); copy(glow, t.glow.rgb())
        copy(fog, t.fog.rgb()); copy(far, t.far.rgb()); copy(mid, t.mid.rgb()); copy(near, t.near.rgb())
        copy(cityLights, t.cityLights.rgb())
        copy(pillarA, t.pillarA.rgb()); copy(pillarB, t.pillarB.rgb())
        copy(ringTop, t.ringTop.rgb()); copy(ringSide, t.ringSide.rgb()); copy(accent, t.accent.rgb())
        copy(hazard, t.hazard.rgb()); copy(hazardGlow, t.hazardGlow.rgb())
        copy(crumble, t.crumble.rgb()); copy(boost, t.boost.rgb()); copy(gate, t.gate.rgb())
        copy(light, t.light.rgb()); copy(ambSky, t.ambSky.rgb()); copy(ambGround, t.ambGround.rgb())
        sunX = t.sunX; sunY = t.sunY; sunSize = t.sunSize; stars = t.stars; clouds = t.clouds
        gloss = t.gloss; saturation = t.saturation; pattern = t.pattern; shape = t.shape; ambient = t.ambient
    }

    /** this = lerp(a, b, k); discrete values switch at the midpoint. */
    fun blend(a: ThemeUniforms, b: ThemeUniforms, k: Float) {
        val dst = all; val sa = a.all; val sb = b.all
        for (i in dst.indices) for (c in 0..2) dst[i][c] = sa[i][c] + (sb[i][c] - sa[i][c]) * k
        sunX = lerp(a.sunX, b.sunX, k); sunY = lerp(a.sunY, b.sunY, k); sunSize = lerp(a.sunSize, b.sunSize, k)
        stars = lerp(a.stars, b.stars, k); clouds = lerp(a.clouds, b.clouds, k)
        gloss = lerp(a.gloss, b.gloss, k); saturation = lerp(a.saturation, b.saturation, k)
        val src = if (k < 0.5f) a else b
        pattern = src.pattern; shape = src.shape; ambient = src.ambient
    }

    fun copyFrom(o: ThemeUniforms) = blend(o, o, 0f)

    private fun copy(d: FloatArray, s: FloatArray) { d[0] = s[0]; d[1] = s[1]; d[2] = s[2] }
    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k
}
