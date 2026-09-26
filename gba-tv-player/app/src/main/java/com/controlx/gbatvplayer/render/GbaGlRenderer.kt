package com.controlx.gbatvplayer.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GbaGlRenderer : GLSurfaceView.Renderer {

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        private val QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )

        // Texture coordinates (flipping Y as needed for screen orientation)
        private val QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )
    }

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureUniformHandle = 0
    private var textureId = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_TEX_COORDS)
            position(0)
        }

    @Volatile
    var isBilinear = false
        set(value) {
            field = value
            filterDirty = true
        }

    private var filterDirty = false
    private var frameBuffer: ByteBuffer? = null
    private val frameLock = Any()
    private var hasNewFrame = false

    private var surfaceWidth = 1280
    private var surfaceHeight = 720

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.05f, 0.07f, 1.0f)

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureUniformHandle = GLES20.glGetUniformLocation(program, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        val filter = if (isBilinear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Set unpack alignment for 16-bit RGB565 (2 bytes per pixel)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 2)

        // Allocate empty 240x160 RGB565 texture
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGB,
            240,
            160,
            0,
            GLES20.GL_RGB,
            GLES20.GL_UNSIGNED_SHORT_5_6_5,
            null
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        updateViewport()
    }

    private fun updateViewport() {
        // GBA aspect ratio is 240:160 = 3:2
        val targetAspect = 3.0f / 2.0f
        val screenAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()

        val vpWidth: Int
        val vpHeight: Int
        val vpX: Int
        val vpY: Int

        if (screenAspect > targetAspect) {
            // Screen is wider than 3:2 (pillarbox: black bars on left/right)
            vpHeight = surfaceHeight
            vpWidth = (surfaceHeight * targetAspect).toInt()
            vpX = (surfaceWidth - vpWidth) / 2
            vpY = 0
        } else {
            // Screen is narrower (letterbox)
            vpWidth = surfaceWidth
            vpHeight = (surfaceWidth / targetAspect).toInt()
            vpX = 0
            vpY = (surfaceHeight - vpHeight) / 2
        }

        GLES20.glViewport(vpX, vpY, vpWidth, vpHeight)
    }

    fun submitFrame(buffer: ByteBuffer) {
        synchronized(frameLock) {
            frameBuffer = buffer
            hasNewFrame = true
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)

        if (filterDirty) {
            val filter = if (isBilinear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            filterDirty = false
        }

        synchronized(frameLock) {
            val buf = frameBuffer
            if (buf != null && hasNewFrame) {
                buf.position(0)
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    240,
                    160,
                    GLES20.GL_RGB,
                    GLES20.GL_UNSIGNED_SHORT_5_6_5,
                    buf
                )
                hasNewFrame = false
            }
        }

        GLES20.glUseProgram(program)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureUniformHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        return prog
    }
}
