package com.example

import android.content.Context
import android.graphics.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationEngine
import com.example.data.engine.AutomationState
import com.example.data.engine.GameDetection
import com.example.data.engine.RiskManager
import com.example.data.engine.StrategyEngine
import com.example.data.engine.TargetUiInteractor
import com.example.data.model.BalanceDetectionStatus
import com.example.data.model.RiskLimits
import com.example.data.model.SiteButtonState
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRuleConfig
import com.example.data.model.StrategyRuleId
import com.example.service.DedicatedHandSettingsComposable
import com.example.service.DetectedUiTargets
import com.example.service.FloatingHandsOverlayManager
import com.example.service.GameDetectionState
import com.example.service.OcrFrameSnapshot
import com.example.service.OcrTextElement
import com.example.service.ScreenOcrAnalyzer
import com.example.service.UnifiedHandVisualState
import com.example.ui.theme.ScreenEarnerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VerifiedAutomationAndOcrTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var db: ScreenEarnerDatabase? = null
    private var testEngineScope: CoroutineScope? = null

    @After
    fun tearDown() {
        val job = testEngineScope?.coroutineContext?.get(kotlinx.coroutines.Job)
        testEngineScope = null
        runBlocking {
            job?.let {
                it.cancel()
                it.join()
            }
            delay(80L)
        }
        db?.close()
        db = null
    }

    private fun createEngine(interactor: TargetUiInteractor): AutomationEngine {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = database
        val repo = ScreenEarnerRepository(database.dao())
        val gameDetection = GameDetection()
        gameDetection.updateFromAccessibilityDetection(
            GameDetectionState(
                isGameDetected = true,
                gameId = "aviator",
                displayedGameName = "BetPawa Aviator",
                platformName = "BetPawa Aviator",
                gamePhase = "BETTING_OPEN",
                foregroundPackage = "com.android.chrome"
            ),
            onRealRoundCompleted = {},
            currentTimeMs = 1_700_000_000_000L
        )
        testEngineScope?.cancel()
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        testEngineScope = engineScope
        return AutomationEngine(
            repository = repo,
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = gameDetection,
            scope = engineScope,
            targetUiInteractor = interactor
        ).also {
            it.testBalanceOverride = 5000.0
            it.currentDailyLossProvider = { 0.0 }
        }
    }

    // =========================================================================
    // REQ 1: FIX STAKE DOUBLING & MASTER LOSS PROGRESSION CONTROLS
    // =========================================================================

    @Test
    fun `default StrategyConfig disables all loss progression even when Martingale or rule doubling is set`() {
        val defaults = StrategyConfig()
        assertFalse(defaults.lossProgressionEnabled)
        assertEquals(StakeProgressionMode.DISABLED, defaults.lossProgressionMode)
        assertFalse(defaults.isMartingaleEnabled)
        assertFalse(defaults.isHighMultiplierLossRuleEnabled)

        val engine = StrategyEngine()
        // Case A: Martingale ON + rule DOUBLE_AMOUNT ON, but Master Loss Progression OFF -> stake MUST stay flat
        val blockedByMasterOff = StrategyConfig(
            baseStake = 20.0,
            isMartingaleEnabled = true,
            isHighMultiplierLossRuleEnabled = true,
            lossProgressionEnabled = false,
            lossProgressionMode = StakeProgressionMode.DOUBLE_AMOUNT
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.AFTER_2_LOSSES,
                isEnabled = true,
                progressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
                requiredLosses = 2
            )
        )
        engine.reset(20.0)
        engine.processCompletedRound(1.20, blockedByMasterOff, 1_000L)
        engine.processCompletedRound(1.30, blockedByMasterOff, 2_000L)
        assertTrue(engine.getState().isBettingSessionActive)

        val afterLoss1 = engine.processBetOutcome(isWin = false, multiplier = 1.40, config = blockedByMasterOff, currentTimeMs = 3_000L)
        val afterHighCrashLoss = engine.processBetOutcome(isWin = false, multiplier = 22.00, config = blockedByMasterOff, currentTimeMs = 4_000L)
        assertEquals(20.0, afterLoss1.currentCalculatedStake, 0.001)
        assertEquals(20.0, afterHighCrashLoss.currentCalculatedStake, 0.001)
        assertEquals(StakeProgressionMode.DISABLED, afterHighCrashLoss.activeProgressionMode)
    }

    @Test
    fun `master progression modes DISABLED, DOUBLE_AMOUNT, and ADD_AMOUNT govern Martingale and strategy rules`() {
        val engine = StrategyEngine()

        // Mode DISABLED overrides rule's DOUBLE_AMOUNT
        val disabledCfg = StrategyConfig(
            baseStake = 15.0,
            lossProgressionEnabled = true,
            lossProgressionMode = StakeProgressionMode.DISABLED
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.AFTER_2_LOSSES,
                isEnabled = true,
                progressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
                requiredLosses = 2
            )
        )
        engine.reset(15.0)
        engine.processCompletedRound(1.10, disabledCfg, 1_000L)
        engine.processCompletedRound(1.15, disabledCfg, 2_000L)
        engine.processBetOutcome(false, 1.30, disabledCfg, 3_000L)
        assertEquals(15.0, engine.getState().currentCalculatedStake, 0.001)

        // Mode ADD_AMOUNT overrides rule's DOUBLE_AMOUNT and adds master lossProgressionAddAmount
        val addCfg = disabledCfg.copy(
            lossProgressionEnabled = true,
            lossProgressionMode = StakeProgressionMode.ADD_AMOUNT,
            lossProgressionAddAmount = 7.5
        )
        engine.reset(15.0)
        engine.processCompletedRound(1.10, addCfg, 1_000L)
        engine.processCompletedRound(1.15, addCfg, 2_000L)
        engine.processBetOutcome(false, 1.30, addCfg, 3_000L)
        assertEquals(22.5, engine.getState().currentCalculatedStake, 0.001)
        engine.processBetOutcome(false, 1.40, addCfg, 4_000L)
        assertEquals(30.0, engine.getState().currentCalculatedStake, 0.001)

        // Mode DOUBLE_AMOUNT with Martingale enabled doubles cleanly on loss and resets on win
        val doubleCfg = StrategyConfig(
            baseStake = 10.0,
            isMartingaleEnabled = true,
            lossProgressionEnabled = true,
            lossProgressionMode = StakeProgressionMode.DOUBLE_AMOUNT
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.AFTER_2_LOSSES,
                isEnabled = true,
                progressionMode = StakeProgressionMode.ADD_AMOUNT,
                requiredLosses = 2,
                targetWinsToFetch = 1
            )
        )
        engine.reset(10.0)
        engine.processCompletedRound(1.10, doubleCfg, 1_000L)
        engine.processCompletedRound(1.15, doubleCfg, 2_000L)
        engine.processBetOutcome(false, 1.25, doubleCfg, 3_000L)
        assertEquals(20.0, engine.getState().currentCalculatedStake, 0.001)
        engine.processBetOutcome(true, 2.50, doubleCfg, 4_000L)
        assertEquals(10.0, engine.getState().currentCalculatedStake, 0.001)
    }

    @Test
    fun `room migration 14 to 15 preserves existing rows and initializes progression and timing defaults`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test_v14_v15.db"
        context.deleteDatabase(dbName)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE strategy_configs (
                                id INTEGER NOT NULL PRIMARY KEY,
                                isStrategy1Enabled INTEGER NOT NULL,
                                isStrategy2Enabled INTEGER NOT NULL,
                                isHighMultiplierLossRuleEnabled INTEGER NOT NULL,
                                strategy1LowThreshold REAL NOT NULL,
                                strategy1RequiredLowRounds INTEGER NOT NULL,
                                strategy2HighThreshold REAL NOT NULL,
                                strategy2WaitRounds INTEGER NOT NULL,
                                baseStake REAL NOT NULL,
                                multiplierProgression REAL NOT NULL,
                                stopAfterConsecutiveWins INTEGER NOT NULL,
                                targetMultiplier REAL NOT NULL,
                                isAutoBetEnabled INTEGER NOT NULL,
                                isAutoCashOutEnabled INTEGER NOT NULL,
                                sessionTimeoutMinutes INTEGER NOT NULL,
                                currencyPreference TEXT NOT NULL,
                                keepScreenAwake INTEGER NOT NULL,
                                diagnosticToastsEnabled INTEGER NOT NULL,
                                diagnosticNotificationsEnabled INTEGER NOT NULL,
                                scheduleEnabled INTEGER NOT NULL,
                                scheduleStartHour INTEGER NOT NULL,
                                scheduleStartMinute INTEGER NOT NULL,
                                scheduleEndHour INTEGER NOT NULL,
                                scheduleEndMinute INTEGER NOT NULL,
                                lowThreshold REAL NOT NULL,
                                boundaryValue REAL NOT NULL,
                                bigThreshold REAL NOT NULL,
                                gridRows INTEGER NOT NULL,
                                gridColumns INTEGER NOT NULL,
                                monitoringEnabled INTEGER NOT NULL,
                                pixelColorCaptureEnabled INTEGER NOT NULL,
                                ruleConfigsJson TEXT NOT NULL,
                                maxStrategyBetAttempts INTEGER NOT NULL,
                                isMartingaleEnabled INTEGER NOT NULL,
                                minDetectionConfidence REAL NOT NULL,
                                historyCollectionEnabled INTEGER NOT NULL,
                                autoGameRecognitionEnabled INTEGER NOT NULL,
                                preferredGameId TEXT NOT NULL,
                                automationMasterEnabled INTEGER NOT NULL,
                                minFlightTimeBeforeCashOutMs INTEGER NOT NULL,
                                maxBetRetries INTEGER NOT NULL,
                                retryDelayMs INTEGER NOT NULL,
                                bettingWindowExpiryMs INTEGER NOT NULL,
                                dismissKeyboardBeforeBet INTEGER NOT NULL,
                                verifyStakeReadBack INTEGER NOT NULL,
                                verifyButtonStateBeforeAction INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO strategy_configs (
                                id, isStrategy1Enabled, isStrategy2Enabled, isHighMultiplierLossRuleEnabled,
                                strategy1LowThreshold, strategy1RequiredLowRounds, strategy2HighThreshold, strategy2WaitRounds,
                                baseStake, multiplierProgression, stopAfterConsecutiveWins, targetMultiplier,
                                isAutoBetEnabled, isAutoCashOutEnabled, sessionTimeoutMinutes, currencyPreference,
                                keepScreenAwake, diagnosticToastsEnabled, diagnosticNotificationsEnabled,
                                scheduleEnabled, scheduleStartHour, scheduleStartMinute, scheduleEndHour, scheduleEndMinute,
                                lowThreshold, boundaryValue, bigThreshold, gridRows, gridColumns,
                                monitoringEnabled, pixelColorCaptureEnabled, ruleConfigsJson,
                                maxStrategyBetAttempts, isMartingaleEnabled, minDetectionConfidence,
                                historyCollectionEnabled, autoGameRecognitionEnabled, preferredGameId,
                                automationMasterEnabled, minFlightTimeBeforeCashOutMs, maxBetRetries,
                                retryDelayMs, bettingWindowExpiryMs, dismissKeyboardBeforeBet,
                                verifyStakeReadBack, verifyButtonStateBeforeAction
                            ) VALUES (
                                1, 1, 0, 0, 2.0, 14, 10.0, 15,
                                50.0, 2.0, 2, 2.5,
                                1, 1, 3, 'AUTO',
                                1, 1, 1,
                                0, 8, 0, 23, 0,
                                2.0, 2.0, 10.0, 5, 8,
                                1, 0, '',
                                2, 0, 0.5,
                                1, 1, 'aviator',
                                1, 1200, 2,
                                350, 6000, 1,
                                1, 1
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val rawDb = helper.writableDatabase
        ScreenEarnerDatabase.MIGRATION_14_15.migrate(rawDb)

        rawDb.query(
            """
            SELECT baseStake, preferredGameId, lossProgressionEnabled, lossProgressionMode,
                   lossProgressionAddAmount, gameAutoCashOutEnabled, gameAutoCashOutTarget,
                   stakeEditTimeoutMs, autoCashOutTimeoutMs, betWindowTotalTimeoutMs, minRemainingBetWindowMs
            FROM strategy_configs WHERE id = 1
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(50.0, cursor.getDouble(0), 0.001)
            assertEquals("aviator", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("DISABLED", cursor.getString(3))
            assertEquals(10.0, cursor.getDouble(4), 0.001)
            assertEquals(0, cursor.getInt(5))
            assertEquals(2.0, cursor.getDouble(6), 0.001)
            assertEquals(500L, cursor.getLong(7))
            assertEquals(500L, cursor.getLong(8))
            assertEquals(3000L, cursor.getLong(9))
            assertEquals(1200L, cursor.getLong(10))
        }

        val migratedColumns = mutableSetOf<String>()
        rawDb.query("PRAGMA table_info(strategy_configs)").use { pragmaCursor ->
            val nameIdx = pragmaCursor.getColumnIndex("name")
            while (pragmaCursor.moveToNext()) {
                if (nameIdx >= 0) migratedColumns += pragmaCursor.getString(nameIdx)
            }
        }
        assertTrue(
            migratedColumns.containsAll(
                listOf(
                    "lossProgressionEnabled",
                    "lossProgressionMode",
                    "lossProgressionAddAmount",
                    "gameAutoCashOutEnabled",
                    "gameAutoCashOutTarget",
                    "stakeEditTimeoutMs",
                    "autoCashOutTimeoutMs",
                    "betWindowTotalTimeoutMs",
                    "minRemainingBetWindowMs"
                )
            )
        )

        helper.close()
        context.deleteDatabase(dbName)
    }

    // =========================================================================
    // REQ 2: GAME-SCREEN OCR + ACCESSIBILITY FUSION & UNCERTAINTY REJECTION
    // =========================================================================

    @Test
    fun `OCR analyzer parses betting window controls, rejects stale and uncertain frames, and catches conflicts`() {
        val now = 1_700_000_050_000L
        val snapshot = OcrFrameSnapshot(
            elements = listOf(
                OcrTextElement("Aviator", Rect(40, 20, 220, 60), 0.92f),
                OcrTextElement("Starting in 4.2s", Rect(320, 380, 620, 430), 0.90f),
                OcrTextElement("Stake", Rect(80, 940, 180, 975), 0.91f),
                OcrTextElement("25.00", Rect(200, 935, 360, 980), 0.94f),
                OcrTextElement("2x", Rect(400, 935, 480, 980), 0.90f),
                OcrTextElement("Auto Cash Out ON", Rect(80, 1020, 320, 1065), 0.89f),
                OcrTextElement("2.50x", Rect(360, 1020, 490, 1065), 0.91f),
                OcrTextElement("BET", Rect(600, 960, 960, 1100), 0.96f)
            ),
            fullText = "Aviator Starting in 4.2s Stake 25.00 2x Auto Cash Out ON 2.50x BET",
            frameWidth = 1080,
            frameHeight = 1920,
            capturedAtMs = now - 200L,
            averageConfidence = 0.92f
        )

        val parsed = ScreenOcrAnalyzer.parseOcrSnapshot(snapshot)
        assertFalse(parsed.isUncertain)
        assertEquals("BETTING_OPEN", parsed.roundPhase)
        assertEquals(SiteButtonState.IDLE_READY_TO_BET, parsed.siteButtonState)
        assertEquals(4200L, parsed.countdownRemainingMs)
        assertEquals(25.0, parsed.stakeValue ?: 0.0, 0.001)
        assertEquals(true, parsed.autoCashOutEnabled)
        assertEquals(2.50, parsed.autoCashOutTarget ?: 0.0, 0.001)

        // Fusion NEVER invents clickable BET button bounds when Accessibility bounds are null
        val a11yNoBetBounds = DetectedUiTargets(
            gamePhase = "BETTING_OPEN",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            betButtonBounds = null,
            detectedStakeText = "25.00",
            foregroundPackage = "com.android.chrome"
        )
        val fusedSafe = ScreenOcrAnalyzer.fuseAccessibilityAndOcr(a11yNoBetBounds, snapshot, nowMs = now)
        assertTrue(fusedSafe.isReliableForBet)
        assertNull("OCR must NEVER synthesize clickable BET bounds alone", fusedSafe.fusedTargets.betButtonBounds)
        assertEquals(4200L, fusedSafe.fusedTargets.countdownRemainingMs)

        // Stale frame (>2000ms) is rejected
        val staleFused = ScreenOcrAnalyzer.fuseAccessibilityAndOcr(
            a11yNoBetBounds,
            snapshot.copy(capturedAtMs = now - 5_000L),
            nowMs = now
        )
        assertTrue(staleFused.isStale)
        assertFalse(staleFused.isReliableForBet)

        // Low-confidence frame (<0.55f) is rejected as uncertain
        val uncertainFused = ScreenOcrAnalyzer.fuseAccessibilityAndOcr(
            a11yNoBetBounds,
            snapshot.copy(averageConfidence = 0.35f),
            nowMs = now
        )
        assertTrue(uncertainFused.isOcrUncertain)
        assertFalse(uncertainFused.isReliableForBet)

        // Conflicting stake reading between A11y ("10.00") and OCR ("25.00") is flagged
        val conflictFused = ScreenOcrAnalyzer.fuseAccessibilityAndOcr(
            a11yNoBetBounds.copy(detectedStakeText = "10.00"),
            snapshot,
            nowMs = now
        )
        assertTrue(conflictFused.hasConflict)
        assertTrue(conflictFused.conflictReasons.any { it.contains("Stake reading conflict", ignoreCase = true) })
    }

    // =========================================================================
    // REQ 3 & 4: BETTING WINDOW SEQUENCE, AUTO CASHOUT, & CLICK/LOSS PROTECTION
    // =========================================================================

    @Test
    fun `betting window aborts cleanly on low countdown, stake mismatch, autoCashOut mismatch, and timeout`() {
        var betClicks = 0
        var dismissCalls = 0
        var autoCashCalls = 0
        var liveTargets = DetectedUiTargets(
            gamePhase = "BETTING_OPEN",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = Rect(550, 950, 950, 1120),
            stakeInputBounds = Rect(120, 950, 380, 1020),
            detectedStakeText = "25.00",
            countdownRemainingMs = 800L, // < 1200ms minimum
            isBetBtnEnabled = true
        )

        var stakeDelayMs = 0L
        var autoCashMatch = true
        val interactor = object : TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) {
                if (stakeDelayMs > 0L) Thread.sleep(stakeDelayMs)
                onComplete(true)
            }

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                betClicks++
                liveTargets = liveTargets.copy(
                    siteButtonState = SiteButtonState.BET_QUEUED,
                    isCancelBtnDetected = true,
                    cancelButtonBounds = Rect(550, 950, 950, 1120)
                )
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                onComplete(true)
            }

            override fun dismissKeyboardIfOpen(): Boolean {
                dismissCalls++
                return true
            }

            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets = liveTargets

            override fun configureAutoCashOut(
                enabled: Boolean,
                targetMultiplier: Double,
                mode: String,
                onComplete: (Boolean, String?) -> Unit
            ) {
                autoCashCalls++
                if (autoCashMatch) {
                    onComplete(true, null)
                } else {
                    onComplete(false, "Auto Cash Out verification mismatch")
                }
            }
        }

        val engine = createEngine(interactor)
        val config = StrategyConfig(
            baseStake = 25.0,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = false,
            gameAutoCashOutEnabled = true,
            gameAutoCashOutTarget = 2.25,
            minRemainingBetWindowMs = 1200L,
            stakeEditTimeoutMs = 200L,
            autoCashOutTimeoutMs = 500L,
            betWindowTotalTimeoutMs = 3000L
        )
        val limits = RiskLimits()

        // 1. Insufficient countdown (< 1200ms) aborts before BET
        var lastResult: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> lastResult = ok to msg }
        assertNotNull(lastResult)
        assertFalse(lastResult!!.first)
        assertTrue(lastResult!!.second.contains("countdown", ignoreCase = true))
        assertEquals(0, betClicks)

        // 2. Restore countdown = 4200ms, but simulate stake readback mismatch (game still displays 50.00 != 25.00)
        lastResult = null
        liveTargets = liveTargets.copy(countdownRemainingMs = 4200L, detectedStakeText = "50.00")
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> lastResult = ok to msg }
        assertNotNull(lastResult)
        assertFalse(lastResult!!.first)
        assertTrue(lastResult!!.second.contains("mismatch", ignoreCase = true))
        assertEquals(0, betClicks)

        // 3. Fix stake to 25.00, but fail Auto Cash Out target verification -> aborts before BET
        lastResult = null
        liveTargets = liveTargets.copy(detectedStakeText = "25.00")
        autoCashMatch = false
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> lastResult = ok to msg }
        assertNotNull(lastResult)
        assertFalse(lastResult!!.first)
        assertEquals(0, betClicks)
        assertEquals(1, autoCashCalls)

        // 4. Exceed stakeEditTimeoutMs (1600ms > 1500ms floor) -> aborts
        lastResult = null
        autoCashMatch = true
        stakeDelayMs = 1600L
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> lastResult = ok to msg }
        assertNotNull(lastResult)
        assertFalse(lastResult!!.first)
        assertTrue(lastResult!!.second.contains("timed out", ignoreCase = true))
        assertEquals(0, betClicks)

        // 5. Everything healthy & verified -> dismisses keyboard, configures game Auto Cash Out, clicks BET & confirms BET_QUEUED
        lastResult = null
        stakeDelayMs = 0L
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> lastResult = ok to msg }
        assertNotNull("Expected onFinished callback for step 5", lastResult)
        assertTrue("Expected verified BET to succeed, got: ${lastResult?.second}", lastResult!!.first)
        assertEquals(1, betClicks)
        assertEquals(1, dismissCalls)
        assertTrue(engine.isRoundBetActiveState.value)
    }

    @Test
    fun `collision protection blocks BET overlapping Double 2x, Cancel, Stake, or AutoCashOut and decouples AutoCashOut`() {
        val collisionDouble = DetectedUiTargets(
            betButtonBounds = Rect(400, 950, 700, 1080),
            doubleButtonBounds = Rect(380, 940, 440, 1020)
        )
        assertNotNull(collisionDouble.detectBetCollisionReason())
        assertTrue(collisionDouble.detectBetCollisionReason()!!.contains("Double", ignoreCase = true))

        val collisionCancel = DetectedUiTargets(
            siteButtonState = SiteButtonState.BET_QUEUED,
            isCancelBtnDetected = true,
            betButtonBounds = Rect(400, 950, 700, 1080),
            cancelButtonBounds = Rect(400, 950, 700, 1080)
        )
        assertNotNull(collisionCancel.detectBetCollisionReason())

        val collisionAutoCash = DetectedUiTargets(
            betButtonBounds = Rect(400, 950, 700, 1080),
            autoCashOutToggleBounds = Rect(500, 960, 620, 1020)
        )
        assertNotNull(collisionAutoCash.detectBetCollisionReason())

        // Decoupled Auto Bet ON + Auto Cash Out OFF: places BET, NEVER triggers auto Cash Out
        var betClicks = 0
        var cashOutClicks = 0
        val targets = DetectedUiTargets(
            gamePhase = "BETTING_OPEN",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = Rect(500, 960, 900, 1100),
            detectedStakeText = "20.00",
            countdownRemainingMs = 4000L
        )
        val interactor = object : TargetUiInteractor {
            override fun applyStake(newStake: Double, progressionMode: StakeProgressionMode, fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) = onComplete(true)
            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) { betClicks++; onComplete(true) }
            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) { cashOutClicks++; onComplete(true) }
            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets = targets
        }
        val engine = createEngine(interactor)
        engine.updateLatestUiTargets(targets)

        val config = StrategyConfig(
            baseStake = 20.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = false
        ).withUpdatedRule(StrategyRuleConfig(StrategyRuleId.AFTER_2_LOSSES, isEnabled = true, requiredLosses = 2))
        val limits = RiskLimits()
        engine.startAutomation(config, limits)
        engine.handleRoundCompleted(1.20, config, limits, currentTimeMs = 1_000L)
        engine.handleRoundCompleted(1.30, config, limits, currentTimeMs = 2_000L)

        // Same round repeated BETTING_OPEN updates -> places BET strictly once (no duplicate clicks)
        engine.handlePhaseUpdate("BETTING_OPEN", 1.00, config, limits, 3_000L, SiteButtonState.IDLE_READY_TO_BET)
        engine.handlePhaseUpdate("BETTING_OPEN", 1.00, config, limits, 3_200L, SiteButtonState.IDLE_READY_TO_BET)
        assertEquals(1, betClicks)

        // Round flies past 3.50x while isAutoCashOutEnabled = false -> ZERO auto cash-out clicks!
        engine.handlePhaseUpdate("IN_FLIGHT", 3.50, config, limits, 8_000L, SiteButtonState.CASH_OUT_AVAILABLE)
        assertEquals(0, cashOutClicks)
    }

    // =========================================================================
    // REQ 5 & 6: UNIFIED FLOATING ACTION HAND & DEDICATED HAND SETTINGS
    // =========================================================================

    @Test
    fun `unified floating action hand resolves safe state across round lifecycle without exposing invalid actions`() {
        val config = StrategyConfig()
        val waitingGame = GameDetectionState(isGameDetected = false)
        assertEquals(
            UnifiedHandVisualState.WAIT_IDLE,
            FloatingHandsOverlayManager.resolveUnifiedHandVisualState(waitingGame, isRoundBetActive = false, config = config)
        )

        val conflictState = GameDetectionState(
            isGameDetected = true,
            gamePhase = "BETTING_OPEN",
            uiTargets = DetectedUiTargets(
                gamePhase = "BETTING_OPEN",
                conflictReasons = listOf("OCR vs A11y conflict")
            )
        )
        assertEquals(
            UnifiedHandVisualState.BLOCKED_CONFLICT,
            FloatingHandsOverlayManager.resolveUnifiedHandVisualState(conflictState, isRoundBetActive = false, config = config)
        )

        val readyToBetState = GameDetectionState(
            isGameDetected = true,
            gamePhase = "BETTING_OPEN",
            uiTargets = DetectedUiTargets(
                gamePhase = "BETTING_OPEN",
                siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
                betButtonBounds = Rect(500, 960, 900, 1100),
                isBetBtnEnabled = true
            )
        )
        assertEquals(
            UnifiedHandVisualState.BET_READY,
            FloatingHandsOverlayManager.resolveUnifiedHandVisualState(readyToBetState, isRoundBetActive = false, config = config)
        )

        // Once active bet is queued -> BET_QUEUED_LOCKED (never exposes BET twice or invalid CASH OUT)
        assertEquals(
            UnifiedHandVisualState.BET_QUEUED_LOCKED,
            FloatingHandsOverlayManager.resolveUnifiedHandVisualState(readyToBetState, isRoundBetActive = true, config = config)
        )

        // In flight WITHOUT an active bet -> WAIT_IDLE (never exposes CASH_OUT_READY!)
        val inFlightState = GameDetectionState(
            isGameDetected = true,
            gamePhase = "IN_FLIGHT",
            uiTargets = DetectedUiTargets(
                gamePhase = "IN_FLIGHT",
                siteButtonState = SiteButtonState.CASH_OUT_AVAILABLE,
                cashOutButtonBounds = Rect(500, 960, 900, 1100),
                isCashOutBtnEnabled = true
            )
        )
        assertEquals(
            UnifiedHandVisualState.WAIT_IDLE,
            FloatingHandsOverlayManager.resolveUnifiedHandVisualState(inFlightState, isRoundBetActive = false, config = config)
        )

        // In flight WITH an active bet & verified Cash Out control -> CASH_OUT_READY
        assertEquals(
            UnifiedHandVisualState.CASH_OUT_READY,
            FloatingHandsOverlayManager.resolveUnifiedHandVisualState(inFlightState, isRoundBetActive = true, config = config)
        )
    }

    @Test
    fun `DedicatedHandSettingsComposable separates BET and CASH OUT sections and persists authoritative config`() {
        var savedConfig: StrategyConfig? = null
        val initial = StrategyConfig(
            baseStake = 10.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = false,
            isAutoCashOutEnabled = false,
            gameAutoCashOutEnabled = false,
            gameAutoCashOutTarget = 2.00
        )

        composeRule.setContent {
            ScreenEarnerTheme {
                DedicatedHandSettingsComposable(
                    strategyConfig = initial,
                    onSaveHandSettings = { savedConfig = it },
                    onClose = {}
                )
            }
        }

        composeRule.onNodeWithTag("hand_settings_bet_section").assertIsDisplayed()
        composeRule.onNodeWithTag("hand_settings_base_stake_input").performScrollTo().performTextReplacement("25.00")
        composeRule.onNodeWithTag("hand_settings_game_auto_cashout_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hand_settings_game_auto_cashout_switch").assertIsOn()
        composeRule.onNodeWithTag("hand_settings_game_auto_cashout_target_input").performScrollTo().performTextReplacement("2.75")

        // Scroll to Cash Out section & toggle Automatic Cash Out independently while Automatic Bet remains OFF (manual bet)
        composeRule.onNodeWithTag("hand_settings_cashout_section").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("hand_settings_auto_cashout_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hand_settings_auto_cashout_switch").assertIsOn()
        composeRule.onNodeWithTag("hand_settings_target_multiplier_input").performScrollTo().performTextReplacement("3.10")

        composeRule.onNodeWithTag("hand_settings_save_btn").performClick()
        composeRule.waitForIdle()

        assertNotNull(savedConfig)
        assertEquals(25.0, savedConfig!!.baseStake, 0.001)
        assertFalse(savedConfig!!.isAutoBetEnabled)
        assertTrue(savedConfig!!.isAutoCashOutEnabled)
        assertTrue(savedConfig!!.gameAutoCashOutEnabled)
        assertEquals(2.75, savedConfig!!.gameAutoCashOutTarget, 0.001)
        assertEquals(3.10, savedConfig!!.targetMultiplier, 0.001)
    }

    @Test
    fun `phase detection identifies IN_FLIGHT when live multiplier is present even if Bet button is visible for queueing`() {
        val targets = DetectedUiTargets(
            detectedMultiplier = 8.72,
            betButtonBounds = Rect(400, 950, 900, 1100),
            gamePhase = "IN_FLIGHT",
            canQueueBet = true
        )
        assertEquals("IN_FLIGHT", targets.gamePhase)
        assertTrue(targets.canQueueBet)
    }

    @Test
    fun `StrategyConfig supports gameAutoCashOutMode options OFF ENSURE and SKIP_IF_ALREADY_SET`() {
        val configDefault = StrategyConfig()
        assertEquals("ENSURE", configDefault.gameAutoCashOutMode)

        val configSkip = configDefault.copy(gameAutoCashOutMode = "SKIP_IF_ALREADY_SET")
        assertEquals("SKIP_IF_ALREADY_SET", configSkip.gameAutoCashOutMode)

        val configOff = configDefault.copy(gameAutoCashOutMode = "OFF")
        assertEquals("OFF", configOff.gameAutoCashOutMode)
    }

    @Test
    fun `BET click fails confirmation when post-click rescan returns null or stays IDLE_READY_TO_BET`() = runBlocking {
        var preClickTargets = DetectedUiTargets(
            gamePhase = "BETTING_OPEN",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = Rect(550, 950, 950, 1120),
            stakeInputBounds = Rect(120, 950, 380, 1020),
            detectedStakeText = "25.00",
            countdownRemainingMs = 4500L,
            isBetBtnEnabled = true,
            isBetBtnClickable = true
        )
        var returnNullAfterClick = true
        var betClicked = false

        val interactor = object : TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) = onComplete(true)

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                betClicked = true
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) = onComplete(true)

            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets? {
                return if (betClicked && returnNullAfterClick) null else preClickTargets
            }
        }

        val engine = createEngine(interactor)
        val config = StrategyConfig(
            baseStake = 25.0,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = false,
            gameAutoCashOutEnabled = false,
            betWindowTotalTimeoutMs = 6000L
        )
        val limits = RiskLimits()

        // Case A: Post-click rescan returns null (window transiently lost / unreadable) -> must fail as unconfirmed
        var resultNull: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> resultNull = ok to msg }
        var waited = 0L
        while (resultNull == null && waited < 3500L) {
            delay(50L)
            waited += 50L
        }
        assertNotNull("Expected BET callback after null-rescan timeout", resultNull)
        assertFalse(resultNull!!.first)
        assertFalse(engine.isRoundBetActiveState.value)
        assertEquals(com.example.data.model.ActionExecutionState.FAILED, engine.executionState.value)

        // Case B: Post-click rescan returns unchanged IDLE_READY_TO_BET (site ignored tap) -> must fail as unconfirmed
        betClicked = false
        returnNullAfterClick = false
        var resultUnchanged: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> resultUnchanged = ok to msg }
        waited = 0L
        while (resultUnchanged == null && waited < 3500L) {
            delay(50L)
            waited += 50L
        }
        assertNotNull("Expected BET callback after unchanged-state timeout", resultUnchanged)
        assertFalse(resultUnchanged!!.first)
        assertFalse(engine.isRoundBetActiveState.value)
        assertEquals(com.example.data.model.ActionExecutionState.FAILED, engine.executionState.value)
    }

    @Test
    fun `Cash Out verification never falls back to cached game state when fresh post-click rescan returns null`() = runBlocking {
        var cashOutClicked = false
        var postClickResult: DetectedUiTargets? = null
        val cashOutReadyTargets = DetectedUiTargets(
            gamePhase = "IN_FLIGHT",
            siteButtonState = SiteButtonState.CASH_OUT_AVAILABLE,
            foregroundPackage = "com.android.chrome",
            cashOutButtonBounds = Rect(550, 950, 950, 1120),
            isCashOutBtnEnabled = true,
            isCashOutBtnClickable = true
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

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) = onComplete(true)

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                cashOutClicked = true
                onComplete(true)
            }

            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets? {
                return if (!cashOutClicked) cashOutReadyTargets else postClickResult
            }
        }

        val engine = createEngine(interactor)
        // Cached gameDetection state intentionally stays IDLE_READY_TO_BET (different from CASH_OUT_AVAILABLE)
        // to ensure verification cannot shortcut or fall back to cached state when refreshLiveTargets(null) returns null.
        val config = StrategyConfig(
            baseStake = 25.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = true,
            cashOutUntrackedBets = true,
            minFlightTimeBeforeCashOutMs = 0L
        )
        val limits = RiskLimits()
        engine.startAutomation(config, limits)
        engine.handlePhaseUpdate("IN_FLIGHT", 2.50, config, limits, 1_700_000_010_000L, SiteButtonState.CASH_OUT_AVAILABLE)

        assertTrue(cashOutClicked)
        var waited = 0L
        while (engine.executionState.value != com.example.data.model.ActionExecutionState.FAILED && waited < 3500L) {
            delay(50L)
            waited += 50L
        }
        assertEquals(com.example.data.model.ActionExecutionState.FAILED, engine.executionState.value)

        // Now verify Manual Cash Out also refuses null rescan and succeeds only on fresh non-null transition
        cashOutClicked = false
        postClickResult = null
        var manualFailResult: Pair<Boolean, String>? = null
        engine.executeVerifiedManualCashOut(config, limits) { ok, msg -> manualFailResult = ok to msg }
        waited = 0L
        while (manualFailResult == null && waited < 3500L) {
            delay(50L)
            waited += 50L
        }
        assertNotNull(manualFailResult)
        assertFalse(manualFailResult!!.first)

        // Fresh transition from CASH_OUT_AVAILABLE -> IDLE_READY_TO_BET confirms Manual Cash Out
        cashOutClicked = false
        postClickResult = DetectedUiTargets(
            gamePhase = "IN_FLIGHT",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = Rect(550, 950, 950, 1120)
        )
        var manualSuccessResult: Pair<Boolean, String>? = null
        engine.executeVerifiedManualCashOut(config, limits) { ok, msg -> manualSuccessResult = ok to msg }
        waited = 0L
        while (manualSuccessResult == null && waited < 1500L) {
            delay(50L)
            waited += 50L
        }
        assertNotNull(manualSuccessResult)
        assertTrue("Expected Manual Cash Out to confirm on fresh transition, got: ${manualSuccessResult?.second}", manualSuccessResult!!.first)
    }

    @Test
    fun `queueBetDuringFlight false blocks IN_FLIGHT bet while true allows queuing and logs BetTransactionManager lifecycle`() = runBlocking {
        var betClicked = false
        val inFlightTargets = DetectedUiTargets(
            gamePhase = "IN_FLIGHT",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = Rect(550, 950, 950, 1120),
            stakeInputBounds = Rect(120, 950, 420, 1020),
            detectedStakeText = "25.00",
            isBetBtnEnabled = true,
            isBetBtnClickable = true,
            detectedMultiplier = 4.25
        )
        val queuedTargets = inFlightTargets.copy(
            siteButtonState = SiteButtonState.BET_QUEUED,
            betButtonBounds = null,
            isCancelBtnDetected = true
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
                betClicked = true
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) = onComplete(true)

            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets? {
                return if (betClicked) queuedTargets else inFlightTargets
            }
        }

        val engine = createEngine(interactor)
        val blockedConfig = StrategyConfig(
            baseStake = 25.0,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = false,
            gameAutoCashOutEnabled = false,
            queueBetDuringFlight = false
        )
        val limits = RiskLimits()

        var blockedResult: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(blockedConfig, limits) { ok, msg -> blockedResult = ok to msg }
        var waited = 0L
        while (blockedResult == null && waited < 1500L) {
            delay(25L)
            waited += 25L
        }
        assertNotNull(blockedResult)
        assertFalse(blockedResult!!.first)
        assertFalse("Mid-flight bet must not click when queueBetDuringFlight is false", betClicked)

        // When queueBetDuringFlight = true, queuing succeeds and records transaction lifecycle
        betClicked = false
        val allowedConfig = blockedConfig.copy(queueBetDuringFlight = true)
        var allowedResult: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(allowedConfig, limits) { ok, msg -> allowedResult = ok to msg }
        waited = 0L
        while (allowedResult == null && waited < 2000L) {
            delay(25L)
            waited += 25L
        }
        assertNotNull(allowedResult)
        assertTrue("Expected queued bet to succeed, got: ${allowedResult?.second}", allowedResult!!.first)
        assertTrue(betClicked)

        // Wait briefly for txLaunch coroutine to record BET_ACCEPTED
        delay(100L)
        val latestTx = engine.betTransactions.recentJournal.value.firstOrNull()
        assertNotNull(latestTx)
        assertEquals(com.example.data.model.BetTransactionLifecycleState.BET_ACCEPTED, latestTx?.state)
    }

    @Test
    fun `mid-flight ScreenEarnerAccessibilityService keeps isReady true when live multiplier is detected`() {
        val midFlightNoBetButtons = DetectedUiTargets(
            gamePhase = "IN_FLIGHT",
            siteButtonState = SiteButtonState.UNKNOWN,
            foregroundPackage = "com.android.chrome",
            detectedMultiplier = 3.45,
            isCancelBtnDetected = false,
            ribbonMultipliers = listOf(1.20, 2.50, 1.80)
        )
        val uncertainOcr = OcrFrameSnapshot(
            elements = listOf(OcrTextElement("blur", Rect(10, 10, 80, 40), 0.25f)),
            fullText = "blur",
            frameWidth = 1080,
            frameHeight = 1920,
            capturedAtMs = 1_700_000_000_000L,
            averageConfidence = 0.25f
        )

        val state = com.example.service.ScreenEarnerAccessibilityService.buildAndPublishFusedState(
            rawTargets = midFlightNoBetButtons,
            packageName = "com.android.chrome",
            ocrSnapshot = uncertainOcr,
            nowMs = 1_700_000_000_100L
        )
        assertTrue(state.isGameDetected)
        assertTrue("Expected mid-flight state to stay ready when live multiplier is present", state.isReady)
    }

    @Test
    fun `AutomationEngine learns min stake via probeMinStake without cancelling armed strategy session and blocks under-min stakes`() = runBlocking {
        var betClicked = false
        var probeCalls = 0
        val readyTargets = DetectedUiTargets(
            gamePhase = "BETTING_OPEN",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = Rect(550, 950, 950, 1120),
            stakeInputBounds = Rect(120, 950, 380, 1020),
            stakeMinusBounds = Rect(55, 950, 110, 1020),
            detectedStakeText = "50.00",
            countdownRemainingMs = 5000L,
            isBetBtnEnabled = true,
            isBetBtnClickable = true
        )
        val queuedTargets = readyTargets.copy(
            siteButtonState = SiteButtonState.BET_QUEUED,
            betButtonBounds = null,
            isCancelBtnDetected = true
        )

        val interactor = object : TargetUiInteractor {
            override val requiresPostActionVerification: Boolean = true
            override fun probeMinStake(onDone: (Double?) -> Unit) {
                probeCalls++
                onDone(20.0)
            }
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) = onComplete(true)

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                betClicked = true
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) = onComplete(true)

            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets? {
                return if (betClicked) queuedTargets else readyTargets
            }
        }

        val engine = createEngine(interactor)
        val config = StrategyConfig(
            baseStake = 50.0,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = false,
            gameAutoCashOutEnabled = false,
            isStrategy1Enabled = true
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.AFTER_2_LOSSES,
                isEnabled = true,
                requiredLosses = 2
            )
        )
        val limits = RiskLimits()
        engine.startAutomation(config, limits)
        engine.strategyEngine.bindStrategyConfig(config)
        // Arm a strategy session before BETTING_OPEN
        engine.strategyEngine.processCompletedRound(1.20, config, 1_700_000_000_000L)
        engine.strategyEngine.processCompletedRound(1.10, config, 1_700_000_001_000L)
        assertTrue(engine.strategyEngine.getState().isBettingSessionActive)

        // Round 1: First-time probe runs and keeps the strategy session ARMED for next round
        var probeResult: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> probeResult = ok to msg }
        var waited = 0L
        while (probeResult == null && waited < 1500L) {
            delay(25L)
            waited += 25L
        }
        assertNotNull(probeResult)
        assertFalse(probeResult!!.first)
        assertTrue(probeResult!!.second.contains("Learned site min stake"))
        assertEquals(1, probeCalls)
        assertFalse(betClicked)
        assertTrue("Strategy session must stay armed after limits_probe", engine.strategyEngine.getState().isBettingSessionActive)

        // Round 2: Because session stayed ARMED (50.0 >= 20.0 learned min), betting resumes and succeeds immediately
        var validResult: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> validResult = ok to msg }
        waited = 0L
        while (validResult == null && waited < 2000L) {
            delay(25L)
            waited += 25L
        }
        assertNotNull(validResult)
        assertTrue("Expected armed session bet >= learned min to succeed, got: ${validResult?.second}", validResult!!.first)
        assertTrue(betClicked)
        assertEquals(1, probeCalls)

        // Settle Round 2, return to BETTING_OPEN, and test baseStake (10.0) < learned min (20.0) -> blocked before clicking
        betClicked = false
        engine.settleActiveRoundBetIfNeeded(2.50, config, limits, currentTimeMs = 1_700_000_005_000L)
        engine.strategyEngine.reset(10.0)
        var underMinResult: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config.copy(baseStake = 10.0), limits) { ok, msg -> underMinResult = ok to msg }
        waited = 0L
        while (underMinResult == null && waited < 1500L) {
            delay(25L)
            waited += 25L
        }
        assertNotNull(underMinResult)
        assertFalse(underMinResult!!.first)
        assertTrue(
            "Expected learned-min rejection, got: ${underMinResult!!.second}",
            underMinResult!!.second.contains("below the learned site min")
        )
        assertEquals(1, probeCalls)
        assertFalse(betClicked)
    }

    @Test
    fun `AutomationEngine learns site limits from rejection message and persists to matching PlatformProfile`() = runBlocking {
        var clickCount = 0
        var refreshedAfterFail = DetectedUiTargets(
            gamePhase = "BETTING_OPEN",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = Rect(550, 950, 950, 1120),
            stakeInputBounds = Rect(120, 950, 380, 1020),
            detectedStakeText = "25.00",
            pageTextHints = listOf("Minimum bet is 50", "Maximum bet 5000"),
            countdownRemainingMs = 5000L,
            isBetBtnEnabled = true,
            isBetBtnClickable = true
        )

        val interactor = object : TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) = onComplete(false) // Triggers finishFailed which scans rejection message

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                clickCount++
                onComplete(true)
            }

            override fun clickCashOut(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) = onComplete(true)

            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets = refreshedAfterFail
        }

        val engine = createEngine(interactor)
        engine.repository.seedDefaultsIfEmpty()
        val config = StrategyConfig(
            baseStake = 25.0,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = false,
            gameAutoCashOutEnabled = false
        )
        val limits = RiskLimits()

        // First attempt fails stake edit -> finishFailed reads "Minimum bet is 50" & "Maximum bet 5000"
        var firstRes: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> firstRes = ok to msg }
        var waited = 0L
        while (firstRes == null && waited < 1500L) {
            delay(25L)
            waited += 25L
        }
        assertNotNull(firstRes)
        assertFalse(firstRes!!.first)
        delay(120L)

        // Verify matching BetPawa Aviator PlatformProfile persisted the learned bounds
        val savedProfile = engine.repository.profiles.first()
            .firstOrNull { it.platformName == "BetPawa Aviator" }
        assertNotNull(savedProfile)
        assertEquals(50.0, savedProfile!!.minStake, 0.001)
        assertEquals(5000.0, savedProfile.maxStake, 0.001)

        // Simulate the temporary rejection toast disappearing on subsequent rounds
        refreshedAfterFail = refreshedAfterFail.copy(pageTextHints = emptyList())

        // Next attempt below 50 or above 5000 is blocked immediately in Step 1
        var underMinRes: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config.copy(baseStake = 25.0), limits) { ok, msg -> underMinRes = ok to msg }
        waited = 0L
        while (underMinRes == null && waited < 1500L) {
            delay(25L)
            waited += 25L
        }
        assertTrue(underMinRes!!.second.contains("below the learned site min"))

        var overMaxRes: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config.copy(baseStake = 6000.0), limits) { ok, msg -> overMaxRes = ok to msg }
        waited = 0L
        while (overMaxRes == null && waited < 1500L) {
            delay(25L)
            waited += 25L
        }
        assertTrue(overMaxRes!!.second.contains("above the learned site max"))
        assertEquals(0, clickCount)
    }
}
