package com.example.tophatgpu

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * A custom SurfaceView that integrates with the TopHatWebGPURenderer.
 */
class MyWebGPUSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    val renderer: TopHatWebGPURenderer = TopHatWebGPURenderer(context)

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderer.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.setSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer.releaseSurface()
    }
}
