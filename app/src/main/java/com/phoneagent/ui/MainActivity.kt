package com.phoneagent.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.phoneagent.controller.AgentController
import com.phoneagent.service.AgentAccessibilityService
import com.phoneagent.service.ScreenCaptureService
import com.phoneagent.voice.LocalGemmaBrain
import com.phoneagent.voice.VoiceInteractionManager
import java.io.File

/**
 * Setup + control screen.
 */
class MainActivity : ComponentActivity() {

    private lateinit var voice: VoiceInteractionManager
    private lateinit var statusText: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var logText: TextView
    private var activeController: AgentController? = null
    private var localBrain: LocalGemmaBrain? = null

    private val localModelFile: File
        get() = File(filesDir, "gemma3n.task")

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        statusText.text = if (granted) "Mic: granted" else "Mic: denied - can't listen without it"
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(serviceIntent)
            appendLog("Screen capture: running")
        } else {
            appendLog("Screen capture: permission denied")
        }
    }

    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            appendLog("Model file pick cancelled")
            return@registerForActivityResult
        }
        copyModelFileIntoAppStorage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        voice = VoiceInteractionManager(this)
        voice.initialize { appendLog("Voice engine ready") }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        statusText = TextView(this).apply { text = "Status: not started (on-device only, no cloud, no API key)" }
        root.addView(statusText)

        root.addView(Button(this).apply {
            text = "1. Enable Accessibility Service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = "2. Start Screen Capture"
            setOnClickListener {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        })

        root.addView(Button(this).apply {
            text = "3. Grant Mic Permission"
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    appendLog("Mic already granted")
                }
            }
        })

        root.addView(Button(this).apply {
            text = "4. Pick Gemma model file (.task)"
            setOnClickListener {
                modelPickerLauncher.launch(arrayOf("*/*"))
            }
        })

        modelStatusText = TextView(this).apply {
            text = if (localModelFile.exists())
                "Model: ready (${localModelFile.length() / 1_000_000} MB)"
            else
                "Model: not loaded yet - use step 4"
        }
        root.addView(modelStatusText)

        root.addView(Button(this).apply {
            text = "Talk to agent (mic)"
            setOnClickListener { startListeningForTask() }
        })

        root.addView(Button(this).apply {
            text = "Stop current task"
            setOnClickListener {
                activeController?.stop()
                localBrain?.close()
                localBrain = null
                appendLog("Stopped current task")
                voice.speak("Stopped.")
            }
        })

        logText = TextView(this).apply { text = "" }
        root.addView(ScrollView(this).apply { addView(logText) })

        setContentView(root)
    }

    private fun copyModelFileIntoAppStorage(sourceUri: Uri) {
        appendLog("Copying model file - this can take a minute for a multi-GB file...")
        Thread {
            try {
                contentResolver.openInputStream(sourceUri)?.use { input ->
                    localModelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                runOnUiThread {
                    modelStatusText.text = "Model: ready (${localModelFile.length() / 1_000_000} MB)"
                    appendLog("Model copied successfully to app storage")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("Failed to copy model file: ${e.message}")
                }
            }
        }.start()
    }

    private fun startListeningForTask() {
        if (AgentAccessibilityService.instance == null) {
            appendLog("Can't start - enable accessibility service first (step 1)")
            voice.speak("Please enable the accessibility service first.")
            return
        }
        if (ScreenCaptureService.latestFrame.get() == null) {
            appendLog("Can't start - start screen capture first (step 2)")
            voice.speak("Please start screen capture first.")
            return
        }
        if (!localModelFile.exists()) {
            appendLog("Can't start - pick the model file first (step 4)")
            voice.speak("Please pick the model file first.")
            return
        }

        appendLog("Listening...")
        voice.listenOnce(
            onListeningStarted = { appendLog("(listening)") },
            onResult = { spokenGoal ->
                appendLog("Heard: \"$spokenGoal\"")
                voice.speak("On it: $spokenGoal")
                runGoal(spokenGoal)
            },
            onError = { error ->
                appendLog("Didn't catch that ($error)")
                voice.speak("Sorry, I didn't catch that.")
            }
        )
    }

    private fun runGoal(goal: String) {
        activeController?.stop()
        localBrain?.close()

        appendLog("Loading on-device model (first run after boot may take a moment)...")
        val brain = LocalGemmaBrain(context = this, goal = goal, modelPath = localModelFile.absolutePath)
        localBrain = brain

        val controller = AgentController(
            brain = brain,
            tickIntervalMs = 2000,
            onSpeak = { text ->
                appendLog("Agent says: $text")
                voice.speak(text)
            },
            onTaskComplete = { summary ->
                appendLog("Done: $summary")
                voice.speak(summary)
                localBrain?.close(); localBrain = null
            },
            onNeedsClarification = { question ->
                appendLog("Agent needs input: $question")
                voice.speak(question)
                localBrain?.close(); localBrain = null
            }
        )
        activeController = controller
        controller.start()
    }

    private fun appendLog(line: String) {
        runOnUiThread { logText.append("$line\n") }
    }

    override fun onDestroy() {
        voice.destroy()
        activeController?.stop()
        localBrain?.close()
        super.onDestroy()
    }
}
