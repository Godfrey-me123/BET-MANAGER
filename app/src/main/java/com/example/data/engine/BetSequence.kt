package com.example.data.engine

import com.example.data.model.StrategyConfig
import com.example.data.model.RiskLimits
import com.example.service.AutoTouchDriver
import kotlinx.coroutines.delay

class BetSequence(
    private val driver: AutoTouchDriver
) {

    suspend fun executeBet(
        proposedStake: Double,
        targetMultiplier: Double,
        gamePhase: String,
        isBettingSessionActive: Boolean,
        isAutoBetEnabled: Boolean
    ): BetResult {
        // 1. Pre-checks
        if (gamePhase != "BETTING_OPEN") {
            return BetResult.Aborted("Betting window is not open (phase=$gamePhase).")
        }

        // 3. Fresh scan / verify stake field
        val stakeOk = driver.setStakeText(proposedStake)
        if (!stakeOk) {
            return BetResult.Aborted("Stake could not be set or verified.")
        }

        // 7. Tap Bet button
        val betNode = driver.waitForNode("bet", editableOnly = false, timeoutMs = 1500)
        if (betNode == null) {
            return BetResult.Aborted("Bet button not found on screen.")
        }
        val clicked = driver.clickNode(betNode)
        if (!clicked) {
            return BetResult.Aborted("Failed to click Bet button.")
        }

        // 8. Confirm (S3): check for cancel / bet queued within 1800ms
        val confirmed = driver.waitForNode("cancel", editableOnly = false, timeoutMs = 1800) != null
            || driver.waitForNode("bet queued", editableOnly = false, timeoutMs = 200L) != null

        if (!confirmed) {
            return BetResult.Aborted("Bet not confirmed by game interface within timeout.")
        }

        return BetResult.Success(proposedStake)
    }
}

sealed class BetResult {
    data class Success(val stake: Double) : BetResult()
    data class Aborted(val reason: String) : BetResult()
}
