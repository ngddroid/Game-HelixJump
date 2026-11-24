package com.ngstudio.spindledrop.game.theme

import android.graphics.Color
import com.ngstudio.spindledrop.game.level.Difficulty

/** Backdrop silhouette shapes understood by the sky shader. */
object Shape {
    const val MOUNTAINS = 0
    const val CITY = 1
    const val DUNES = 2
    const val SPIRES = 3
    const val CORAL = 4
    const val CLOUDS = 5
    const val RUINS = 6
    const val PALMS = 7
}

/** Ambient particle styles. */
enum class Ambient { SPARKLE, RAIN, SNOW, EMBERS, LEAVES, BUBBLES, SAND, STARS, PETALS, GLINTS, CONFETTI, FIREFLIES }

/** One of the 12 worlds. All colours are packed ARGB ints. */
data class WorldTheme(
    val id: String,
    val name: String,
    val skyTop: Int,
    val skyBottom: Int,
    val glow: Int,
    val sunX: Float,
    val sunY: Float,
    val sunSize: Float,
    val fog: Int,
    val far: Int,
    val mid: Int,
    val near: Int,
    val shape: Int,
    val stars: Float,
    val clouds: Float,
    val cityLights: Int,
    val pillarA: Int,
    val pillarB: Int,
    val ringTop: Int,
    val ringSide: Int,
    val accent: Int,
    val pattern: Int,
    val gloss: Float,
    val hazard: Int,
    val hazardGlow: Int,
    val crumble: Int,
    val boost: Int,
    val gate: Int,
    val light: Int,
    val ambSky: Int,
    val ambGround: Int,
    val ambient: Ambient,
    val saturation: Float = 1.06f,
    val music: String,
    /** UI gradient for cards and the home background. */
    val uiA: Int,
    val uiB: Int,
)

object Worlds {
    private fun c(hex: String) = Color.parseColor(hex)

