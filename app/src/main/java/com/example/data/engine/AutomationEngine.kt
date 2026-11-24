package com.example.data.engine
import com.example.data.database.ScreenEarnerRepository
import com.example.data.model.FloatingShortcut
import com.example.data.model.MultiplierRecord
import com.example.data.model.RiskLimits
import com.example.data.model.SessionLog
import com.example.data.model.SessionStats
import com.example.data.model.ShortcutType
import com.example.data.model.ActionExecutionRecord
import com.example.data.model.ActionExecutionState
import com.example.data.model.ActivityLogEntry
import com.example.data.model.BalanceDetectionStatus
import com.example.data.model.SiteButtonState
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.service.AccessibilityController
import com.example.service.DetectedUiTargets
import com.example.service.FloatingHandsOverlayManager
import com.example.service.ScreenEarnerAccessibilityService
import com.example.service.ScreenOcrAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.data.model.CalendarHourUtils
import com.example.data.model.CollectionSource
import com.example.data.model.ColorClassifier
import com.example.data.model.CrashRecord
import com.example.data.model.NotificationPriorityLevel
import com.example.data.model.StrategyNotificationType
import com.example.data.model.TimestampSource
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class AutomationState {
    IDLE,
    RUNNING,
    PAUSED,
    EMERGENCY_STOP
}

data class StartAutomationResult(
    val started: Boolean,
    val message: String
)

data class PendingBetOpportunity(
    val id: String,
    val strategyId: Long,
    val strategyName: String,
    val targetGameId: String = "ALL",
    val occurrenceKey: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val targetWinsToFetch: Int = 2,
    val targetMultiplierOverride: Double? = null,
    val progressionMode: StakeProgressionMode? = null,
    val customAddAmount: Double? = null,
    val priorityScore: Int = 0
)

interface TargetUiInteractor {
    fun applyStake(
        newStake: Double,
        progressionMode: StakeProgressionMode,
        fallbackX: Float?,
        fallbackY: Float?,
        onComplete: (Boolean) -> Unit
    )

    fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit)
    fun cancelPendingGestures(reason: String) {}
    fun dismissKeyboardIfOpen(): Boolean = false
    /** Presses the stake box's "-" button down to the site floor; null when it cannot be determined. */
    fun probeMinStake(onDone: (Double?) -> Unit) { onDone(null) }
    fun isButtonReady(expectBet: Boolean, config: StrategyConfig): Boolean = true
    val requiresPostActionVerification: Boolean get() = false
    fun refreshLiveTargets(fallbackTargets: DetectedUiTargets? = null): DetectedUiTargets? = fallbackTargets
    /** Waits until the game layout stops moving (keyboard closed, button bounds unchanged); true = settled. */
    fun awaitStableLayout(onDone: (Boolean) -> Unit) {
        onDone(true)
    }
}

class AccessibilityTargetUiInteractor : TargetUiInteractor {
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val requiresPostActionVerification: Boolean = true

    override fun applyStake(
        newStake: Double,
        progressionMode: StakeProgressionMode,
        fallbackX: Float?,
        fallbackY: Float?,
        onComplete: (Boolean) -> Unit
    ) {
        val controller = AccessibilityController.controllerInstance
        val service = ScreenEarnerAccessibilityService.instance
        val formatted = StrategyEngine.formatStakeAmount(newStake)

        if (controller != null) {
            controller.applyStakeToTargetApp(newStake, progressionMode, null, null) { ok ->
                onComplete(ok)
            }
            return
        }

        if (service == null) {
            onComplete(false)
            return
        }
        actionScope.launch {
            val ok = try {
                val executor = service.actionExecutor
                val fieldRes = executor.replaceFieldText("stake", formatted).let { first ->
                    if (first.success) first else executor.replaceFieldText("amount", formatted)
                }
                fieldRes.success
            } catch (_: Throwable) {
                false
            }
            onComplete(ok)
        }
    }

    override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
        val controller = AccessibilityController.controllerInstance
        if (controller != null) {
            controller.clickBetTarget(fallbackX, fallbackY, onComplete)
        } else {
            onComplete(false)
        }
    }

    override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets? {
        return AccessibilityController.controllerInstance?.refreshLiveTargetsNow() ?: fallbackTargets
    }

    override fun awaitStableLayout(onDone: (Boolean) -> Unit) {
        val controller = AccessibilityController.controllerInstance
        if (controller == null) {
            onDone(true)
        } else {
            controller.awaitStableBetLayout(onDone = onDone)
        }
    }

    override fun cancelPendingGestures(reason: String) {
        AccessibilityController.controllerInstance?.haltAllAutomationGestures(reason)
    }

    override fun dismissKeyboardIfOpen(): Boolean {
        val controller = AccessibilityController.controllerInstance
        if (controller != null) {
            return controller.dismissKeyboardIfOpen()
        }
        val service = ScreenEarnerAccessibilityService.instance
        if (service != null && service.actionExecutor.isSoftKeyboardOpen()) {
            return service.actionExecutor.dismissKeyboardSafely().success
        }
        return false
    }

    override fun probeMinStake(onDone: (Double?) -> Unit) {
        val controller = AccessibilityController.controllerInstance
        if (controller == null) onDone(null) else controller.probeMinStake(onDone)
    }

    override fun isButtonReady(expectBet: Boolean, config: StrategyConfig): Boolean {
        if (!config.verifyButtonStateBeforeAction) return true
        val service = ScreenEarnerAccessibilityService.instance ?: return true
        return service.actionExecutor.isTargetButtonReady(expectBet)
    }
}

