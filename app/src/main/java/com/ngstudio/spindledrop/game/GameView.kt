package com.ngstudio.spindledrop.game

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent

/** OpenGL ES 3 surface that hosts the [GameEngine] and forwards horizontal swipes. */
@SuppressLint("ViewConstructor")
class GameView(context: Context, val engine: GameEngine) : GLSurfaceView(context) {
    private var lastX = 0f
    private var activePointer = -1

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(engine)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.viewWidthPx = w.toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = e.getPointerId(0)
                lastX = e.x
                engine.onTouch(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> Unit
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(activePointer)
                if (idx >= 0) {
                    val x = e.getX(idx)
                    engine.onDrag(x - lastX)
                    lastX = x
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                if (e.getPointerId(idx) == activePointer) {
                    val newIdx = if (idx == 0) 1 else 0
                    activePointer = e.getPointerId(newIdx)
                    lastX = e.getX(newIdx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointer = -1
                engine.onTouch(false)
            }
        }
        return true
    }
}
