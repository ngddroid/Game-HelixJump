package com.ngstudio.spindledrop.game

import com.ngstudio.spindledrop.game.level.Mechanic
import com.ngstudio.spindledrop.game.level.PowerUpType
import com.ngstudio.spindledrop.game.theme.BallSkin
import com.ngstudio.spindledrop.game.theme.TrailSkin
import com.ngstudio.spindledrop.game.theme.SplatSkin

enum class Sfx {
    BOUNCE, RING_BREAK, COMBO_NOTE, BLAZE_IGNITE, BLAZE_SMASH, DEATH, SHIELD_POP, POWERUP, COIN, GEM,
    GATE_HIT, GATE_BREAK, VAULT_BREAK, CRUMBLE, BOOST, LEVEL_COMPLETE, STAR, FAIL,
    UI_TAP, UI_BACK, UI_TOGGLE, UI_OPEN, SWOOSH, PURCHASE, ERROR, REWARD, CHEST_SHAKE, CHEST_OPEN,
    WHEEL_TICK, WHEEL_WIN, UNLOCK, COUNTDOWN_TICK,
}

enum class Haptic { TICK, LIGHT, MEDIUM, HEAVY, SUCCESS, FAIL }

/** Sound + vibration sink, implemented by the app layer. Must be thread-safe. */
interface Feedback {
    fun sfx(s: Sfx, rate: Float = 1f, volume: Float = 1f)
    fun haptic(h: Haptic)
}

enum class Quality(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

enum class Mode { PREVIEW, LEVEL, ENDLESS, DAILY }

data class Cosmetics(val ball: BallSkin, val trail: TrailSkin, val splat: SplatSkin)

sealed class EngineCommand {
    data class StartLevel(val level: Int) : EngineCommand()
    data class StartEndless(val seed: Long) : EngineCommand()
    data class StartDaily(val day: Long, val playerLevel: Int) : EngineCommand()
    data class Preview(val level: Int) : EngineCommand()
    data object Revive : EngineCommand()
    data class SetPaused(val paused: Boolean) : EngineCommand()
    data class SetCosmetics(val c: Cosmetics) : EngineCommand()
    data class SetThemeOverride(val themeId: String?) : EngineCommand()
    data class SetQuality(val q: Quality, val auto: Boolean) : EngineCommand()
    data class SetSensitivity(val s: Float) : EngineCommand()
    data class SetAutoplay(val on: Boolean) : EngineCommand()
    data class SetHighRefresh(val on: Boolean) : EngineCommand()
}

enum class PlayStatus { INTRO, PLAYING, DEAD, WON }

data class HudState(
    val mode: Mode = Mode.LEVEL,
    val level: Int = 1,
    val status: PlayStatus = PlayStatus.INTRO,
    val score: Int = 0,
    val progress: Float = 0f,
    val combo: Int = 0,
    val blaze: Boolean = false,
    val coins: Int = 0,
    val shield: Boolean = false,
    val magnet: Float = 0f,
    val slow: Float = 0f,
    val drill: Int = 0,
    val depth: Int = 0,
    val worldName: String = "",
    val isBoss: Boolean = false,
    val fps: Int = 0,
)

data class RunResult(
    val mode: Mode,
    val level: Int,
    val won: Boolean,
    val stars: Int,
    val score: Int,
    val coins: Int,
    val ringsPassed: Int,
    val ringsSmashed: Int,
    val bestCombo: Int,
    val revives: Int,
    val blazeTriggered: Boolean,
    val progress: Float,
    val depth: Int,
)

sealed class UiEvent {
    data class Points(val points: Int, val smashed: Boolean) : UiEvent()
    data class ComboText(val text: String, val level: Int) : UiEvent()
    data class Tooltip(val mechanic: Mechanic) : UiEvent()
    data class PowerUp(val type: PowerUpType) : UiEvent()
    data class Vault(val multiplier: Int, val coins: Int) : UiEvent()
    data class GateHit(val hpLeft: Int, val maxHp: Int) : UiEvent()
    data object IntroDone : UiEvent()
    data class Died(val result: RunResult) : UiEvent()
    data class Won(val result: RunResult) : UiEvent()
}

/** Called on the GL thread; implementations must hop to the main thread. */
interface EngineListener {
    fun onHud(h: HudState)
    fun onEvent(e: UiEvent)
}
