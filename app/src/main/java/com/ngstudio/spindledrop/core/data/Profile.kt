package com.ngstudio.spindledrop.core.data

import android.util.Base64
import com.ngstudio.spindledrop.game.Quality
import com.ngstudio.spindledrop.game.level.Difficulty
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val music: Boolean = true,
    val sfx: Boolean = true,
    val vibration: Boolean = true,
    val musicVolume: Float = 0.7f,
    val sfxVolume: Float = 1f,
    val sensitivity: Float = 1f,
    val autoQuality: Boolean = true,
    val quality: Quality = Quality.MEDIUM,
    val highRefresh: Boolean = false,
)

data class Stats(
    val levelsCleared: Int = 0,
    val ringsPassed: Long = 0,
    val ringsSmashed: Long = 0,
    val bestCombo: Int = 0,
    val blazes: Int = 0,
    val coinsEarned: Long = 0,
    val playSeconds: Long = 0,
    val endlessBest: Int = 0,
    val deaths: Int = 0,
    val perfectLevels: Int = 0,
    val dailyChallenges: Int = 0,
    val gamesPlayed: Int = 0,
    val chestsOpened: Int = 0,
)

enum class MissionType(val title: String) {
    CLEAR_LEVELS("Clear %d levels"),
    PASS_RINGS("Drop through %d rings"),
    BLAZES("Ignite Blaze %d times"),
    COLLECT_COINS("Collect %d coins in runs"),
    SMASH("Smash %d rings"),
    STARS("Earn %d stars"),
    COMBO("Reach a %d-ring combo"),
    ENDLESS_DEPTH("Reach depth %d in Endless"),
}

data class Mission(val type: MissionType, val target: Int, val progress: Int = 0, val rewardCoins: Int, val rewardGems: Int, val claimed: Boolean = false) {
    val done get() = progress >= target
    val title get() = type.title.format(target)
}

data class DailyState(
    val loginDay: Long = -1,
    val streak: Int = 0,
    val claimedDay: Long = -1,
    val wheelDay: Long = -1,
    val missionsDay: Long = -1,
    val missions: List<Mission> = emptyList(),
    val challengeDoneDay: Long = -1,
)

/** Stars per level, copy-on-write. Index = level (1..10000). */
class StarBook(private val bytes: ByteArray = ByteArray(Difficulty.MAX_LEVEL + 1)) {
    operator fun get(level: Int): Int = if (level in 1..Difficulty.MAX_LEVEL) bytes[level].toInt() else 0
    fun with(level: Int, stars: Int): StarBook {
        if (stars <= get(level)) return this
        val c = bytes.copyOf(); c[level] = stars.toByte(); return StarBook(c)
    }
    val total: Int by lazy { bytes.sumOf { it.toInt() } }
    fun encode(): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    override fun equals(other: Any?) = other is StarBook && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    companion object {
        fun decode(s: String?): StarBook {
            if (s.isNullOrEmpty()) return StarBook()
            return try {
                val b = Base64.decode(s, Base64.NO_WRAP)
                StarBook(if (b.size == Difficulty.MAX_LEVEL + 1) b else b.copyOf(Difficulty.MAX_LEVEL + 1))
            } catch (e: Exception) { StarBook() }
        }
    }
}

