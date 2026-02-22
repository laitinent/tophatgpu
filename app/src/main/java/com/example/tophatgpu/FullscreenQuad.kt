package com.example.tophatgpu

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A utility class to create and draw a full-screen quad.
 * 
 * This class handles the initialization of Vertex Array Objects (VAO) and
 * Vertex Buffer Objects (VBO) for a simple quad that covers the entire viewport.
 */
class FullscreenQuad {

    private val vao = IntArray(1)
    private val vbo = IntArray(1)

    init {
        val vertices = floatArrayOf(
            -1f,-1f, 0f,0f,
            1f,-1f, 1f,0f,
            -1f, 1f, 0f,1f,
            1f, 1f, 1f,1f
        )

        val buffer = ByteBuffer.allocateDirect(vertices.size*4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        buffer.position(0)

        GLES30.glGenVertexArrays(1,vao,0)
        GLES30.glGenBuffers(1,vbo,0)

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,vertices.size*4,buffer,GLES30.GL_STATIC_DRAW)

        val stride = 4*4

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0,2,GLES30.GL_FLOAT,false,stride,0)

        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1,2,GLES30.GL_FLOAT,false,stride,2*4)

        GLES30.glBindVertexArray(0)
    }

    /**
     * Binds the Vertex Array Object (VAO) for the quad.
     */
    fun bind() {
        GLES30.glBindVertexArray(vao[0])
    }

    /**
     * Draws the quad using triangle strips.
     */
    fun draw() {
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP,0,4)
    }
}
