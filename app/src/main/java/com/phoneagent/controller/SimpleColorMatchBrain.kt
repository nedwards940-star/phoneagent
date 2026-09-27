package com.phoneagent.controller

import android.graphics.Bitmap
import com.phoneagent.service.AgentAccessibilityService

/**
 * Rule-based brain: taps the first clickable element whose text contains the
 * given keyword. Free, fast, zero API cost. Good for simple deterministic games.
 */
class SimpleColorMatchBrain(
    private val targetKeyword: String = "play"
) : AgentBrain {
    override suspend fun decideNextAction(
        screenshot: Bitmap,
        uiTree: List<AgentAccessibilityService.NodeInfo>
    ): AgentAction {
        val match = uiTree.firstOrNull { it.clickable && it.text != null && it.text.contains(targetKeyword, ignoreCase = true) }
        return if (match != null) {
            val x = match.bounds.centerX().toFloat()
            val y = match.bounds.centerY().toFloat()
            AgentAction.Tap(x, y)
        } else {
            AgentAction.Wait
        }
    }
}
