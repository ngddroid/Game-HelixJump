package com.ngstudio.spindledrop.game

import android.graphics.Color
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.ngstudio.spindledrop.game.level.Cell
import com.ngstudio.spindledrop.game.level.Difficulty
import com.ngstudio.spindledrop.game.level.FairnessSolver
import com.ngstudio.spindledrop.game.level.LevelGenerator
import com.ngstudio.spindledrop.game.level.LevelSpec
import com.ngstudio.spindledrop.game.level.Physics
import com.ngstudio.spindledrop.game.level.PowerUpType
import com.ngstudio.spindledrop.game.level.RingKind
import com.ngstudio.spindledrop.game.level.RingSpec
import com.ngstudio.spindledrop.game.render.ArcPart
import com.ngstudio.spindledrop.game.render.DecalSystem
import com.ngstudio.spindledrop.game.render.InstanceBatch
import com.ngstudio.spindledrop.game.render.Mesh
import com.ngstudio.spindledrop.game.render.MeshFactory
import com.ngstudio.spindledrop.game.render.ParticleSystem
import com.ngstudio.spindledrop.game.render.PatternBaker
import com.ngstudio.spindledrop.game.render.PostFx
import com.ngstudio.spindledrop.game.render.ProceduralTextures
import com.ngstudio.spindledrop.game.render.ProceduralTextures.Tile
import com.ngstudio.spindledrop.game.render.Program
import com.ngstudio.spindledrop.game.render.RingMesh
import com.ngstudio.spindledrop.game.render.Shaders
import com.ngstudio.spindledrop.game.render.ShadowMap
import com.ngstudio.spindledrop.game.render.SkyProfiles
import com.ngstudio.spindledrop.game.render.ThemeUniforms
import com.ngstudio.spindledrop.game.render.TrailRenderer
import com.ngstudio.spindledrop.game.sim.EndlessSource
import com.ngstudio.spindledrop.game.sim.GameSim
import com.ngstudio.spindledrop.game.sim.LevelSource
import com.ngstudio.spindledrop.game.sim.SimEvent
import com.ngstudio.spindledrop.game.sim.SimStatus
import com.ngstudio.spindledrop.game.sim.TowerSource
import com.ngstudio.spindledrop.game.theme.Ambient
import com.ngstudio.spindledrop.game.theme.Catalog
import com.ngstudio.spindledrop.game.theme.TrailStyle
import com.ngstudio.spindledrop.game.theme.WorldTheme
import com.ngstudio.spindledrop.game.theme.Worlds
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The game engine: owns the simulation, camera, effects and all OpenGL rendering.
 * Everything here runs on the GL thread. The UI talks to it through [send] and touch input.
 */
class GameEngine(private val feedback: Feedback) : GLSurfaceView.Renderer {

    var listener: EngineListener? = null
    private val commands = ConcurrentLinkedQueue<EngineCommand>()
    fun send(c: EngineCommand) { commands.add(c) }

    // ------------------------------------------------------------------ input (UI thread -> GL thread)
    private val dragLock = Any()
    private var pendingDrag = 0f
    @Volatile private var touching = false
    @Volatile private var tapped = false
    @Volatile var viewWidthPx = 1080f

    fun onDrag(dxPx: Float) { synchronized(dragLock) { pendingDrag += dxPx } }
    fun onTouch(down: Boolean) { touching = down; if (down) tapped = true }

    // ------------------------------------------------------------------ GL resources
    private var glReady = false
    private lateinit var surface: Program
    private val surfaceVariants = HashMap<Int, Program>()
    private fun surfaceFor(pattern: Int): Program =
        surfaceVariants.getOrPut(pattern) { Program(Shaders.SURFACE_VS, Shaders.surfaceFs(pattern), "surface$pattern") }
    private lateinit var depth: Program
    private lateinit var ballProg: Program
    private lateinit var sky: Program
    private lateinit var particleProg: Program
    private lateinit var decalProg: Program
    private lateinit var trailProg: Program
    private lateinit var sphere: Mesh
    private lateinit var cylinder: Mesh
    private lateinit var coinMesh: Mesh
    private var particleAtlas = 0
    private var splatAtlas = 0
    private var maskTex = 0
    private var emptyVao = 0
    private val post = PostFx()
    private val baker = PatternBaker()
    private val skyProfiles = SkyProfiles()
    private var shadow: ShadowMap? = null
    private lateinit var particleBatch: InstanceBatch
    private lateinit var decalBatch: InstanceBatch
    private lateinit var trail: TrailRenderer

    private val particles = ParticleSystem()
    private val decals = DecalSystem()

    // ------------------------------------------------------------------ settings
    private var quality = Quality.MEDIUM
    private var autoQuality = true
    private var sensitivity = 1f
    private var autoplay = false
    private var cosmetics = Cosmetics(Catalog.balls[0], Catalog.trails[1], Catalog.splats[0])
    private var themeOverride: String? = null
    private var screenW = 1
    private var screenH = 1

    // ------------------------------------------------------------------ game state
    private var mode = Mode.PREVIEW
    private var level = 1
    private var spec: LevelSpec? = null
    private var source: TowerSource = EndlessSource(1L)
    private var sim = GameSim(source, 15, endless = true)
    private var paused = false
    private var status = PlayStatus.PLAYING
    private var introT = 1f
    private var introFrom = 0f
    private var resultSent = false
    private var deathTime = 0f
    private var winTime = 0f
    private var ballVisible = true
    private var angVel = 0f
    private var lastDragDir = 0f
    private var blazeShown = false
    private var lastHud: HudState? = null
    private var hudTimer = 0f
    private var depthRing = 0

    private val theme = ThemeUniforms()
    private val themeFrom = ThemeUniforms()
    private val themeTo = ThemeUniforms()
    private var themeBlend = 1f
    private var currentWorld: WorldTheme = Worlds.all[0]
    private var endlessWorldIdx = -1

    // ------------------------------------------------------------------ camera & juice
    private var focusY = 0f
    private var shake = 0f
    private var flash = 0f
    private var flashColor = floatArrayOf(1f, 1f, 1f)
    private var chroma = 0f
    private var punch = 0f
    private var clock = 0f
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val lightVP = FloatArray(16)
    private val camPos = FloatArray(3)
    private val camRight = floatArrayOf(1f, 0f, 0f)
    private val camUp = floatArrayOf(0f, 1f, 0f)
    private val lightDir = normalize(floatArrayOf(0.35f, 1.0f, 0.55f))

    // Scratch matrices.
    private val mA = FloatArray(16)
    private val mB = FloatArray(16)
    private val mC = FloatArray(16)
    private val mObj3 = FloatArray(9)

    // ------------------------------------------------------------------ ring mesh cache & shards
    private class MeshOwner(val mesh: RingMesh) { var refs = 0 }
    private class RingEntry(val owner: MeshOwner, val version: Int)
    private val ringCache = HashMap<Int, RingEntry>()
    private val ringVersion = HashMap<Int, Int>()
    private val sliderMeshes = HashMap<Int, RingMesh>()

    private class Shard(
        val owner: MeshOwner, val part: ArcPart, val first: Int, val count: Int,
        val caps: IntArray, val base: FloatArray,
        var px: Float, var py: Float, var pz: Float, var vx: Float, var vy: Float, var vz: Float,
        val ax: Float, val ay: Float, val az: Float, val spin: Float, var angle: Float,
        var age: Float, val life: Float, val gateDamage: Float,
    )
    private val shards = ArrayList<Shard>()

    // ------------------------------------------------------------------ perf
    private var lastNanos = 0L
    private var frameCount = 0
    private var fpsTimer = 0f
    private var fps = 60
    private var lowFpsTime = 0f
    private var worstFrame = 0f

