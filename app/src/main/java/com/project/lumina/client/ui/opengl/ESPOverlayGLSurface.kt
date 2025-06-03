package com.project.lumina.client.ui.opengl

import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.Matrix
import org.cloudburstmc.math.vector.Vector3f
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class ESPOverlayGLSurface(context: Context) : GLSurfaceView(context) {

    private val renderer: ESPRenderer

    init {
        setEGLContextClientVersion(2)
        
        setEGLConfigChooser(8, 8, 8, 8, 16, 0) 
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        renderer = ESPRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        
        setZOrderOnTop(true) 
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
            GLES20.glClearColor(0f, 0f, 0f, 0f) 
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
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
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            // Calculate the lookAt vector based on player's rotationYaw and rotationPitch
            val pitchRad = Math.toRadians(rotationPitch.toDouble()).toFloat()
            val yawRad = Math.toRadians(rotationYaw.toDouble()).toFloat()

            val lookX = cos(pitchRad) * sin(yawRad)
            val lookY = sin(pitchRad)
            val lookZ = cos(pitchRad) * cos(yawRad)

            val eyeX = playerPos.x
            val eyeY = playerPos.y + 1.5f // eye height offset
            val eyeZ = playerPos.z

            val centerX = eyeX + lookX
            val centerY = eyeY + lookY
            val centerZ = eyeZ + lookZ

            Matrix.setLookAtM(
                viewMatrix, 0,
                eyeX, eyeY, eyeZ,
                centerX, centerY, centerZ,
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
