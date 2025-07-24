package com.project.lumina.client.ui.opengl

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import org.cloudburstmc.math.vector.Vector3f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

object OpenGLESPRenderer {

    fun renderESPBoxes(
        playerPos: Vector3f,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        entities: List<Vector3f>
    ) {
        // Тестовый куб - 3 метра перед игроком
        drawTestCube(
            Vector3f.from(playerPos.x, playerPos.y + 1.5f, playerPos.z - 3f),
            viewMatrix,
            projectionMatrix
        )
        
        for (entity in entities) {
            drawBoxAroundEntity(entity, playerPos, viewMatrix, projectionMatrix)
        }
    }

    private fun drawBoxAroundEntity(
        entity: Vector3f,
        player: Vector3f,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, entity.x - player.x, entity.y - player.y + 0.9f, entity.z - player.z)

        val mvpMatrix = FloatArray(16)
        val tempMatrix = FloatArray(16)
        
        Matrix.multiplyMM(tempMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, tempMatrix, 0, modelMatrix, 0)

        val vertices = floatArrayOf(
            -0.3f, 0f, -0.3f,
            0.3f, 0f, -0.3f,
            0.3f, 0f, 0.3f,
            -0.3f, 0f, 0.3f,
            -0.3f, 1.8f, -0.3f,
            0.3f, 1.8f, -0.3f,
            0.3f, 1.8f, 0.3f,
            -0.3f, 1.8f, 0.3f
        )

        val indices = shortArrayOf(
            0,1, 1,2, 2,3, 3,0,
            4,5, 5,6, 6,7, 7,4,
            0,4, 1,5, 2,6, 3,7
        )

        renderGeometry(mvpMatrix, vertices, indices, floatArrayOf(0f, 1f, 1f, 0.7f))
    }
    
    private fun drawTestCube(
        position: Vector3f,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray
    ) {
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, position.x, position.y, position.z)

        val mvpMatrix = FloatArray(16)
        val tempMatrix = FloatArray(16)
        Matrix.multiplyMM(tempMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, tempMatrix, 0, modelMatrix, 0)

        val vertices = floatArrayOf(
            -0.5f, -0.5f, -0.5f,
            0.5f, -0.5f, -0.5f,
            0.5f, 0.5f, -0.5f,
            -0.5f, 0.5f, -0.5f,
            -0.5f, -0.5f, 0.5f,
            0.5f, -0.5f, 0.5f,
            0.5f, 0.5f, 0.5f,
            -0.5f, 0.5f, 0.5f
        )

        val indices = shortArrayOf(
            0,1, 1,2, 2,3, 3,0,
            4,5, 5,6, 6,7, 7,4,
            0,4, 1,5, 2,6, 3,7
        )

        renderGeometry(mvpMatrix, vertices, indices, floatArrayOf(1f, 0f, 0f, 1f))
    }
    
    private fun renderGeometry(
        mvpMatrix: FloatArray,
        vertices: FloatArray,
        indices: ShortArray,
        color: FloatArray
    ) {
        if (SimpleShader.program == 0) {
            Log.e("Renderer", "Shader program not initialized")
            return
        }
        
        GLES20.glUseProgram(SimpleShader.program)
        GLES20.glUniformMatrix4fv(SimpleShader.uMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4f(SimpleShader.uColorHandle, color[0], color[1], color[2], color[3])
        
        GLES20.glEnableVertexAttribArray(SimpleShader.aPositionHandle)
        GLES20.glVertexAttribPointer(
            SimpleShader.aPositionHandle,
            3,
            GLES20.GL_FLOAT,
            false,
            0,
            createFloatBuffer(vertices)
        )
        
        GLES20.glDrawElements(
            GLES20.GL_LINES,
            indices.size,
            GLES20.GL_UNSIGNED_SHORT,
            createShortBuffer(indices)
        )
        GLES20.glDisableVertexAttribArray(SimpleShader.aPositionHandle)
    }
    
    private fun createFloatBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }
    }

    private fun createShortBuffer(data: ShortArray): ShortBuffer {
        return ByteBuffer.allocateDirect(data.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply {
                put(data)
                position(0)
            }
    }
}
