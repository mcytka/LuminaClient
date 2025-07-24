package com.project.lumina.client.ui.opengl

import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.MotionEvent
import org.cloudburstmc.math.vector.Vector3f
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class ESPOverlayGLSurface(context: Context) : GLSurfaceView(context) {

    private val renderer: ESPRenderer

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0) // Убрали буфер глубины
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        renderer = ESPRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        setZOrderOnTop(true)
        
        // Разрешаем прохождение касаний
        isClickable = false
        isFocusable = false
        setOnTouchListener { _, _ -> false }
    }

    // Пропускаем все события касания
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }

    fun updateEntities(entities: List<Vector3f>) {
        renderer.updateEntities(entities)
    }

    fun updatePlayerPosition(player: Vector3f, rotationYaw: Float, rotationPitch: Float) {
        renderer.updatePlayerPosition(player, rotationYaw, rotationPitch)
    }

    private class ESPRenderer : Renderer {
        private var playerPos = Vector3f.from(0f, 0f, 0f)
        private var rotationYaw = 0f
        private var rotationPitch = 0f
        private var entityList = emptyList<Vector3f>()

        private val viewMatrix = FloatArray(16)
        private val projectionMatrix = FloatArray(16)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f) // Полностью прозрачный фон
            GLES20.glDisable(GLES20.GL_DEPTH_TEST) // Отключаем тест глубины
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            SimpleShader.init()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height
            Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 0.1f, 100f)
        }

        override fun onDrawFrame(gl: GL10?) {
            // Очищаем только цветовой буфер
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Если нет сущностей - пропускаем рендеринг
            if (entityList.isEmpty()) return

            val pitchRad = Math.toRadians(rotationPitch.toDouble()).toFloat()
            val yawRad = Math.toRadians(rotationYaw.toDouble()).toFloat()

            val lookX = cos(pitchRad) * sin(yawRad)
            val lookY = sin(pitchRad)
            val lookZ = cos(pitchRad) * cos(yawRad)

            val eyeX = playerPos.x
            val eyeY = playerPos.y + 1.5f
            val eyeZ = playerPos.z

            Matrix.setLookAtM(
                viewMatrix, 0,
                eyeX, eyeY, eyeZ,
                eyeX + lookX, eyeY + lookY, eyeZ + lookZ,
                0f, 1f, 0f
            )

            OpenGLESPRenderer.renderESPBoxes(playerPos, viewMatrix, projectionMatrix, entityList)
        }

        fun updateEntities(entities: List<Vector3f>) {
            this.entityList = entities
        }

        fun updatePlayerPosition(pos: Vector3f, yaw: Float, pitch: Float) {
            this.playerPos = pos
            this.rotationYaw = yaw
            this.rotationPitch = pitch
        }
    }
}
