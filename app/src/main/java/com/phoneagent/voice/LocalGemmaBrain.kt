package com.phoneagent.voice

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.phoneagent.controller.AgentAction
import com.phoneagent.controller.AgentBrain
import com.phoneagent.service.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * On-device brain via Gemma 3n through MediaPipe's LLM Inference API.
 */
class LocalGemmaBrain(
    context: Context,
    private val goal: String,
    private val modelPath: String = "/data/local/tmp/llm/gemma3n.task",
    private val maxSteps: Int = 25
) : AgentBrain {

    private val history = mutableListOf<String>()
    private var stepCount = 0

    private val llmInference: LlmInference = LlmInference.createFromOptions(
        context,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxNumImages(1)
            .build()
    )

    private val session: LlmInferenceSession = LlmInferenceSession.createFromOptions(
        llmInference,
        LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTemperature(0.2f)
            .setGraphOptions(
                GraphOptions.builder().setEnableVisionModality(true).build()
            )
            .build()
    )

    override suspend fun decideNextAction(
        screenshot: Bitmap,
        uiTree: List<AgentAccessibilityService.NodeInfo>
    ): AgentAction = withContext(Dispatchers.Default) {
        stepCount++
        if (stepCount > maxSteps) {
            return@withContext AgentAction.NeedsClarification(
                "I've tried $maxSteps steps on-device without finishing '$goal' - want me to keep going, or switch to the cloud model?"
            )
        }

        val uiSummary = uiTree.take(30).joinToString("\n") { node ->
            "- text=\"${node.text ?: ""}\" desc=\"${node.contentDescription ?: ""}\" clickable=${node.clickable} bounds=${node.bounds}"
        }

        val prompt = """
            You control an Android phone by tapping, swiping, and typing to complete a task.
            GOAL: "$goal"
            Steps so far: ${if (history.isEmpty()) "none yet" else history.joinToString("; ")}
            Screen is ${screenshot.width}x${screenshot.height} px. Visible elements:
            $uiSummary

            Reply with ONLY one JSON object, nothing else:
            {"action":"tap","x":0,"y":0}
            {"action":"swipe","x":0,"y":0,"x2":0,"y2":0}
            {"action":"type","text":"..."}
            {"action":"wait"}
            {"action":"done","summary":"..."}
            {"action":"needs_clarification","question":"..."}
        """.trimIndent()

        val action = try {
            session.addQueryChunk(prompt)
            session.addImage(BitmapImageBuilder(screenshot).build())
            val response = session.generateResponse()
            parseAction(response)
        } catch (e: Exception) {
            AgentAction.NeedsClarification("The on-device model hit an error: ${e.message}")
        }

        history.add(describeForHistory(action))
        if (history.size > 12) history.removeAt(0)
        action
    }

    private fun parseAction(raw: String): AgentAction {
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd < jsonStart) return AgentAction.Wait

        val json = try {
            JSONObject(raw.substring(jsonStart, jsonEnd + 1))
        } catch (e: Exception) {
            return AgentAction.Wait
        }

        return when (json.optString("action")) {
            "tap" -> AgentAction.Tap(json.optDouble("x").toFloat(), json.optDouble("y").toFloat())
            "swipe" -> AgentAction.Swipe(
                json.optDouble("x").toFloat(), json.optDouble("y").toFloat(),
                json.optDouble("x2").toFloat(), json.optDouble("y2").toFloat()
            )
            "type" -> AgentAction.TypeText(json.optString("text"))
            "done" -> AgentAction.TaskComplete(json.optString("summary", "Task finished"))
            "needs_clarification" -> AgentAction.NeedsClarification(
                json.optString("question", "Can you clarify the task?")
            )
            else -> AgentAction.Wait
        }
    }

    private fun describeForHistory(action: AgentAction): String = when (action) {
        is AgentAction.Tap -> "tapped (${action.x.toInt()},${action.y.toInt()})"
        is AgentAction.Swipe -> "swiped (${action.x1.toInt()},${action.y1.toInt()})->(${action.x2.toInt()},${action.y2.toInt()})"
        is AgentAction.TypeText -> "typed \"${action.text}\""
        AgentAction.Wait -> "waited"
        is AgentAction.Speak -> "said \"${action.text}\""
        is AgentAction.TaskComplete -> "completed: ${action.summary}"
        is AgentAction.NeedsClarification -> "asked: ${action.question}"
        AgentAction.Stop -> "stopped"
        is AgentAction.LongPress -> "long-pressed (${action.x.toInt()},${action.y.toInt()})"
    }

    fun close() {
        session.close()
        llmInference.close()
    }
}
