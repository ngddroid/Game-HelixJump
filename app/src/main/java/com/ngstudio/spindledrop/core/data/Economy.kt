package com.ngstudio.spindledrop.core.data

import com.ngstudio.spindledrop.game.Mode
import com.ngstudio.spindledrop.game.RunResult
import com.ngstudio.spindledrop.game.level.Difficulty
import com.ngstudio.spindledrop.game.level.Rng
import com.ngstudio.spindledrop.game.theme.Catalog
import com.ngstudio.spindledrop.game.theme.Price
import com.ngstudio.spindledrop.game.theme.Rarity
import java.util.Calendar

/** All reward, mission, achievement and store rules in one place. */
object Economy {

    // ------------------------------------------------------------------ feature unlocks
    const val WHEEL_UNLOCK = 3
    const val ENDLESS_UNLOCK = 8
    const val DAILY_CHALLENGE_UNLOCK = 12
    const val REVIVE_COST = 120

    fun today(): Long {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }

    /** Consecutive day check that works across year boundaries. */
    private fun isNextDay(prev: Long, now: Long): Boolean {
        if (prev < 0) return false
        val c = Calendar.getInstance()
        c.set(Calendar.YEAR, (prev / 1000).toInt()); c.set(Calendar.DAY_OF_YEAR, (prev % 1000).toInt())
        c.add(Calendar.DAY_OF_YEAR, 1)
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR) == now
    }

    // ------------------------------------------------------------------ level rewards
    fun levelReward(level: Int, stars: Int): Int {
        val base = (25 + 2 * Difficulty.chapter(level)).coerceAtMost(150)
        return base + stars * 10 + if (Difficulty.isBoss(level)) 50 else 0
    }

    data class WinSummary(val baseCoins: Int, val runCoins: Int, val stars: Int, val firstClear: Boolean, val chest: Boolean, val gems: Int) {
        val total get() = baseCoins + runCoins
    }

    /** Applies a finished run to the profile and returns the updated profile + summary. */
    fun applyRun(p: PlayerProfile, r: RunResult, playSeconds: Int): Pair<PlayerProfile, WinSummary?> {
        var s = p.stats.copy(
            ringsPassed = p.stats.ringsPassed + r.ringsPassed,
            ringsSmashed = p.stats.ringsSmashed + r.ringsSmashed,
            bestCombo = maxOf(p.stats.bestCombo, r.bestCombo),
            blazes = p.stats.blazes + if (r.blazeTriggered) 1 else 0,
            coinsEarned = p.stats.coinsEarned + r.coins,
            playSeconds = p.stats.playSeconds + playSeconds,
            gamesPlayed = p.stats.gamesPlayed + 1,
            deaths = p.stats.deaths + if (r.won) 0 else 1,
        )
        var np = p
        var summary: WinSummary? = null
        when (r.mode) {
            Mode.LEVEL -> if (r.won) {
                val prevStars = p.stars[r.level]
                val first = prevStars == 0 && r.level > p.maxCleared
                val newStars = p.stars.with(r.level, r.stars)
                // Progress only advances sequentially; ad-unlocked levels further ahead keep their stars
                // and are absorbed once the player catches up to them.
                var newMax = p.maxCleared
                if (r.level == newMax + 1) {
                    newMax = r.level
                    while (newMax < Difficulty.MAX_LEVEL && newStars[newMax + 1] > 0) newMax++
                }
                val reward = if (first) levelReward(r.level, r.stars) else levelReward(r.level, r.stars) / 3
                val chest = first && Difficulty.isBoss(r.level)
                val gems = if (first && Difficulty.isBoss(r.level)) 2 else 0
                s = s.copy(
                    levelsCleared = s.levelsCleared + if (first) 1 else 0,
                    perfectLevels = s.perfectLevels + if (r.stars == 3 && prevStars < 3) 1 else 0,
                )
                np = np.copy(
                    maxCleared = newMax,
                    stars = newStars,
                    coins = p.coins + reward + r.coins,
                    gems = p.gems + gems,
                    chests = p.chests + if (chest) 1 else 0,
                )
                summary = WinSummary(reward, r.coins, r.stars, first, chest, gems)
            } else {
                np = np.copy(coins = p.coins + r.coins)
            }
            Mode.ENDLESS -> {
                s = s.copy(endlessBest = maxOf(s.endlessBest, r.depth))
                np = np.copy(coins = p.coins + r.coins)
            }
            Mode.DAILY -> {
                if (r.won) {
                    val firstToday = p.daily.challengeDoneDay != today()
                    s = s.copy(dailyChallenges = s.dailyChallenges + if (firstToday) 1 else 0)
                    val gems = if (firstToday) 5 else 0
                    val coins = if (firstToday) 250 else 50
                    np = np.copy(coins = p.coins + coins + r.coins, gems = p.gems + gems,
                        daily = p.daily.copy(challengeDoneDay = today()))
                    summary = WinSummary(coins, r.coins, r.stars, firstToday, false, gems)
                } else np = np.copy(coins = p.coins + r.coins)
            }
            Mode.PREVIEW -> Unit
        }
        np = np.copy(stats = s)
        np = progressMissions(np, r)
        return Pair(np, summary)
    }

    // ------------------------------------------------------------------ daily login (7-day calendar)
    val loginRewards = listOf(50, 75, 100, 150, 200, 300, 0) // day 7 = chest + gems

    /** Ensures today's login/missions state exists. */
    fun refreshDaily(p: PlayerProfile): PlayerProfile {
        val now = today()
        var d = p.daily
        if (d.loginDay != now) {
            val streak = if (isNextDay(d.loginDay, now)) (d.streak % 7) + 1 else 1
            d = d.copy(loginDay = now, streak = streak)
        }
        if (d.missionsDay != now) d = d.copy(missionsDay = now, missions = generateMissions(now, p.maxCleared))
        return if (d == p.daily) p else p.copy(daily = d)
    }

    fun canClaimLogin(p: PlayerProfile) = p.daily.claimedDay != today()

    fun claimLogin(p: PlayerProfile): Pair<PlayerProfile, String> {
        if (!canClaimLogin(p)) return Pair(p, "")
        val day = p.daily.streak.coerceIn(1, 7)
        return if (day == 7) {
            Pair(p.copy(gems = p.gems + 10, chests = p.chests + 1, daily = p.daily.copy(claimedDay = today())), "Chest + 10 gems")
        } else {
            val c = loginRewards[day - 1]
            Pair(p.copy(coins = p.coins + c, daily = p.daily.copy(claimedDay = today())), "$c coins")
        }
    }

    // ------------------------------------------------------------------ lucky wheel
    data class WheelPrize(val label: String, val coins: Int = 0, val gems: Int = 0, val chest: Boolean = false, val color: Long)

    val wheel = listOf(
        WheelPrize("50", coins = 50, color = 0xFFFF4F9A),
        WheelPrize("2", gems = 2, color = 0xFF39B8FF),
        WheelPrize("100", coins = 100, color = 0xFFFFC93C),
        WheelPrize("CHEST", chest = true, color = 0xFF8B5CFF),
        WheelPrize("75", coins = 75, color = 0xFF3DDC97),
        WheelPrize("5", gems = 5, color = 0xFF2F80ED),
        WheelPrize("250", coins = 250, color = 0xFFFF8A3D),
        WheelPrize("150", coins = 150, color = 0xFFE040FB),
    )
    private val wheelWeights = floatArrayOf(22f, 12f, 18f, 6f, 20f, 5f, 7f, 10f)

    fun canSpin(p: PlayerProfile) = p.maxCleared >= WHEEL_UNLOCK && p.daily.wheelDay != today()

    fun spinIndex(seed: Long): Int {
        val rng = Rng(seed)
        return rng.pickWeighted(wheel.indices.map { it to wheelWeights[it] })
    }

    fun applyPrize(p: PlayerProfile, prize: WheelPrize, markSpin: Boolean): PlayerProfile =
        p.copy(
            coins = p.coins + prize.coins, gems = p.gems + prize.gems, chests = p.chests + if (prize.chest) 1 else 0,
            daily = if (markSpin) p.daily.copy(wheelDay = today()) else p.daily,
        )

    // ------------------------------------------------------------------ missions
    private fun generateMissions(day: Long, maxCleared: Int): List<Mission> {
        val rng = Rng(Rng.mix(day, 0x515L))
        val scale = 1f + (maxCleared / 200f).coerceAtMost(2f)
        val pool = MissionType.entries.filter { it != MissionType.ENDLESS_DEPTH || maxCleared >= ENDLESS_UNLOCK }.shuffled(java.util.Random(rng.nextLong()))
        return pool.take(3).map { t ->
            val target = when (t) {
                MissionType.CLEAR_LEVELS -> 3
                MissionType.PASS_RINGS -> (120 * scale).toInt() / 10 * 10
                MissionType.BLAZES -> 4
                MissionType.COLLECT_COINS -> (60 * scale).toInt() / 10 * 10
                MissionType.SMASH -> (15 * scale).toInt()
                MissionType.STARS -> 6
                MissionType.COMBO -> 5
                MissionType.ENDLESS_DEPTH -> 40
            }
            val gems = if (rng.chance(0.25f)) 3 else 0
            Mission(t, target, 0, if (gems > 0) 0 else 100 + rng.nextInt(6) * 20, gems)
        }
    }

    private fun progressMissions(p: PlayerProfile, r: RunResult): PlayerProfile {
        if (p.daily.missions.isEmpty()) return p
        val ms = p.daily.missions.map { m ->
            if (m.claimed) return@map m
            val add = when (m.type) {
                MissionType.CLEAR_LEVELS -> if (r.won && r.mode == Mode.LEVEL) 1 else 0
                MissionType.PASS_RINGS -> r.ringsPassed
                MissionType.BLAZES -> if (r.blazeTriggered) 1 else 0
                MissionType.COLLECT_COINS -> r.coins
                MissionType.SMASH -> r.ringsSmashed
                MissionType.STARS -> if (r.won && r.mode == Mode.LEVEL) r.stars else 0
                MissionType.COMBO -> if (r.bestCombo >= m.target) m.target else 0
                MissionType.ENDLESS_DEPTH -> if (r.mode == Mode.ENDLESS && r.depth >= m.target) m.target else 0
            }
            val prog = if (m.type == MissionType.COMBO || m.type == MissionType.ENDLESS_DEPTH) maxOf(m.progress, add) else m.progress + add
            m.copy(progress = prog.coerceAtMost(m.target))
        }
        return p.copy(daily = p.daily.copy(missions = ms))
    }

    fun claimMission(p: PlayerProfile, index: Int): PlayerProfile {
        val m = p.daily.missions.getOrNull(index) ?: return p
        if (!m.done || m.claimed) return p
        val ms = p.daily.missions.toMutableList().also { it[index] = m.copy(claimed = true) }
        return p.copy(coins = p.coins + m.rewardCoins, gems = p.gems + m.rewardGems, daily = p.daily.copy(missions = ms))
    }

    // ------------------------------------------------------------------ achievements
    data class Achievement(val id: String, val title: String, val target: Long, val gems: Int, val value: (PlayerProfile) -> Long)

    val achievements: List<Achievement> = buildList {
        for ((i, n) in listOf(10, 50, 100, 250, 500, 1000, 2500, 5000, 10000).withIndex())
            add(Achievement("lv$n", "Clear $n levels", n.toLong(), 5 + i * 5) { it.stats.levelsCleared.toLong() })
        for ((i, n) in listOf(1000, 10000, 100000).withIndex())
            add(Achievement("rg$n", "Drop through ${"%,d".format(n)} rings", n.toLong(), 5 + i * 10) { it.stats.ringsPassed })
        for ((i, n) in listOf(5, 8, 12).withIndex())
            add(Achievement("cb$n", "Reach a $n-ring combo", n.toLong(), 5 + i * 10) { it.stats.bestCombo.toLong() })
        for ((i, n) in listOf(10, 100, 1000).withIndex())
            add(Achievement("bz$n", "Ignite Blaze $n times", n.toLong(), 5 + i * 10) { it.stats.blazes.toLong() })
        for ((i, n) in listOf(50, 150, 300).withIndex())
            add(Achievement("en$n", "Reach depth $n in Endless", n.toLong(), 5 + i * 10) { it.stats.endlessBest.toLong() })
        for ((i, n) in listOf(10, 100).withIndex())
            add(Achievement("pf$n", "Earn 3 stars on $n levels", n.toLong(), 10 + i * 15) { it.stats.perfectLevels.toLong() })
        for ((i, n) in listOf(7, 30).withIndex())
            add(Achievement("dc$n", "Win $n Daily Challenges", n.toLong(), 10 + i * 20) { it.stats.dailyChallenges.toLong() })
        add(Achievement("collector", "Own 10 balls", 10, 15) { it.ownedBalls.size.toLong() })
    }

    fun claimAchievement(p: PlayerProfile, a: Achievement): PlayerProfile {
        if (a.id in p.achievementsClaimed || a.value(p) < a.target) return p
        return p.copy(gems = p.gems + a.gems, achievementsClaimed = p.achievementsClaimed + a.id)
    }

    // ------------------------------------------------------------------ trophy road
    data class Milestone(val index: Int, val stars: Int, val label: String, val coins: Int = 0, val gems: Int = 0, val chest: Boolean = false)

    fun milestone(i: Int): Milestone {
        val stars = 15 + i * 15
        return when (i % 4) {
            0 -> Milestone(i, stars, "${200 + i * 20}", coins = 200 + i * 20)
            1 -> Milestone(i, stars, "${5 + i / 2}", gems = 5 + i / 2)
            2 -> Milestone(i, stars, "Chest", chest = true)
            else -> Milestone(i, stars, "${400 + i * 30}", coins = 400 + i * 30)
        }
    }

    fun claimMilestone(p: PlayerProfile, m: Milestone): PlayerProfile {
        if (m.index in p.trophyClaimed || p.stars.total < m.stars) return p
        return p.copy(coins = p.coins + m.coins, gems = p.gems + m.gems, chests = p.chests + if (m.chest) 1 else 0,
            trophyClaimed = p.trophyClaimed + m.index)
    }

    // ------------------------------------------------------------------ chests
    sealed class ChestReward {
        data class Coins(val amount: Int) : ChestReward()
        data class Gems(val amount: Int) : ChestReward()
        data class Item(val kind: String, val id: String, val name: String) : ChestReward()
    }

    fun openChest(p: PlayerProfile, seed: Long): Pair<PlayerProfile, ChestReward> {
        if (p.chests <= 0) return Pair(p, ChestReward.Coins(0))
        val rng = Rng(seed)
        val base = p.copy(chests = p.chests - 1, stats = p.stats.copy(chestsOpened = p.stats.chestsOpened + 1))
        val roll = rng.nextFloat()
        if (roll < 0.35f) {
            val candidates = Catalog.balls.filter { it.id !in p.ownedBalls && it.rarity <= Rarity.RARE && it.price.gems == 0 }
            if (candidates.isNotEmpty()) {
                val b = rng.pick(candidates)
                return Pair(base.copy(ownedBalls = base.ownedBalls + b.id), ChestReward.Item("ball", b.id, b.name))
            }
            val t = Catalog.trails.filter { it.id !in p.ownedTrails && it.price.gems == 0 }
            if (t.isNotEmpty()) {
                val x = rng.pick(t)
                return Pair(base.copy(ownedTrails = base.ownedTrails + x.id), ChestReward.Item("trail", x.id, x.name))
            }
        }
        if (roll < 0.55f) {
            val g = 3 + rng.nextInt(6)
            return Pair(base.copy(gems = base.gems + g), ChestReward.Gems(g))
        }
        val c = 150 + rng.nextInt(10) * 50
        return Pair(base.copy(coins = base.coins + c), ChestReward.Coins(c))
    }

    // ------------------------------------------------------------------ store
    fun canAfford(p: PlayerProfile, price: Price) = if (price.gems > 0) p.gems >= price.gems else p.coins >= price.coins

    fun pay(p: PlayerProfile, price: Price): PlayerProfile =
        if (price.gems > 0) p.copy(gems = p.gems - price.gems) else p.copy(coins = p.coins - price.coins)

    /** Worlds the player has reached can be locked for free. */
    fun themeOwned(p: PlayerProfile, worldIndex: Int, worldId: String): Boolean =
        worldId in p.ownedThemes || p.currentLevel > worldIndex * Difficulty.LEVELS_PER_CHAPTER
}
