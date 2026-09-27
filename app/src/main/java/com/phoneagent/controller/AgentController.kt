package com.phoneagent.controller

import android.graphics.Bitmap
import android.util.Log
import com.phoneagent.service.AgentAccessibilityService
import com.phoneagent.service.ScreenCaptureService
import kotlinx.coroutines.*

/**
 * Actions the "brain" can decide to take. Keep this small and generic so it
 * works across any game/app, rather than modeling game-specific concepts.
 */
sealed class AgentAction {
    data class Tap(val x: Float, val y: Float) : AgentAction()
    data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long = 300) : AgentAction()
    data class LongPress(val x: Float, val y: Float) : AgentAction()
    data class TypeText(val text: String) : AgentAction()
    object Wait : AgentAction()
    object Stop : AgentAction()
    data class Speak(val text: String) : AgentAction()
    data class TaskComplete(val summary: String) : AgentAction()
    data class NeedsClarification(val question: String) : AgentAction()
}

/**
 * Plug in the actual intelligence here.
 */
interface AgentBrain {
    suspend fun decideNextAction(screenshot: Bitmap, uiTree: List<AgentAccessibilityService.NodeInfo>): AgentAction
}

/**
 * Runs the perceive -> decide -> act loop.
 */
class AgentController(
    private val brain: AgentBrain,
    private val tickIntervalMs: Long = 800,
    private val onSpeak: (String) -> Unit = {},
    private val onTaskComplete: (String) -> Unit = {},
    private val onNeedsClarification: (String) -> Unit = {}
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val frame = ScreenCaptureService.latestFrame.get()
                val service = AgentAccessibilityService.instance

                if (frame == null || service == null) {
                    Log.w("AgentController", "Waiting for screen capture / accessibility service to be ready...")
                    delay(tickIntervalMs)
                    continue
                }

                val uiTree = service.dumpScreenTree()
                val action = try {
                    brain.decideNextAction(frame, uiTree)
                } catch (e: Exception) {
                    Log.e("AgentController", "Brain failed to decide action", e)
                    AgentAction.Wait
                }

                when (action) {
                    is AgentAction.Tap -> service.tap(action.x, action.y)
                    is AgentAction.Swipe -> service.swipe(action.x1, action.y1, action.x2, action.y2, action.durationMs)
                    is AgentAction.LongPress -> service.longPress(action.x, action.y)
                    is AgentAction.TypeText -> service.typeIntoFocusedField(action.text)
                    AgentAction.Wait -> {}
                    is AgentAction.Speak -> onSpeak(action.text)
                    is AgentAction.TaskComplete -> {
                        onTaskComplete(action.summary)
                        stop()
                        return@launch
                    }
                    is AgentAction.NeedsClarification -> {
                        onNeedsClarification(action.question)
                        stop()
                        return@launch
                    }
                    AgentAction.Stop -> {
                        stop()
                        return@launch
                    }
                }

                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