    val all: List<WorldTheme> = listOf(
        WorldTheme(
            id = "candy", name = "Candy Clouds",
            skyTop = c("#7FC8FF"), skyBottom = c("#FFD6EC"), glow = c("#FFF3B0"), sunX = 0.78f, sunY = 0.86f, sunSize = 0.07f,
            fog = c("#FFDCEF"), far = c("#FFC2DE"), mid = c("#FFE4F1"), near = c("#FFFFFF"), shape = Shape.CLOUDS, stars = 0f, clouds = 0.6f, cityLights = c("#000000"),
            pillarA = c("#FFF4E6"), pillarB = c("#FF9CC8"), ringTop = c("#FF7EB6"), ringSide = c("#E0579A"), accent = c("#FFFFFF"), pattern = 1, gloss = 48f,
            hazard = c("#3B2A8F"), hazardGlow = c("#7B5CFF"), crumble = c("#F7D7A8"), boost = c("#36E2C8"), gate = c("#B08CFF"),
            light = c("#FFF6EE"), ambSky = c("#D9ECFF"), ambGround = c("#FFC7DF"), ambient = Ambient.SPARKLE, music = "music_play_a",
            uiA = c("#FF7EB6"), uiB = c("#7FC8FF"),
        ),
        WorldTheme(
            id = "neon", name = "Neon Metro",
            skyTop = c("#120A3A"), skyBottom = c("#4A1A7A"), glow = c("#FF4FD8"), sunX = 0.25f, sunY = 0.8f, sunSize = 0.09f,
            fog = c("#2A1059"), far = c("#2C1463"), mid = c("#1B0C40"), near = c("#0B0522"), shape = Shape.CITY, stars = 0.6f, clouds = 0f, cityLights = c("#FFD36E"),
            pillarA = c("#2A2350"), pillarB = c("#3C2F73"), ringTop = c("#2B2660"), ringSide = c("#1A163D"), accent = c("#34F5FF"), pattern = 2, gloss = 80f,
            hazard = c("#FF2E88"), hazardGlow = c("#FF3FA0"), crumble = c("#5A4E9A"), boost = c("#7CFF6B"), gate = c("#3A3170"),
            light = c("#D6D2FF"), ambSky = c("#6B5CD6"), ambGround = c("#2A1452"), ambient = Ambient.RAIN, saturation = 1.12f, music = "music_play_b",
            uiA = c("#3A1C8C"), uiB = c("#FF3FA0"),
        ),
        WorldTheme(
            id = "frost", name = "Frost Peak",
            skyTop = c("#5B8CFF"), skyBottom = c("#CFF3FF"), glow = c("#FFFFFF"), sunX = 0.7f, sunY = 0.9f, sunSize = 0.06f,
            fog = c("#DDF4FF"), far = c("#A9CCF0"), mid = c("#D8ECFA"), near = c("#F4FBFF"), shape = Shape.MOUNTAINS, stars = 0f, clouds = 0.35f, cityLights = c("#000000"),
            pillarA = c("#E8F6FF"), pillarB = c("#9FD4F5"), ringTop = c("#BDE8FF"), ringSide = c("#7DBDE6"), accent = c("#FFFFFF"), pattern = 3, gloss = 90f,
            hazard = c("#FF5A4F"), hazardGlow = c("#FF7A5C"), crumble = c("#E6EEF5"), boost = c("#FFB13B"), gate = c("#8FB4D8"),
            light = c("#FFFFFF"), ambSky = c("#CBE6FF"), ambGround = c("#A3C7E8"), ambient = Ambient.SNOW, music = "music_play_b",
            uiA = c("#5B8CFF"), uiB = c("#9DE7FF"),
        ),
        WorldTheme(
            id = "lava", name = "Lava Forge",
            skyTop = c("#1E0B12"), skyBottom = c("#7A1E12"), glow = c("#FF7A2A"), sunX = 0.3f, sunY = 0.42f, sunSize = 0.12f,
            fog = c("#3D140F"), far = c("#4A1712"), mid = c("#2B0D0B"), near = c("#140606"), shape = Shape.MOUNTAINS, stars = 0.2f, clouds = 0f, cityLights = c("#000000"),
            pillarA = c("#3A2A26"), pillarB = c("#56372C"), ringTop = c("#3F3533"), ringSide = c("#2A2120"), accent = c("#FF7A1A"), pattern = 4, gloss = 20f,
            hazard = c("#FFB020"), hazardGlow = c("#FF7A00"), crumble = c("#6B5048"), boost = c("#4FE3FF"), gate = c("#4A3A36"),
            light = c("#FFD2B0"), ambSky = c("#B0503A"), ambGround = c("#3B1208"), ambient = Ambient.EMBERS, saturation = 1.1f, music = "music_play_a",
            uiA = c("#7A1E12"), uiB = c("#FF7A1A"),
        ),
        WorldTheme(
            id = "jungle", name = "Jungle Ruins",
            skyTop = c("#4FB6A0"), skyBottom = c("#E6F5C8"), glow = c("#FFF6C2"), sunX = 0.8f, sunY = 0.8f, sunSize = 0.07f,
            fog = c("#CFE9C0"), far = c("#7DBE9A"), mid = c("#4E9A70"), near = c("#2B6A47"), shape = Shape.RUINS, stars = 0f, clouds = 0.25f, cityLights = c("#000000"),
            pillarA = c("#BFB49A"), pillarB = c("#8F8670"), ringTop = c("#B8AE96"), ringSide = c("#8A816C"), accent = c("#5FAE48"), pattern = 5, gloss = 12f,
            hazard = c("#E9452F"), hazardGlow = c("#FF6B3D"), crumble = c("#D7C49A"), boost = c("#3FD0FF"), gate = c("#7E7764"),
            light = c("#FFF8E6"), ambSky = c("#CDEBD6"), ambGround = c("#7E9A6A"), ambient = Ambient.LEAVES, music = "music_play_b",
            uiA = c("#2B8A6A"), uiB = c("#A8D86B"),
        ),
        WorldTheme(
            id = "reef", name = "Coral Reef",
            skyTop = c("#0E4FA8"), skyBottom = c("#3CD2E6"), glow = c("#BFFFF8"), sunX = 0.5f, sunY = 0.97f, sunSize = 0.1f,
            fog = c("#2AA3C9"), far = c("#1A7FB0"), mid = c("#156A98"), near = c("#0C4B73"), shape = Shape.CORAL, stars = 0f, clouds = 0f, cityLights = c("#000000"),
            pillarA = c("#FFE7D6"), pillarB = c("#FFB59A"), ringTop = c("#FFD8C4"), ringSide = c("#F0A58C"), accent = c("#FFFFFF"), pattern = 6, gloss = 50f,
            hazard = c("#7A1FD1"), hazardGlow = c("#B05CFF"), crumble = c("#F3E2C0"), boost = c("#FFE14D"), gate = c("#C98F7A"),
            light = c("#E6FFFF"), ambSky = c("#9EE8FF"), ambGround = c("#2F7DA8"), ambient = Ambient.BUBBLES, music = "music_play_b",
            uiA = c("#0E4FA8"), uiB = c("#3CD2E6"),
        ),
        WorldTheme(
            id = "desert", name = "Desert Mirage",
            skyTop = c("#FF8A5C"), skyBottom = c("#FFE1A8"), glow = c("#FFF0B0"), sunX = 0.62f, sunY = 0.58f, sunSize = 0.11f,
            fog = c("#FFD9A0"), far = c("#E8A86C"), mid = c("#D98B52"), near = c("#B4683A"), shape = Shape.DUNES, stars = 0f, clouds = 0.15f, cityLights = c("#000000"),
            pillarA = c("#F2D2A2"), pillarB = c("#DDAE72"), ringTop = c("#EBC28A"), ringSide = c("#C9955C"), accent = c("#FFFFFF"), pattern = 7, gloss = 10f,
            hazard = c("#2F7BFF"), hazardGlow = c("#4FA0FF"), crumble = c("#F7E3BE"), boost = c("#3DE08A"), gate = c("#B48A5A"),
            light = c("#FFF1DC"), ambSky = c("#FFD9B0"), ambGround = c("#C98A52"), ambient = Ambient.SAND, music = "music_play_a",
            uiA = c("#FF8A5C"), uiB = c("#FFC46B"),
        ),
        WorldTheme(
            id = "cosmic", name = "Cosmic Station",
            skyTop = c("#05051A"), skyBottom = c("#1D1450"), glow = c("#8FA8FF"), sunX = 0.75f, sunY = 0.72f, sunSize = 0.16f,
            fog = c("#120E35"), far = c("#141040"), mid = c("#0D0A2C"), near = c("#07051A"), shape = Shape.SPIRES, stars = 1f, clouds = 0f, cityLights = c("#6FE7FF"),
            pillarA = c("#9AA3B8"), pillarB = c("#5E667A"), ringTop = c("#B8C0D4"), ringSide = c("#6F778C"), accent = c("#4FE0FF"), pattern = 8, gloss = 70f,
            hazard = c("#FF4060"), hazardGlow = c("#FF3355"), crumble = c("#8D86A8"), boost = c("#B8FF4F"), gate = c("#5A6178"),
            light = c("#E8EEFF"), ambSky = c("#6C7BD9"), ambGround = c("#24204F"), ambient = Ambient.STARS, saturation = 1.08f, music = "music_play_b",
            uiA = c("#1D1450"), uiB = c("#4F6BFF"),
        ),
        WorldTheme(
            id = "sakura", name = "Sakura Garden",
            skyTop = c("#B48CFF"), skyBottom = c("#FFD7E8"), glow = c("#FFFFFF"), sunX = 0.3f, sunY = 0.78f, sunSize = 0.08f,
            fog = c("#FADCEB"), far = c("#D9A6D6"), mid = c("#B983B8"), near = c("#7C4F86"), shape = Shape.MOUNTAINS, stars = 0f, clouds = 0.3f, cityLights = c("#000000"),
            pillarA = c("#3A1F2A"), pillarB = c("#5A2E3E"), ringTop = c("#C2333F"), ringSide = c("#8A1F2B"), accent = c("#FFD36E"), pattern = 9, gloss = 64f,
            hazard = c("#1F1A33"), hazardGlow = c("#6E5CFF"), crumble = c("#F1D6C6"), boost = c("#5CF0D2"), gate = c("#6A3A44"),
            light = c("#FFF3F7"), ambSky = c("#F3D6FF"), ambGround = c("#C98AA8"), ambient = Ambient.PETALS, music = "music_play_b",
            uiA = c("#B48CFF"), uiB = c("#FF9CC8"),
        ),
        WorldTheme(
            id = "crystal", name = "Crystal Cavern",
            skyTop = c("#0F1B3D"), skyBottom = c("#3C2A7A"), glow = c("#7CF8FF"), sunX = 0.5f, sunY = 0.95f, sunSize = 0.05f,
            fog = c("#24205A"), far = c("#2E2770"), mid = c("#1E1A52"), near = c("#110E33"), shape = Shape.SPIRES, stars = 0.5f, clouds = 0f, cityLights = c("#7CF8FF"),
            pillarA = c("#4C3D9A"), pillarB = c("#6F5BD1"), ringTop = c("#7A68E0"), ringSide = c("#4A3BA6"), accent = c("#7CF8FF"), pattern = 10, gloss = 110f,
            hazard = c("#FF5BA8"), hazardGlow = c("#FF4FB0"), crumble = c("#A497D8"), boost = c("#FFE14D"), gate = c("#3E3487"),
            light = c("#E6E8FF"), ambSky = c("#8C7CFF"), ambGround = c("#2A2066"), ambient = Ambient.GLINTS, saturation = 1.1f, music = "music_play_b",
            uiA = c("#3C2A7A"), uiB = c("#7CF8FF"),
        ),
        WorldTheme(
            id = "toy", name = "Toy Workshop",
            skyTop = c("#FFC93C"), skyBottom = c("#FFF1C4"), glow = c("#FFFFFF"), sunX = 0.2f, sunY = 0.88f, sunSize = 0.06f,
            fog = c("#FFF0C8"), far = c("#FFB84D"), mid = c("#FF8A3D"), near = c("#E85D3A"), shape = Shape.RUINS, stars = 0f, clouds = 0.2f, cityLights = c("#000000"),
            pillarA = c("#FFFFFF"), pillarB = c("#39B8FF"), ringTop = c("#39B8FF"), ringSide = c("#1F86D1"), accent = c("#FFFFFF"), pattern = 11, gloss = 36f,
            hazard = c("#FF3D5A"), hazardGlow = c("#FF5A6E"), crumble = c("#FFE7A8"), boost = c("#3DDC97"), gate = c("#7A5CFF"),
            light = c("#FFFFFF"), ambSky = c("#FFF2CC"), ambGround = c("#F2B65E"), ambient = Ambient.CONFETTI, music = "music_play_a",
            uiA = c("#FF8A3D"), uiB = c("#FFC93C"),
        ),
        WorldTheme(
            id = "tropic", name = "Tropic Sunset",
            skyTop = c("#3A2A8C"), skyBottom = c("#FF7A59"), glow = c("#FFD36E"), sunX = 0.5f, sunY = 0.33f, sunSize = 0.13f,
            fog = c("#E0627A"), far = c("#7B3C8C"), mid = c("#4E2470"), near = c("#2A1240"), shape = Shape.PALMS, stars = 0.25f, clouds = 0.2f, cityLights = c("#000000"),
            pillarA = c("#FFE2B8"), pillarB = c("#E8A36B"), ringTop = c("#3DDC97"), ringSide = c("#23A06D"), accent = c("#FFF3C4"), pattern = 12, gloss = 24f,
            hazard = c("#FF2E63"), hazardGlow = c("#FF4F7A"), crumble = c("#F5DCB8"), boost = c("#FFE14D"), gate = c("#2A6B5A"),
            light = c("#FFE6CC"), ambSky = c("#FFB08A"), ambGround = c("#5A2A6E"), ambient = Ambient.FIREFLIES, saturation = 1.08f, music = "music_play_a",
            uiA = c("#FF7A59"), uiB = c("#3A2A8C"),
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[0]

    /** World for a level, including the remix palette for later cycles. */
    fun forLevel(level: Int): WorldTheme {
        val base = all[Difficulty.worldIndex(level)]
        return remix(base, Difficulty.remixCycle(level))
    }

    val remixNames = listOf("", "Dusk", "Night", "Neon", "Dawn", "Storm", "Aurora", "Prism")

    fun displayName(level: Int): String {
        val cycle = Difficulty.remixCycle(level)
        val base = all[Difficulty.worldIndex(level)].name
        if (cycle == 0) return base
        val tag = remixNames[1 + (cycle - 1) % (remixNames.size - 1)]
        val roman = toRoman(1 + (cycle - 1) / (remixNames.size - 1))
        return if (roman == "I") "$base · $tag" else "$base · $tag $roman"
    }

    private fun toRoman(n: Int): String {
        val v = intArrayOf(10, 9, 5, 4, 1)
        val s = arrayOf("X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    /** Remixes shift the hue of sky/backdrop/rings and change time of day, keeping hazards readable. */
    fun remix(w: WorldTheme, cycle: Int): WorldTheme {
        if (cycle <= 0) return w
        val variant = (cycle - 1) % (remixNames.size - 1)
        val hueShift = when (variant) { 0 -> -18f; 1 -> 200f; 2 -> 120f; 3 -> 30f; 4 -> 160f; 5 -> 260f; else -> 300f } + (cycle / 8) * 23f
        val darken = when (variant) { 1 -> 0.55f; 4 -> 0.7f; 5 -> 0.6f; else -> 1f }
        val sat = when (variant) { 2 -> 1.25f; 6 -> 1.2f; else -> 1f }
        fun sh(col: Int, dark: Float = darken) = shift(col, hueShift, sat, dark)
        fun shRing(col: Int) = shift(col, hueShift * 0.6f, sat, 1f)
        return w.copy(
            skyTop = sh(w.skyTop), skyBottom = sh(w.skyBottom), glow = sh(w.glow, 1f), fog = sh(w.fog),
            far = sh(w.far), mid = sh(w.mid), near = sh(w.near),
            stars = if (variant == 1 || variant == 5) 1f else w.stars,
            pillarA = shRing(w.pillarA), pillarB = shRing(w.pillarB), ringTop = shRing(w.ringTop), ringSide = shRing(w.ringSide),
            accent = shRing(w.accent), ambSky = sh(w.ambSky, (darken + 1f) / 2f), ambGround = sh(w.ambGround),
            uiA = sh(w.uiA, 1f), uiB = sh(w.uiB, 1f),
        )
    }

    private fun shift(col: Int, hue: Float, sat: Float, value: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(col, hsv)
        hsv[0] = (hsv[0] + hue + 360f) % 360f
        hsv[1] = (hsv[1] * sat).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * value).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }
}

/** Unpacks an ARGB int into a float[3] RGB array (0..1). */
fun Int.rgb(): FloatArray = floatArrayOf(Color.red(this) / 255f, Color.green(this) / 255f, Color.blue(this) / 255f)