class AutomationEngine(
    val repository: ScreenEarnerRepository,
    val strategyEngine: StrategyEngine,
    val riskManager: RiskManager,
    val gameDetection: GameDetection,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val targetUiInteractor: TargetUiInteractor = AccessibilityTargetUiInteractor()
) {

    private val _automationState = MutableStateFlow(AutomationState.IDLE)
    val automationState: StateFlow<AutomationState> = _automationState.asStateFlow()

    private val _lastRiskViolation = MutableStateFlow<String?>(null)
    val lastRiskViolation: StateFlow<String?> = _lastRiskViolation.asStateFlow()

    private val _sessionStats = MutableStateFlow(SessionStats(sessionStartMs = System.currentTimeMillis()))
    val sessionStats: StateFlow<SessionStats> = _sessionStats.asStateFlow()

    private val _agentStatusTicker = MutableStateFlow("Waiting for crash game window...")
    val agentStatusTicker: StateFlow<String> = _agentStatusTicker.asStateFlow()

    private val _executionState = MutableStateFlow(ActionExecutionState.IDLE)
    val executionState: StateFlow<ActionExecutionState> = _executionState.asStateFlow()

    private val _executionRecord = MutableStateFlow<ActionExecutionRecord?>(null)
    val executionRecord: StateFlow<ActionExecutionRecord?> = _executionRecord.asStateFlow()

    private val _activityLogs = MutableStateFlow<List<ActivityLogEntry>>(emptyList())
    val activityLogs: StateFlow<List<ActivityLogEntry>> = _activityLogs.asStateFlow()

    private val _isStandbyOutsideSchedule = MutableStateFlow(false)
    val isStandbyOutsideSchedule: StateFlow<Boolean> = _isStandbyOutsideSchedule.asStateFlow()

    @Volatile
    private var isPausedBySchedule = false

    private val isActionInProgress = AtomicBoolean(false)
    private val actionStartedAtMs = AtomicLong(0L)
    private val liveCrashSeqCounter = AtomicLong(0L)
    var testBalanceOverride: Double? = if (targetUiInteractor !is AccessibilityTargetUiInteractor) 1000.0 else null

    @Volatile
    private var latestLimitsCache = RiskLimits()

    @Volatile
    private var latestConfigCache = StrategyConfig()

    private var activeSessionLoss = 0.0
    private var activeSessionRounds = 0
    private var activeSessionProfit = 0.0
    private var sessionWins = 0
    private var sessionLosses = 0
    private var sessionObservedCrashes = 0
    private var sessionStartTimestampMs = System.currentTimeMillis()

    // Per-round live execution tracking for BETTING_OPEN -> IN_FLIGHT -> CRASHED lifecycle
    private val _isRoundBetActive = MutableStateFlow(false)
    val isRoundBetActiveState: StateFlow<Boolean> = _isRoundBetActive.asStateFlow()

    private var currentRoundBetActive = false
    private var currentRoundStake = 0.0
    private var currentRoundCashedOut = false
    private var currentRoundTriggerLabel = ""
    private var activeSequenceExposure = 0.0

    @Volatile
    private var latestUiTargets: DetectedUiTargets? = null

    @Volatile
    private var currentRoundBetAttemptLockKey: String? = null

    // Phase timing & bounded retry state
    private var lastObservedPhaseInEngine: String = ""
    private var lastBettingOpenAtMs: Long = 0L
    private var inFlightStartedAtMs: Long = 0L
    private var activeTargetMultiplierOverride: Double? = null

    // Audit trail for every bet (intent -> stake -> dispatch -> accept -> settlement).
    private val txManager = BetTransactionManager()
    val betTransactions: BetTransactionManager get() = txManager
    private var activeTxId: String? = null

    var onProgressionStateChanged: ((StrategyProgressionSnapshot) -> Unit)? = null

    private fun notifyProgressionChanged() {
        onProgressionStateChanged?.invoke(strategyEngine.exportProgressionSnapshot())
    }

    // Stake limits learned per "platform|game": from the - button probe, the site's own rejection messages, or the saved profile.
    private val learnedStakeLimits = ConcurrentHashMap<String, Pair<Double?, Double?>>()
    private val limitProbeAttempted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val profileLimitsLoaded: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun findMatchingProfile(
        profiles: List<com.example.data.model.PlatformProfile>,
        key: String
    ): com.example.data.model.PlatformProfile? {
        val platform = key.substringBefore('|').trim()
        if (platform.isNotBlank() && !platform.equals("Unknown", ignoreCase = true)) {
            profiles.firstOrNull { it.platformName.equals(platform, ignoreCase = true) }?.let { return it }
            profiles.firstOrNull {
                it.platformName.contains(platform, ignoreCase = true) ||
                    platform.contains(it.platformName, ignoreCase = true)
            }?.let { return it }
        }
        return profiles.firstOrNull { it.isActive }
    }

    private fun learnStakeLimits(key: String, min: Double?, max: Double?, source: String) {
        val cleanMin = min?.takeIf { it.isFinite() && it > 0.0 }
        val cleanMax = max?.takeIf { it.isFinite() && it > 0.0 }
        if (cleanMin == null && cleanMax == null) return
        if (cleanMin != null && cleanMax != null && cleanMin > cleanMax) return

        val old = learnedStakeLimits[key]
        var newMin = cleanMin ?: old?.first
        var newMax = cleanMax ?: old?.second
        if (newMin != null && newMax != null && newMin > newMax) {
            // Fresh single bound conflicts with stale bound; keep the fresh bound only.
            if (cleanMin != null && cleanMax == null) newMax = null
            else if (cleanMax != null && cleanMin == null) newMin = null
            else return
        }
        val updated = newMin to newMax
        if (old == updated) return
        learnedStakeLimits[key] = updated
        publishStatusTicker("Learned site stake limits ($source): min=${newMin ?: "?"} max=${newMax ?: "?"}")
    }

    private fun loadProfileLimitsOnce(key: String) {
        profileLimitsLoaded.add(key)
    }

    private fun txLaunch(block: suspend BetTransactionManager.() -> Unit) {
        scope.launch { runCatching { txManager.block() } }
    }

    private fun setRoundBetActive(active: Boolean) {
        currentRoundBetActive = active
        _isRoundBetActive.value = active
    }

    fun updateLatestUiTargets(targets: DetectedUiTargets) {
        latestUiTargets = targets
    }

    // Executable custom strategy opportunity queue
    private val opportunityQueueLock = Any()
    private val pendingOpportunityQueue = ArrayDeque<PendingBetOpportunity>()
    private val enqueuedOccurrenceKeys = LinkedHashSet<String>()
    private val _queuedOpportunityCount = MutableStateFlow(0)
    val queuedOpportunityCount: StateFlow<Int> = _queuedOpportunityCount.asStateFlow()

    var currentDailyLossProvider: ((nowMs: Long) -> Double?)? = null
    var onDailyNetOutcomeRecorded: ((netProfit: Double, nowMs: Long) -> Double)? = null

    @Volatile
    private var lastLifecycleNoticeStage: String? = null

    @Volatile
    private var calibratedShortcuts: List<FloatingShortcut> = emptyList()

    @Volatile
    private var activePlatformName: String = "Unknown"

    var onGestureDispatchRequested: ((targetX: Float, targetY: Float, actionLabel: String) -> Unit)? = null

    var onExecutionNotification: ((
        type: StrategyNotificationType,
        title: String,
        message: String,
        occurrenceKey: String,
        priority: NotificationPriorityLevel
    ) -> Unit)? = null

    private val recentExecutionNoticeKeys = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastBetFailureEpisodeKey: String? = null

    fun resetBetFailureEpisode() {
        lastBetFailureEpisodeKey = null
    }

    fun enqueueCustomStrategyOpportunity(
        opportunity: PendingBetOpportunity,
        config: StrategyConfig = latestConfigCache,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (currentTimeMs > opportunity.expiresAtMs) return false
        synchronized(opportunityQueueLock) {
            pruneExpiredOpportunitiesLocked(currentTimeMs)
            if (opportunity.occurrenceKey.isNotBlank() && !enqueuedOccurrenceKeys.add(opportunity.occurrenceKey)) {
                return false
            }
            pendingOpportunityQueue.addLast(opportunity)
            if (pendingOpportunityQueue.size > 1) {
                val sorted = pendingOpportunityQueue.sortedByDescending { it.priorityScore }
                pendingOpportunityQueue.clear()
                pendingOpportunityQueue.addAll(sorted)
            }
            _queuedOpportunityCount.value = pendingOpportunityQueue.size
        }
        tryActivateNextQueuedOpportunity(config, currentTimeMs)
        return true
    }

    fun tryActivateNextQueuedOpportunity(
        config: StrategyConfig = latestConfigCache,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (strategyEngine.getState().isBettingSessionActive) return false
        val activeGameId = gameDetection.gameState.value.gameId.ifBlank { "unknown" }
        val nextOpp: PendingBetOpportunity = synchronized(opportunityQueueLock) {
            pruneExpiredOpportunitiesLocked(currentTimeMs)
            val idx = pendingOpportunityQueue.indexOfFirst { opp ->
                opp.targetGameId.isBlank() ||
                    opp.targetGameId.equals("ALL", ignoreCase = true) ||
                    activeGameId == "unknown" ||
                    opp.targetGameId.equals(activeGameId, ignoreCase = true)
            }
            if (idx < 0) return false
            val removed = pendingOpportunityQueue.removeAt(idx)
            _queuedOpportunityCount.value = pendingOpportunityQueue.size
            removed
        }
        val activated = strategyEngine.activateExternalPatternTrigger(
            strategyName = nextOpp.strategyName,
            config = config,
            currentTimeMs = currentTimeMs,
            progressionModeOverride = nextOpp.progressionMode,
            customAddAmountOverride = nextOpp.customAddAmount,
            targetWinsOverride = nextOpp.targetWinsToFetch,
            targetMultiplierOverride = nextOpp.targetMultiplierOverride
        )
        if (activated) {
            activeTargetMultiplierOverride = nextOpp.targetMultiplierOverride
            notifyProgressionChanged()
            gameDetection.updateStrategyTelemetry(
                strategyEngine.getState(),
                "Pattern Triggered: ${nextOpp.strategyName}"
            )
            if (_automationState.value == AutomationState.RUNNING && config.isAutoBetEnabled) {
                notifyStrategyWaitingToBet(nextOpp.strategyName)
            }
        }
        return activated
    }

    private fun pruneExpiredOpportunitiesLocked(currentTimeMs: Long) {
        if (pendingOpportunityQueue.isEmpty()) return
        val iter = pendingOpportunityQueue.iterator()
        while (iter.hasNext()) {
            val item = iter.next()
            if (currentTimeMs > item.expiresAtMs) {
                iter.remove()
            }
        }
        if (enqueuedOccurrenceKeys.size > 200) {
            val toKeep = pendingOpportunityQueue.mapTo(HashSet()) { it.occurrenceKey }
            enqueuedOccurrenceKeys.retainAll(toKeep)
        }
        _queuedOpportunityCount.value = pendingOpportunityQueue.size
    }

    private fun clearOpportunityQueue() {
        synchronized(opportunityQueueLock) {
            pendingOpportunityQueue.clear()
            enqueuedOccurrenceKeys.clear()
            _queuedOpportunityCount.value = 0
        }
        activeTargetMultiplierOverride = null
    }

    private fun emitExecutionNotice(
        type: StrategyNotificationType,
        title: String,
        message: String,
        occurrenceKey: String,
        priority: NotificationPriorityLevel = NotificationPriorityLevel.STANDARD,
        cooldownMs: Long = 30_000L
    ) {
        val now = System.currentTimeMillis()
        val last = recentExecutionNoticeKeys[occurrenceKey] ?: 0L
        if (cooldownMs > 0L && (now - last) < cooldownMs) return
        recentExecutionNoticeKeys[occurrenceKey] = now
        onExecutionNotification?.invoke(type, title, message, occurrenceKey, priority)
    }

    private fun emitEpisodeFailureNotice(
        episodeTag: String,
        title: String,
        message: String,
        priority: NotificationPriorityLevel = NotificationPriorityLevel.HIGH
    ) {
        val gs = gameDetection.gameState.value
        val roundPart = (gs.currentRoundId ?: gs.latestRoundId)?.takeIf { it.isNotBlank() } ?: "live"
        val key = "${episodeTag}_${roundPart}"
        if (lastBetFailureEpisodeKey == key) return
        lastBetFailureEpisodeKey = key
        emitExecutionNotice(
            type = StrategyNotificationType.MONITORING_ERROR,
            title = title,
            message = message,
            occurrenceKey = "exec_err_${key}_${System.currentTimeMillis() / 30_000L}",
            priority = priority,
            cooldownMs = 15_000L
        )
    }

    fun recordActivityLog(
        actionId: String,
        state: ActionExecutionState,
        target: String,
        coordinates: Pair<Float, Float>?,
        gestureResult: Boolean?,
        eventObserved: String?,
        verificationResult: Boolean?,
        errorDetails: String?
    ) {
        val entry = ActivityLogEntry(
            id = System.currentTimeMillis(),
            timestamp = System.currentTimeMillis(),
            actionId = actionId,
            state = state,
            target = target,
            coordinates = coordinates,
            gestureResult = gestureResult,
            eventObserved = eventObserved,
            verificationResult = verificationResult,
            errorDetails = errorDetails
        )
        _activityLogs.value = (_activityLogs.value + entry).takeLast(100)
        // Feed unified App Logs panel (copyable success + errors).
        runCatching { com.example.service.AppLogManager.fromActivityLog(entry) }
    }

    fun resolveEffectiveBalance(): Double {
        if (testBalanceOverride != null) return testBalanceOverride!!
        val balState = gameDetection.balanceState.value
        if ((balState.status == BalanceDetectionStatus.VERIFIED || balState.status == BalanceDetectionStatus.DETECTED) &&
            balState.amount != null && balState.amount.isFinite() && balState.amount >= 0.0
        ) {
            return balState.amount
        }
        val gs = gameDetection.gameState.value
        val liveBalance = gs.detectedBalance
        if ((gs.balanceStatus == BalanceDetectionStatus.VERIFIED || gs.balanceStatus == BalanceDetectionStatus.DETECTED) &&
            liveBalance != null && liveBalance.isFinite() && liveBalance >= 0.0
        ) {
            return liveBalance
        }
        return 0.0
    }

    fun updateCachedLimits(limits: RiskLimits) {
        latestLimitsCache = limits
    }

    fun updateCachedConfig(config: StrategyConfig) {
        latestConfigCache = config
    }

    fun publishStatusTicker(message: String) {
        if (message.isNotBlank() && _agentStatusTicker.value != message) {
            _agentStatusTicker.value = message
        }
    }

    fun updateLiveDiagnostics(
        targets: DetectedUiTargets,
        gameState: com.example.data.model.GameState,
        config: StrategyConfig
    ) {
        latestConfigCache = config
        if (_isStandbyOutsideSchedule.value || gameState.isStandbyOutsideSchedule) {
            val standbyMsg = gameState.scheduleStandbyMessage
                ?: OperatingScheduleEvaluator.evaluate(config).standbyBanner
            publishStatusTicker(standbyMsg)
            return
        }

        if (!gameState.isDetected) {
            publishStatusTicker("Scanning active windows for crash game...")
            return
        }

        val ticker = when {
            gameState.liveDetectionLost ->
                "LIVE DETECTION LOST — Check active game tab or scroll view"
            gameState.pipelineStatus == com.example.data.model.MonitoringPipelineStatus.PAUSED ->
                "MONITORING PAUSED"
            gameState.status == "IN_FLIGHT" ->
                "${gameState.displayedGameName}: Plane in flight (${String.format(Locale.US, "%.2f", gameState.currentMultiplier)}x)"
            gameState.status == "CRASHED" && gameState.latestCrashMultiplier != null ->
                "${gameState.displayedGameName}: Crashed at ${String.format(Locale.US, "%.2f", gameState.latestCrashMultiplier)}x (${gameState.latestCrashColorCategory.displayName})"
            _automationState.value == AutomationState.PAUSED || _automationState.value == AutomationState.EMERGENCY_STOP ->
                "Monitoring Live (${gameState.displayedGameName}) — Automation Paused"
            else ->
                "Monitoring ${gameState.displayedGameName} (${gameState.platformName})"
        }
        publishStatusTicker(ticker)
    }

    fun updateCalibratedShortcuts(shortcuts: List<FloatingShortcut>) {
        calibratedShortcuts = shortcuts
    }

    fun updateActivePlatform(platformName: String) {
        if (platformName.isNotBlank()) {
            activePlatformName = platformName
        }
    }

    fun findActiveShortcut(type: ShortcutType): FloatingShortcut? {
        val siteKey = GameDetection.resolveSiteKey(activePlatformName)
        val candidates = calibratedShortcuts.filter {
            it.type == type && it.isEnabled && it.isCalibrated && it.targetX > 0f && it.targetY > 0f
        }
        if (candidates.isEmpty()) return null
        if (siteKey.isNotEmpty()) {
            val siteMatch = candidates.firstOrNull { it.targetPlatform.equals(siteKey, ignoreCase = true) }
            if (siteMatch != null) return siteMatch
        }
        return candidates.first()
    }

    fun verifyReadyForMonitoring(isAccessibilityConnected: Boolean, limits: RiskLimits): StartAutomationResult {
        if (!isAccessibilityConnected) {
            return StartAutomationResult(false, "Enable Accessibility Service so Predict Manager can monitor live rounds.")
        }
        return StartAutomationResult(true, "Live monitoring started cleanly.")
    }

    fun startMonitoringWithValidation(
        config: StrategyConfig,
        limits: RiskLimits,
        isAccessibilityConnected: Boolean
    ): StartAutomationResult {
        val check = verifyReadyForMonitoring(isAccessibilityConnected, limits)
        if (!check.started) {
            _lastRiskViolation.value = check.message
            return check
        }
        if (limits.emergencyStop) {
            latestConfigCache = config
            latestLimitsCache = limits
            _automationState.value = AutomationState.EMERGENCY_STOP
            _lastRiskViolation.value = "Automation locked by Emergency Stop (Monitoring continues)."
        } else {
            startAutomation(config, limits)
        }
        return check
    }

    fun restoreMonitoringRunningPreservingSession(
        config: StrategyConfig,
        limits: RiskLimits,
        confirmedCrashCount: Int
    ) {
        latestConfigCache = config
        latestLimitsCache = limits
        sessionObservedCrashes = confirmedCrashCount.coerceAtLeast(0)
        if (limits.emergencyStop) {
            _automationState.value = AutomationState.EMERGENCY_STOP
            publishSessionStats(limitHaltReason = "Emergency Stop active (Monitoring live)")
            return
        }
        isPausedBySchedule = false
        val scheduleEval = OperatingScheduleEvaluator.evaluate(config)
        if (!scheduleEval.isWithinOperatingHours) {
            isPausedBySchedule = true
            _isStandbyOutsideSchedule.value = true
            _automationState.value = AutomationState.PAUSED
            gameDetection.updateScheduleStandby(true, scheduleEval.standbyBanner)
        } else {
            _isStandbyOutsideSchedule.value = false
            _automationState.value = AutomationState.RUNNING
            gameDetection.updateScheduleStandby(false, null)
        }
        publishSessionStats(limitHaltReason = null)
    }

    fun setObservedCrashCount(count: Int) {
        sessionObservedCrashes = count.coerceAtLeast(0)
        publishSessionStats(limitHaltReason = _sessionStats.value.limitHaltReason)
    }

    fun verifyReadyToStart(isAccessibilityConnected: Boolean, limits: RiskLimits): StartAutomationResult {
        if (limits.emergencyStop) {
            return StartAutomationResult(false, "Emergency Stop is active in Risk Settings.")
        }
        if (!isAccessibilityConnected) {
            return StartAutomationResult(false, "Enable Accessibility Service so Predict Manager can detect live targets and rounds.")
        }
        return StartAutomationResult(true, "Automation started cleanly.")
    }

    fun startAutomationWithValidation(
        config: StrategyConfig,
        limits: RiskLimits,
        isAccessibilityConnected: Boolean
    ): StartAutomationResult {
        val check = verifyReadyToStart(isAccessibilityConnected, limits)
        if (!check.started) {
            if (limits.emergencyStop) {
                _automationState.value = AutomationState.EMERGENCY_STOP
            }
            _lastRiskViolation.value = check.message
            return check
        }
        startAutomation(config, limits)
        return check
    }

    fun startAutomation(config: StrategyConfig, limits: RiskLimits, resetProgression: Boolean = false) {
        latestConfigCache = config
        latestLimitsCache = limits
        if (limits.emergencyStop) {
            _automationState.value = AutomationState.EMERGENCY_STOP
            _lastRiskViolation.value = "Emergency Stop is active in Risk Settings."
            return
        }
        resetSessionStats()
        isPausedBySchedule = false
        val scheduleEval = OperatingScheduleEvaluator.evaluate(config)
        if (!scheduleEval.isWithinOperatingHours) {
            isPausedBySchedule = true
            _isStandbyOutsideSchedule.value = true
            _automationState.value = AutomationState.PAUSED
            gameDetection.updateScheduleStandby(true, scheduleEval.standbyBanner)
            publishStatusTicker(scheduleEval.standbyBanner)
        } else {
            _isStandbyOutsideSchedule.value = false
            _automationState.value = AutomationState.RUNNING
            gameDetection.updateScheduleStandby(false, null)
            publishStatusTicker("Checking strategy for current game state...")
        }
        _lastRiskViolation.value = null
        setRoundBetActive(false)
        currentRoundBetAttemptLockKey = null
        currentRoundCashedOut = false
        lastLifecycleNoticeStage = null
        isActionInProgress.set(false)
        lastBetFailureEpisodeKey = null
        if (resetProgression) {
            strategyEngine.reset(config.baseStake)
            notifyProgressionChanged()
        } else {
            strategyEngine.bindStrategyConfig(config)
        }
        gameDetection.updateStrategyTelemetry(
            strategyEngine.getState(),
            formatTriggerLabel(strategyEngine.getState().activeTrigger, strategyEngine.getState())
        )
    }

    fun resetStrategyProgression(config: StrategyConfig = latestConfigCache) {
        latestConfigCache = config
        strategyEngine.reset(config.baseStake)
        strategyEngine.bindStrategyConfig(config)
        activeTargetMultiplierOverride = null
        gameDetection.updateStrategyTelemetry(strategyEngine.getState(), null)
        notifyProgressionChanged()
        publishStatusTicker("Strategy & Martingale progression reset to base stake.")
    }

    fun resetSessionStats(currentTimeMs: Long = System.currentTimeMillis()) {
        sessionStartTimestampMs = currentTimeMs
        activeSessionLoss = 0.0
        activeSessionRounds = 0
        activeSessionProfit = 0.0
        sessionWins = 0
        sessionLosses = 0
        sessionObservedCrashes = 0
        activeSequenceExposure = 0.0
        lastObservedPhaseInEngine = ""
        lastBettingOpenAtMs = 0L
        inFlightStartedAtMs = 0L
        currentRoundBetAttemptLockKey = null
        clearOpportunityQueue()
        _lastRiskViolation.value = null
        publishSessionStats(limitHaltReason = null)
    }

    fun syncSessionWithCrashHistory(records: List<MultiplierRecord>, limits: RiskLimits) {
        latestLimitsCache = limits
        if (_automationState.value == AutomationState.RUNNING) {
            enforceLimitsNow(limits)
        }
    }

    fun settleActiveRoundBetIfNeeded(
        crashedMultiplier: Double,
        config: StrategyConfig,
        limits: RiskLimits = latestLimitsCache,
        platformName: String = activePlatformName.ifBlank { gameDetection.gameState.value.platformName },
        currentTimeMs: Long = System.currentTimeMillis()
    ) {
        if (currentRoundBetActive) {
            settleActiveRoundBet(
                stakeUsed = currentRoundStake,
                crashedMultiplier = crashedMultiplier,
                cashedOutInFlight = currentRoundCashedOut,
                triggerLabel = currentRoundTriggerLabel,
                config = config,
                limits = limits,
                platformName = platformName,
                currentTimeMs = currentTimeMs
            )
            setRoundBetActive(false)
            currentRoundCashedOut = false
            gameDetection.setBetPlaced(false)
        }
    }

    fun notifyStrategyWaitingToBet(strategyLabel: String) {
        if (_automationState.value != AutomationState.RUNNING) return
        lastLifecycleNoticeStage = "READY_TO_BET"
        emitExecutionNotice(
            type = StrategyNotificationType.AUTOMATION_ACTION,
            title = "Waiting to Bet: Ready ($strategyLabel)",
            message = "Strategy triggered on ${gameDetection.gameState.value.displayedGameName.ifBlank { "game" }}. Waiting for BETTING OPEN to place bet.",
            occurrenceKey = "ready_to_bet_${strategyLabel.hashCode()}",
            priority = NotificationPriorityLevel.STANDARD,
            cooldownMs = 5_000L
        )
    }

    fun recordAuthoritativeCompletedRound(
        multiplier: Double,
        config: StrategyConfig,
        sessionCrashCount: Int,
        currentTimeMs: Long = System.currentTimeMillis(),
        limits: RiskLimits = latestLimitsCache,
        platformName: String = activePlatformName.ifBlank { gameDetection.gameState.value.platformName }
    ) {
        latestConfigCache = config
        latestLimitsCache = limits
        sessionObservedCrashes = sessionCrashCount.coerceAtLeast(0)
        lastObservedPhaseInEngine = "CRASHED"
        inFlightStartedAtMs = 0L
        publishSessionStats()
        settleActiveRoundBetIfNeeded(
            crashedMultiplier = multiplier,
            config = config,
            limits = limits,
            platformName = platformName,
            currentTimeMs = currentTimeMs
        )
        val trigger = strategyEngine.processCompletedRound(multiplier, config, currentTimeMs)
        var stratState = strategyEngine.getState()
        if (!stratState.isBettingSessionActive) {
            activeSequenceExposure = 0.0
            activeTargetMultiplierOverride = null
            tryActivateNextQueuedOpportunity(config, currentTimeMs)
            stratState = strategyEngine.getState()
        }
        val triggerName = formatTriggerLabel(trigger, stratState)
        gameDetection.updateStrategyTelemetry(stratState, triggerName)
        notifyProgressionChanged()
        if (_automationState.value == AutomationState.RUNNING &&
            config.isAutoBetEnabled &&
            stratState.isBettingSessionActive &&
            trigger !is StrategyTrigger.None &&
            trigger !is StrategyTrigger.SessionTimedOut &&
            trigger !is StrategyTrigger.RangeWaitStarted
        ) {
            notifyStrategyWaitingToBet(triggerName ?: stratState.activeRuleLabel ?: "Active Strategy")
        }
    }

    fun enforceLimitsNow(limits: RiskLimits, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        latestLimitsCache = limits
        if (_automationState.value != AutomationState.RUNNING) return false
        val dailyLoss = currentDailyLossProvider?.invoke(currentTimeMs)
        if (limits.stopOnLossEnabled && limits.maxDailyLoss > 0.0 && dailyLoss != null) {
            if (dailyLoss >= limits.maxDailyLoss) {
                haltAutomationForLimit("Daily loss limit reached (-${CurrencyManager.formatAmount(dailyLoss)} >= ${CurrencyManager.formatAmount(limits.maxDailyLoss)}).")
                return true
            }
        }
        val check = riskManager.evaluateSessionLimits(
            currentSessionProfit = activeSessionProfit,
            currentSessionLoss = activeSessionLoss,
            limits = limits,
            currentDailyLoss = dailyLoss ?: 0.0
        )
        if (!check.isAllowed) {
            haltAutomationForLimit(check.violationReason ?: "Session limit reached.")
            return true
        }
        return false
    }

    private fun haltAutomationForLimit(reason: String) {
        setRoundBetActive(false)
        currentRoundCashedOut = false
        lastLifecycleNoticeStage = null
        isActionInProgress.set(false)
        gameDetection.setBetPlaced(false)
        targetUiInteractor.cancelPendingGestures(reason)
        _automationState.value = AutomationState.PAUSED
        _lastRiskViolation.value = reason
        publishSessionStats(limitHaltReason = reason)
        emitExecutionNotice(
            type = StrategyNotificationType.MONITORING_ERROR,
            title = "Automation Paused (Safety Guard)",
            message = reason,
            occurrenceKey = "halt_${reason.hashCode()}",
            priority = NotificationPriorityLevel.HIGH,
            cooldownMs = 30_000L
        )
    }

    private fun publishSessionStats(limitHaltReason: String? = _sessionStats.value.limitHaltReason) {
        val stats = SessionStats(
            sessionStartMs = sessionStartTimestampMs,
            winCount = sessionWins,
            lossCount = sessionLosses,
            totalProfit = activeSessionProfit,
            observedCrashCount = sessionObservedCrashes,
            limitHaltReason = limitHaltReason
        )
        _sessionStats.value = stats
        gameDetection.updateSessionStats(stats)
    }

    fun pauseAutomation() {
        isPausedBySchedule = false
        if (_automationState.value == AutomationState.RUNNING) {
            _automationState.value = AutomationState.PAUSED
        }
    }

    fun resumeAutomation(config: StrategyConfig, limits: RiskLimits) {
        latestConfigCache = config
        latestLimitsCache = limits
        if (_automationState.value == AutomationState.PAUSED) {
            isPausedBySchedule = false
            val check = riskManager.evaluateSessionLimits(
                currentSessionProfit = activeSessionProfit,
                currentSessionLoss = activeSessionLoss,
                limits = limits
            )
            if (!check.isAllowed) {
                val reason = check.violationReason ?: "Session limit reached."
                if (limits.emergencyStop) {
                    _automationState.value = AutomationState.EMERGENCY_STOP
                }
                _lastRiskViolation.value = reason
                publishSessionStats(limitHaltReason = reason)
                return
            }
            _automationState.value = AutomationState.RUNNING
            _lastRiskViolation.value = null
            publishSessionStats(limitHaltReason = null)
        }
    }

    /**
     * QUIT: Stops active automation and cancels pending actions,
     * but keeps floating bubble/utility and last session stats active on screen.
     */
    fun quitAutomation() {
        isPausedBySchedule = false
        _isStandbyOutsideSchedule.value = false
        gameDetection.updateScheduleStandby(false, null)
        targetUiInteractor.cancelPendingGestures("Automation stopped by user")
        _automationState.value = AutomationState.IDLE
        setRoundBetActive(false)
        currentRoundBetAttemptLockKey = null
        currentRoundCashedOut = false
        lastLifecycleNoticeStage = null
        isActionInProgress.set(false)
        lastBetFailureEpisodeKey = null
        activeSequenceExposure = 0.0
        clearOpportunityQueue()
        gameDetection.setBetPlaced(false)
        notifyProgressionChanged()
        publishStatusTicker("Automation stopped (Monitoring continues; progression preserved).")
    }

    fun triggerEmergencyStop(reason: String = "Emergency Stop active in Risk Settings.") {
        latestLimitsCache = latestLimitsCache.copy(emergencyStop = true)
        quitAutomation()
        _automationState.value = AutomationState.EMERGENCY_STOP
        _lastRiskViolation.value = reason
        publishSessionStats(limitHaltReason = reason)
        publishStatusTicker("Automation locked by Emergency Stop (Monitoring continues).")
    }

    fun clearEmergencyStop() {
        latestLimitsCache = latestLimitsCache.copy(emergencyStop = false)
        if (_automationState.value == AutomationState.EMERGENCY_STOP) {
            _automationState.value = AutomationState.IDLE
        }
        _lastRiskViolation.value = null
        publishSessionStats(limitHaltReason = null)
        publishStatusTicker("Emergency Stop cleared.")
    }

    /**
     * Enforces configured Scheduled Operating Hours: pauses automation into Standby when outside
     * the chosen window, and automatically resumes monitoring when back inside scheduled hours.
     */
    fun evaluateOperatingSchedule(
        config: StrategyConfig,
        limits: RiskLimits = latestLimitsCache,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        latestConfigCache = config
        latestLimitsCache = limits
        val scheduleEval = OperatingScheduleEvaluator.evaluate(config, currentTimeMs)
        if (!scheduleEval.isWithinOperatingHours) {
            _isStandbyOutsideSchedule.value = true
            gameDetection.updateScheduleStandby(true, scheduleEval.standbyBanner)
            publishStatusTicker(scheduleEval.standbyBanner)
            if (_automationState.value == AutomationState.RUNNING) {
                isPausedBySchedule = true
                setRoundBetActive(false)
                currentRoundCashedOut = false
                lastLifecycleNoticeStage = null
                isActionInProgress.set(false)
                gameDetection.setBetPlaced(false)
                targetUiInteractor.cancelPendingGestures(scheduleEval.standbyBanner)
                _automationState.value = AutomationState.PAUSED
            }
            return false
        } else {
            val wasStandby = _isStandbyOutsideSchedule.value
            _isStandbyOutsideSchedule.value = false
            gameDetection.updateScheduleStandby(false, null)
            if (isPausedBySchedule && _automationState.value == AutomationState.PAUSED) {
                isPausedBySchedule = false
                resumeAutomation(config, limits)
                publishStatusTicker("Scheduled window active — Monitoring resumed")
            } else if (wasStandby) {
                publishStatusTicker("Scheduled window active — Ready")
            }
            return true
        }
    }

    /**
     * Updates pending range-wait countdown timers (5m / 10m), checks scheduled operating hours,
     * and enforces the 3-minute timeout guard.
     */
    fun tickTimers(config: StrategyConfig, currentTimeMs: Long = System.currentTimeMillis()) {
        evaluateOperatingSchedule(config, latestLimitsCache, currentTimeMs)
        val timerTrigger = strategyEngine.checkPendingTimers(config, currentTimeMs)
        if (timerTrigger is StrategyTrigger.SessionTimedOut) {
            setRoundBetActive(false)
            currentRoundCashedOut = false
            lastLifecycleNoticeStage = null
            // A strategy-session timeout must not unlock an asynchronous UI action.
            gameDetection.setBetPlaced(false)
            notifyProgressionChanged()
        } else if (timerTrigger is StrategyTrigger.WaitTimerCompleted) {
            notifyProgressionChanged()
        }
        val stratState = strategyEngine.getState()
        val triggerLabel = formatTriggerLabel(timerTrigger, stratState)
        gameDetection.updateStrategyTelemetry(stratState, triggerLabel)
    }

    /**
     * Called continuously as game phases, site button states, & live multipliers arrive from AccessibilityService:
     * - During BETTING_OPEN: places verified BET ONLY when siteButtonState is ready and an eligible strategy
     *   session is active (never double-clicks when "Cancel" / BET_QUEUED is active).
     * - During IN_FLIGHT: leaves Cash Out completely to the game or user and awaits settlement at CRASHED.
     */
    fun handlePhaseUpdate(
        phase: String,
        liveMultiplier: Double,
        config: StrategyConfig,
        limits: RiskLimits,
        currentTimeMs: Long = System.currentTimeMillis(),
        siteButtonState: SiteButtonState = SiteButtonState.IDLE_READY_TO_BET,
        observationOnly: Boolean = false
    ) {
        latestConfigCache = config
        latestLimitsCache = limits
        if (phase != lastObservedPhaseInEngine) {
            if (phase == "BETTING_OPEN") {
                lastBettingOpenAtMs = currentTimeMs
                inFlightStartedAtMs = 0L
                currentRoundBetAttemptLockKey = null
            } else if (phase == "IN_FLIGHT") {
                inFlightStartedAtMs = currentTimeMs
            } else if (phase == "CRASHED") {
                inFlightStartedAtMs = 0L
                currentRoundBetAttemptLockKey = null
            }
            lastObservedPhaseInEngine = phase
        }
        if (!evaluateOperatingSchedule(config, limits, currentTimeMs)) return
        if (_automationState.value != AutomationState.RUNNING) return
        if (observationOnly || !config.automationMasterEnabled) {
            return
        }
        if (enforceLimitsNow(limits, currentTimeMs)) return

        if (phase == "BETTING_OPEN" && !strategyEngine.getState().isBettingSessionActive) {
            tryActivateNextQueuedOpportunity(config, currentTimeMs)
        }
        val stratState = strategyEngine.getState()

        if (siteButtonState == SiteButtonState.BET_QUEUED) {
            publishStatusTicker("Button state: CANCEL -> Waiting for round start")
            if (lastLifecycleNoticeStage != "BET_QUEUED") {
                lastLifecycleNoticeStage = "BET_QUEUED"
                emitExecutionNotice(
                    type = StrategyNotificationType.AUTOMATION_ACTION,
                    title = "Waiting for Round Start (Bet Queued)",
                    message = "Bet queued on ${gameDetection.gameState.value.displayedGameName.ifBlank { "game" }}; waiting for takeoff.",
                    occurrenceKey = "waiting_round_start_${gameDetection.gameState.value.currentRoundId ?: "live"}",
                    priority = NotificationPriorityLevel.STANDARD,
                    cooldownMs = 10_000L
                )
            }
            return
        }

        val maxAttempts = config.maxStrategyBetAttempts.coerceAtLeast(1)
        if (stratState.isBettingSessionActive && stratState.consecutiveLossesInSession >= maxAttempts) {
            return
        }

        if (phase == "BETTING_OPEN" && !stratState.isBettingSessionActive && config.isAutoBetEnabled && !currentRoundBetActive) {
            if (lastLifecycleNoticeStage != "WAITING_TRIGGER") {
                lastLifecycleNoticeStage = "WAITING_TRIGGER"
                emitExecutionNotice(
                    type = StrategyNotificationType.AUTOMATION_ACTION,
                    title = "Waiting to Bet (Awaiting Strategy Trigger)",
                    message = "Monitoring ${gameDetection.gameState.value.displayedGameName.ifBlank { "game" }}; waiting for an enabled strategy condition.",
                    occurrenceKey = "waiting_strategy_trigger",
                    priority = NotificationPriorityLevel.STANDARD,
                    cooldownMs = 45_000L
                )
            }
        }

        val buttonLooksReady = siteButtonState == SiteButtonState.IDLE_READY_TO_BET ||
            siteButtonState == SiteButtonState.UNKNOWN
        if (phase == "BETTING_OPEN" &&
            buttonLooksReady &&
            stratState.isBettingSessionActive &&
            !currentRoundBetActive &&
            config.isAutoBetEnabled
        ) {
            val gs = gameDetection.gameState.value
            val activeGameId = gs.gameId.ifBlank { "unknown" }
            val activeGameName = gs.displayedGameName.ifBlank { "Unknown" }
            val activeRoundId = (gs.currentRoundId ?: gs.latestRoundId)?.takeIf { it.isNotBlank() } ?: "Unknown"
            val roundLockKey = buildRoundBetAttemptKey(activeGameId, activeRoundId, lastBettingOpenAtMs)
            if (currentRoundBetAttemptLockKey == roundLockKey) {
                return
            }

            // Clear stuck action lock after 20s so a failed sequence cannot block all future bets.
            if (isActionInProgress.get()) {
                val started = actionStartedAtMs.get()
                if (started > 0L && (System.currentTimeMillis() - started) > 20_000L) {
                    isActionInProgress.set(false)
                    actionStartedAtMs.set(0L)
                } else {
                    return
                }
            }
            if (!isActionInProgress.compareAndSet(false, true)) return
            actionStartedAtMs.set(System.currentTimeMillis())

            val stakeInstruction = strategyEngine.getPendingStakeInstruction()
            val effectiveMode = if (stakeInstruction.isSessionActive &&
                stakeInstruction.stakeLevel == stratState.currentStakeLevel
            ) {
                stakeInstruction.progressionMode
            } else {
                stratState.activeProgressionMode
            }
            val strategyLabel = stratState.activeRuleLabel ?: "Auto Progression (Level ${stratState.currentStakeLevel})"

            executeVerifiedBetSequence(
                proposedStake = stratState.currentCalculatedStake,
                effectiveMode = effectiveMode,
                config = config,
                limits = limits,
                activeGameId = activeGameId,
                activeGameName = activeGameName,
                activeRoundId = activeRoundId,
                strategyLabel = strategyLabel,
                currentTimeMs = currentTimeMs,
                isManualTrigger = false,
                roundLockKey = roundLockKey
            )
        }
    }

    private fun buildRoundBetAttemptKey(gameId: String, roundId: String, openAtMs: Long): String {
        val roundPart = roundId.takeIf { it.isNotBlank() && it != "Unknown" } ?: "win_${openAtMs}"
        return "${gameId}_${roundPart}"
    }

    fun executeVerifiedManualBet(
        config: StrategyConfig = latestConfigCache,
        limits: RiskLimits = latestLimitsCache,
        onFinished: ((Boolean, String) -> Unit)? = null
    ) {
        if (limits.emergencyStop) {
            onFinished?.invoke(false, "Blocked: Emergency Stop active.")
            return
        }
        val gs = gameDetection.gameState.value
        val activeGameId = gs.gameId.ifBlank { "unknown" }
        val activeGameName = gs.displayedGameName.ifBlank { "Unknown" }
        val activeRoundId = (gs.currentRoundId ?: gs.latestRoundId)?.takeIf { it.isNotBlank() } ?: "Unknown"
        if (currentRoundBetActive) {
            onFinished?.invoke(false, "Bet already active in current round.")
            return
        }
        if (isActionInProgress.get()) {
            onFinished?.invoke(false, "Another bet action is already in progress.")
            return
        }
        if (!isActionInProgress.compareAndSet(false, true)) {
            onFinished?.invoke(false, "Another bet action is already in progress.")
            return
        }
        actionStartedAtMs.set(System.currentTimeMillis())

        val stratState = strategyEngine.getState()
        val proposedStake = if (stratState.isBettingSessionActive && stratState.currentCalculatedStake >= 1.0) {
            stratState.currentCalculatedStake
        } else {
            config.baseStake.coerceAtLeast(1.0)
        }
        val effectiveMode = config.resolvedMasterProgressionMode()
        val strategyLabel = stratState.activeRuleLabel ?: if (config.isAutoBetEnabled) "Auto Bet Hand (Automatic Mode)" else "Manual BET Hand"
        val nowMs = System.currentTimeMillis()
        val roundLockKey = "manual_${buildRoundBetAttemptKey(activeGameId, activeRoundId, lastBettingOpenAtMs)}_$nowMs"

        executeVerifiedBetSequence(
            proposedStake = proposedStake,
            effectiveMode = effectiveMode,
            config = config,
            limits = limits,
            activeGameId = activeGameId,
            activeGameName = activeGameName,
            activeRoundId = activeRoundId,
            strategyLabel = strategyLabel,
            currentTimeMs = nowMs,
            isManualTrigger = true,
            roundLockKey = roundLockKey,
            onFinished = onFinished
        )
    }

    private fun executeVerifiedBetSequence(
        proposedStake: Double,
        effectiveMode: StakeProgressionMode,
        config: StrategyConfig,
        limits: RiskLimits,
        activeGameId: String,
        activeGameName: String,
        activeRoundId: String,
        strategyLabel: String,
        currentTimeMs: Long,
        isManualTrigger: Boolean,
        roundLockKey: String,
        onFinished: ((Boolean, String) -> Unit)? = null
    ) {
        val sequenceWallStartMs = System.currentTimeMillis()
        val isRealA11y = targetUiInteractor is AccessibilityTargetUiInteractor
        val betWindowBudgetMs = if (isRealA11y) config.betWindowTotalTimeoutMs.coerceAtLeast(6000L) else config.betWindowTotalTimeoutMs.coerceAtLeast(100L)
        val stakeTimeoutMs = if (isRealA11y) config.stakeEditTimeoutMs.coerceAtLeast(1500L) else config.stakeEditTimeoutMs.coerceAtLeast(50L)
        val actionId = "bet_${currentTimeMs}_${(1000..9999).random()}"
        val contextDetails = "game=$activeGameName ($activeGameId), round=$activeRoundId, strategy=$strategyLabel, stake=${CurrencyManager.formatAmount(proposedStake)}"

        val initialRecord = ActionExecutionRecord(
            actionId = actionId,
            operationName = "PLACE_BET",
            state = ActionExecutionState.VALIDATING_GAME_STATE,
            startTimestampMs = currentTimeMs,
            targetCoordinates = null,
            details = contextDetails
        )
        _executionState.value = ActionExecutionState.VALIDATING_GAME_STATE
        _executionRecord.value = initialRecord
        gameDetection.updateExecutionRecord(initialRecord)
        recordActivityLog(actionId, ActionExecutionState.VALIDATING_GAME_STATE, "PLACE_BET", null, null, "Step 1: Validating live game target ($contextDetails)", null, null)
        val limitKey = "${gameDetection.gameState.value.platformName}|$activeGameId"
        loadProfileLimitsOnce(limitKey)
        activeTxId = actionId
        txLaunch { recordIntent(actionId, activeGameId, activeRoundId, proposedStake, config.targetMultiplier) }

        fun finishFailed(
            reason: String,
            targetCoords: Pair<Float, Float>? = null,
            gestureDispatched: Boolean = false,
            operation: String = "PLACE_BET",
            episodeTag: String = "bet_verify",
            emitNotice: Boolean = true,
            cancelSessionOnFail: Boolean = true
        ) {
            val failedRecord = initialRecord.copy(
                state = ActionExecutionState.FAILED,
                completionTimestampMs = System.currentTimeMillis(),
                targetCoordinates = targetCoords ?: initialRecord.targetCoordinates,
                isSuccess = false,
                gestureDispatched = gestureDispatched,
                failureReason = reason
            )
            _executionRecord.value = failedRecord
            _executionState.value = ActionExecutionState.FAILED
            gameDetection.updateExecutionRecord(failedRecord)
            recordActivityLog(actionId, ActionExecutionState.FAILED, operation, targetCoords, gestureDispatched, contextDetails, false, reason)
            publishStatusTicker(reason)
            txLaunch { recordBetUnconfirmed(actionId, reason) }
            if (episodeTag != "limits_probe") {
                runCatching {
                    val t = targetUiInteractor.refreshLiveTargets(latestUiTargets)
                    if (t != null) {
                        val (mn, mx) = parseSiteStakeLimits(t.visibleRawTitles + t.pageTextHints)
                        learnStakeLimits(limitKey, mn, mx, "site message")
                    }
                }
            }
            if (emitNotice) {
                emitEpisodeFailureNotice(episodeTag, "Bet Aborted", reason)
            }
            if (cancelSessionOnFail && !currentRoundBetActive && !gameDetection.gameState.value.isBetPlaced) {
                strategyEngine.cancelPatternSession(reason)
                currentRoundBetAttemptLockKey = null
            } else if (!cancelSessionOnFail) {
                currentRoundBetAttemptLockKey = null
            }
            isActionInProgress.set(false)
            onFinished?.invoke(false, reason)
        }

        fun isWindowBudgetExceeded(): Boolean =
            (System.currentTimeMillis() - sequenceWallStartMs) > betWindowBudgetMs

        try {
            // STEP 1: Refresh live screen targets and verify game package, phase, and BET control readiness
            val step1Targets = targetUiInteractor.refreshLiveTargets(latestUiTargets)
            if (step1Targets != null) latestUiTargets = step1Targets
            val initialPackage = step1Targets?.foregroundPackage?.takeIf { it.isNotBlank() }
                ?: gameDetection.gameState.value.sourcePackage

            if (targetUiInteractor is AccessibilityTargetUiInteractor && !ScreenEarnerAccessibilityService.isServiceConnected()) {
                finishFailed("Accessibility Service is not connected.", episodeTag = "a11y_disconnected")
                return
            }

            if (AccessibilityController.isExcludedForegroundPackage(initialPackage)) {
                finishFailed("Blocked non-game foreground overlay/panel ($initialPackage).", episodeTag = "excluded_package")
                return
            }

            if (step1Targets != null && step1Targets.conflictReasons.isNotEmpty()) {
                finishFailed("Detection conflict or uncertain screen reading: ${step1Targets.conflictReasons.first()}", episodeTag = "step1_conflict")
                return
            }

            val flightBlocksBet = step1Targets?.gamePhase == "IN_FLIGHT" && !config.queueBetDuringFlight
            val isBetReadyInTargets = !flightBlocksBet && (step1Targets?.siteButtonState == SiteButtonState.IDLE_READY_TO_BET ||
                (step1Targets?.isBetBtnDetected == true && step1Targets.isBetBtnEnabled))
            val observedPhaseStep1 = step1Targets?.gamePhase ?: lastObservedPhaseInEngine
            if (observedPhaseStep1 != "BETTING_OPEN" &&
                !isBetReadyInTargets &&
                !(isManualTrigger && (gameDetection.gameState.value.status == "BETTING_OPEN" || isBetReadyInTargets))
            ) {
                val reason = if (observedPhaseStep1 == "IN_FLIGHT") {
                    "Betting window is closed (Round is currently IN_FLIGHT). Crash games do not accept bets while a round is flying — please wait for BETTING OPEN."
                } else {
                    "Betting window is not open (phase=$observedPhaseStep1). Please wait for BETTING OPEN."
                }
                finishFailed(reason, episodeTag = "window_closed")
                return
            }

            step1Targets?.detectBetCollisionReason()?.let { collision ->
                finishFailed(collision, episodeTag = "step1_collision")
                return
            }

            if (step1Targets != null &&
                step1Targets.siteButtonState != SiteButtonState.IDLE_READY_TO_BET &&
                step1Targets.siteButtonState != SiteButtonState.UNKNOWN
            ) {
                finishFailed("Game BET button not ready (${step1Targets.siteButtonState}).", episodeTag = "btn_not_ready", cancelSessionOnFail = false)
                return
            }
            if (step1Targets != null && step1Targets.isBetBtnDetected &&
                (!step1Targets.isBetBtnEnabled || !step1Targets.isBetBtnClickable)
            ) {
                finishFailed("Game BET button is disabled or not clickable.", episodeTag = "btn_disabled", cancelSessionOnFail = false)
                return
            }
            if (config.verifyButtonStateBeforeAction &&
                !targetUiInteractor.isButtonReady(expectBet = true, config = config)
            ) {
                finishFailed("Target BET button is not ready (${gameDetection.gameState.value.siteButtonState}).", episodeTag = "btn_not_ready", cancelSessionOnFail = false)
                return
            }

            // Configured & site stake limits
            if (config.maxStake > 0.0 && proposedStake > config.maxStake + 1e-9) {
                finishFailed(
                    "Stake ${CurrencyManager.formatAmount(proposedStake)} is above your max stake ${CurrencyManager.formatAmount(config.maxStake)}.",
                    episodeTag = "stake_over_max"
                )
                return
            }
            if (config.minStake > 0.0 && proposedStake < config.minStake - 1e-9) {
                finishFailed(
                    "Stake ${CurrencyManager.formatAmount(proposedStake)} is below your min stake ${CurrencyManager.formatAmount(config.minStake)}.",
                    episodeTag = "stake_under_min"
                )
                return
            }

            val (siteMin, siteMax) = parseSiteStakeLimits(
                step1Targets?.visibleRawTitles.orEmpty() + step1Targets?.pageTextHints.orEmpty()
            )
            if (siteMax != null && proposedStake > siteMax + 1e-9) {
                finishFailed("Stake ${CurrencyManager.formatAmount(proposedStake)} is above the site max ${CurrencyManager.formatAmount(siteMax)}.", episodeTag = "stake_over_site_max")
                return
            }
            if (siteMin != null && proposedStake < siteMin - 1e-9) {
                finishFailed("Stake ${CurrencyManager.formatAmount(proposedStake)} is below the site min ${CurrencyManager.formatAmount(siteMin)}.", episodeTag = "stake_under_site_min")
                return
            }

            val learned = learnedStakeLimits[limitKey]
            learned?.second?.let { lm ->
                if (proposedStake > lm + 1e-9) {
                    finishFailed("Stake ${CurrencyManager.formatAmount(proposedStake)} is above the learned site max ${CurrencyManager.formatAmount(lm)}.", episodeTag = "stake_over_learned_max")
                    return
                }
            }
            learned?.first?.let { lm ->
                if (proposedStake < lm - 1e-9) {
                    finishFailed("Stake ${CurrencyManager.formatAmount(proposedStake)} is below the learned site min ${CurrencyManager.formatAmount(lm)}.", episodeTag = "stake_under_learned_min")
                    return
                }
            }
            val hasMinusStepper = targetUiInteractor is AccessibilityTargetUiInteractor && step1Targets?.stakeMinusBounds != null
            if (learned?.first == null && siteMin == null &&
                step1Targets?.gamePhase != "IN_FLIGHT" &&
                targetUiInteractor.requiresPostActionVerification &&
                hasMinusStepper &&
                limitProbeAttempted.add(limitKey)
            ) {
                publishStatusTicker("Learning the site's minimum stake with the - button...")
                targetUiInteractor.probeMinStake { m ->
                    if (m != null) learnStakeLimits(limitKey, m, null, "minus button")
                    finishFailed(
                        reason = if (m != null) {
                            "Learned site min stake ${CurrencyManager.formatAmount(m)}; betting resumes next round."
                        } else {
                            "Could not read the site's min stake; betting resumes next round."
                        },
                        emitNotice = false,
                        episodeTag = "limits_probe",
                        cancelSessionOnFail = false
                    )
                }
                return
            }

            // Risk checks (session limits, max rounds, max sequence exposure, emergency stop — no balance gating)
            val dailyLoss = currentDailyLossProvider?.invoke(currentTimeMs)
            if (limits.stopOnLossEnabled && limits.maxDailyLoss > 0.0 && dailyLoss != null && dailyLoss >= limits.maxDailyLoss) {
                finishFailed("Daily loss limit reached (-${CurrencyManager.formatAmount(dailyLoss)} >= ${CurrencyManager.formatAmount(limits.maxDailyLoss)}).", operation = "VALIDATE_LIMITS", episodeTag = "daily_loss_limit")
                return
            }
            val stratStateBefore = strategyEngine.getState()
            val riskCheck = riskManager.validateBetExecution(
                proposedStake = proposedStake,
                currentSessionLoss = activeSessionLoss,
                currentRounds = activeSessionRounds,
                currentProgressionLevel = stratStateBefore.currentStakeLevel,
                currentBalance = resolveEffectiveBalance(),
                limits = limits,
                currentSessionProfit = activeSessionProfit,
                currentDailyLoss = dailyLoss ?: 0.0,
                currentSequenceExposure = activeSequenceExposure + proposedStake
            )
            if (!riskCheck.isAllowed) {
                val reason = riskCheck.violationReason ?: "Risk limit reached."
                finishFailed(reason, episodeTag = "risk_limit", emitNotice = false)
                haltAutomationForLimit(reason)
                return
            }

            // STEP 2: Edit stake field accurately (never edit unrelated fields)
            _executionState.value = ActionExecutionState.EXECUTING_ACTION
            _executionRecord.value = initialRecord.copy(state = ActionExecutionState.EXECUTING_ACTION)
            publishStatusTicker("Step 2: Setting stake ${CurrencyManager.formatAmount(proposedStake)}...")
            FloatingHandsOverlayManager.pulsePassThroughAll(240L)

            val stakeStartWallMs = System.currentTimeMillis()
            targetUiInteractor.applyStake(
                newStake = proposedStake,
                progressionMode = effectiveMode,
                fallbackX = null,
                fallbackY = null
            ) { stakeOk ->
                val stakeElapsed = System.currentTimeMillis() - stakeStartWallMs
                if (stakeElapsed > stakeTimeoutMs || isWindowBudgetExceeded()) {
                    finishFailed("Stake editing timed out (${stakeElapsed}ms > ${stakeTimeoutMs}ms).", operation = "APPLY_STAKE", episodeTag = "stake_timeout")
                    return@applyStake
                }
                if (!stakeOk) {
                    finishFailed("Stake editing failed: stake field could not be identified or edited for ${CurrencyManager.formatAmount(proposedStake)}.", operation = "APPLY_STAKE", episodeTag = "stake_edit")
                    return@applyStake
                }

                // STEP 3: Read back and verify exact configured stake before clicking BET
                val step3Targets = targetUiInteractor.refreshLiveTargets(latestUiTargets)
                if (step3Targets != null) latestUiTargets = step3Targets
                if (step3Targets != null && step3Targets.conflictReasons.isNotEmpty()) {
                    finishFailed("Conflict reading stake back: ${step3Targets.conflictReasons.first()}", operation = "APPLY_STAKE", episodeTag = "stake_conflict")
                    return@applyStake
                }
                if (config.verifyStakeReadBack && (isRealA11y || !step3Targets?.detectedStakeText.isNullOrBlank())) {
                    val stakeText = step3Targets?.detectedStakeText
                    if (stakeText.isNullOrBlank() || !AccessibilityController.verifyStakeAccepted(stakeText, proposedStake)) {
                        val reason = if (stakeText.isNullOrBlank()) {
                            "Mandatory stake verification failed: game stake reading is missing or unreadable."
                        } else {
                            "Stake verification mismatch: expected ${CurrencyManager.formatAmount(proposedStake)}, game displays '$stakeText'."
                        }
                        finishFailed(reason, operation = "APPLY_STAKE", episodeTag = "stake_mismatch")
                        return@applyStake
                    }
                }

                txLaunch { recordStakeVerified(actionId, proposedStake, "stake read-back matched") }

                fun proceedBetClick(layoutSettled: Boolean = false) {
                    if (isWindowBudgetExceeded()) {
                        finishFailed("Betting window total timeout (${betWindowBudgetMs}ms) reached before BET.", episodeTag = "window_timeout")
                        return
                    }

                    if (!layoutSettled) {
                        if (config.dismissKeyboardBeforeBet) {
                            runCatching { targetUiInteractor.dismissKeyboardIfOpen() }
                        }
                        targetUiInteractor.awaitStableLayout { stable ->
                            if (!stable) {
                                finishFailed("Game layout still moving after editing; aborting BET.", episodeTag = "layout_unstable")
                            } else {
                                proceedBetClick(layoutSettled = true)
                            }
                        }
                        return
                    }

                    // STEP 4: Re-scan live foreground target right before clicking BET (never use stale coordinates)
                    val step6Targets = targetUiInteractor.refreshLiveTargets(latestUiTargets)
                    if (step6Targets != null) latestUiTargets = step6Targets

                    if (isWindowBudgetExceeded()) {
                        finishFailed("Betting window timeout exceeded during pre-BET re-scan.", episodeTag = "window_timeout")
                        return
                    }
                    if (!isManualTrigger && _automationState.value != AutomationState.RUNNING) {
                        isActionInProgress.set(false)
                        onFinished?.invoke(false, "Automation paused before BET click.")
                        return
                    }
                    val currentPkg = step6Targets?.foregroundPackage.orEmpty()
                    if (AccessibilityController.isExcludedForegroundPackage(currentPkg)) {
                        finishFailed("Blocked non-game overlay/panel ($currentPkg); aborting BET.", episodeTag = "excluded_package")
                        return
                    }
                    if (initialPackage.isNotBlank() &&
                        currentPkg.isNotBlank() &&
                        currentPkg != initialPackage
                    ) {
                        finishFailed("Active app changed to $currentPkg; aborting BET.", episodeTag = "package_switched")
                        return
                    }
                    if (step6Targets != null && step6Targets.conflictReasons.isNotEmpty()) {
                        finishFailed("Pre-BET scan conflict: ${step6Targets.conflictReasons.first()}", episodeTag = "prebet_conflict")
                        return
                    }
                    step6Targets?.detectBetCollisionReason()?.let { collision ->
                        finishFailed(collision, episodeTag = "prebet_collision")
                        return
                    }
                    if (step6Targets != null &&
                        step6Targets.siteButtonState != SiteButtonState.IDLE_READY_TO_BET &&
                        !(step6Targets.isBetBtnDetected && step6Targets.isBetBtnEnabled)
                    ) {
                        finishFailed("BET button unavailable (${step6Targets.siteButtonState}).", episodeTag = "btn_unavailable")
                        return
                    }
                    if (config.verifyStakeReadBack && (isRealA11y || !step6Targets?.detectedStakeText.isNullOrBlank())) {
                        val finalStakeText = step6Targets?.detectedStakeText
                        if (finalStakeText.isNullOrBlank() || !AccessibilityController.verifyStakeAccepted(finalStakeText, proposedStake)) {
                            val reason = if (finalStakeText.isNullOrBlank()) {
                                "Pre-BET stake re-check failed: stake reading missing."
                            } else {
                                "Pre-BET stake mismatch: expected ${CurrencyManager.formatAmount(proposedStake)}, displays '$finalStakeText'."
                            }
                            finishFailed(reason, operation = "PLACE_BET", episodeTag = "prebet_stake_mismatch")
                            return
                        }
                    }

                    // Always resolve click coordinates from the freshly refreshed live BET button bounds (never stale hand coords)
                    val verifiedCoords = scannedBetRect?.let { it.centerX().toFloat() to it.centerY().toFloat() }
                        ?: findActiveShortcut(ShortcutType.BET)?.let { it.targetX to it.targetY }

                    // STEP 5: Lock round attempt key, click live BET target, and confirm action
                    currentRoundBetAttemptLockKey = roundLockKey
                    val coordSource = if (verifiedCoords != null) "live_bounds" else "live_a11y"
                    publishStatusTicker("Placing bet (${CurrencyManager.formatAmount(proposedStake)}) via $coordSource...")
                    recordActivityLog(actionId, ActionExecutionState.EXECUTING_ACTION, "CLICK_BET", verifiedCoords, null, "Dispatching BET via $coordSource ($contextDetails).", null, null)

                    if (verifiedCoords != null) {
                        onGestureDispatchRequested?.invoke(verifiedCoords.first, verifiedCoords.second, "PLACE BET")
                    }
                    FloatingHandsOverlayManager.pulsePassThroughAll(280L)

                    lastLifecycleNoticeStage = "PLACING_BET"
                    emitExecutionNotice(
                        type = StrategyNotificationType.AUTOMATION_ACTION,
                        title = "Placing Bet: ${CurrencyManager.formatAmount(proposedStake)}",
                        message = "Verified stake & live BET target on $activeGameName ($strategyLabel, Round $activeRoundId).",
                        occurrenceKey = "placing_bet_${activeRoundId}_${actionId}",
                        priority = NotificationPriorityLevel.STANDARD,
                        cooldownMs = 0L
                    )

                    targetUiInteractor.clickBet(verifiedCoords?.first, verifiedCoords?.second) { gestureOk ->
                        if (!gestureOk) {
                            finishFailed("Bet click rejected during live target validation.", targetCoords = verifiedCoords, gestureDispatched = false, operation = "CLICK_BET", episodeTag = "bet_click")
                            return@clickBet
                        }

                        _executionState.value = ActionExecutionState.VERIFYING_ACTION
                        txLaunch { recordBetDispatched(actionId, "Bet click accepted by UI") }
                        val verifyingRecord = initialRecord.copy(
                            state = ActionExecutionState.VERIFYING_ACTION,
                            targetCoordinates = verifiedCoords,
                            gestureDispatched = true,
                            interfaceResponded = true
                        )
                        _executionRecord.value = verifyingRecord
                        gameDetection.updateExecutionRecord(verifyingRecord)

                        fun confirmBetSuccess() {
                            val completedRecord = initialRecord.copy(
                                state = ActionExecutionState.COMPLETED,
                                completionTimestampMs = System.currentTimeMillis(),
                                targetCoordinates = verifiedCoords,
                                isSuccess = true,
                                gestureDispatched = true,
                                interfaceResponded = true,
                                stateConfirmed = true
                            )
                            _executionRecord.value = completedRecord
                            _executionState.value = ActionExecutionState.COMPLETED
                            gameDetection.updateExecutionRecord(completedRecord)
                            recordActivityLog(actionId, ActionExecutionState.COMPLETED, "PLACE_BET", verifiedCoords, true, "Site confirmed bet ($contextDetails)", true, null)
                            publishStatusTicker("Bet confirmed on site (${CurrencyManager.formatAmount(proposedStake)}).")
                            txLaunch { recordBetAccepted(actionId, "Site confirmed bet") }

                            setRoundBetActive(true)
                            currentRoundStake = proposedStake
                            activeSequenceExposure += proposedStake
                            currentRoundCashedOut = false
                            currentRoundTriggerLabel = strategyLabel
                            activeSessionRounds += 1
                            lastBetFailureEpisodeKey = null
                            gameDetection.setBetPlaced(true)

                            StrategyEngine.triggerAction(
                                AutomationActionEvent(
                                    actionType = ActionType.BET,
                                    amountOrMultiplier = proposedStake,
                                    reason = "$strategyLabel (${CurrencyManager.formatCompact(proposedStake)})",
                                    timestamp = currentTimeMs
                                )
                            )
                            lastLifecycleNoticeStage = "BET_PLACED"
                            emitExecutionNotice(
                                type = StrategyNotificationType.AUTOMATION_ACTION,
                                title = "Bet Placed: ${CurrencyManager.formatAmount(proposedStake)}",
                                message = "Confirmed on $activeGameName ($strategyLabel, Round $activeRoundId).",
                                occurrenceKey = "bet_ok_${actionId}",
                                priority = NotificationPriorityLevel.STANDARD,
                                cooldownMs = 0L
                            )
                            isActionInProgress.set(false)
                            onFinished?.invoke(true, "Bet confirmed: ${CurrencyManager.formatAmount(proposedStake)}")
                        }

                        val postClickScan = targetUiInteractor.refreshLiveTargets(null)
                        val acceptedImmediately = !targetUiInteractor.requiresPostActionVerification || (
                            postClickScan != null && (
                                postClickScan.isCancelBtnDetected ||
                                    postClickScan.siteButtonState == SiteButtonState.BET_QUEUED ||
                                    postClickScan.siteButtonState == SiteButtonState.CASH_OUT_AVAILABLE
                                )
                            )
                        if (acceptedImmediately) {
                            confirmBetSuccess()
                        } else {
                            val confirmJob = scope.launch {
                                try {
                                    var elapsed = 0L
                                    var confirmed = false
                                    while (elapsed < 1800L) {
                                        delay(100L)
                                        elapsed += 100L
                                        val rescan = targetUiInteractor.refreshLiveTargets(null)
                                        if (rescan != null && (
                                                rescan.isCancelBtnDetected ||
                                                    rescan.siteButtonState == SiteButtonState.BET_QUEUED ||
                                                    rescan.siteButtonState == SiteButtonState.CASH_OUT_AVAILABLE
                                                )
                                        ) {
                                            confirmed = true
                                            break
                                        }
                                    }
                                    if (confirmed) confirmBetSuccess() else finishFailed(
                                        "Bet acceptance not confirmed by game: no fresh positive state transition detected.",
                                        targetCoords = verifiedCoords,
                                        gestureDispatched = true,
                                        operation = "PLACE_BET",
                                        episodeTag = "bet_unconfirmed"
                                    )
                                } catch (t: Throwable) {
                                    finishFailed("Bet verification interrupted: ${t.message}", targetCoords = verifiedCoords, gestureDispatched = true, episodeTag = "bet_verify_err")
                                }
                            }
                            confirmJob.invokeOnCompletion {
                                if (confirmJob.isCancelled) {
                                    isActionInProgress.set(false)
                                }
                            }
                        }
                    }
                }

                proceedBetClick()
            }
        } catch (t: Throwable) {
            finishFailed("Automation execution error: ${t.message}", episodeTag = "exec_exception")
        }
    }

    /**
     * Triggered when a round reaches CRASHED (Flew Away).
     * Records MultiplierRecord + exact crash timestamp, settles any active round bet,
     * and evaluates strategy rules for the next round.
     */
    fun handleRoundCompleted(
        multiplier: Double,
        config: StrategyConfig,
        limits: RiskLimits,
        platformName: String = gameDetection.gameState.value.platformName,
        currentTimeMs: Long = System.currentTimeMillis(),
        observationOnly: Boolean = false
    ) {
        sessionObservedCrashes += 1
        publishSessionStats()

        if (observationOnly) {
            val trigger = strategyEngine.processCompletedRound(multiplier, config, currentTimeMs)
            val stratState = strategyEngine.getState()
            val triggerName = formatTriggerLabel(trigger, stratState)
            gameDetection.updateStrategyTelemetry(stratState, triggerName)
            return
        }

        val gs = gameDetection.gameState.value
        val activeGameId = gs.gameId.ifBlank { "unknown" }
        val activeGameName = gs.displayedGameName.ifBlank { "Unknown" }
        val resolvedCat = gs.latestCrashColorCategory.takeIf { gs.latestCrashTimestampMs == currentTimeMs }
            ?: ColorClassifier.fallbackCategoryForMultiplier(multiplier, config.lowThreshold, config.bigThreshold)
        val resolvedHex = gs.latestCrashColorHex.takeIf { gs.latestCrashTimestampMs == currentTimeMs && it.isNotBlank() }
            ?: resolvedCat.defaultHex

        val seq = liveCrashSeqCounter.incrementAndGet().toInt()
        val dedup = MultiplierRecord.buildFallbackDedupKey(
            gameId = activeGameId,
            platform = platformName,
            roundId = null,
            gameTimestampMs = null,
            observationTimestampMs = currentTimeMs,
            crashTimestamp = currentTimeMs,
            multiplier = multiplier,
            sequenceIndex = seq
        )
        scope.launch {
            repository.addMultiplierRecord(
                MultiplierRecord(
                    sessionId = gs.activeSessionId,
                    gameId = activeGameId,
                    multiplier = multiplier,
                    gameTimestampMs = null,
                    observationTimestampMs = currentTimeMs,
                    crashTimestamp = currentTimeMs,
                    platform = platformName,
                    timestampSource = TimestampSource.LOCAL_OBSERVATION,
                    collectionSource = CollectionSource.LIVE_CRASH,
                    collectionStatus = gameDetection.historyMetadata.value.status,
                    dedupKey = dedup,
                    sequenceIndex = seq
                )
            )
            repository.insertCrashRecord(
                CrashRecord(
                    sessionId = gs.activeSessionId,
                    gameId = activeGameId,
                    gameName = activeGameName,
                    multiplier = multiplier,
                    detectedColorHex = resolvedHex,
                    colorCategory = resolvedCat,
                    isPixelColorVerified = gs.isLatestCrashColorVerified,
                    crashTimestamp = currentTimeMs,
                    hourStartTimestamp = CalendarHourUtils.hourStartFor(currentTimeMs),
                    sourcePackage = gs.sourcePackage,
                    roundIdentifier = null,
                    confidence = gs.detectionConfidence.coerceAtLeast(0.8f),
                    manuallyCorrected = false,
                    collectionSource = CollectionSource.LIVE_CRASH,
                    dedupKey = dedup
                )
            )
        }

        // If a bet was placed in this round during BETTING_OPEN, settle it at CRASHED
        currentRoundBetAttemptLockKey = null
        if (currentRoundBetActive) {
            settleActiveRoundBet(
                stakeUsed = currentRoundStake,
                crashedMultiplier = multiplier,
                cashedOutInFlight = currentRoundCashedOut,
                triggerLabel = currentRoundTriggerLabel,
                config = config,
                limits = limits,
                platformName = platformName,
                currentTimeMs = currentTimeMs
            )
            setRoundBetActive(false)
            currentRoundCashedOut = false
            gameDetection.setBetPlaced(false)
        } else if (targetUiInteractor !is AccessibilityTargetUiInteractor &&
            _automationState.value == AutomationState.RUNNING &&
            strategyEngine.getState().isBettingSessionActive &&
            config.isAutoBetEnabled
        ) {
            // Direct round-completion fallback for non-UI synchronous unit tests only
            val stratBefore = strategyEngine.getState()
            val proposedStake = stratBefore.currentCalculatedStake
            val riskCheck = riskManager.validateBetExecution(
                proposedStake = proposedStake,
                currentSessionLoss = activeSessionLoss,
                currentRounds = activeSessionRounds,
                currentProgressionLevel = stratBefore.currentStakeLevel,
                currentBalance = resolveEffectiveBalance(),
                limits = limits,
                currentSessionProfit = activeSessionProfit
            )

            if (!riskCheck.isAllowed) {
                haltAutomationForLimit(riskCheck.violationReason ?: "Risk limit reached.")
            } else {
                val instructionBefore = strategyEngine.getPendingStakeInstruction()
                val effectiveMode = if (instructionBefore.isSessionActive &&
                    instructionBefore.stakeLevel == stratBefore.currentStakeLevel
                ) {
                    instructionBefore.progressionMode
                } else {
                    stratBefore.activeProgressionMode
                }

                targetUiInteractor.applyStake(
                    newStake = proposedStake,
                    progressionMode = effectiveMode,
                    fallbackX = null,
                    fallbackY = null
                ) {
                    targetUiInteractor.clickBet(null, null) { }
                }

                activeSessionRounds += 1

                StrategyEngine.triggerAction(
                    AutomationActionEvent(
                        actionType = ActionType.BET,
                        amountOrMultiplier = proposedStake,
                        reason = stratBefore.activeRuleLabel ?: "Auto Bet (Level ${stratBefore.currentStakeLevel})",
                        timestamp = currentTimeMs
                    )
                )

                settleActiveRoundBet(
                    stakeUsed = proposedStake,
                    crashedMultiplier = multiplier,
                    cashedOutInFlight = multiplier >= config.targetMultiplier,
                    triggerLabel = stratBefore.activeRuleLabel ?: "Auto Progression (Level ${stratBefore.currentStakeLevel})",
                    config = config,
                    limits = limits,
                    platformName = platformName,
                    currentTimeMs = currentTimeMs
                )
            }
        }

        // Now evaluate the crashed round against StrategyEngine rules for upcoming rounds
        val trigger = strategyEngine.processCompletedRound(multiplier, config, currentTimeMs)
        val stratState = strategyEngine.getState()
        val triggerName = formatTriggerLabel(trigger, stratState)
        gameDetection.updateStrategyTelemetry(stratState, triggerName)
        notifyProgressionChanged()
        if (_automationState.value == AutomationState.RUNNING &&
            config.isAutoBetEnabled &&
            stratState.isBettingSessionActive &&
            trigger !is StrategyTrigger.None &&
            trigger !is StrategyTrigger.SessionTimedOut &&
            trigger !is StrategyTrigger.RangeWaitStarted
        ) {
            notifyStrategyWaitingToBet(triggerName ?: stratState.activeRuleLabel ?: "Active Strategy")
        }
    }

    private fun settleActiveRoundBet(
        stakeUsed: Double,
        crashedMultiplier: Double,
        cashedOutInFlight: Boolean,
        triggerLabel: String,
        config: StrategyConfig,
        limits: RiskLimits,
        platformName: String,
        currentTimeMs: Long
    ) {
        val settleTarget = activeTargetMultiplierOverride ?: config.targetMultiplier
        val isWin = cashedOutInFlight || (crashedMultiplier + 1e-9 >= settleTarget)
        val payout = if (isWin) stakeUsed * settleTarget else 0.0
        val netProfit = payout - stakeUsed
        activeTxId?.let { id ->
            txLaunch {
                if (isWin) {
                    recordCashOutDispatched(id, triggerLabel)
                    recordCashOutConfirmed(id, payout, "settled at ${settleTarget}x")
                    authorizeAndRecordSettlement(id)
                } else {
                    recordRoundLost(id, crashedMultiplier, "crashed before target")
                }
            }
        }
        activeTxId = null

        if (isWin) {
            sessionWins += 1
        } else {
            sessionLosses += 1
        }
        activeSessionProfit += netProfit
        activeSessionLoss = kotlin.math.max(0.0, -activeSessionProfit)
        onDailyNetOutcomeRecorded?.invoke(netProfit, currentTimeMs)

        val updatedState = strategyEngine.processBetOutcome(isWin, crashedMultiplier, config, currentTimeMs)
        if (!updatedState.isBettingSessionActive) {
            activeSequenceExposure = 0.0
            activeTargetMultiplierOverride = null
        }
        gameDetection.updateStrategyTelemetry(updatedState, formatTriggerLabel(updatedState.activeTrigger, updatedState))
        notifyProgressionChanged()

        publishSessionStats()

        lastLifecycleNoticeStage = if (isWin) "ROUND_WON" else "ROUND_LOST"
        emitExecutionNotice(
            type = StrategyNotificationType.AUTOMATION_ACTION,
            title = if (isWin) {
                "Round Won (+${CurrencyManager.formatAmount(netProfit)})"
            } else {
                "Round Lost (-${CurrencyManager.formatAmount(stakeUsed)})"
            },
            message = "Crashed at ${String.format(Locale.US, "%.2fx", crashedMultiplier)} on $platformName ($triggerLabel).",
            occurrenceKey = "round_settled_${currentTimeMs}_${if (isWin) "w" else "l"}",
            priority = NotificationPriorityLevel.STANDARD,
            cooldownMs = 0L
        )

        // Immediately halt all automation gestures if Stop if Profit Reaches, Stop if Loss Reaches, or Daily Loss triggers
        enforceLimitsNow(limits, currentTimeMs)

        scope.launch {
            repository.addSessionLog(
                SessionLog(
                    timestamp = currentTimeMs,
                    platform = platformName,
                    roundNumber = activeSessionRounds,
                    detectedMultiplier = crashedMultiplier,
                    outcome = if (isWin) "WIN" else "LOSS",
                    stake = stakeUsed,
                    payout = payout,
                    netProfit = netProfit,
                    triggerReason = triggerLabel
                )
            )
        }
    }

    private fun formatTriggerLabel(trigger: StrategyTrigger, state: StrategyState): String? {
        return when (trigger) {
            is StrategyTrigger.LossRecoveryFired -> "${trigger.ruleConfig.dynamicTitle()} (Fetch ${trigger.ruleConfig.targetWinsToFetch} wins)"
            is StrategyTrigger.RangeWaitStarted -> "${trigger.ruleConfig.dynamicTitle()} (Waiting)"
            is StrategyTrigger.WaitTimerCompleted -> "${trigger.ruleConfig.dynamicTitle()} Ready"
            is StrategyTrigger.SessionTimedOut -> "3m Guard Timed Out (${trigger.previousRuleName})"
            is StrategyTrigger.Strategy1Fired -> "Strategy 1 Triggered (${trigger.consecutiveLowRounds}x low)"
            is StrategyTrigger.Strategy2Fired -> "Strategy 2 Triggered (>10x + ${trigger.waitRoundsCompleted} rounds)"
            is StrategyTrigger.HighMultiplierLossTrigger -> ">13.00x Doubled Stake (${CurrencyManager.formatCompact(trigger.doubledStake)})"
            StrategyTrigger.None -> state.activeRuleLabel?.let { "$it (${state.consecutiveWinsInSession}/${state.targetWinsToFetch} wins)" }
        }
    }
}

private val SITE_MIN_REGEX = Regex("""(?i)(?:\bmin(?:imum)?\.?\s*(?:bet|stake)?\s*(?:amount)?\s*(?:is|of)?\s*[:=]?|\bat least)\s*([0-9][0-9,]*(?:\.[0-9]+)?)(?![0-9.,]|\s*[xX%])""")
private val SITE_MAX_REGEX = Regex("""(?i)(?:\bmax(?:imum)?\.?\s*(?:bet|stake)?\s*(?:amount)?\s*(?:is|of)?\s*[:=]?|\b(?:at most|no more than|not exceed|cannot exceed))\s*([0-9][0-9,]*(?:\.[0-9]+)?)(?![0-9.,]|\s*[xX%])""")

/** Reads "Min 10 / Max 100,000" style stake limits from visible page text. Returns (min, max); null when absent. */
internal fun parseSiteStakeLimits(texts: List<String>): Pair<Double?, Double?> {
    var min: Double? = null
    var max: Double? = null
    for (t in texts) {
        if (min == null) min = SITE_MIN_REGEX.find(t)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        if (max == null) max = SITE_MAX_REGEX.find(t)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    }
    if (min != null && max != null && min > max) return null to null
    return min to max
}
