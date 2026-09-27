package com.phoneagent.controller

import android.graphics.Bitmap
import com.phoneagent.service.AgentAccessibilityService

/**
 * Stub brain that always waits. Replace with real logic or use one of the
 * provided brains (SimpleColorMatchBrain, TextMatchBrain, LocalGemmaBrain).
 */
class GoalDrivenBrain : AgentBrain {
    override suspend fun decideNextAction(
        screenshot: Bitmap,
        uiTree: List<AgentAccessibilityService.NodeInfo>
    ): AgentAction = AgentAction.Wait
}
