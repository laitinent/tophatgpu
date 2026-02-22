package com.example.tophatgpu

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.util.Size
import android.view.Surface
import androidx.annotation.RawRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A custom GLSurfaceView.Renderer that implements a GPU-accelerated Top-Hat transformation and Labeling.
 */
class TopHatRenderer(private val context: Context) : GLSurfaceView.Renderer {

    var kernelSize: Int = 7
    var isSubtractionEnabled: Boolean = true
    var isOpening: Boolean = true
    var isThresholdEnabled: Boolean = false
    var isLabelingEnabled: Boolean = false

    var onStatsUpdated: ((Int, Int) -> Unit)? = null

    private var frameCount = 0
    private var lastFpsTime = 0L
    private var lastLabelCount = 0
    private var glesVersion = "Unknown"

    private lateinit var cameraTexture: SurfaceTexture
    private var oesTex = 0

    private var width = 1920
    private var height = 1080
    
    private var screenWidth = 0
    private var screenHeight = 0

    private lateinit var quad: FullscreenQuad

    private var programOES = 0
    private var programErodeH = 0
    private var programErodeV = 0
    private var programDilateH = 0
    private var programDilateV = 0
    private var programSubtract = 0
    private var programThreshold = 0
    
    private var programLabelInit = 0
    private var programLabelPropagate = 0
    private var programLabelColor = 0
    private var programLabelClear = 0
    private var programLabelCount = 0

    private lateinit var fboGray: FBO
    private lateinit var fbo1: FBO
    private lateinit var fbo2: FBO
    private lateinit var fbo3: FBO
    private lateinit var fboResult: FBO
    
    private var labelTex1 = 0
    private var labelTex2 = 0
    
    private var ssboSeenFlags = 0
    private var ssboCounter = 0

    private val downsampleWidth = 256
    private val downsampleHeight = 144
    private lateinit var fboLowRes: FBO
    private val lowResPixelBuffer = ByteBuffer.allocateDirect(downsampleWidth * downsampleHeight * 4).order(ByteOrder.nativeOrder())

    private var preview: Preview? = null