    // ================================================================== GLSurfaceView.Renderer
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            surfaceVariants.clear()
            surface = surfaceFor(theme.pattern)
            depth = Program(Shaders.DEPTH_VS, Shaders.DEPTH_FS, "depth")
            ballProg = Program(Shaders.BALL_VS, Shaders.BALL_FS, "ball")
            sky = Program(Shaders.FULLSCREEN_VS, Shaders.SKY_FS, "sky")
            particleProg = Program(Shaders.PARTICLE_VS, Shaders.PARTICLE_FS, "particle")
            decalProg = Program(Shaders.DECAL_VS, Shaders.DECAL_FS, "decal")
            trailProg = Program(Shaders.TRAIL_VS, Shaders.TRAIL_FS, "trail")
            sphere = MeshFactory.sphere(3)
            cylinder = MeshFactory.cylinder()
            coinMesh = MeshFactory.coin()
            particleAtlas = ProceduralTextures.upload(ProceduralTextures.particleAtlas(), true)
            splatAtlas = ProceduralTextures.upload(ProceduralTextures.splatAtlas(), true)
            maskTex = ProceduralTextures.createMaskTexture()
            emptyVao = Gl_gen()
            post.init()
            baker.clearPrograms()
            skyProfiles.reset()
            baker.init()
            particleBatch = InstanceBatch(2000)
            decalBatch = InstanceBatch(32)
            trail = TrailRenderer()
            // Any meshes from a previous context are gone.
            ringCache.clear(); sliderMeshes.clear(); shards.clear()
            shadow = null
            glReady = true
        } catch (t: Throwable) {
            Log.e(Gl_TAG, "GL init failed", t)
            glReady = false
        }
        lastNanos = System.nanoTime()
    }

    private fun Gl_gen(): Int = com.ngstudio.spindledrop.game.render.Gl.gen { n, a, o -> glGenVertexArrays(n, a, o) }
    private val Gl_TAG = "SpindleGL"

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = width; screenH = height
        applyQuality()
    }

    private fun applyQuality() {
        if (!glReady) return
        val (scale, bloom) = when (quality) {
            Quality.LOW -> Pair(0.75f, false)
            Quality.MEDIUM -> Pair(1f, true)
            Quality.HIGH -> Pair(1f, true)
        }
        post.resize(screenW, screenH, scale, 0, bloom, useFxaa = quality != Quality.LOW)
        shadow?.release()
        shadow = when (quality) {
            Quality.LOW -> null
            Quality.MEDIUM -> ShadowMap(1024)
            Quality.HIGH -> ShadowMap(2048)
        }
        particles.budget = when (quality) { Quality.LOW -> 0.45f; Quality.MEDIUM -> 0.75f; Quality.HIGH -> 1f }
        val aspect = post.renderW.toFloat() / post.renderH
        Matrix.perspectiveM(proj, 0, if (aspect < 0.6f) 52f else 46f, aspect, 0.3f, 80f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = ((now - lastNanos) / 1e9f)
        lastNanos = now
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0f) dt = 0f
        processCommands()
        if (!glReady) {
            glClearColor(0.1f, 0.05f, 0.2f, 1f); glClear(GL_COLOR_BUFFER_BIT); return
        }
        trackFps(dt)
        if (!paused) update(dt)
        render()
        publishHud(dt)
    }

    // ================================================================== commands
    private fun processCommands() {
        while (true) {
            val c = commands.poll() ?: break
            when (c) {
                is EngineCommand.StartLevel -> startLevel(c.level)
                is EngineCommand.StartEndless -> startEndless(c.seed)
                is EngineCommand.StartDaily -> startDaily(c.day, c.playerLevel)
                is EngineCommand.Preview -> startPreview(c.level)
                is EngineCommand.Revive -> revive()
                is EngineCommand.SetPaused -> paused = c.paused
                is EngineCommand.SetCosmetics -> cosmetics = c.c
                is EngineCommand.SetThemeOverride -> { themeOverride = c.themeId; refreshTheme(instant = mode != Mode.PREVIEW) }
                is EngineCommand.SetQuality -> { quality = c.q; autoQuality = c.auto; applyQuality() }
                is EngineCommand.SetSensitivity -> sensitivity = c.s
                is EngineCommand.SetAutoplay -> autoplay = c.on
                is EngineCommand.SetHighRefresh -> Unit
            }
        }
    }

    private fun resetWorld() {
        for (e in ringCache.values) if (e.owner.refs == 0) e.owner.mesh.release()
        ringCache.clear()
        ringVersion.clear()
        for (s in shards) { s.owner.refs--; if (s.owner.refs <= 0) s.owner.mesh.release() }
        shards.clear()
        particles.clear()
        decals.clear()
        if (glReady) trail.reset()
        shake = 0f; flash = 0f; chroma = 0f; punch = 0f
        ballVisible = true
        resultSent = false
        blazeShown = false
        angVel = 0f
        synchronized(dragLock) { pendingDrag = 0f }
        lastHud = null
        depthRing = 0
    }

    private fun startLevel(l: Int) {
        resetWorld()
        mode = Mode.LEVEL
        level = l
        val s = LevelGenerator.generate(l)
        spec = s
        source = LevelSource(s)
        sim = GameSim(source, l)
        refreshTheme(instant = true)
        beginIntro(s.rings.last().y)
        s.introduced?.let { listener?.onEvent(UiEvent.Tooltip(it)) }
    }

    private fun startDaily(day: Long, playerLevel: Int) {
        resetWorld()
        mode = Mode.DAILY
        val s = LevelGenerator.daily(day, playerLevel)
        spec = s
        level = s.level
        source = LevelSource(s)
        sim = GameSim(source, s.level)
        currentWorld = Worlds.all[s.worldIndex]
        refreshTheme(instant = true)
        beginIntro(s.rings.last().y)
    }

    private fun startEndless(seed: Long) {
        resetWorld()
        mode = Mode.ENDLESS
        spec = null
        level = 15
        source = EndlessSource(seed)
        sim = GameSim(source, 15, endless = true)
        endlessWorldIdx = -1
        refreshTheme(instant = true)
        beginIntro(-14 * Physics.RING_SPACING)
    }

    private fun startPreview(l: Int) {
        resetWorld()
        mode = Mode.PREVIEW
        level = l
        spec = null
        source = EndlessSource(0xD3E0L + l)
        sim = GameSim(source, 12, endless = true)
        sim.setDemoShield()
        refreshTheme(instant = true)
        status = PlayStatus.PLAYING
        introT = 1f
        focusY = cameraTarget()
    }

    private fun beginIntro(bottomY: Float) {
        status = PlayStatus.INTRO
        introT = 0f
        introFrom = bottomY + 2f
        focusY = introFrom
        tapped = false
    }

    private fun refreshTheme(instant: Boolean) {
        val w = themeOverride?.let { Worlds.byId(it) } ?: when (mode) {
            Mode.ENDLESS -> Worlds.all[max(0, endlessWorldIdx) % Worlds.all.size]
            Mode.DAILY -> currentWorld
            else -> Worlds.forLevel(level)
        }
        currentWorld = w
        if (instant) {
            theme.set(w); themeFrom.set(w); themeTo.set(w); themeBlend = 1f
        } else {
            themeFrom.copyFrom(theme); themeTo.set(w); themeBlend = 0f
        }
    }

    private fun revive() {
        if (sim.status != SimStatus.DEAD) return
        sim.revive()
        rebuildRing(sim.nextRing, emptySet())
        ballVisible = true
        status = PlayStatus.PLAYING
        resultSent = false
        flash = 0.25f; flashColor = floatArrayOf(0.6f, 0.9f, 1f)
        feedback.sfx(Sfx.SHIELD_POP)
        burst(0f, sim.ballY, Physics.BALL_ORBIT, 24, Color.parseColor("#9EE8FF"), Tile.SPARKLE, 3.5f, additive = true)
    }

    // ================================================================== update
    private fun update(dt: Float) {
        clock += dt
        // Theme blending (Endless world changes).
        if (themeBlend < 1f) {
            themeBlend = min(1f, themeBlend + dt / 2.2f)
            theme.blend(themeFrom, themeTo, smooth(themeBlend))
        }

        // ---- Input ----
        var drag: Float
        synchronized(dragLock) { drag = pendingDrag; pendingDrag = 0f }
        val degPerPx = 300f / max(1f, viewWidthPx) * sensitivity
        val canControl = status == PlayStatus.PLAYING && mode != Mode.PREVIEW && !autoplay
        if (canControl) {
            val d = -drag * degPerPx
            sim.alpha += d
            if (dt > 0f && drag != 0f) {
                val v = d / dt
                angVel = angVel * 0.5f + v * 0.5f
                lastDragDir = d
            }
            if (!touching && drag == 0f) {
                // Gentle flick inertia.
                if (abs(angVel) > 40f) sim.alpha += angVel * dt
                angVel *= exp(-dt / 0.085f)
            } else if (drag == 0f) {
                angVel *= exp(-dt / 0.05f)
            }
        }
        if (mode == Mode.PREVIEW || (autoplay && status == PlayStatus.PLAYING)) autopilot(dt)

        // ---- Intro swoop ----
        if (status == PlayStatus.INTRO) {
            if (tapped && introT > 0.15f) introT = 1f
            introT = min(1f, introT + dt / 1.5f)
            val target = cameraTarget()
            focusY = introFrom + (target - introFrom) * smooth(introT)
            if (introT >= 1f) {
                status = PlayStatus.PLAYING
                tapped = false
                listener?.onEvent(UiEvent.IntroDone)
            }
        }

        // ---- Simulation ----
        if (status == PlayStatus.PLAYING) {
            sim.update(dt)
            handleSimEvents()
            if (sim.status == SimStatus.DEAD && mode == Mode.PREVIEW) {
                sim.revive(); sim.setDemoShield(); ballVisible = true
            }
        } else {
            sim.events.clear()
        }
        if (status == PlayStatus.WON) {
            // Victory orbit.
            sim.alpha += 28f * dt
            winTime += dt
        }
        if (status == PlayStatus.DEAD) deathTime += dt

        // Endless: change world every 40 rings.
        if (mode == Mode.ENDLESS && themeOverride == null) {
            val idx = (sim.ringsPassed / 40) % Worlds.all.size
            if (idx != endlessWorldIdx) {
                val first = endlessWorldIdx < 0
                endlessWorldIdx = idx
                refreshTheme(instant = first)
            }
        }

        // ---- Camera ----
        if (status != PlayStatus.INTRO) {
            val target = cameraTarget()
            val k = 1f - exp(-dt * (if (target < focusY - 2.5f) 12f else 7f))
            focusY += (target - focusY) * k
        }
        shake = max(0f, shake - dt * 1.6f)
        flash = max(0f, flash - dt * 2.4f)
        chroma = max(0f, chroma - dt * 0.05f)
        punch = max(0f, punch - dt * 2.5f)

        // ---- Effects ----
        particles.update(dt)
        decals.update(dt)
        updateShards(dt)
        val fallSpeed = -sim.ballVy
        if (glReady) trail.push(sim.ballY, dt)
        emitBallEffects(dt, fallSpeed)
        emitAmbient(dt)
        cleanupCache()
    }

    private fun cameraTarget(): Float {
        val r = source.ring(sim.nextRing)
        val base = (r?.y ?: sim.ballY) + 0.4f
        val t = if (status == PlayStatus.WON) (source.ring(max(0, sim.lastContactRing))?.y ?: base) + 0.4f else base
        return min(t, sim.ballY - 0.25f).let { if (sim.status == SimStatus.PLAYING || status == PlayStatus.INTRO) it else t }
    }

    // ---- Autopilot: used by the home demo tower and by the debug autoplay bot.
    // Plans once per flight (after every bounce / ring pass) with slack, then commits to the target.
    private var botSolver: FairnessSolver? = null
    private var flightId = 0L
    private var botPlannedFlight = -1L
    private var botTarget = Float.NaN

    private fun autopilot(dt: Float) {
        if (sim.status != SimStatus.PLAYING) return
        val ring = source.ring(sim.nextRing) ?: return
        if (ring.kind != RingKind.NORMAL || sim.blazeActive) return
        val speed = if (mode == Mode.PREVIEW) 300f else 560f
        if (botPlannedFlight != flightId) {
            botPlannedFlight = flightId
            botTarget = planTarget(ring, speed)
        }
        val t = botTarget
        if (t.isNaN()) return
        val d = angDiff(t, Physics.wrap360(sim.alpha))
        val step = speed * dt
        sim.alpha += d.coerceIn(-step, step)
    }

    private fun planTarget(ring: RingSpec, speed: Float): Float {
        val solver = botSolver ?: FairnessSolver(600f, 9f, 7f, sim.gravityScale).also { botSolver = it }
        val g = Physics.gravity(sim.gravityScale)
        val h = sim.ballY - Physics.BALL_RADIUS - ring.y
        val v = sim.ballVy
        val tHit = (v + sqrt(max(0f, v * v + 2f * g * max(0f, h)))) / g
        val masks = solver.masks(ring, sim.time + tHit)
        val reach = speed * tHit * 0.8f
        val cur = Physics.wrap360(sim.alpha)
        fun bit(lo: Long, hi: Long, b: Int) = if (b < 64) (lo ushr b) and 1L == 1L else (hi ushr (b - 64)) and 1L == 1L
        fun alphaOf(b: Int) = (b + 0.5f) * FairnessSolver.BIN_DEG
        // 1. Nearest reachable gap.
        var best = Float.NaN; var bestAbs = Float.MAX_VALUE
        var nearestGap = Float.NaN; var nearestGapAbs = Float.MAX_VALUE
        for (b in 0 until FairnessSolver.BINS) {
            if (!bit(masks[0], masks[1], b)) continue
            val a = alphaOf(b); val d = abs(angDiff(a, cur))
            if (d < nearestGapAbs) { nearestGapAbs = d; nearestGap = a }
            if (d <= reach && d < bestAbs) { bestAbs = d; best = a }
        }
        if (!best.isNaN()) return best
        // 2. Reachable safe tile, closest to the nearest gap (or to where we are).
        var safe = Float.NaN; var score = Float.MAX_VALUE
        var anySafe = Float.NaN; var anySafeAbs = Float.MAX_VALUE
        for (b in 0 until FairnessSolver.BINS) {
            if (!bit(masks[2], masks[3], b)) continue
            val a = alphaOf(b); val d = abs(angDiff(a, cur))
            if (d < anySafeAbs) { anySafeAbs = d; anySafe = a }
            if (d > reach) continue
            val sc = if (nearestGap.isNaN()) d else abs(angDiff(a, nearestGap)) + d * 0.05f
            if (sc < score) { score = sc; safe = a }
        }
        if (!safe.isNaN()) return safe
        // 3. Nothing reachable: head for the nearest safe tile anyway.
        return anySafe
    }

    private fun angDiff(a: Float, b: Float): Float {
        var d = Physics.wrap360(a - b)
        if (d > 180f) d -= 360f
        return d
    }

    // ================================================================== simulation events -> juice
    private fun handleSimEvents() {
        val evs = sim.events
        if (evs.isEmpty()) return
        val ball = cosmetics.ball
        for (e in evs) when (e) {
            is SimEvent.Bounce -> {
                flightId++
                val r = source.ring(e.ring) ?: continue
                decals.add(e.ring, e.theta, Physics.BALL_ORBIT, ball.splat, cosmetics.splat.tile, big = false)
                val n = particles.scaled(10)
                repeat(n) {
                    val a = particles.rnd.nextFloat() * 6.283f
                    val sp = 1.2f + particles.rnd.nextFloat() * 2.2f
                    particles.emit(0f, r.y + 0.05f, Physics.BALL_ORBIT, cos(a) * sp, 1.6f + particles.rnd.nextFloat() * 2.4f, sin(a) * sp * 0.6f,
                        0.45f, 0.10f, 0.03f, ball.splat, 1f, 0.8f, Tile.DROP, gravity = 16f)
                }
                if (mode != Mode.PREVIEW) {
                    feedback.sfx(Sfx.BOUNCE, 0.94f + particles.rnd.nextFloat() * 0.12f, 0.8f)
                    feedback.haptic(Haptic.TICK)
                }
                if (e.onCrumble) dust(r.y, theme.crumble, 8)
            }
            is SimEvent.RingPassed -> {
                flightId++
                val r = source.ring(e.ring) ?: continue
                breakRing(r, e.smashed)
                if (mode != Mode.PREVIEW) {
                    val rate = (0.85f + min(e.combo, 10) * 0.07f).coerceAtMost(1.6f)
                    feedback.sfx(if (e.smashed) Sfx.BLAZE_SMASH else Sfx.RING_BREAK, if (e.smashed) 1f else rate, if (e.smashed) 1f else 0.7f)
                    if (!e.smashed && e.combo >= 2) feedback.sfx(Sfx.COMBO_NOTE, (1f + (e.combo - 2) * 0.12246f).coerceAtMost(2f), 0.55f)
                    if (e.smashed) feedback.haptic(Haptic.HEAVY) else feedback.haptic(Haptic.LIGHT)
                    listener?.onEvent(UiEvent.Points(e.points, e.smashed))
                    comboText(e.combo, e.smashed)
                }
                if (e.smashed) {
                    shake = max(shake, 0.22f); flash = max(flash, 0.18f); flashColor = floatArrayOf(1f, 0.8f, 0.5f)
                    chroma = 0.006f; punch = 1f
                }
            }
            is SimEvent.BlazeStart -> {
                if (mode != Mode.PREVIEW) feedback.sfx(Sfx.BLAZE_IGNITE)
                burst(0f, sim.ballY, Physics.BALL_ORBIT, 18, Color.parseColor("#FF9A2A"), Tile.SPARKLE, 3f, additive = true)
            }
            is SimEvent.Coin -> {
                val r = source.ring(e.ring) ?: continue
                burst(0f, r.y + 0.35f, Physics.BALL_ORBIT, 8, Color.parseColor("#FFD54A"), Tile.SPARKLE, 2.2f, additive = true)
                if (mode != Mode.PREVIEW) feedback.sfx(Sfx.COIN, 0.97f + particles.rnd.nextFloat() * 0.08f, 0.7f)
            }
            is SimEvent.PowerUp -> {
                burst(0f, sim.ballY, Physics.BALL_ORBIT, 22, powerColor(e.type), Tile.STAR, 3f, additive = true)
                if (mode != Mode.PREVIEW) {
                    feedback.sfx(Sfx.POWERUP)
                    feedback.haptic(Haptic.MEDIUM)
                    listener?.onEvent(UiEvent.PowerUp(e.type))
                }
            }
            SimEvent.ShieldPop -> {
                burst(0f, sim.ballY, Physics.BALL_ORBIT, 20, Color.parseColor("#9EE8FF"), Tile.BUBBLE, 2.5f, additive = false)
                if (mode != Mode.PREVIEW) feedback.sfx(Sfx.SHIELD_POP)
            }
            is SimEvent.CrumbleCrack -> {
                val r = source.ring(e.ring) ?: continue
                dust(r.y, theme.crumble, if (e.broke) 18 else 8)
                if (mode != Mode.PREVIEW) feedback.sfx(Sfx.CRUMBLE, if (e.broke) 0.85f else 1.1f)
                if (e.broke) rebuildRing(e.ring, setOf(Cell.CRUMBLE)) else ringVersion[e.ring] = (ringVersion[e.ring] ?: 0)
            }
            is SimEvent.GateHit -> {
                val r = source.ring(e.ring) ?: continue
                shake = max(shake, if (e.hpLeft == 0) 0.4f else 0.16f)
                flash = max(flash, 0.2f); flashColor = theme.accent.copyOf()
                dust(r.y, theme.gate, if (e.hpLeft == 0) 30 else 12)
                if (mode != Mode.PREVIEW) {
                    feedback.sfx(if (e.hpLeft == 0) Sfx.GATE_BREAK else Sfx.GATE_HIT)
                    feedback.haptic(if (e.hpLeft == 0) Haptic.HEAVY else Haptic.MEDIUM)
                    listener?.onEvent(UiEvent.GateHit(e.hpLeft, e.maxHp))
                }
            }
            is SimEvent.Vault -> {
                val r = source.ring(e.ring) ?: continue
                repeat(particles.scaled(26)) {
                    val a = particles.rnd.nextFloat() * 6.283f
                    val sp = 2f + particles.rnd.nextFloat() * 3f
                    particles.emit(cos(a) * 1.1f, r.y + 0.1f, sin(a) * 1.1f + 0.3f, cos(a) * sp, 3f + particles.rnd.nextFloat() * 3f, sin(a) * sp,
                        1.1f, 0.16f, 0.1f, Color.parseColor("#FFD54A"), 1f, 0.6f, Tile.SPARKLE, gravity = 12f, additive = true, rotVel = 4f)
                }
                if (mode != Mode.PREVIEW) {
                    feedback.sfx(Sfx.VAULT_BREAK, 0.9f + e.multiplier * 0.04f)
                    feedback.haptic(Haptic.MEDIUM)
                    listener?.onEvent(UiEvent.Vault(e.multiplier, e.coins))
                }
            }
            is SimEvent.Boost -> {
                shake = max(shake, 0.15f); punch = 1f
                if (mode != Mode.PREVIEW) feedback.sfx(Sfx.BOOST)
                burst(0f, sim.ballY, Physics.BALL_ORBIT, 16, theme.boost.toColor(), Tile.SPARKLE, 3f, additive = true)
            }
            is SimEvent.Death -> {
                val r = source.ring(e.ring)
                if (r != null && com.ngstudio.spindledrop.BuildConfig.DEBUG) {
                    val raw = (0..12).joinToString("") { k -> r.contactAt(e.theta - 12f + k * 2f, sim.time, sim.cellsFor(r), sim.slidersEnabled(e.ring)).name.take(1) }
                    Log.w("SpindleDeath", "level=$level ring=${e.ring} theta=${"%.1f".format(e.theta)} alpha=${"%.1f".format(Physics.wrap360(sim.alpha))} t=${"%.2f".format(sim.time)} " +
                        "shutterOpen=${r.shutterOpen(sim.time)} pulseLit=${r.pulseLit(sim.time)} spin=${r.spinSpeed} mirror=${r.mirror} around(-12..+12 step2)=$raw autoplay=$autoplay")
                }
                ballVisible = false
                deathTime = 0f
                status = PlayStatus.DEAD
                decals.add(e.ring, e.theta, Physics.BALL_ORBIT, ball.splat, cosmetics.splat.tile, big = true)
                repeat(particles.scaled(30)) {
                    val a = particles.rnd.nextFloat() * 6.283f
                    val sp = 1.5f + particles.rnd.nextFloat() * 3.5f
                    particles.emit(0f, (r?.y ?: sim.ballY) + 0.15f, Physics.BALL_ORBIT, cos(a) * sp, 2f + particles.rnd.nextFloat() * 3f, sin(a) * sp,
                        0.9f, 0.13f, 0.04f, ball.c1, 1f, 0.7f, Tile.DOT, gravity = 14f)
                }
                shake = 0.35f; flash = 0.35f; flashColor = floatArrayOf(1f, 0.25f, 0.3f); chroma = 0.01f
                if (mode != Mode.PREVIEW) {
                    feedback.sfx(Sfx.DEATH)
                    feedback.haptic(Haptic.FAIL)
                    sendResult(won = false)
                }
            }
            is SimEvent.Win -> {
                status = PlayStatus.WON
                winTime = 0f
                val r = source.ring(e.ring)
                val y = (r?.y ?: sim.ballY) + 1.5f
                repeat(particles.scaled(140)) {
                    val colors = intArrayOf(0xFFFF4F9A.toInt(), 0xFFFFC93C.toInt(), 0xFF3DDC97.toInt(), 0xFF39B8FF.toInt(), 0xFFB45CFF.toInt(), 0xFFFFFFFF.toInt())
                    particles.emit(
                        (particles.rnd.nextFloat() - 0.5f) * 5f, y + particles.rnd.nextFloat() * 3f, (particles.rnd.nextFloat() - 0.3f) * 3f,
                        (particles.rnd.nextFloat() - 0.5f) * 2f, -0.5f - particles.rnd.nextFloat(), (particles.rnd.nextFloat() - 0.5f) * 1f,
                        3.2f, 0.14f, 0.14f, colors[particles.rnd.nextInt(colors.size)], 1f, 0.9f, Tile.CONFETTI,
                        gravity = 1.2f, dragK = 0.6f, rotVel = (particles.rnd.nextFloat() - 0.5f) * 10f,
                    )
                }
                flash = 0.3f; flashColor = floatArrayOf(1f, 1f, 0.9f)
                if (mode != Mode.PREVIEW) {
                    feedback.sfx(Sfx.LEVEL_COMPLETE)
                    feedback.haptic(Haptic.SUCCESS)
                    sendResult(won = true)
                }
            }
        }
        evs.clear()
    }

    private fun comboText(combo: Int, smashed: Boolean) {
        val text = when {
            smashed -> "SMASH!"
            combo == 3 -> "Blazing!"
            combo == 5 -> "Unstoppable!"
            combo == 7 -> "Spiral Storm!"
            combo == 10 -> "Legendary!"
            combo == 2 -> "Smooth!"
            else -> return
        }
        listener?.onEvent(UiEvent.ComboText(text, combo))
    }

    private fun sendResult(won: Boolean) {
        if (resultSent) return
        resultSent = true
        val progressRings = spec?.progressRings ?: 0
        val r = RunResult(
            mode = mode, level = level, won = won, stars = sim.stars(), score = sim.score, coins = sim.coins,
            ringsPassed = sim.ringsPassed, ringsSmashed = sim.ringsSmashed, bestCombo = sim.bestCombo, revives = sim.revives,
            blazeTriggered = sim.blazeTriggered, progress = if (progressRings > 0) sim.progress(progressRings) else 0f,
            depth = sim.ringsPassed,
        )
        listener?.onEvent(if (won) UiEvent.Won(r) else UiEvent.Died(r))
    }

    private fun powerColor(t: PowerUpType) = when (t) {
        PowerUpType.SHIELD -> Color.parseColor("#39B8FF")
        PowerUpType.MAGNET -> Color.parseColor("#FF5A7A")
        PowerUpType.SLOWMO -> Color.parseColor("#B45CFF")
        PowerUpType.DRILL -> Color.parseColor("#FFB020")
    }

    private fun burst(x: Float, y: Float, z: Float, n: Int, color: Int, tile: Float, speed: Float, additive: Boolean) {
        repeat(particles.scaled(n)) {
            val a = particles.rnd.nextFloat() * 6.283f
            val b = particles.rnd.nextFloat() * 3.14f - 1.57f
            val sp = speed * (0.5f + particles.rnd.nextFloat() * 0.7f)
            particles.emit(x, y, z, cos(a) * cos(b) * sp, sin(b) * sp + 1f, sin(a) * cos(b) * sp,
                0.6f + particles.rnd.nextFloat() * 0.4f, 0.16f, 0.04f, color, 1f, 0f, tile, gravity = 5f, dragK = 1.5f, additive = additive)
        }
    }

    private fun dust(y: Float, rgb: FloatArray, n: Int) {
        val c = rgb.toColor()
        repeat(particles.scaled(n)) {
            val a = particles.rnd.nextFloat() * 6.283f
            val sp = 0.8f + particles.rnd.nextFloat() * 1.8f
            particles.emit(cos(a) * 0.3f, y, Physics.BALL_ORBIT + sin(a) * 0.3f, cos(a) * sp, 0.6f + particles.rnd.nextFloat() * 1.5f, sin(a) * sp * 0.5f,
                0.7f, 0.16f, 0.3f, c, 0.85f, 0f, Tile.DOT, gravity = 2f, dragK = 2f)
        }
    }

    private fun emitBallEffects(dt: Float, fallSpeed: Float) {
        if (!ballVisible || status == PlayStatus.INTRO) return
        val by = sim.ballY
        val z = Physics.BALL_ORBIT
        // Blaze fire.
        if (sim.blazeActive) {
            repeat(particles.scaled(3)) {
                val c = if (particles.rnd.nextFloat() < 0.5f) Color.parseColor("#FF7A1A") else Color.parseColor("#FFD23F")
                particles.emit((particles.rnd.nextFloat() - 0.5f) * 0.25f, by + 0.05f, z + (particles.rnd.nextFloat() - 0.5f) * 0.2f,
                    (particles.rnd.nextFloat() - 0.5f) * 0.6f, 2.2f + particles.rnd.nextFloat() * 1.5f, 0f,
                    0.35f, 0.32f, 0.05f, c, 0.9f, 0f, Tile.DOT, additive = true)
            }
        }
        // Trail particles.
        val style = cosmetics.trail.style
        if (fallSpeed > 5f && style != TrailStyle.NONE && particles.rnd.nextFloat() < dt * 30f * particles.budget) {
            val (tile, add, col) = when (style) {
                TrailStyle.BUBBLES -> Triple(Tile.BUBBLE, false, cosmetics.trail.color)
                TrailStyle.STARS -> Triple(Tile.STAR, true, cosmetics.trail.color)
                TrailStyle.HEARTS -> Triple(Tile.HEART, false, cosmetics.trail.color)
                TrailStyle.NOTES -> Triple(Tile.NOTE, false, cosmetics.trail.color)
                TrailStyle.SMOKE -> Triple(Tile.DOT, false, cosmetics.trail.color)
                TrailStyle.LIGHTNING -> Triple(Tile.SPARKLE, true, cosmetics.trail.color)
                TrailStyle.FIRE -> Triple(Tile.DOT, true, Color.parseColor("#FF8A2A"))
                TrailStyle.RAINBOW -> Triple(Tile.SPARKLE, true, Color.HSVToColor(floatArrayOf((clock * 240f) % 360f, 0.7f, 1f)))
                else -> Triple(Tile.SPARKLE, true, Color.WHITE)
            }
            val big = style == TrailStyle.SMOKE
            particles.emit((particles.rnd.nextFloat() - 0.5f) * 0.3f, by + 0.2f, z, (particles.rnd.nextFloat() - 0.5f) * 0.6f, 0.5f, 0f,
                if (big) 0.7f else 0.5f, if (big) 0.25f else 0.16f, if (big) 0.45f else 0.04f, col, if (big) 0.45f else 0.95f, 0f, tile,
                dragK = 1f, additive = add, rotVel = 2f)
        }
        if (sim.drillLeft > 0 && particles.rnd.nextFloat() < dt * 25f) {
            particles.emit(0f, by - 0.2f, z, (particles.rnd.nextFloat() - 0.5f) * 3f, -1f, (particles.rnd.nextFloat() - 0.5f), 0.3f, 0.08f, 0.02f,
                Color.parseColor("#FFB020"), 1f, 0f, Tile.SPARKLE, additive = true)
        }
    }

    private var ambientAcc = 0f
    private fun emitAmbient(dt: Float) {
        ambientAcc += dt * 14f * particles.budget
        while (ambientAcc > 1f) {
            ambientAcc -= 1f
            val x = (particles.rnd.nextFloat() - 0.5f) * 7f
            val z = -1.5f + particles.rnd.nextFloat() * 4.5f
            val yTop = focusY + 3.5f
            val yAny = focusY - 7f + particles.rnd.nextFloat() * 10f
            val r = particles.rnd
            when (theme.ambient) {
                Ambient.SPARKLE -> particles.emit(x, yAny, z, 0f, 0.15f, 0f, 3f, 0.05f, 0.12f, Color.WHITE, 0.9f, 0f, Tile.SPARKLE, additive = true, rotVel = 1f)
                Ambient.RAIN -> particles.emit(x, yTop, z, 0f, -9f, 0f, 1.2f, 0.07f, 0.07f, Color.parseColor("#9FD8FF"), 0.45f, 0.3f, Tile.DROP)
                Ambient.SNOW -> particles.emit(x, yTop, z, 0.2f, -0.8f, 0f, 9f, 0.11f, 0.11f, Color.WHITE, 0.95f, 0.8f, Tile.SNOW, rotVel = 0.6f)
                Ambient.EMBERS -> particles.emit(x, focusY - 8f, z, 0f, 1.4f + r.nextFloat(), 0f, 6f, 0.07f, 0.02f, Color.parseColor("#FF8A2A"), 1f, 0f, Tile.DOT, additive = true)
                Ambient.LEAVES -> particles.emit(x, yTop, z, 0.4f, -0.9f, 0f, 9f, 0.16f, 0.16f, if (r.nextBoolean()) Color.parseColor("#6BBF59") else Color.parseColor("#3E8E41"), 1f, 0.8f, Tile.LEAF, rotVel = 1.5f)
                Ambient.BUBBLES -> particles.emit(x, focusY - 8f, z, 0f, 1.0f + r.nextFloat() * 0.6f, 0f, 9f, 0.08f, 0.16f, Color.WHITE, 0.8f, 0.4f, Tile.BUBBLE)
                Ambient.SAND -> particles.emit(-4f, yAny, z, 2.5f + r.nextFloat(), -0.1f, 0f, 3f, 0.04f, 0.04f, Color.parseColor("#F2D2A2"), 0.7f, 0f, Tile.DOT)
                Ambient.STARS -> particles.emit(x, yAny, -1.5f, 0f, 0.4f, 0f, 3f, 0.04f, 0.1f, Color.parseColor("#BFD4FF"), 1f, 0f, Tile.SPARKLE, additive = true)
                Ambient.PETALS -> particles.emit(x, yTop, z, 0.5f, -0.7f, 0f, 10f, 0.13f, 0.13f, Color.parseColor("#FFB7D5"), 1f, 0.8f, Tile.PETAL, rotVel = 1.8f)
                Ambient.GLINTS -> particles.emit(x, yAny, z, 0f, 0.1f, 0f, 2f, 0.04f, 0.14f, Color.parseColor("#7CF8FF"), 1f, 0f, Tile.SPARKLE, additive = true)
                Ambient.CONFETTI -> particles.emit(x, yTop, z, 0f, -1.1f, 0f, 8f, 0.09f, 0.09f, Color.HSVToColor(floatArrayOf(r.nextFloat() * 360f, 0.7f, 1f)), 1f, 0.8f, Tile.CONFETTI, rotVel = 3f)
                Ambient.FIREFLIES -> particles.emit(x, yAny, z, (r.nextFloat() - 0.5f) * 0.4f, 0.1f, 0f, 4f, 0.05f, 0.08f, Color.parseColor("#E6FF7A"), 1f, 0f, Tile.DOT, additive = true)
            }
        }
    }

    // ================================================================== rings, meshes, shards
    private fun visibleRange(): IntRange {
        val top = focusY + 4f
        val bottom = focusY - 15f
        val first = max(0, floor(-top / Physics.RING_SPACING).toInt())
        var last = ceil(-bottom / Physics.RING_SPACING).toInt()
        if (source.size >= 0) last = min(last, source.size - 1)
        return first..last
    }

    private fun entryFor(ring: RingSpec): RingEntry {
        val v = ringVersion[ring.index] ?: 0
        val e = ringCache[ring.index]
        if (e != null && e.version == v) return e
        if (e != null && e.owner.refs == 0) e.owner.mesh.release()
        val cells = sim.cellsFor(ring)
        val mesh = MeshFactory.ring(cells, ring.kind)
        ProceduralTextures.uploadMaskRow(maskTex, ring.index % 64, cells)
        val ne = RingEntry(MeshOwner(mesh), v)
        ringCache[ring.index] = ne
        return ne
    }

    private fun rebuildRing(index: Int, shardTypes: Set<Byte>) {
        val old = ringCache.remove(index)
        ringVersion[index] = (ringVersion[index] ?: 0) + 1
        if (old != null) {
            val ring = source.ring(index)
            if (ring != null && shardTypes.isNotEmpty()) {
                val model = ringModel(ring, FloatArray(16))
                for (p in old.owner.mesh.parts) if (p.type in shardTypes) spawnShards(old.owner, p, model, smashed = false, crumble = true)
            }
            if (old.owner.refs == 0) old.owner.mesh.release()
        }
    }

    private fun breakRing(ring: RingSpec, smashed: Boolean) {
        val entry = ringCache.remove(ring.index) ?: return
        val model = ringModel(ring, FloatArray(16))
        for (p in entry.owner.mesh.parts) spawnShards(entry.owner, p, model, smashed, crumble = false)
        if (entry.owner.refs == 0) entry.owner.mesh.release()
        if (ring.kind == RingKind.NORMAL && !smashed) {
            // Sparkles in the gap the ball fell through.
            repeat(particles.scaled(6)) {
                particles.emit((particles.rnd.nextFloat() - 0.5f) * 0.6f, ring.y + 0.1f, Physics.BALL_ORBIT, (particles.rnd.nextFloat() - 0.5f) * 1.5f,
                    1.5f, 0.3f, 0.5f, 0.12f, 0.02f, Color.WHITE, 1f, 0f, Tile.SPARKLE, additive = true, dragK = 1f)
            }
        }
    }

    private fun spawnShards(owner: MeshOwner, part: ArcPart, ringModel: FloatArray, smashed: Boolean, crumble: Boolean) {
        val chunks = max(1, (part.lenDeg / 45f).toInt())
        val per = max(1, part.stepPairs / chunks)
        val rnd = particles.rnd
        for (c in 0 until chunks) {
            val a = c * per
            val b = if (c == chunks - 1) part.stepPairs else min(part.stepPairs, (c + 1) * per)
            if (b <= a) continue
            val caps = ArrayList<Int>()
            if (!part.closed && c == 0) { caps += part.startCapFirst; caps += part.capCount }
            if (!part.closed && c == chunks - 1) { caps += part.endCapFirst; caps += part.capCount }
            val phi = Math.toRadians((part.startDeg + part.lenDeg * (c + 0.5f) / chunks).toDouble())
            val lx = (cos(phi) * 1.18).toFloat()
            val lz = (sin(phi) * 1.18).toFloat()
            val p0 = transformPoint(ringModel, lx, -0.12f, lz)
            // base = T(-p0) * ringModel
            Matrix.setIdentityM(mA, 0)
            Matrix.translateM(mA, 0, -p0[0], -p0[1], -p0[2])
            val base = FloatArray(16)
            Matrix.multiplyMM(base, 0, mA, 0, ringModel, 0)
            val dl = sqrt(p0[0] * p0[0] + p0[2] * p0[2]).coerceAtLeast(0.01f)
            val calm = if (mode == Mode.PREVIEW) 0.45f else 1f
            val out = (if (smashed) 3.6f else if (crumble) 1.0f else 2.4f) * (0.7f + rnd.nextFloat() * 0.6f) * calm
            val up = (if (smashed) 1.6f else if (crumble) 0.1f else 0.9f) * (0.6f + rnd.nextFloat() * 0.8f) * calm
            var ax = rnd.nextFloat() - 0.5f; var ay = rnd.nextFloat() - 0.5f; var az = rnd.nextFloat() - 0.5f
            val al = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(0.01f)
            ax /= al; ay /= al; az /= al
            owner.refs++
            shards += Shard(
                owner, part, part.firstIndex + a * part.indicesPerStep, (b - a) * part.indicesPerStep, caps.toIntArray(), base,
                p0[0], p0[1], p0[2], p0[0] / dl * out, up, p0[2] / dl * out,
                ax, ay, az, (3f + rnd.nextFloat() * 6f) * (if (smashed) 1.6f else 1f) * 57.3f, 0f,
                0f, if (crumble) 0.6f else if (mode == Mode.PREVIEW) 0.42f else 0.75f + rnd.nextFloat() * 0.2f, 0f,
            )
        }
    }

    private fun updateShards(dt: Float) {
        val it = shards.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.age += dt
            if (s.age >= s.life) {
                s.owner.refs--
                if (s.owner.refs <= 0) s.owner.mesh.release()
                it.remove()
                continue
            }
            s.vy -= 15f * dt
            s.px += s.vx * dt; s.py += s.vy * dt; s.pz += s.vz * dt
            s.angle += s.spin * dt
        }
    }

    private fun cleanupCache() {
        val range = visibleRange()
        // Pre-build one upcoming ring per frame so meshes are ready before they scroll into view.
        for (i in range.last + 1..range.last + 3) {
            if (source.size >= 0 && i >= source.size) break
            if (!ringCache.containsKey(i)) { source.ring(i)?.let { entryFor(it) }; break }
        }
        while (shards.size > 48) {
            val s0 = shards.removeAt(0)
            s0.owner.refs--
            if (s0.owner.refs <= 0) s0.owner.mesh.release()
        }
        val it = ringCache.entries.iterator()
        while (it.hasNext()) {
            val (idx, e) = it.next()
            if (idx < range.first - 2 || idx > range.last + 6) {
                if (e.owner.refs == 0) e.owner.mesh.release()
                it.remove()
            }
        }
        decals.removeBelow(range.first - 2)
    }

    private fun ringModel(ring: RingSpec, out: FloatArray): FloatArray {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, 0f, ring.y, 0f)
        Matrix.rotateM(out, 0, -sim.ringRotation(ring), 0f, 1f, 0f)
        return out
    }

    // ================================================================== render
    private fun render() {
        val time = clock
        // ---- Camera ----
        val sx = if (shake > 0f) (sin(time * 61f) * shake * 0.12f) else 0f
        val sy = if (shake > 0f) (cos(time * 47f) * shake * 0.1f) else 0f
        val zoom = punch * 0.35f
        val far = if (mode == Mode.PREVIEW) 1.28f else 1f
        camPos[0] = sx; camPos[1] = focusY + 3.6f * far + sy; camPos[2] = 8.4f * far - zoom
        Matrix.setLookAtM(view, 0, camPos[0], camPos[1], camPos[2], sx * 0.5f, focusY - 1.35f - (far - 1f) * 3f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        camRight[0] = view[0]; camRight[1] = view[4]; camRight[2] = view[8]
        camUp[0] = view[1]; camUp[1] = view[5]; camUp[2] = view[9]

        // ---- Light / shadow matrices ----
        val cx = 0f; val cy = focusY - 2.2f; val cz = 0.3f
        val lv = FloatArray(16); val lp = FloatArray(16)
        Matrix.setLookAtM(lv, 0, cx + lightDir[0] * 12f, cy + lightDir[1] * 12f, cz + lightDir[2] * 12f, cx, cy, cz, 0f, 0f, -1f)
        Matrix.orthoM(lp, 0, -2.7f, 2.7f, -2.7f, 2.7f, 1f, 26f)
        Matrix.multiplyMM(lightVP, 0, lp, 0, lv, 0)

        val range = visibleRange()
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)

        // ---- Shadow pass ----
        val sm = shadow
        if (sm != null) {
            sm.begin()
            glColorMask(false, false, false, false)
            glEnable(GL_POLYGON_OFFSET_FILL)
            glPolygonOffset(1.5f, 2f)
            depth.use()
            depth.m4("uLightVP", lightVP)
            val from = max(range.first, sim.nextRing - 1)
            val to = min(range.last, sim.nextRing + 4)
            for (i in from..to) {
                val ring = source.ring(i) ?: continue
                if (sim.isBroken(i)) continue
                val e = entryFor(ring)
                depth.m4("uModel", ringModel(ring, mB))
                e.owner.mesh.mesh.draw()
            }
            if (ballVisible) {
                ballModel(mB)
                depth.m4("uModel", mB)
                sphere.draw()
            }
            glDisable(GL_POLYGON_OFFSET_FILL)
            glColorMask(true, true, true, true)
        }

        // ---- Scene ----
        baker.ensure(theme.pattern, currentWorld.id + (if (themeBlend >= 1f) "" else "~"), theme.accent,
            if (quality == Quality.LOW) 512 else 1024, emptyVao)
        surface = surfaceFor(theme.pattern)
        post.beginScene(theme.fog[0], theme.fog[1], theme.fog[2])
        drawSky(time)
        drawSurfaces(range, time)
        drawBallAndCoins(range, time)
        drawDecals(range)
        drawTransparent(range, time)
        drawParticles()

        val slow = if (sim.slowTime > 0f) 0.8f else 1f
        post.endScene(
            bloomStrength = 1.15f,
            vignette = if (sim.slowTime > 0f) 1.4f else 0.9f,
            saturation = theme.saturation * slow,
            flash = flash * 0.6f, flashR = flashColor[0], flashG = flashColor[1], flashB = flashColor[2],
            chroma = chroma,
        )
    }

    private fun setCommon(p: Program, time: Float) {
        p.v3("uLightDir", lightDir)
        p.v3("uLightColor", theme.light)
        p.v3("uAmbSky", theme.ambSky[0] * 0.55f, theme.ambSky[1] * 0.55f, theme.ambSky[2] * 0.55f)
        p.v3("uAmbGround", theme.ambGround[0] * 0.45f, theme.ambGround[1] * 0.45f, theme.ambGround[2] * 0.45f)
        p.v3("uCamPos", camPos)
        p.v3("uFogColor", theme.fog)
        p.v2("uFog", 6f, 17f)
        p.f("uFocusY", focusY)
        p.f("uTime", time)
        p.m4("uViewProj", viewProj)
        p.m4("uLightVP", lightVP)
        val sm = shadow
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D, sm?.tex ?: 0)
        p.i("uShadow", 3)
        p.f("uShadowOn", if (sm != null) 1f else 0f)
        p.v2("uShadowTexel", 1f / (sm?.size ?: 1024), 1f / (sm?.size ?: 1024))
        glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, baker.texA); p.i("uDetailA", 4)
        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, baker.texB); p.i("uDetailB", 5)
        glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_2D, baker.noiseTex); p.i("uNoise", 6)
        glActiveTexture(GL_TEXTURE0)
    }

    private fun drawSky(time: Float) {
        glDisable(GL_DEPTH_TEST)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        sky.use()
        sky.v3("uSkyTop", theme.skyTop); sky.v3("uSkyBottom", theme.skyBottom); sky.v3("uGlow", theme.glow)
        sky.v2("uSun", theme.sunX, theme.sunY); sky.f("uSunSize", theme.sunSize)
        sky.f("uTime", time)
        sky.f("uAspect", post.renderW.toFloat() / post.renderH)
        sky.f("uPan", sim.alpha / 360f)
        sky.f("uAA", 1.5f / post.renderH)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, skyProfiles.texture(theme.shape)); sky.i("uProfile", 1)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, baker.noiseTex); sky.i("uNoise", 2)
        glActiveTexture(GL_TEXTURE0)
        val progress = spec?.let { sim.progress(it.progressRings) } ?: 0f
        sky.f("uDepth", progress)
        sky.v3("uFar", theme.far); sky.v3("uMid", theme.mid); sky.v3("uNear", theme.near); sky.v3("uFogColor", theme.fog)
        sky.f("uStars", theme.stars); sky.f("uClouds", theme.clouds)
        sky.v3("uLights", theme.cityLights)
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindVertexArray(0)
        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_CULL_FACE)
    }

    private fun setTheme(p: Program) {
        p.v3("uTop", theme.ringTop); p.v3("uSide", theme.ringSide); p.v3("uAccent", theme.accent)
        p.v3("uHazard", theme.hazard); p.v3("uHazardGlow", theme.hazardGlow)
        p.i("uPattern", theme.pattern); p.f("uGloss", theme.gloss)
    }

    private fun drawSurfaces(range: IntRange, time: Float) {
        surface.use()
        setCommon(surface, time)
        setTheme(surface)
        surface.f("uFlash", 0f)
        surface.f("uAlpha", 1f)
        // Pillar.
        Matrix.setIdentityM(mA, 0)
        Matrix.translateM(mA, 0, 0f, focusY - 5f, 0f)
        Matrix.rotateM(mA, 0, -sim.alpha, 0f, 1f, 0f)
        Matrix.scaleM(mA, 0, Physics.PILLAR_RADIUS, 44f, Physics.PILLAR_RADIUS)
        surface.m4("uModel", mA)
        surface.i("uType", 9)
        surface.v3("uTop", theme.pillarA); surface.v3("uSide", theme.pillarB)
        surface.f("uState", 0f)
        cylinder.draw()
        surface.v3("uTop", theme.ringTop); surface.v3("uSide", theme.ringSide)

        // Rings.
        for (i in range) {
            val ring = source.ring(i) ?: continue
            if (sim.isBroken(i)) continue
            val e = entryFor(ring)
            val model = ringModel(ring, mB)
            surface.m4("uModel", model)
            for (b in e.owner.mesh.batches) {
                if (b.type == Cell.SHUTTER) continue
                if (!setBatchUniforms(ring, b)) continue
                e.owner.mesh.mesh.draw(b.first, b.count)
            }
            // Sliding hazards.
            if (ring.sliders.isNotEmpty() && sim.slidersEnabled(i)) {
                surface.i("uType", 1); surface.f("uState", 1f); surface.v3("uSpecial", theme.crumble)
                for (s in ring.sliders) {
                    val sm = sliderMeshes.getOrPut((s.length * 10).toInt()) { MeshFactory.slider(s.length) }
                    Matrix.rotateM(mC.also { System.arraycopy(model, 0, it, 0, 16) }, 0, -s.startAt(sim.time), 0f, 1f, 0f)
                    surface.m4("uModel", mC)
                    sm.mesh.draw()
                }
                surface.m4("uModel", model)
            }
        }

        // Shards (double-sided).
        if (shards.isNotEmpty()) {
            glDisable(GL_CULL_FACE)
            for (s in shards) {
                val t = s.age / s.life
                val scale = if (t < 0.35f) 1f else max(0.01f, 1f - (t - 0.35f) / 0.65f)
                Matrix.setIdentityM(mA, 0)
                Matrix.translateM(mA, 0, s.px, s.py, s.pz)
                Matrix.rotateM(mA, 0, s.angle, s.ax, s.ay, s.az)
                Matrix.scaleM(mA, 0, scale, scale, scale)
                Matrix.multiplyMM(mC, 0, mA, 0, s.base, 0)
                surface.m4("uModel", mC)
                setShardUniforms(s.part.type)
                s.owner.mesh.mesh.draw(s.first, s.count)
                var k = 0
                while (k < s.caps.size) { s.owner.mesh.mesh.draw(s.caps[k], s.caps[k + 1]); k += 2 }
            }
            glEnable(GL_CULL_FACE)
        }
    }

    /** Returns false if the batch should not be drawn. */
    private fun setBatchUniforms(ring: RingSpec, b: com.ngstudio.spindledrop.game.render.DrawBatch): Boolean {
        when (b.type) {
            Cell.NORMAL -> { surface.i("uType", 0); surface.f("uState", 0f) }
            Cell.HAZARD -> { surface.i("uType", 1); surface.f("uState", 1f) }
            Cell.CRUMBLE -> {
                surface.i("uType", 2)
                surface.v3("uSpecial", theme.crumble)
                val p = b.part
                val start = if (p == null) 0 else Physics.wrap360(p.startDeg - 0.6f).let { Math.round(it) % 360 }
                surface.f("uState", sim.crumbleLevel(ring.index, start).toFloat())
            }
            Cell.BOOST -> { surface.i("uType", 3); surface.v3("uSpecial", theme.boost) }
            Cell.PULSE -> {
                surface.i("uType", 5)
                surface.f("uState", if (ring.pulseLit(sim.time)) 1f else 0f)
                surface.f("uAlpha", ring.pulseWarning(sim.time))
            }
            Cell.GATE -> {
                surface.i("uType", 6); surface.v3("uSpecial", theme.gate)
                val hp = sim.gateHpLeft(ring)
                surface.f("uState", if (ring.gateHp > 0) 1f - hp.toFloat() / ring.gateHp else 0f)
            }
            Cell.VAULT -> surface.i("uType", 7)
            Cell.FINISH -> surface.i("uType", 8)
            else -> surface.i("uType", 0)
        }
        if (b.type != Cell.PULSE) surface.f("uAlpha", 1f)
        return true
    }

    private fun setShardUniforms(type: Byte) {
        surface.f("uAlpha", 1f)
        when (type) {
            Cell.HAZARD, Cell.PULSE -> { surface.i("uType", 1); surface.f("uState", 1f) }
            Cell.CRUMBLE -> { surface.i("uType", 2); surface.v3("uSpecial", theme.crumble); surface.f("uState", 1f) }
            Cell.BOOST -> { surface.i("uType", 3); surface.v3("uSpecial", theme.boost) }
            Cell.GATE -> { surface.i("uType", 6); surface.v3("uSpecial", theme.gate); surface.f("uState", 1f) }
            Cell.VAULT -> surface.i("uType", 7)
            Cell.FINISH -> surface.i("uType", 8)
            Cell.SHUTTER -> surface.i("uType", 0)
            else -> { surface.i("uType", 0); surface.f("uState", 0f) }
        }
    }

    private fun ballModel(out: FloatArray) {
        var y = sim.ballY
        if (status == PlayStatus.WON) {
            val decay = exp(-winTime * 0.6f)
            y = (source.ring(sim.lastContactRing)?.y ?: y) + Physics.BALL_RADIUS + abs(sin(winTime * 5.5f)) * 0.9f * decay
        }
        val vy = sim.ballVy
        var sxz = 1f
        var syy = 1f
        val sb = sim.sinceBounce
        if (sb < 0.14f && status == PlayStatus.PLAYING) {
            val k = 1f - sb / 0.14f
            syy = 1f - 0.32f * k; sxz = 1f + 0.22f * k
        } else if (vy < -3f) {
            val k = min(1f, (-vy - 3f) / 10f)
            syy = 1f + 0.28f * k; sxz = 1f - 0.12f * k
        } else if (vy > 3f) {
            val k = min(1f, (vy - 3f) / 6f)
            syy = 1f + 0.12f * k; sxz = 1f - 0.06f * k
        }
        val r = Physics.BALL_RADIUS
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, 0f, y + (syy - 1f) * r * (if (sb < 0.14f) 1f else 0f), Physics.BALL_ORBIT)
        Matrix.scaleM(out, 0, r * sxz, r * syy, r * sxz)
    }

    private fun drawBallAndCoins(range: IntRange, time: Float) {
        ballProg.use()
        setCommon(ballProg, time)
        ballProg.f("uFlash", 0f)
        // Coins.
        ballProg.i("uSkin", 30)
        ballProg.v3("uC1", 1f, 0.78f, 0.22f)
        ballProg.f("uMetal", 0.9f); ballProg.f("uEmis", 0.0f); ballProg.f("uBlaze", 0f)
        setIdentity3(mObj3)
        ballProg.loc("uObjRot").let { if (it >= 0) glUniformMatrix3fv(it, 1, false, mObj3, 0) }
        for (i in range) {
            val ring = source.ring(i) ?: continue
            if (sim.isBroken(i) || ring.kind != RingKind.NORMAL) continue
            val rot = sim.ringRotation(ring)
            for ((k, a) in ring.coins.withIndex()) {
                if (sim.coinCollected(i, k)) continue
                val w = Math.toRadians((a + rot).toDouble())
                val x = (cos(w) * Physics.BALL_ORBIT).toFloat()
                val z = (sin(w) * Physics.BALL_ORBIT).toFloat()
                Matrix.setIdentityM(mA, 0)
                Matrix.translateM(mA, 0, x, ring.y + 0.38f + sin(time * 3f + k + i) * 0.04f, z)
                Matrix.rotateM(mA, 0, time * 160f + i * 40f, 0f, 1f, 0f)
                Matrix.rotateM(mA, 0, 90f, 1f, 0f, 0f)
                ballProg.m4("uModel", mA)
                coinMesh.draw()
            }
            // Power-up sprite.
            val pu = ring.powerUp
            if (pu != null && !sim.powerUpCollected(i)) {
                val w = Math.toRadians((ring.powerUpAngle + rot).toDouble())
                val x = (cos(w) * Physics.BALL_ORBIT).toFloat()
                val z = (sin(w) * Physics.BALL_ORBIT).toFloat()
                val y = ring.y + 0.45f + sin(time * 2.5f) * 0.06f
                val tile = when (pu) { PowerUpType.SHIELD -> Tile.SHIELD; PowerUpType.MAGNET -> Tile.MAGNET; PowerUpType.SLOWMO -> Tile.HOURGLASS; PowerUpType.DRILL -> Tile.DRILL }
                particles.addStatic(x, y, z, 0.62f + sin(time * 4f) * 0.03f, Color.WHITE, 0.9f, Tile.BUBBLE)
                particles.addStatic(x, y, z, 0.44f, Color.WHITE, 1f, tile)
            }
        }
        // Ball.
        if (!ballVisible) return
        val b = cosmetics.ball
        ballProg.i("uSkin", b.skin)
        ballProg.v3("uC1", Color.red(b.c1) / 255f, Color.green(b.c1) / 255f, Color.blue(b.c1) / 255f)
        ballProg.v3("uC2", Color.red(b.c2) / 255f, Color.green(b.c2) / 255f, Color.blue(b.c2) / 255f)
        ballProg.v3("uC3", Color.red(b.c3) / 255f, Color.green(b.c3) / 255f, Color.blue(b.c3) / 255f)
        ballProg.f("uMetal", b.metal)
        ballProg.f("uEmis", b.emissive)
        ballProg.f("uBlaze", if (sim.blazeActive) 1f else 0f)
        val isFace = b.skin == 10 || b.skin == 19
        if (isFace) setIdentity3(mObj3) else {
            Matrix.setRotateM(mA, 0, time * 40f, 1f, 0.35f, 0.2f)
            mObj3[0] = mA[0]; mObj3[1] = mA[1]; mObj3[2] = mA[2]
            mObj3[3] = mA[4]; mObj3[4] = mA[5]; mObj3[5] = mA[6]
            mObj3[6] = mA[8]; mObj3[7] = mA[9]; mObj3[8] = mA[10]
        }
        ballProg.loc("uObjRot").let { if (it >= 0) glUniformMatrix3fv(it, 1, false, mObj3, 0) }
        ballModel(mB)
        ballProg.m4("uModel", mB)
        ballProg.f("uFlash", if (sim.shieldActive && mode != Mode.PREVIEW) 0.06f else 0f)
        sphere.draw()
        if (sim.shieldActive && mode != Mode.PREVIEW) {
            particles.addStatic(0f, sim.ballY, Physics.BALL_ORBIT, 0.72f + sin(time * 6f) * 0.03f, Color.parseColor("#9EE8FF"), 0.85f, Tile.BUBBLE)
        }
    }

    private fun drawDecals(range: IntRange) {
        if (decals.byRing.isEmpty()) return
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glColorMask(true, true, true, false)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        decalProg.use()
        decalProg.m4("uViewProj", viewProj)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, splatAtlas)
        decalProg.i("uAtlas", 0)
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, maskTex)
        decalProg.i("uMask", 1)
        val lt = 0.55f + 0.5f * lightDir[1]
        decalProg.v3("uLightTint", theme.light[0] * lt, theme.light[1] * lt, theme.light[2] * lt)
        for (i in range) {
            if (sim.isBroken(i)) continue
            val n = decals.fill(i, decalBatch.data)
            if (n == 0) continue
            val ring = source.ring(i) ?: continue
            entryFor(ring) // make sure the mask row is current
            decalProg.m4("uModel", ringModel(ring, mB))
            decalProg.f("uRow", (i % 64).toFloat())
            decalBatch.draw(n)
        }
        glActiveTexture(GL_TEXTURE0)
        glDepthMask(true)
        glColorMask(true, true, true, true)
        glDisable(GL_BLEND)
        glEnable(GL_CULL_FACE)
    }

    private fun drawTransparent(range: IntRange, time: Float) {
        // Shutter panels (translucent, fade/slide when open).
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glColorMask(true, true, true, false)
        var shutterSetup = false
        for (i in range) {
            val ring = source.ring(i) ?: continue
            if (sim.isBroken(i) || ring.rhythmPeriod <= 0f) continue
            val e = entryFor(ring)
            for (p in e.owner.mesh.parts) {
                if (p.type != Cell.SHUTTER) continue
                if (!shutterSetup) {
                    surface.use(); setCommon(surface, time); setTheme(surface); surface.i("uType", 4); surface.f("uFlash", 0f)
                    shutterSetup = true
                }
                val phase = RingSpecFrac(sim.time / ring.rhythmPeriod + ring.rhythmPhase)
                // Open during [0, 0.45): smooth open/close amount.
                val open = when {
                    phase < 0.05f -> phase / 0.05f
                    phase < 0.40f -> 1f
                    phase < 0.45f -> 1f - (phase - 0.40f) / 0.05f
                    else -> 0f
                }
                if (open >= 0.999f) continue
                ringModel(ring, mB)
                Matrix.translateM(mC.also { Matrix.setIdentityM(it, 0) }, 0, 0f, -0.16f * open, 0f)
                Matrix.multiplyMM(mA, 0, mC, 0, mB, 0)
                surface.m4("uModel", mA)
                surface.f("uAlpha", (1f - open) * 0.85f)
                e.owner.mesh.mesh.draw(p.firstIndex, p.sweepCount + 2 * p.capCount)
            }
        }
        // Trail ribbon (additive).
        val fall = -sim.ballVy
        val intensity = if (ballVisible) ((fall - 4f) / 6f).coerceIn(0f, 1f) else 0f
        val style = cosmetics.trail.style
        val blaze = sim.blazeActive
        if (intensity > 0f && (style != TrailStyle.NONE || blaze)) {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE)
            glDepthMask(false)
            glDisable(GL_CULL_FACE)
            trailProg.use()
            trailProg.m4("uViewProj", viewProj)
            val base = cosmetics.trail.color
            trail.draw(Physics.BALL_ORBIT, Physics.BALL_RADIUS * 0.85f, intensity) { t ->
                when {
                    blaze || style == TrailStyle.FIRE -> if (t < 0.4f) Color.argb(255, 255, 220, 120) else Color.argb(255, 255, (140 - t * 100).toInt().coerceAtLeast(30), 30)
                    style == TrailStyle.RAINBOW -> Color.HSVToColor(floatArrayOf((t * 300f + clock * 120f) % 360f, 0.75f, 1f))
                    style == TrailStyle.LIGHTNING -> if (((clock * 30).toInt() + (t * 8).toInt()) % 2 == 0) base else Color.WHITE
                    else -> base
                }
            }
            glEnable(GL_CULL_FACE)
            glDepthMask(true)
        }
        glColorMask(true, true, true, true)
        glDisable(GL_BLEND)
    }

    private fun RingSpecFrac(x: Float) = x - floor(x)

    private fun drawParticles() {
        glEnable(GL_BLEND)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        particleProg.use()
        particleProg.m4("uViewProj", viewProj)
        particleProg.v3("uCamRight", camRight)
        particleProg.v3("uCamUp", camUp)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, particleAtlas)
        particleProg.i("uAtlas", 0)
        // Alpha-blended group (doesn't write the bloom mask).
        glColorMask(true, true, true, false)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        var n = particles.fill(false, particleBatch.data, includeStatics = false)
        particleBatch.draw(n)
        // Additive group writes into the bloom mask so it glows.
        glColorMask(true, true, true, true)
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ONE, GL_ONE)
        n = particles.fill(true, particleBatch.data, includeStatics = false)
        particleBatch.draw(n)
        // Static sprites (icons) last, alpha blended.
        glColorMask(true, true, true, false)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        n = particles.fillStatics(particleBatch.data)
        particleBatch.draw(n)
        glColorMask(true, true, true, true)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glEnable(GL_CULL_FACE)
    }

    // ================================================================== HUD & perf
    private fun publishHud(dt: Float) {
        hudTimer += dt
        if (hudTimer < 0.033f || mode == Mode.PREVIEW) return
        hudTimer = 0f
        val progressRings = spec?.progressRings ?: 0
        val h = HudState(
            mode = mode, level = level, status = status, score = sim.score,
            progress = if (progressRings > 0) sim.progress(progressRings) else 0f,
            combo = sim.combo, blaze = sim.blazeActive, coins = sim.coins, shield = sim.shieldActive,
            magnet = sim.magnetTime, slow = sim.slowTime, drill = sim.drillLeft, depth = sim.ringsPassed,
            worldName = currentWorld.name, isBoss = spec?.isBoss ?: false, fps = fps,
        )
        if (h != lastHud) {
            lastHud = h
            listener?.onHud(h)
        }
    }

    private fun trackFps(dt: Float) {
        frameCount++
        fpsTimer += dt
        worstFrame = max(worstFrame, dt)
        if (fpsTimer >= 1f) {
            fps = (frameCount / fpsTimer).toInt()
            if (mode != Mode.PREVIEW && com.ngstudio.spindledrop.BuildConfig.DEBUG) Log.i("SpindleFPS", "fps=$fps worst=${(worstFrame * 1000).toInt()}ms quality=$quality particles=${particles.count} shards=${shards.size} ring=${sim.nextRing}/${source.size} status=$status mode=$mode autoplay=$autoplay alpha=${"%.1f".format(sim.alpha)} angVel=${"%.0f".format(angVel)} engine=${System.identityHashCode(this)}")
            if (autoQuality && status == PlayStatus.PLAYING && mode != Mode.PREVIEW) {
                if (fps < 50) lowFpsTime += fpsTimer else lowFpsTime = 0f
                if (lowFpsTime >= 3f && quality != Quality.LOW) {
                    quality = if (quality == Quality.HIGH) Quality.MEDIUM else Quality.LOW
                    Log.w("SpindleFPS", "Auto quality step down to $quality")
                    applyQuality()
                    lowFpsTime = 0f
                }
            }
            frameCount = 0; fpsTimer = 0f; worstFrame = 0f
        }
    }

    // ================================================================== helpers
    private fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    private fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val v = floatArrayOf(x, y, z, 1f)
        val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, m, 0, v, 0)
        return o
    }

    private fun setIdentity3(m: FloatArray) {
        m.fill(0f); m[0] = 1f; m[4] = 1f; m[8] = 1f
    }

    private fun FloatArray.toColor(): Int = Color.rgb((this[0] * 255).toInt().coerceIn(0, 255), (this[1] * 255).toInt().coerceIn(0, 255), (this[2] * 255).toInt().coerceIn(0, 255))

    companion object {
        private fun normalize(v: FloatArray): FloatArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
        @Suppress("unused") private const val TAU = (2 * PI).toFloat()
        @Suppress("unused") private val unusedDifficulty = Difficulty.MAX_LEVEL
    }
}
