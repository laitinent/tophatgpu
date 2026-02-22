package com.example.tophatgpu

import android.opengl.GLES30

/**
 * A helper class for managing Framebuffer Objects (FBO) and their associated textures.
 *
 * @param width The width of the framebuffer.
 * @param height The height of the framebuffer.
 */
class FBO(val width:Int,val height:Int){

    /**
     * The Framebuffer Object ID.
     */
    val fbo:Int

    /**
     * The Texture ID attached to the color buffer of the FBO.
     */
    val texture:Int

    init{
        val fbos=IntArray(1)
        val tex=IntArray(1)

        GLES30.glGenFramebuffers(1,fbos,0)
        GLES30.glGenTextures(1,tex,0)

        fbo=fbos[0]
        texture=tex[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,GLES30.GL_RGBA,width,height,0,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,texture,0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0)
    }
}
