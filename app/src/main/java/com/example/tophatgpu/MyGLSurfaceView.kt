package com.example.tophatgpu

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * A custom GLSurfaceView that sets up the OpenGL ES context and registers the renderer.
 */
class MyGLSurfaceView : GLSurfaceView {

    /**
     * The renderer instance used by this view.
     */
    lateinit var renderer: TopHatRenderer
        private set

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context)
    }

    private fun init(context: Context) {
        setEGLContextClientVersion(3)
        // Request GLES 3.1 context to use Compute Shaders
        // Note: setEGLContextClientVersion(3) usually provides 3.1 if available on Pixel 7a
        renderer = TopHatRenderer(context)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
