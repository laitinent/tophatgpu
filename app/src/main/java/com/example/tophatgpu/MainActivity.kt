package com.example.tophatgpu

import android.Manifest
import android.opengl.GLES32
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var glView: MyGLSurfaceView
    private lateinit var tvFps: TextView
    private var thresholdMode = 0 // 0: None, 1: Threshold, 2: Threshold and Label

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.glView)
        tvFps = findViewById(R.id.tv_fps)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<Button>(R.id.btn_kernel_original).setOnClickListener {
            glView.renderer.kernelSize = 7
        }
        findViewById<Button>(R.id.btn_kernel_x2).setOnClickListener {
            glView.renderer.kernelSize = 14
        }
        findViewById<Button>(R.id.btn_kernel_x4).setOnClickListener {
            glView.renderer.kernelSize = 28
        }

        findViewById<ToggleButton>(R.id.toggle_subtraction).setOnCheckedChangeListener { _, isChecked ->
            glView.renderer.isSubtractionEnabled = isChecked
        }

        findViewById<ToggleButton>(R.id.toggle_op_cl).setOnCheckedChangeListener { _, isChecked ->
            glView.renderer.isOpening = isChecked
        }

        val btnThresholdMode = findViewById<Button>(R.id.btn_threshold_mode)
        btnThresholdMode.setOnClickListener {
            thresholdMode = (thresholdMode + 1) % 3
            when (thresholdMode) {
                0 -> {
                    btnThresholdMode.text = "None"
                    glView.renderer.isThresholdEnabled = false
                    glView.renderer.isLabelingEnabled = false
                }
                1 -> {
                    btnThresholdMode.text = "Threshold"
                    glView.renderer.isThresholdEnabled = true
                    glView.renderer.isLabelingEnabled = false
                }
                2 -> {
                    btnThresholdMode.text = "Labeling"
                    glView.renderer.isThresholdEnabled = true
                    glView.renderer.isLabelingEnabled = true
                }
            }
        }

        findViewById<ImageButton>(R.id.btn_info).setOnClickListener {
            showInfoDialog()
        }

        glView.renderer.onStatsUpdated = { fps, labels ->
            runOnUiThread {
                if (glView.renderer.isLabelingEnabled) {
                    tvFps.text = "FPS: $fps | Labels: $labels"
                } else {
                    tvFps.text = "FPS: $fps"
                }
            }
        }

        requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)
    }

    private fun showInfoDialog() {
        val glVersion = glView.renderer.getGlesVersion()
        val message = """
            Top-Hat GPU Transformation Guide:
            - Original / x2 / x4: Adjust kernel size.
            - Sub ON/OFF: Toggle subtraction pass.
            - Opening/Closing: Toggle morphological mode.
            - None/Threshold/Labeling: Cycle output mode.
            
            System Info:
            - Graphics API: $glVersion
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Information")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }
}