data class PlayerProfile(
    val onboardingDone: Boolean = false,
    val name: String = "Player",
    val avatar: Int = 0,
    val coins: Int = 150,
    val gems: Int = 5,
    val maxCleared: Int = 0,
    val adUnlocked: Set<Int> = emptySet(),
    val stars: StarBook = StarBook(),
    val ownedBalls: Set<String> = setOf("bubblegum"),
    val ownedTrails: Set<String> = setOf("none", "comet"),
    val ownedSplats: Set<String> = setOf("paint"),
    val ownedThemes: Set<String> = emptySet(),
    val ball: String = "bubblegum",
    val trail: String = "comet",
    val splat: String = "paint",
    val lockedTheme: String? = null,
    val settings: Settings = Settings(),
    val stats: Stats = Stats(),
    val daily: DailyState = DailyState(),
    val achievementsClaimed: Set<String> = emptySet(),
    val trophyClaimed: Set<Int> = emptySet(),
    val seenTips: Set<String> = emptySet(),
    val chests: Int = 0,
) {
    /** Level the player should play next. */
    val currentLevel: Int get() = (maxCleared + 1).coerceAtMost(Difficulty.MAX_LEVEL)

    fun isUnlocked(level: Int): Boolean = level <= maxCleared + 1 || level in adUnlocked

    fun toJson(): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("onboardingDone", onboardingDone); o.put("name", name); o.put("avatar", avatar)
        o.put("coins", coins); o.put("gems", gems); o.put("maxCleared", maxCleared)
        o.put("adUnlocked", JSONArray(adUnlocked.toList())); o.put("stars", stars.encode())
        o.put("ownedBalls", JSONArray(ownedBalls.toList())); o.put("ownedTrails", JSONArray(ownedTrails.toList()))
        o.put("ownedSplats", JSONArray(ownedSplats.toList())); o.put("ownedThemes", JSONArray(ownedThemes.toList()))
        o.put("ball", ball); o.put("trail", trail); o.put("splat", splat); o.put("lockedTheme", lockedTheme ?: "")
        o.put("settings", JSONObject().apply {
            put("music", settings.music); put("sfx", settings.sfx); put("vibration", settings.vibration)
            put("musicVolume", settings.musicVolume.toDouble()); put("sfxVolume", settings.sfxVolume.toDouble())
            put("sensitivity", settings.sensitivity.toDouble()); put("autoQuality", settings.autoQuality)
            put("quality", settings.quality.name); put("highRefresh", settings.highRefresh)
        })
        o.put("stats", JSONObject().apply {
            put("levelsCleared", stats.levelsCleared); put("ringsPassed", stats.ringsPassed); put("ringsSmashed", stats.ringsSmashed)
            put("bestCombo", stats.bestCombo); put("blazes", stats.blazes); put("coinsEarned", stats.coinsEarned)
            put("playSeconds", stats.playSeconds); put("endlessBest", stats.endlessBest); put("deaths", stats.deaths)
            put("perfectLevels", stats.perfectLevels); put("dailyChallenges", stats.dailyChallenges)
            put("gamesPlayed", stats.gamesPlayed); put("chestsOpened", stats.chestsOpened)
        })
        o.put("daily", JSONObject().apply {
            put("loginDay", daily.loginDay); put("streak", daily.streak); put("claimedDay", daily.claimedDay)
            put("wheelDay", daily.wheelDay); put("missionsDay", daily.missionsDay); put("challengeDoneDay", daily.challengeDoneDay)
            put("missions", JSONArray().apply {
                daily.missions.forEach { m ->
                    put(JSONObject().apply {
                        put("type", m.type.name); put("target", m.target); put("progress", m.progress)
                        put("coins", m.rewardCoins); put("gems", m.rewardGems); put("claimed", m.claimed)
                    })
                }
            })
        })
        o.put("achievementsClaimed", JSONArray(achievementsClaimed.toList()))
        o.put("trophyClaimed", JSONArray(trophyClaimed.toList()))
        o.put("seenTips", JSONArray(seenTips.toList()))
        o.put("chests", chests)
        return o.toString()
    }

    companion object {
        private fun JSONObject.strSet(k: String): Set<String>? = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        private fun JSONObject.intSet(k: String): Set<Int>? = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }

        fun fromJson(json: String): PlayerProfile {
            val d = PlayerProfile()
            val o = JSONObject(json)
            val st = o.optJSONObject("settings")
            val sa = o.optJSONObject("stats")
            val dy = o.optJSONObject("daily")
            return PlayerProfile(
                onboardingDone = o.optBoolean("onboardingDone", false),
                name = o.optString("name", d.name),
                avatar = o.optInt("avatar", 0),
                coins = o.optInt("coins", d.coins),
                gems = o.optInt("gems", d.gems),
                maxCleared = o.optInt("maxCleared", 0),
                adUnlocked = o.intSet("adUnlocked") ?: emptySet(),
                stars = StarBook.decode(o.optString("stars")),
                ownedBalls = (o.strSet("ownedBalls") ?: d.ownedBalls) + "bubblegum",
                ownedTrails = (o.strSet("ownedTrails") ?: d.ownedTrails) + setOf("none", "comet"),
                ownedSplats = (o.strSet("ownedSplats") ?: d.ownedSplats) + "paint",
                ownedThemes = o.strSet("ownedThemes") ?: emptySet(),
                ball = o.optString("ball", d.ball), trail = o.optString("trail", d.trail), splat = o.optString("splat", d.splat),
                lockedTheme = o.optString("lockedTheme", "").ifEmpty { null },
                settings = if (st == null) Settings() else Settings(
                    music = st.optBoolean("music", true), sfx = st.optBoolean("sfx", true), vibration = st.optBoolean("vibration", true),
                    musicVolume = st.optDouble("musicVolume", 0.7).toFloat(), sfxVolume = st.optDouble("sfxVolume", 1.0).toFloat(),
                    sensitivity = st.optDouble("sensitivity", 1.0).toFloat(), autoQuality = st.optBoolean("autoQuality", true),
                    quality = runCatching { Quality.valueOf(st.optString("quality", "MEDIUM")) }.getOrDefault(Quality.MEDIUM),
                    highRefresh = st.optBoolean("highRefresh", false),
                ),
                stats = if (sa == null) Stats() else Stats(
                    levelsCleared = sa.optInt("levelsCleared"), ringsPassed = sa.optLong("ringsPassed"), ringsSmashed = sa.optLong("ringsSmashed"),
                    bestCombo = sa.optInt("bestCombo"), blazes = sa.optInt("blazes"), coinsEarned = sa.optLong("coinsEarned"),
                    playSeconds = sa.optLong("playSeconds"), endlessBest = sa.optInt("endlessBest"), deaths = sa.optInt("deaths"),
                    perfectLevels = sa.optInt("perfectLevels"), dailyChallenges = sa.optInt("dailyChallenges"),
                    gamesPlayed = sa.optInt("gamesPlayed"), chestsOpened = sa.optInt("chestsOpened"),
                ),
                daily = if (dy == null) DailyState() else DailyState(
                    loginDay = dy.optLong("loginDay", -1), streak = dy.optInt("streak", 0), claimedDay = dy.optLong("claimedDay", -1),
                    wheelDay = dy.optLong("wheelDay", -1), missionsDay = dy.optLong("missionsDay", -1),
                    challengeDoneDay = dy.optLong("challengeDoneDay", -1),
                    missions = dy.optJSONArray("missions")?.let { a ->
                        (0 until a.length()).mapNotNull { i ->
                            val m = a.getJSONObject(i)
                            runCatching {
                                Mission(MissionType.valueOf(m.getString("type")), m.getInt("target"), m.optInt("progress"),
                                    m.optInt("coins"), m.optInt("gems"), m.optBoolean("claimed"))
                            }.getOrNull()
                        }
                    } ?: emptyList(),
                ),
                achievementsClaimed = o.strSet("achievementsClaimed") ?: emptySet(),
                trophyClaimed = o.intSet("trophyClaimed") ?: emptySet(),
                seenTips = o.strSet("seenTips") ?: emptySet(),
                chests = o.optInt("chests", 0),
            )
        }
    }
}
