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
        testEngineScope?.cancel()
        testEngineScope = null
        runBlocking { delay(60L) }
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
    fun `betting window allows short countdown, blocks excluded screen, aborts on stake mismatch and timeout`() {
        var betClicks = 0
        var dismissCalls = 0
        var liveTargets = DetectedUiTargets(
            gamePhase = "BETTING_OPEN",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = Rect(550, 950, 950, 1120),
            stakeInputBounds = Rect(120, 950, 380, 1020),
            detectedStakeText = "25.00",
            countdownRemainingMs = 800L, // Prompt 3: countdown prediction must NOT block BET
            isBetBtnEnabled = true
        )

        var stakeDelayMs = 0L
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

            override fun dismissKeyboardIfOpen(): Boolean {
                dismissCalls++
                return true
            }

            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets = liveTargets
        }

        val engine = createEngine(interactor)
        val config = StrategyConfig(
            baseStake = 25.0,
            isAutoBetEnabled = true,
            isAutoCashOutEnabled = false,
            minRemainingBetWindowMs = 1200L,
            stakeEditTimeoutMs = 200L,
            betWindowTotalTimeoutMs = 3000L
        )
        val limits = RiskLimits()

        // 1. Excluded foreground package (Samsung Edge panel) aborts before BET
        var lastResult: Pair<Boolean, String>? = null
        liveTargets = liveTargets.copy(foregroundPackage = "com.samsung.android.app.cocktailbarservice")
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> lastResult = ok to msg }
        assertNotNull(lastResult)
        assertFalse(lastResult!!.first)
        assertTrue(
            lastResult!!.second.contains("non-game", ignoreCase = true) ||
                lastResult!!.second.contains("overlay/panel", ignoreCase = true)
        )
        assertEquals(0, betClicks)

        // 2. Restore Chrome foreground, simulate stake readback mismatch (game displays 50.00 != 25.00) -> aborts
        lastResult = null
        liveTargets = liveTargets.copy(
            foregroundPackage = "com.android.chrome",
            countdownRemainingMs = 4200L,
            detectedStakeText = "50.00"
        )
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> lastResult = ok to msg }
        assertNotNull(lastResult)
        assertFalse(lastResult!!.first)
        assertTrue(lastResult!!.second.contains("mismatch", ignoreCase = true))
        assertEquals(0, betClicks)

        // 3. Fix stake to 25.00, exceed stakeEditTimeoutMs (260ms > 200ms) -> aborts
        lastResult = null
        liveTargets = liveTargets.copy(detectedStakeText = "25.00")
        stakeDelayMs = 260L
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> lastResult = ok to msg }
        assertNotNull(lastResult)
        assertFalse(lastResult!!.first)
        assertTrue(lastResult!!.second.contains("timed out", ignoreCase = true))
        assertEquals(0, betClicks)

        // 4. Short countdown (800ms) + valid stake + live BET bounds -> still succeeds cleanly without countdown gate!
        lastResult = null
        stakeDelayMs = 0L
        liveTargets = liveTargets.copy(countdownRemainingMs = 800L)
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> lastResult = ok to msg }
        assertNotNull("Expected onFinished callback for step 4", lastResult)
        assertTrue("Expected verified BET to succeed even with short countdown, got: ${lastResult?.second}", lastResult!!.first)
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

        // Auto Bet places BET strictly once and leaves Cash Out entirely to the game
        var betClicks = 0
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

        // Round flies past 3.50x -> settles as WIN once CRASHED arrives, no extra BET clicks
        engine.handlePhaseUpdate("IN_FLIGHT", 3.50, config, limits, 8_000L, SiteButtonState.CASH_OUT_AVAILABLE)
        assertEquals(1, betClicks)
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

        // In flight WITHOUT an active bet -> WAIT_IDLE
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

        // In flight WITH an active bet -> BET_QUEUED_LOCKED (BET-only floating hand never shows Cash Out)
        assertEquals(
            UnifiedHandVisualState.BET_QUEUED_LOCKED,
            FloatingHandsOverlayManager.resolveUnifiedHandVisualState(inFlightState, isRoundBetActive = true, config = config)
        )
    }

    @Test
    fun `DedicatedHandSettingsComposable shows BET-only settings without Cash Out controls and persists authoritative config`() {
        var savedConfig: StrategyConfig? = null
        val initial = StrategyConfig(
            baseStake = 10.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = false,
            isAutoCashOutEnabled = false
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
        composeRule.onNodeWithTag("hand_settings_cashout_section").assertDoesNotExist()
        composeRule.onNodeWithTag("hand_settings_auto_cashout_switch").assertDoesNotExist()
        composeRule.onNodeWithTag("hand_settings_base_stake_input").performScrollTo().performTextReplacement("25.00")
        composeRule.onNodeWithTag("hand_settings_target_multiplier_input").performScrollTo().performTextReplacement("3.10")

        composeRule.onNodeWithTag("hand_settings_save_btn").performClick()
        composeRule.waitForIdle()

        assertNotNull(savedConfig)
        assertEquals(25.0, savedConfig!!.baseStake, 0.001)
        assertFalse(savedConfig!!.isAutoBetEnabled)
        assertEquals(3.10, savedConfig!!.targetMultiplier, 0.001)
    }
}
