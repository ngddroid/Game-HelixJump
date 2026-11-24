package com.ngstudio.spindledrop.game.theme

import android.graphics.Color

enum class Rarity(val label: String, val color: Long) {
    COMMON("Common", 0xFF8FA3BF),
    RARE("Rare", 0xFF39B8FF),
    EPIC("Epic", 0xFFB45CFF),
    LEGENDARY("Legendary", 0xFFFFB020),
}

/** Price: coins, or gems when [gems] > 0. Zero = free/starter. */
data class Price(val coins: Int = 0, val gems: Int = 0) {
    val free get() = coins == 0 && gems == 0
}

data class BallSkin(
    val id: String,
    val name: String,
    val rarity: Rarity,
    val price: Price,
    val skin: Int,
    val c1: Int,
    val c2: Int,
    val c3: Int = Color.WHITE,
    val metal: Float = 0f,
    val emissive: Float = 0f,
    val splat: Int,
)

enum class TrailStyle { NONE, COMET, RAINBOW, FIRE, BUBBLES, STARS, HEARTS, NOTES, SMOKE, LIGHTNING }

data class TrailSkin(val id: String, val name: String, val rarity: Rarity, val price: Price, val style: TrailStyle, val color: Int)

data class SplatSkin(val id: String, val name: String, val rarity: Rarity, val price: Price, val tile: Int)

object Catalog {
    private fun c(hex: String) = Color.parseColor(hex)

    val balls: List<BallSkin> = listOf(
        BallSkin("bubblegum", "Bubblegum", Rarity.COMMON, Price(), 0, c("#FF4F9A"), c("#FFFFFF"), splat = c("#FF4F9A")),
        BallSkin("ocean", "Ocean Drop", Rarity.COMMON, Price(300), 0, c("#2F9BFF"), c("#FFFFFF"), splat = c("#2F9BFF")),
        BallSkin("lime", "Lime Pop", Rarity.COMMON, Price(300), 0, c("#7BE33B"), c("#FFFFFF"), splat = c("#6BD32F")),
        BallSkin("sunny", "Sunny", Rarity.COMMON, Price(300), 0, c("#FFC93C"), c("#FFFFFF"), splat = c("#FFB300")),
        BallSkin("grape", "Grape Soda", Rarity.COMMON, Price(400), 0, c("#8B5CFF"), c("#FFFFFF"), splat = c("#8B5CFF")),
        BallSkin("beach", "Beach Day", Rarity.RARE, Price(900), 1, c("#FF5A5A"), c("#FFFFFF"), splat = c("#FF5A5A")),
        BallSkin("swirl", "Candy Swirl", Rarity.RARE, Price(900), 2, c("#FF6FB5"), c("#FFFFFF"), splat = c("#FF6FB5")),
        BallSkin("polka", "Polka", Rarity.RARE, Price(900), 3, c("#2EC4B6"), c("#FFFFFF"), splat = c("#2EC4B6")),
        BallSkin("checker", "Checkmate", Rarity.RARE, Price(1200), 11, c("#1D1D2B"), c("#FFFFFF"), splat = c("#3A3A55")),
        BallSkin("smiley", "Sunny Smile", Rarity.RARE, Price(1200), 10, c("#FFD23F"), c("#3B2A1A"), c("#FF8A7A"), splat = c("#FFC21A")),
        BallSkin("sleepy", "Sleepyhead", Rarity.RARE, Price(1200), 19, c("#9AD8FF"), c("#22304A"), c("#FF9FB8"), splat = c("#7CC8FF")),
        BallSkin("moon", "Moon Rock", Rarity.RARE, Price(1500), 12, c("#C9CED8"), c("#8E95A3"), splat = c("#AEB5C2")),
        BallSkin("marble", "Marble", Rarity.RARE, Price(1500), 16, c("#F4F1EC"), c("#4A4A5A"), splat = c("#BFB9B0")),
        BallSkin("pearl", "Pearl", Rarity.EPIC, Price(2200), 18, c("#F7F2FF"), c("#FFFFFF"), splat = c("#E6DDF8")),
        BallSkin("chrome", "Chrome", Rarity.EPIC, Price(2500), 5, c("#E8ECF2"), c("#FFFFFF"), metal = 1f, splat = c("#B7C0CC")),
        BallSkin("gold", "Gold Rush", Rarity.EPIC, Price(2500), 5, c("#FFC53D"), c("#FFFFFF"), metal = 1f, splat = c("#FFB020")),
        BallSkin("donut", "Glazed Donut", Rarity.EPIC, Price(2800), 14, c("#FF8FC8"), c("#E0A060"), splat = c("#FF8FC8")),
        BallSkin("frost", "Frostbite", Rarity.EPIC, Price(3000), 7, c("#BFEFFF"), c("#3FA9FF"), splat = c("#8FD8FF")),
        BallSkin("lava", "Lava Core", Rarity.EPIC, Price(3000), 6, c("#2A1A18"), c("#FF6A1A"), splat = c("#FF6A1A")),
        BallSkin("disco", "Disco Night", Rarity.EPIC, Price(3500), 8, c("#D8DEEA"), c("#FFFFFF"), metal = 0.6f, splat = c("#C08CFF")),
        BallSkin("neon", "Neon Globe", Rarity.EPIC, Price(3500), 15, c("#14102B"), c("#34F5FF"), splat = c("#34F5FF")),
        BallSkin("planet", "Ringworld", Rarity.LEGENDARY, Price(gems = 60), 9, c("#FFB067"), c("#C96B3C"), splat = c("#FF9A4D")),
        BallSkin("galaxy", "Galaxy", Rarity.LEGENDARY, Price(6000), 4, c("#120A3A"), c("#5A2DB8"), c("#FF4FD8"), splat = c("#7B4DFF")),
        BallSkin("sun", "Solar Flare", Rarity.LEGENDARY, Price(6000), 13, c("#FFD23F"), c("#FF5A1F"), splat = c("#FF8A1F")),
        BallSkin("rainbow", "Prism", Rarity.LEGENDARY, Price(gems = 90), 17, c("#FFFFFF"), c("#FFFFFF"), splat = c("#FF5AA8")),
    )

