package com.project.lumina.client.ui.opengl

import android.opengl.GLES20
import android.util.Log

object SimpleShader {
    var program: Int = 0
    var aPositionHandle: Int = 0
    var uMVPMatrixHandle: Int = 0
    var uColorHandle: Int = 0

    fun init() {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
            }
        """.trimIndent())
        
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """.trimIndent())

        if (vertexShader == 0 || fragmentShader == 0) {
            Log.e("Shader", "Shader compilation failed")
            return
        }

        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
            
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e("Shader", "Linking failed: ${GLES20.glGetProgramInfoLog(it)}")
                GLES20.glDeleteProgram(it)
                program = 0
                return@also
            }
            
            aPositionHandle = GLES20.glGetAttribLocation(it, "aPosition")
            uMVPMatrixHandle = GLES20.glGetUniformLocation(it, "uMVPMatrix")
            uColorHandle = GLES20.glGetUniformLocation(it, "uColor")
            
            Log.d("Shader", "Shader initialized: aPosition=$aPositionHandle, uMVPMatrix=$uMVPMatrixHandle")
        }
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            Log.e("Shader", "Compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