    private val stMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).apply {
        android.opengl.Matrix.setIdentityM(this, 0)
    }

    private var otsuThreshold = 0.5f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glesVersion = GLES32.glGetString(GLES32.GL_VERSION) ?: "Unknown"
        
        oesTex = createOESTexture()
        cameraTexture = SurfaceTexture(oesTex)
        quad = FullscreenQuad()

        programOES = createProgram(R.raw.vertex_2d, R.raw.oes_to_2d_frag)
        programErodeH = createProgram(R.raw.vertex_2d, R.raw.erode_h_frag)
        programErodeV = createProgram(R.raw.vertex_2d, R.raw.erode_v_frag)
        programDilateH = createProgram(R.raw.vertex_2d, R.raw.dilate_h_frag)
        programDilateV = createProgram(R.raw.vertex_2d, R.raw.dilate_v_frag)
        programSubtract = createProgram(R.raw.vertex_2d, R.raw.subtract_frag)
        programThreshold = createProgram(R.raw.vertex_2d, R.raw.threshold_frag)
        
        programLabelInit = createComputeProgram(R.raw.label_init_comp)
        programLabelPropagate = createComputeProgram(R.raw.label_propagate_comp)
        programLabelColor = createProgram(R.raw.vertex_2d, R.raw.label_color_frag)
        programLabelClear = createComputeProgram(R.raw.label_clear_comp)
        programLabelCount = createComputeProgram(R.raw.label_count_comp)

        recreateFBOs(width, height)
        fboLowRes = FBO(downsampleWidth, downsampleHeight)
        
        setupSSBOs()

        startCamera(cameraTexture)
    }

    private fun recreateFBOs(w: Int, h: Int) {
        width = w
        height = h
        fboGray = FBO(width, height)
        fbo1 = FBO(width, height)
        fbo2 = FBO(width, height)
        fbo3 = FBO(width, height)
        fboResult = FBO(width, height)
        
        if (labelTex1 != 0) GLES32.glDeleteTextures(2, intArrayOf(labelTex1, labelTex2), 0)
        labelTex1 = createLabelTexture(width, height)
        labelTex2 = createLabelTexture(width, height)
    }

    private fun createLabelTexture(w: Int, h: Int): Int {
        val tex = IntArray(1)
        GLES32.glGenTextures(1, tex, 0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, tex[0])
        GLES32.glTexStorage2D(GLES32.GL_TEXTURE_2D, 1, GLES32.GL_R32UI, w, h)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_NEAREST)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_NEAREST)
        return tex[0]
    }

    private fun setupSSBOs() {
        val ssbos = IntArray(2)
        GLES32.glGenBuffers(2, ssbos, 0)
        ssboSeenFlags = ssbos[0]
        ssboCounter = ssbos[1]

        val numPixels = 1920 * 1080
        val flagsSize = ((numPixels + 1 + 31) / 32) * 4
        
        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, ssboSeenFlags)
        GLES32.glBufferData(GLES32.GL_SHADER_STORAGE_BUFFER, flagsSize, null, GLES32.GL_DYNAMIC_DRAW)
        
        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, ssboCounter)
        GLES32.glBufferData(GLES32.GL_SHADER_STORAGE_BUFFER, 4, null, GLES32.GL_DYNAMIC_READ)
        
        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        screenWidth = w
        screenHeight = h

        val rotation = (context as? Activity)?.let {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) it.display?.rotation else @Suppress("DEPRECATION") it.windowManager.defaultDisplay.rotation
        } ?: Surface.ROTATION_0
        
        preview?.targetRotation = rotation

        if (w > h && width < height) {
            recreateFBOs(1920, 1080)
        } else if (h > w && height < width) {
            recreateFBOs(1080, 1920)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        calculateStats()

        cameraTexture.updateTexImage()
        cameraTexture.getTransformMatrix(stMatrix)

        renderToFBO(fboGray, programOES, oesTex, true, stMatrix)

        if (isOpening) {
            renderToFBO(fbo1, programErodeH, fboGray.texture, false, identityMatrix)
            renderToFBO(fbo2, programErodeV, fbo1.texture, false, identityMatrix)
            renderToFBO(fbo1, programDilateH, fbo2.texture, false, identityMatrix)
            renderToFBO(fbo3, programDilateV, fbo1.texture, false, identityMatrix)
        } else {
            renderToFBO(fbo1, programDilateH, fboGray.texture, false, identityMatrix)
            renderToFBO(fbo2, programDilateV, fbo1.texture, false, identityMatrix)
            renderToFBO(fbo1, programErodeH, fbo2.texture, false, identityMatrix)
            renderToFBO(fbo3, programErodeV, fbo1.texture, false, identityMatrix)
        }

        val finalTexture = if (isSubtractionEnabled) {
            if (isOpening) {
                renderToFBO(fboResult, programSubtract, fboGray.texture, fbo3.texture)
            } else {
                renderToFBO(fboResult, programSubtract, fbo3.texture, fboGray.texture)
            }
            fboResult.texture
        } else {
            fbo3.texture
        }

        if (isThresholdEnabled) {
            updateThreshold(finalTexture)
            
            if (isLabelingEnabled) {
                doLabeling(finalTexture, otsuThreshold)
                countLabelsGpu()
                renderLabelsToScreen()
            } else {
                renderThresholdToScreen(finalTexture, otsuThreshold)
            }
        } else {
            renderToScreen(finalTexture)
        }
    }
    
    private fun doLabeling(textureId: Int, threshold: Float) {
        GLES32.glUseProgram(programLabelInit)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, textureId)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(programLabelInit, "uTexture"), 0)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(programLabelInit, "uThreshold"), threshold)
        GLES32.glBindImageTexture(0, labelTex1, 0, false, 0, GLES32.GL_WRITE_ONLY, GLES32.GL_R32UI)
        
        GLES32.glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1)
        GLES32.glMemoryBarrier(GLES32.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)

        GLES32.glUseProgram(programLabelPropagate)
        val propagateIterations = 16 
        var readTex = labelTex1
        var writeTex = labelTex2
        
        for (i in 0 until propagateIterations) {
            GLES32.glBindImageTexture(0, readTex, 0, false, 0, GLES32.GL_READ_ONLY, GLES32.GL_R32UI)
            GLES32.glBindImageTexture(1, writeTex, 0, false, 0, GLES32.GL_WRITE_ONLY, GLES32.GL_R32UI)
            
            GLES32.glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1)
            GLES32.glMemoryBarrier(GLES32.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            
            val tmp = readTex
            readTex = writeTex
            writeTex = tmp
        }
        
        labelTex1 = readTex
        labelTex2 = writeTex
    }

    private fun countLabelsGpu() {
        GLES32.glUseProgram(programLabelClear)
        GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 1, ssboSeenFlags)
        GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 2, ssboCounter)
        
        val numPixels = width * height
        val flagsWords = (numPixels + 1 + 31) / 32
        GLES32.glDispatchCompute((flagsWords + 255) / 256, 1, 1)
        GLES32.glMemoryBarrier(GLES32.GL_SHADER_STORAGE_BARRIER_BIT)

        GLES32.glUseProgram(programLabelCount)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, labelTex1)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(programLabelCount, "uLabelTexture"), 0)
        
        GLES32.glDispatchCompute((width + 15) / 16, (height + 15) / 16, 1)
        GLES32.glMemoryBarrier(GLES32.GL_SHADER_STORAGE_BARRIER_BIT)

        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, ssboCounter)
        val mappedBuffer = GLES32.glMapBufferRange(
            GLES32.GL_SHADER_STORAGE_BUFFER,
            0, 4,
            GLES32.GL_MAP_READ_BIT
        ) as? ByteBuffer
        
        if (mappedBuffer != null) {
            mappedBuffer.order(ByteOrder.nativeOrder())
            lastLabelCount = mappedBuffer.getInt(0)
            GLES32.glUnmapBuffer(GLES32.GL_SHADER_STORAGE_BUFFER)
        }
        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, 0)
    }
    
    private fun renderLabelsToScreen() {
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        GLES32.glViewport(0, 0, screenWidth, screenHeight)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        
        GLES32.glUseProgram(programLabelColor)
        quad.bind()
        
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, labelTex1)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(programLabelColor, "uLabelTexture"), 0)
        GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(programLabelColor, "uSTMatrix"), 1, false, identityMatrix, 0)
        
        quad.draw()
    }

    private fun updateThreshold(textureId: Int) {
        renderToFBO(fboLowRes, programErodeH, textureId, false, identityMatrix, true)

        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fboLowRes.fbo)
        lowResPixelBuffer.rewind()
        GLES32.glReadPixels(0, 0, downsampleWidth, downsampleHeight, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, lowResPixelBuffer)
        
        val histogram = IntArray(256)
        val numPixels = downsampleWidth * downsampleHeight
        for (i in 0 until numPixels) {
            val r = lowResPixelBuffer.get(i * 4).toInt() and 0xFF
            histogram[r]++
        }
        
        otsuThreshold = calculateOtsuThreshold(histogram, numPixels) / 255f
    }

    private fun calculateOtsuThreshold(histogram: IntArray, totalPixels: Int): Int {
        var sum = 0f
        for (i in 0..255) sum += (i * histogram[i]).toFloat()

        var sumB = 0f
        var wB = 0
        var wF = 0
        var varMax = 0f
        var threshold = 0

        for (i in 0..255) {
            wB += histogram[i]
            if (wB == 0) continue
            wF = totalPixels - wB
            if (wF == 0) break

            sumB += (i * histogram[i]).toFloat()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF

            val varBetween = wB.toFloat() * wF.toFloat() * (mB - mF) * (mB - mF)

            if (varBetween > varMax) {
                varMax = varBetween
                threshold = i
            }
        }
        return threshold
    }

    private fun calculateStats() {
        frameCount++
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastFpsTime
        if (elapsed >= 1000) {
            val fps = (frameCount * 1000 / elapsed).toInt()
            onStatsUpdated?.invoke(fps, lastLabelCount)
            frameCount = 0
            lastFpsTime = currentTime
        }
    }

    private fun renderToFBO(fbo: FBO, program: Int, tex1: Int, tex2: Int) {
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fbo.fbo)
        GLES32.glViewport(0, 0, fbo.width, fbo.height)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)

        GLES32.glUseProgram(program)
        quad.bind()

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, tex1)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "original"), 0)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE1)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, tex2)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "opened"), 1)

        GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(program, "uSTMatrix"), 1, false, identityMatrix, 0)

        quad.draw()
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
    }

    private fun renderToFBO(fbo: FBO, program:Int, texture:Int, oes:Boolean=false, matrix: FloatArray, isBlit: Boolean = false){
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, fbo.fbo)
        GLES32.glViewport(0, 0, fbo.width, fbo.height)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)

        GLES32.glUseProgram(program)
        quad.bind()

        GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(program, "uSTMatrix"), 1, false, matrix, 0)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        if(oes)
            GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        else
            GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texture)

        GLES32.glUniform1i(GLES32.glGetUniformLocation(program,"uTexture"),0)
        
        if (isBlit) {
            GLES32.glUniform1i(GLES32.glGetUniformLocation(program,"kernelSize"), 0)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(program,"texelWidth"), 0f)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(program,"texelHeight"), 0f)
        } else {
            GLES32.glUniform1i(GLES32.glGetUniformLocation(program,"kernelSize"), kernelSize)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(program,"texelWidth"), 1f/width)
            GLES32.glUniform1f(GLES32.glGetUniformLocation(program,"texelHeight"), 1f/height)
        }

        quad.draw()
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER,0)
    }

    private fun renderToScreen(texture: Int) {
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        GLES32.glViewport(0, 0, screenWidth, screenHeight)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        GLES32.glUseProgram(programErodeH)
        quad.bind()
        GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(programErodeH, "uSTMatrix"), 1, false, identityMatrix, 0)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texture)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(programErodeH, "uTexture"), 0)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(programErodeH, "texelWidth"), 0f)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(programErodeH, "kernelSize"), 0)
        quad.draw()
    }

    private fun renderThresholdToScreen(texture: Int, threshold: Float) {
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
        GLES32.glViewport(0, 0, screenWidth, screenHeight)
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        GLES32.glUseProgram(programThreshold)
        quad.bind()
        GLES32.glUniformMatrix4fv(GLES32.glGetUniformLocation(programThreshold, "uSTMatrix"), 1, false, identityMatrix, 0)
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texture)
        GLES32.glUniform1i(GLES32.glGetUniformLocation(programThreshold, "uTexture"), 0)
        GLES32.glUniform1f(GLES32.glGetUniformLocation(programThreshold, "uThreshold"), threshold)
        quad.draw()
    }

    private fun createOESTexture(): Int {
        val tex = IntArray(1)
        GLES32.glGenTextures(1, tex, 0)
        GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)
        return tex[0]
    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val rotation = (context as? Activity)?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) it.display?.rotation else @Suppress("DEPRECATION") it.windowManager.defaultDisplay.rotation
            } ?: Surface.ROTATION_0
            val resolutionSelector = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()
            val previewInstance = Preview.Builder().setResolutionSelector(resolutionSelector).setTargetRotation(rotation).build()
            this.preview = previewInstance
            previewInstance.setSurfaceProvider { request ->
                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { surface.release() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(context as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, previewInstance)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun createProgram(vertexRes: Int, fragmentRes: Int): Int {
        val vertexShader = compileShader(GLES32.GL_VERTEX_SHADER, loadRawResource(vertexRes))
        val fragmentShader = compileShader(GLES32.GL_FRAGMENT_SHADER, loadRawResource(fragmentRes))
        val program = GLES32.glCreateProgram()
        GLES32.glAttachShader(program, vertexShader)
        GLES32.glAttachShader(program, fragmentShader)
        GLES32.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES32.glGetProgramiv(program, GLES32.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) throw RuntimeException("Program link error: ${GLES32.glGetProgramInfoLog(program)}")
        return program
    }
    
    private fun createComputeProgram(computeRes: Int): Int {
        val computeShader = compileShader(GLES32.GL_COMPUTE_SHADER, loadRawResource(computeRes))
        val program = GLES32.glCreateProgram()
        GLES32.glAttachShader(program, computeShader)
        GLES32.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES32.glGetProgramiv(program, GLES32.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) throw RuntimeException("Compute program link error: ${GLES32.glGetProgramInfoLog(program)}")
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES32.glCreateShader(type)
        GLES32.glShaderSource(shader, source)
        GLES32.glCompileShader(shader)
        val status = IntArray(1)
        GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Shader compile error: ${GLES32.glGetShaderInfoLog(shader)}")
        return shader
    }

    private fun loadRawResource(@RawRes id: Int): String {
        return context.resources.openRawResource(id).bufferedReader().use { it.readText() }
    }

    fun getGlesVersion(): String {
        return glesVersion
    }
}