    val trails: List<TrailSkin> = listOf(
        TrailSkin("none", "No Trail", Rarity.COMMON, Price(), TrailStyle.NONE, Color.WHITE),
        TrailSkin("comet", "Comet", Rarity.COMMON, Price(), TrailStyle.COMET, c("#FFFFFF")),
        TrailSkin("bubbles", "Bubbles", Rarity.RARE, Price(800), TrailStyle.BUBBLES, c("#9EE8FF")),
        TrailSkin("smoke", "Puff", Rarity.RARE, Price(800), TrailStyle.SMOKE, c("#E8E8F0")),
        TrailSkin("hearts", "Lovestruck", Rarity.RARE, Price(1200), TrailStyle.HEARTS, c("#FF5A8A")),
        TrailSkin("notes", "Melody", Rarity.RARE, Price(1200), TrailStyle.NOTES, c("#FFD36E")),
        TrailSkin("stars", "Stardust", Rarity.EPIC, Price(2000), TrailStyle.STARS, c("#FFE066")),
        TrailSkin("fire", "Afterburner", Rarity.EPIC, Price(2500), TrailStyle.FIRE, c("#FF7A1A")),
        TrailSkin("lightning", "Zap", Rarity.EPIC, Price(3000), TrailStyle.LIGHTNING, c("#7CF8FF")),
        TrailSkin("rainbow", "Rainbow Ribbon", Rarity.LEGENDARY, Price(gems = 50), TrailStyle.RAINBOW, Color.WHITE),
    )

    val splats: List<SplatSkin> = listOf(
        SplatSkin("paint", "Paint", Rarity.COMMON, Price(), 0),
        SplatSkin("ink", "Ink Blot", Rarity.COMMON, Price(400), 3),
        SplatSkin("star", "Star Stamp", Rarity.RARE, Price(800), 1),
        SplatSkin("heart", "Heart Stamp", Rarity.RARE, Price(800), 2),
        SplatSkin("paw", "Paw Print", Rarity.RARE, Price(1000), 4),
        SplatSkin("flower", "Blossom", Rarity.EPIC, Price(1500), 5),
        SplatSkin("bolt", "Bolt", Rarity.EPIC, Price(1500), 6),
        SplatSkin("pixel", "Pixel", Rarity.EPIC, Price(1800), 7),
    )

    /** Theme lock prices (worlds you have reached are free to lock). */
    fun themePrice(index: Int): Price = when {
        index < 3 -> Price(2000)
        index < 6 -> Price(3000)
        index < 9 -> Price(4000)
        else -> Price(5000)
    }

    fun ball(id: String) = balls.firstOrNull { it.id == id } ?: balls[0]
    fun trail(id: String) = trails.firstOrNull { it.id == id } ?: trails[1]
    fun splat(id: String) = splats.firstOrNull { it.id == id } ?: splats[0]

    val starterBalls = listOf("bubblegum", "ocean", "lime")
}
