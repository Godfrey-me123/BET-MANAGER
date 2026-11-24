package com.example

import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PathMeasure
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDao
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationEngine
import com.example.data.engine.GameDetection
import com.example.data.engine.RiskManager
import com.example.data.engine.StrategyEngine
import com.example.data.engine.TargetUiInteractor
import com.example.data.model.ActionExecutionState
import com.example.data.model.ActivityLogEntry
import com.example.data.model.AppLogLevel
import com.example.data.model.CustomPatternStrategy
import com.example.data.model.FloatingShortcut
import com.example.data.model.MultiplierRecord
import com.example.data.model.NotificationHistoryRecord
import com.example.data.model.NotificationInteractionStatus
import com.example.data.model.NotificationSettings
import com.example.data.model.PlatformProfile
import com.example.data.model.RiskLimits
import com.example.data.model.SessionLog
import com.example.data.model.ShortcutType
import com.example.data.model.SiteButtonState
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRuleConfig
import com.example.data.model.StrategyRuleId
import com.example.service.AccessibilityController
import com.example.service.AppLogManager
import com.example.service.DetectedUiTargets
import com.example.ui.screens.LogsAnalyticsScreen
import com.example.ui.theme.AutoPredictorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.IdentityHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLogAndHandAutoBetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        AppLogManager.clear()
    }

    @After
    fun tearDown() {
        AppLogManager.clear()
    }

    private class HandTestAccessibilityController(
        private val tree: IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>> = IdentityHashMap(),
        private val activeRoot: AccessibilityNodeInfo? = null
    ) : AccessibilityController() {
        val dispatchedGestures = mutableListOf<GestureDescription>()

        override fun childNodesOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = tree[node] ?: emptyList()

        override fun resolveBestTargetWindowRoot(): Pair<AccessibilityNodeInfo, Boolean>? {
            return activeRoot?.let { it to false }
        }

        override fun performPlatformDispatchGesture(
            gesture: GestureDescription,
            callback: GestureResultCallback,
            handler: Handler
        ): Boolean {
            dispatchedGestures.add(gesture)
            callback.onCompleted(gesture)
            return true
        }
    }

    private fun extractGestureStartXY(gesture: GestureDescription): Pair<Float, Float> {
        val path = gesture.getStroke(0).path
        val pts = Shadows.shadowOf(path).points
        val first = pts.first()
        return first.x to first.y
    }

    @Test
    fun `AppLogManager maps ActivityLogEntry levels exports filtered blocks clears and enforces ring cap`() {
        AppLogManager.clear()
        assertEquals(1, AppLogManager.logs.value.size)
        assertEquals("Logs cleared", AppLogManager.logs.value.single().message)

        val okEntry = ActivityLogEntry(
            actionId = "act_ok_1",
            state = ActionExecutionState.COMPLETED,
            target = "PLACE_BET",
            coordinates = 520f to 1180f,
            gestureResult = true,
            eventObserved = "Site confirmed bet",
            verificationResult = true,
            errorDetails = null
        )
        val failEntry = ActivityLogEntry(
            actionId = "act_err_1",
            state = ActionExecutionState.FAILED,
            target = "PLACE_BET",
            coordinates = 520f to 1180f,
            gestureResult = false,
            eventObserved = "Tap failed",
            verificationResult = false,
            errorDetails = "Gesture rejected"
        )
        AppLogManager.fromActivityLog(okEntry)
        AppLogManager.fromActivityLog(failEntry)

        val logs = AppLogManager.logs.value
        assertTrue(logs.any { it.level == AppLogLevel.SUCCESS && it.actionId == "act_ok_1" && it.coordinates == (520f to 1180f) })
        assertTrue(logs.any { it.level == AppLogLevel.ERROR && it.actionId == "act_err_1" && it.details == "Gesture rejected" })

        val errorExport = AppLogManager.exportAllText(AppLogLevel.ERROR)
        assertTrue(errorExport.contains("Predict Manager App Logs (1 entries)"))
        assertTrue(errorExport.contains("act_err_1"))
        assertFalse(errorExport.contains("act_ok_1"))

        val warnExport = AppLogManager.exportAllText(AppLogLevel.WARN)
        assertEquals("(no log entries)", warnExport)

        // Ring buffer caps strictly at 500 entries
        repeat(550) { idx ->
            AppLogManager.i("RingTest", "line-$idx")
        }
        assertEquals(500, AppLogManager.logs.value.size)
        assertEquals("line-549", AppLogManager.logs.value.last().message)

        AppLogManager.clear()
        assertEquals(1, AppLogManager.logs.value.size)
        assertEquals("Logs cleared", AppLogManager.logs.value.single().message)
    }

    @Test
    fun `LogsAnalyticsScreen switches to App Logs filters levels copies to clipboard and clears`() {
        AppLogManager.clear()
        AppLogManager.success("Gesture", "BET gesture dispatched", details = "stake=20", coordinates = 480f to 1240f)
        AppLogManager.e("Gesture", "BET click aborted", details = "simulated error")

        composeRule.setContent {
            AutoPredictorTheme {
                LogsAnalyticsScreen()
            }
        }

        // Default tab is Crash History; switch to App Logs
        composeRule.onNodeWithTag("logs_section_app_logs").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("diagnostic_logs_panel").assertIsDisplayed()
        composeRule.onNodeWithText("BET gesture dispatched").assertIsDisplayed()
        composeRule.onNodeWithText("BET click aborted").assertIsDisplayed()

        // Filter to ERROR only
        composeRule.onNodeWithTag("app_logs_filter_error").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("BET click aborted").assertIsDisplayed()
        composeRule.onNodeWithText("APP LOGS (1)").assertIsDisplayed()

        // Copy All copies filtered diagnostic text to system clipboard
        composeRule.onNodeWithTag("app_logs_copy_all_btn").performClick()
        composeRule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(clipText.contains("BET click aborted"))
        assertFalse(clipText.contains("BET gesture dispatched"))

        // Switch back to All and Clear
        composeRule.onNodeWithTag("app_logs_filter_all").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("app_logs_clear_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Logs cleared").assertIsDisplayed()
    }

    @Test
    fun `clickBetTarget and clickCashOutTarget dispatch calibrated hand coords even without active root`() {
        val controller = HandTestAccessibilityController(activeRoot = null)

        var betOk: Boolean? = null
        controller.clickBetTarget(fallbackX = 640f, fallbackY = 1280f) { betOk = it }
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, betOk)
        assertEquals(1, controller.dispatchedGestures.size)
        assertEquals(640f to 1280f, extractGestureStartXY(controller.dispatchedGestures[0]))
        assertTrue(AppLogManager.logs.value.any {
            it.level == AppLogLevel.SUCCESS && it.message == "BET gesture dispatched" && it.coordinates == (640f to 1280f)
        })

        // Without hand coords and without active root, abort cleanly and log error
        var missingOk: Boolean? = null
        controller.clickBetTarget(fallbackX = null, fallbackY = null) { missingOk = it }
        assertEquals(false, missingOk)
        assertTrue(AppLogManager.logs.value.any {
            it.level == AppLogLevel.ERROR && it.message == "BET click aborted"
        })
    }

    @Test
    fun `AutomationEngine calibrated hand autobet succeeds even with SiteButtonState UNKNOWN and OCR conflict noise`() = runBlocking {
        val fakeDao = object : ScreenEarnerDao {
            override fun getAllShortcuts(): Flow<List<FloatingShortcut>> = emptyFlow()
            override suspend fun insertShortcut(shortcut: FloatingShortcut): Long = 1L
            override suspend fun updateShortcut(shortcut: FloatingShortcut) {}
            override suspend fun deleteShortcutById(id: Long) {}
            override suspend fun deleteAllShortcuts() {}
            override fun getAllProfiles(): Flow<List<PlatformProfile>> = emptyFlow()
            override suspend fun insertProfile(profile: PlatformProfile) {}
            override suspend fun setActiveProfile(activeId: String) {}
            override fun getStrategyConfig(): Flow<StrategyConfig?> = emptyFlow()
            override suspend fun saveStrategyConfig(config: StrategyConfig) {}
            override fun getRiskLimits(): Flow<RiskLimits?> = emptyFlow()
            override suspend fun saveRiskLimits(limits: RiskLimits) {}
            override fun getRecentSessionLogs(): Flow<List<SessionLog>> = emptyFlow()
            override suspend fun insertSessionLog(log: SessionLog) {}
            override suspend fun clearSessionLogs() {}
            override fun getAllMultiplierRecords(): Flow<List<MultiplierRecord>> = emptyFlow()
            override fun getMultiplierRecordsSince(sinceTimestamp: Long): Flow<List<MultiplierRecord>> = emptyFlow()
            override suspend fun getMultiplierRecordsSinceChronological(sinceTimestamp: Long): List<MultiplierRecord> = emptyList()
            override suspend fun insertMultiplierRecord(record: MultiplierRecord) {}
            override suspend fun insertMultiplierRecords(records: List<MultiplierRecord>) {}
            override suspend fun pruneMultiplierRecordsOlderThan(cutoffTimestamp: Long) {}
            override suspend fun clearMultiplierRecords() {}
            override suspend fun clearMultiplierRecordsForSession(sessionId: String) {}
            override fun getAllCustomStrategies(): Flow<List<CustomPatternStrategy>> = emptyFlow()
            override suspend fun getCustomStrategiesOnce(): List<CustomPatternStrategy> = emptyList()
            override suspend fun insertCustomStrategy(strategy: CustomPatternStrategy): Long = 1L
            override suspend fun updateCustomStrategy(strategy: CustomPatternStrategy) {}
            override suspend fun deleteCustomStrategyById(id: Long) {}
            override fun getNotificationSettings(): Flow<NotificationSettings?> = emptyFlow()
            override suspend fun getNotificationSettingsOnce(): NotificationSettings? = NotificationSettings()
            override suspend fun saveNotificationSettings(settings: NotificationSettings) {}
            override fun getAllNotificationHistory(): Flow<List<NotificationHistoryRecord>> = emptyFlow()
            override suspend fun insertNotificationHistory(record: NotificationHistoryRecord): Long = 1L
            override suspend fun updateNotificationInteraction(id: Long, status: NotificationInteractionStatus) {}
            override suspend fun clearNotificationHistory() {}
            override fun getAllCrashRecords(): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override fun getCrashRecordsForSession(sessionId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForSessionOnce(sessionId: String): List<com.example.data.model.CrashRecord> = emptyList()
            override fun getCrashRecordsForGame(gameId: String): Flow<List<com.example.data.model.CrashRecord>> = emptyFlow()
            override suspend fun getCrashRecordsForGameSinceAsc(gameId: String, sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsForGameBetweenAsc(gameId: String, startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsSinceAsc(sinceMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordsBetweenAsc(startMs: Long, endMs: Long): List<com.example.data.model.CrashRecord> = emptyList()
            override suspend fun getCrashRecordById(id: Long): com.example.data.model.CrashRecord? = null
            override suspend fun existsCrashByDedupKey(dedupKey: String): Boolean = false
            override suspend fun insertCrashRecord(record: com.example.data.model.CrashRecord): Long = 1L
            override suspend fun insertCrashRecords(records: List<com.example.data.model.CrashRecord>): List<Long> = emptyList()
            override suspend fun updateCrashRecord(record: com.example.data.model.CrashRecord) {}
            override suspend fun clearCrashRecordsForSession(sessionId: String) {}
            override suspend fun clearCrashRecordsForGame(gameId: String) {}
            override suspend fun clearAllCrashRecords() {}
            override fun getAllCrashCorrectionAudits(): Flow<List<com.example.data.model.CrashCorrectionAudit>> = emptyFlow()
            override suspend fun insertCrashCorrectionAudit(audit: com.example.data.model.CrashCorrectionAudit): Long = 1L
            override fun getAllHourlySummaries(): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override fun getHourlySummariesForGame(gameId: String): Flow<List<com.example.data.model.HourlySessionSummary>> = emptyFlow()
            override suspend fun getHourlySummary(gameId: String, hourStartMs: Long): com.example.data.model.HourlySessionSummary? = null
            override suspend fun upsertHourlySummary(summary: com.example.data.model.HourlySessionSummary): Long = 1L
            override fun getAllPatternOccurrences(): Flow<List<com.example.data.model.PatternOccurrenceRecord>> = emptyFlow()
            override suspend fun getPatternOccurrencesForStrategyHour(strategyId: Long, hourStartMs: Long): List<com.example.data.model.PatternOccurrenceRecord> = emptyList()
            override suspend fun getLatestOccurrenceForStrategy(strategyId: Long): com.example.data.model.PatternOccurrenceRecord? = null
            override suspend fun insertPatternOccurrence(record: com.example.data.model.PatternOccurrenceRecord): Long = 1L
            override fun getAllMonitoringSessions(): Flow<List<com.example.data.model.MonitoringSessionEntity>> = emptyFlow()
            override suspend fun getActiveMonitoringSession(): com.example.data.model.MonitoringSessionEntity? = null
            override suspend fun upsertMonitoringSession(session: com.example.data.model.MonitoringSessionEntity) {}
            override suspend fun closeActiveMonitoringSessions(endedAtMs: Long) {}
            override suspend fun deleteArchivedSessionsForGame(gameId: String) {}
            override suspend fun clearAllMonitoringSessions() {}
            override suspend fun clearArchivedMultiplierRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun clearArchivedCrashRecordsForGame(gameId: String, activeSessionId: String) {}
            override suspend fun countCrashRecordsForSession(sessionId: String): Int = 0
            override suspend fun countArchivedCrashRecordsForGame(gameId: String, activeSessionId: String): Int = 0
            override suspend fun countAllCrashRecords(): Int = 0
            override suspend fun clearHourlySummariesForGame(gameId: String) {}
            override suspend fun clearAllHourlySummaries() {}
            override suspend fun clearPatternOccurrencesForGame(gameId: String) {}
            override suspend fun clearAllPatternOccurrences() {}
            override fun getAllAlarmProfiles(): Flow<List<com.example.data.model.AlarmProfileEntity>> = emptyFlow()
            override suspend fun getAlarmProfilesOnce(): List<com.example.data.model.AlarmProfileEntity> = emptyList()
            override suspend fun getAlarmProfile(categoryName: String): com.example.data.model.AlarmProfileEntity? = null
            override suspend fun upsertAlarmProfile(profile: com.example.data.model.AlarmProfileEntity) {}
            override suspend fun insertAlarmProfilesIfAbsent(profiles: List<com.example.data.model.AlarmProfileEntity>) {}
            override suspend fun clearNotificationHistoryForGame(gameId: String) {}
        }
        val repo = ScreenEarnerRepository(fakeDao)

        var betClicks = 0
        var cashOutClicks = 0
        var clickedBetCoords: Pair<Float?, Float?>? = null
        var clickedCashOutCoords: Pair<Float?, Float?>? = null
        var betDispatched = false
        var inFlight = false

        val noisyTargetsBeforeBet = DetectedUiTargets(
            gamePhase = "BETTING_OPEN",
            siteButtonState = SiteButtonState.UNKNOWN,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = null,
            stakeInputBounds = Rect(120, 1150, 380, 1220),
            detectedStakeText = "25.00",
            pageTextHints = listOf("Min 10  Max 100000"),
            conflictReasons = listOf("OCR vs A11y noise"),
            isBetBtnEnabled = false,
            isBetBtnClickable = false
        )
        val queuedTargetsAfterBet = noisyTargetsBeforeBet.copy(
            siteButtonState = SiteButtonState.BET_QUEUED,
            isCancelBtnDetected = true,
            conflictReasons = emptyList()
        )
        val inFlightTargets = DetectedUiTargets(
            gamePhase = "IN_FLIGHT",
            siteButtonState = SiteButtonState.CASH_OUT_AVAILABLE,
            foregroundPackage = "com.android.chrome",
            cashOutButtonBounds = Rect(450, 1150, 900, 1300),
            isCashOutBtnEnabled = true,
            isCashOutBtnClickable = true
        )
        val postCashOutTargets = inFlightTargets.copy(
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            cashOutButtonBounds = null
        )

        val interactor = object : TargetUiInteractor {
            override val requiresPostActionVerification: Boolean = true
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) = onComplete(true)

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                betClicks++
                clickedBetCoords = fallbackX to fallbackY
                betDispatched = true
                onComplete(true)
            }



            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets? {
                return when {
                    cashOutClicks > 0 -> postCashOutTargets
                    inFlight -> inFlightTargets
                    betDispatched -> queuedTargetsAfterBet
                    else -> noisyTargetsBeforeBet
                }
            }
        }

        val engine = AutomationEngine(
            repository = repo,
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = GameDetection(),
            targetUiInteractor = interactor
        )
        engine.testBalanceOverride = 500.0
        engine.updateActivePlatform("SportyBet Aviator")
        engine.updateCalibratedShortcuts(
            listOf(
                FloatingShortcut(
                    id = 1L,
                    label = "ACTION HAND",
                    type = ShortcutType.BET,
                    targetPlatform = "SportyBet",
                    targetX = 775f,
                    targetY = 1235f,
                    isCalibrated = true
                )
            )
        )

        val config = StrategyConfig(
            baseStake = 25.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = true,
            automationMasterEnabled = true,
            gameAutoCashOutEnabled = false,
            verifyStakeReadBack = true,
            verifyButtonStateBeforeAction = true,
            minFlightTimeBeforeCashOutMs = 0L,
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.AFTER_2_LOSSES,
                isEnabled = true,
                requiredLosses = 2
            )
        )
        val limits = RiskLimits()
        engine.startAutomation(config, limits)
        engine.handleRoundCompleted(1.20, config, limits, currentTimeMs = 1_000L)
        engine.handleRoundCompleted(1.30, config, limits, currentTimeMs = 2_000L)
        assertTrue(engine.strategyEngine.getState().isBettingSessionActive)

        // Phase BETTING_OPEN with UNKNOWN button state + noisy OCR conflict -> Action Hand calibrated path still places BET!
        engine.handlePhaseUpdate(
            phase = "BETTING_OPEN",
            liveMultiplier = 1.00,
            config = config,
            limits = limits,
            currentTimeMs = 3_000L,
            siteButtonState = SiteButtonState.UNKNOWN
        )
        delay(50L)
        assertEquals(1, betClicks)
        assertEquals(775f to 1235f, clickedBetCoords)
        assertTrue(engine.isRoundBetActiveState.value)

        // In-flight >= 2.00x -> Action Hand coordinates also drive CASH OUT
        inFlight = true
        engine.handlePhaseUpdate(
            phase = "IN_FLIGHT",
            liveMultiplier = 2.15,
            config = config,
            limits = limits,
            currentTimeMs = 5_000L,
            siteButtonState = SiteButtonState.CASH_OUT_AVAILABLE
        )
        delay(50L)
        assertEquals(1, cashOutClicks)
        assertEquals(775f to 1235f, clickedCashOutCoords)
        assertTrue(AppLogManager.logs.value.any {
            it.level == AppLogLevel.SUCCESS && it.message.contains("PLACE_BET")
        })
        delay(200L)
    }
}
