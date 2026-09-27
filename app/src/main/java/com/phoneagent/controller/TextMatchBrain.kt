package com.phoneagent.controller

import android.graphics.Bitmap
import com.phoneagent.service.AgentAccessibilityService

/**
 * Text-match brain: taps the first clickable UI element whose visible text
 * matches any entry in the supplied keyword list.
 */
class TextMatchBrain(
    private val keywords: List<String> = listOf("play", "start", "next", "continue", "ok", "yes")
) : AgentBrain {
    override suspend fun decideNextAction(
        screenshot: Bitmap,
        uiTree: List<AgentAccessibilityService.NodeInfo>
    ): AgentAction {
        val match = uiTree.firstOrNull { node ->
            node.clickable && node.text != null &&
                keywords.any { node.text!!.contains(it, ignoreCase = true) }
        }
        return if (match != null) {
            AgentAction.Tap(match.bounds.centerX().toFloat(), match.bounds.centerY().toFloat())
        } else {
            AgentAction.Wait
        }
    }
}
