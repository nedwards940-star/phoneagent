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
import com.phoneagent.voice.GemmaModelDownloader
import com.phoneagent.voice.LocalGemmaBrain
import com.phoneagent.voice.VoiceInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Setup + control screen.
 *
 * Model sourcing (in priority order):
 *   1. Automatic download from Hugging Face via GemmaModelDownloader
 *   2. Manual pick from Downloads via system file picker (fallback)
 *
 * On app start, checks whether a model update is available and downloads it
 * automatically if the remote file size differs from the local one.
 */
class MainActivity : ComponentActivity() {

    private lateinit var voice: VoiceInteractionManager
    private lateinit var statusText: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var logText: TextView
    private var activeController: AgentController? = null
    private var localBrain: LocalGemmaBrain? = null
    private val downloader = GemmaModelDownloader(this)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Button references for enabling/disabling the model download buttons
    private var downloadBtn: Button? = null
    private var pickBtn: Button? = null

    private val localModelFile: File
        get() = downloader.getLocalModelPath()?.let { File(it) }
              ?: File(filesDir, "gemma-3n-E2B-it-int4.task")

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

        // Auto-update: check for newer model on startup (background, non-blocking)
        scope.launch(Dispatchers.IO) {
            try {
                val updated = downloader.checkAndAutoUpdate(
                    onProgress = { downloaded, total ->
                        runOnUiThread {
                            modelStatusText.text = "Model: updating... ${downloaded / 1_000_000} / ${total / 1_000_000} MB"
                        }
                    },
                    onError = { error ->
                        runOnUiThread { appendLog("Auto-update check: $error") }
                    }
                )
                if (updated) {
                    runOnUiThread {
                        appendLog("Model auto-updated from Hugging Face")
                        updateModelStatus()
                    }
                } else if (!downloader.hasModel()) {
                    runOnUiThread {
                        appendLog("No model found - use the download button or pick manually")
                        updateModelStatus()
                    }
                } else {
                    runOnUiThread {
                        appendLog("Model is up to date")
                        updateModelStatus()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Auto-update check failed: ${e.message}") }
            }
        }

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

        root.addView(downloadBtn = Button(this).apply {
            text = "4. Download Gemma model (Hugging Face)"
            setOnClickListener { startModelDownload() }
        })

        root.addView(pickBtn = Button(this).apply {
            text = "4b. Pick model file manually (fallback)"
            setOnClickListener {
                modelPickerLauncher.launch(arrayOf("*/*"))
            }
        })

        modelStatusText = TextView(this)
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

        root.addView(Button(this).apply {
            text = "Delete model & free space"
            setOnClickListener {
                downloader.deleteModel()
                appendLog("Model file deleted")
                updateModelStatus()
                voice.speak("Model deleted.")
            }
        })

        logText = TextView(this).apply { text = "" }
        root.addView(ScrollView(this).apply { addView(logText) })

        setContentView(root)
        updateModelStatus()
    }

    private fun updateModelStatus() {
        val has = downloader.hasModel()
        val sizeMB = if (has) downloader.localSizeBytes() / 1_000_000 else 0
        modelStatusText.text = if (has)
            "Model: ready (${sizeMB} MB) — auto-updates from Hugging Face"
        else
            "Model: not loaded — tap 'Download Gemma model' or pick manually"
    }

    private fun startModelDownload() {
        if (!downloader.hasModel()) {
            appendLog("Downloading Gemma 3n from Hugging Face...")
        } else {
            appendLog("Model already present. Tap again to re-download (auto-update).")
        }
        setModelButtonEnabled(false, "Downloading...")
        scope.launch(Dispatchers.IO) {
            downloader.download(
                onProgress = { downloaded, total ->
                    runOnUiThread {
                        val totalStr = if (total > 0) " / ${total / 1_000_000} MB" else " (size unknown)"
                        modelStatusText.text = "Model: downloading... ${downloaded / 1_000_000} MB$totalStr"
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        appendLog("Download failed: $error")
                        setModelButtonEnabled(true, "4. Download Gemma model (Hugging Face)")
                        updateModelStatus()
                    }
                },
                onComplete = {
                    runOnUiThread {
                        appendLog("Model downloaded successfully from Hugging Face")
                        setModelButtonEnabled(true, "4. Download Gemma model (Hugging Face)")
                        updateModelStatus()
                    }
                }
            )
        }
    }

    private fun setModelButtonEnabled(enabled: Boolean, text: String) {
        runOnUiThread {
            downloadBtn?.isEnabled = enabled
            downloadBtn?.text = text
            pickBtn?.isEnabled = enabled
        }
    }

    private fun copyModelFileIntoAppStorage(sourceUri: Uri) {
        appendLog("Copying model file from picker - this can take a minute for a multi-GB file...")
        scope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(sourceUri)?.use { input ->
                    val dest = File(filesDir, "gemma-3n-E2B-it-int4.task")
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                runOnUiThread {
                    appendLog("Model copied successfully to app storage")
                    updateModelStatus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("Failed to copy model file: ${e.message}")
                }
            }
        }
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
        if (!downloader.hasModel()) {
            appendLog("Can't start - download or pick the model file first (step 4)")
            voice.speak("Please get the model file first.")
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
        val brain = LocalGemmaBrain(
            context = this,
            goal = goal,
            modelPath = downloader.getLocalModelPath() ?: return
        )
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
