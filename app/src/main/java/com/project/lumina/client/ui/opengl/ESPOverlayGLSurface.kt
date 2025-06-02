package com.project.lumina.client.ui.opengl

import android.content.Context
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.opengl.Matrix
import org.cloudburstmc.math.vector.Vector3f
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

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

    fun updatePlayerPosition(player: Vector3f) {
        renderer.updatePlayerPosition(player)
    }

    class ESPRenderer internal constructor() : Renderer {
        var playerPos = Vector3f.from(0f, 0f, 0f)
        var playerRotation = Vector3f.from(0f, 0f, 0f)
        var cameraOrientation = Vector3f.from(0f, 0f, 0f)
        var entityList = emptyList<Vector3f>()

        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)

        var useDynamicViewMatrix = false

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

            android.util.Log.d("ESPOverlayGLSurface", "PlayerPos: $playerPos, PlayerRotation: $playerRotation, Entities: ${entityList.size}")

            if (useDynamicViewMatrix) {
                val eyeX = playerPos.x
                val eyeY = playerPos.y + 1.5f
                val eyeZ = playerPos.z

                val pitch = Math.toRadians(playerRotation.x.toDouble()).toFloat()
                val yaw = Math.toRadians(playerRotation.y.toDouble()).toFloat()

                val lookX = eyeX + (-Math.sin(yaw.toDouble()) * Math.cos(pitch.toDouble())).toFloat()
                val lookY = eyeY + Math.sin(pitch.toDouble()).toFloat()
                val lookZ = eyeZ + (-Math.cos(yaw.toDouble()) * Math.cos(pitch.toDouble())).toFloat()

                try {
                    Matrix.setLookAtM(
                        viewMatrix, 0,
                        eyeX, eyeY, eyeZ,
                        lookX, lookY, lookZ,
                        0f, 1f, 0f
                    )
                } catch (e: Exception) {
                    android.util.Log.e("ESPOverlayGLSurface", "Error setting view matrix: ${e.message}")
                    Matrix.setLookAtM(
                        viewMatrix, 0,
                        0f, 1.5f, 0f,
                        0f, 1.5f, -5f,
                        0f, 1f, 0f
                    )
                }
            } else {
                Matrix.setLookAtM(
                    viewMatrix, 0,
                    0f, 1.5f, 0f,
                    0f, 1.5f, -5f,
                    0f, 1f, 0f
                )
            }

            OpenGLESPRenderer.renderESPBoxes(playerPos, viewMatrix, projectionMatrix, entityList)
        }

        fun updateEntities(entities: List<Vector3f>) {
            this.entityList = entities
        }

        fun updatePlayerPosition(pos: Vector3f) {
            this.playerPos = pos
        }

        fun updatePlayerRotation(rotation: Vector3f) {
            this.playerRotation = rotation
        }

        fun updateCameraOrientation(orientation: Vector3f) {
            this.cameraOrientation = orientation
        }
    }
}
