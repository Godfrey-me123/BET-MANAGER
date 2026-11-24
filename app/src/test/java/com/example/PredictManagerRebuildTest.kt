package com.example

import android.content.Context
import android.graphics.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ScreenEarnerDatabase
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationEngine
import com.example.data.engine.AutomationState
import com.example.data.engine.GameDetection
import com.example.data.engine.RiskManager
import com.example.data.engine.StrategyEngine
import com.example.data.engine.TargetUiInteractor
import com.example.data.model.FloatingShortcut
import com.example.data.model.GameState
import com.example.data.model.MonitoringStatus
import com.example.data.model.NotificationSettings
import com.example.data.model.RiskLimits
import com.example.data.model.ShortcutType
import com.example.data.model.SiteButtonState
import com.example.data.model.StakeProgressionMode
import com.example.data.model.StrategyConfig
import com.example.data.model.StrategyRuleConfig
import com.example.data.model.StrategyRuleId
import com.example.service.AccessibilityController
import com.example.service.AutomationController
import com.example.service.DetectedUiTargets
import com.example.service.GameDetectionState
import com.example.service.OverlaySettingsPanelComposable
import com.example.service.SessionStateManager
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.OverlayManagerScreen
import com.example.ui.theme.ScreenEarnerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PredictManagerRebuildTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var db: ScreenEarnerDatabase? = null

    @After
    fun tearDown() {
        AutomationController.cancelTestBackgroundLoops()
        AutomationController.resetTestState()
        AutomationController.engine = null
        AutomationController.repository = null
        db?.close()
        db = null
    }

    @Test
    fun `isExcludedForegroundPackage blocks Edge panel SystemUI launcher settings and IME while allowing Chrome`() {
        assertTrue(AccessibilityController.isExcludedForegroundPackage("com.samsung.android.app.cocktailbarservice"))
        assertTrue(AccessibilityController.isExcludedForegroundPackage("com.samsung.android.app.appsedge"))
        assertTrue(AccessibilityController.isExcludedForegroundPackage("com.android.systemui"))
        assertTrue(AccessibilityController.isExcludedForegroundPackage("com.sec.android.app.launcher"))
        assertTrue(AccessibilityController.isExcludedForegroundPackage("com.android.settings"))
        assertTrue(AccessibilityController.isExcludedForegroundPackage("com.google.android.inputmethod.latin"))

        assertFalse(AccessibilityController.isExcludedForegroundPackage("com.android.chrome"))
        assertFalse(AccessibilityController.isExcludedForegroundPackage("org.mozilla.firefox"))
    }

    @Test
    fun `automation taps moved live betButtonBounds ignoring stale shortcut coords and aborts on Edge panel`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SessionStateManager(context).clearSessionRuntimeState()
        val database = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = database
        val repo = ScreenEarnerRepository(database.dao())

        val clickedCoords = mutableListOf<Pair<Float?, Float?>>()
        var currentTargets = DetectedUiTargets(
            gamePhase = "BETTING_OPEN",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            foregroundPackage = "com.android.chrome",
            betButtonBounds = Rect(700, 1400, 900, 1600), // exact center (800, 1500)
            stakeInputBounds = Rect(120, 1400, 420, 1480),
            detectedStakeText = "20.00",
            isBetBtnEnabled = true
        )

        val interactor = object : TargetUiInteractor {
            override fun applyStake(
                newStake: Double,
                progressionMode: StakeProgressionMode,
                fallbackX: Float?,
                fallbackY: Float?,
                onComplete: (Boolean) -> Unit
            ) = onComplete(true)

            override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) {
                clickedCoords.add(fallbackX to fallbackY)
                currentTargets = currentTargets.copy(
                    siteButtonState = SiteButtonState.BET_QUEUED,
                    isCancelBtnDetected = true,
                    cancelButtonBounds = currentTargets.betButtonBounds
                )
                onComplete(true)
            }

            override fun refreshLiveTargets(fallbackTargets: DetectedUiTargets?): DetectedUiTargets = currentTargets
        }

        val engine = AutomationEngine(
            repository = repo,
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = GameDetection(),
            targetUiInteractor = interactor
        )
        // Deliberately configure stale shortcut coordinates far away from live betButtonBounds
        engine.updateCalibratedShortcuts(
            listOf(
                FloatingShortcut(
                    id = 1,
                    label = "BET HAND",
                    type = ShortcutType.BET,
                    targetX = 111f,
                    targetY = 222f,
                    isCalibrated = true
                )
            )
        )
        engine.updateLatestUiTargets(currentTargets)

        val config = StrategyConfig(baseStake = 20.0, isAutoBetEnabled = true)
        val limits = RiskLimits()

        var betResult: Pair<Boolean, String>? = null
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> betResult = ok to msg }
        assertNotNull(betResult)
        assertTrue("Expected BET to succeed, got ${betResult?.second}", betResult!!.first)
        assertEquals(1, clickedCoords.size)
        // Must click live betButtonBounds center (800f, 1500f), NOT stale shortcut (111f, 222f)
        assertEquals(800f, clickedCoords.first().first!!, 0.5f)
        assertEquals(1500f, clickedCoords.first().second!!, 0.5f)

        // Now move betButtonBounds and settle previous round -> next BET must track the moved button center (650, 1250)
        engine.handleRoundCompleted(2.50, config, limits, currentTimeMs = 10_000L)
        currentTargets = currentTargets.copy(
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            isCancelBtnDetected = false,
            cancelButtonBounds = null,
            betButtonBounds = Rect(550, 1150, 750, 1350) // center (650, 1250)
        )
        engine.updateLatestUiTargets(currentTargets)
        betResult = null
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> betResult = ok to msg }
        assertTrue(betResult!!.first)
        assertEquals(2, clickedCoords.size)
        assertEquals(650f, clickedCoords[1].first!!, 0.5f)
        assertEquals(1250f, clickedCoords[1].second!!, 0.5f)

        // Switch foreground to Samsung Edge Panel -> next BET must abort without clicking
        engine.handleRoundCompleted(2.50, config, limits, currentTimeMs = 20_000L)
        currentTargets = currentTargets.copy(
            foregroundPackage = "com.samsung.android.app.appsedge",
            siteButtonState = SiteButtonState.IDLE_READY_TO_BET,
            isCancelBtnDetected = false
        )
        engine.updateLatestUiTargets(currentTargets)
        betResult = null
        engine.executeVerifiedManualBet(config, limits) { ok, msg -> betResult = ok to msg }
        assertFalse(betResult!!.first)
        assertEquals(2, clickedCoords.size)
        Thread.sleep(200L)
    }

    @Test
    fun `martingale respects maxStake cap and persists progression across restart until resetStrategyProgression`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionMgr = SessionStateManager(context)
        sessionMgr.clearSessionRuntimeState()

        val database = Room.inMemoryDatabaseBuilder(context, ScreenEarnerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = database
        val repo = ScreenEarnerRepository(database.dao())

        val engine1 = AutomationEngine(
            repository = repo,
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = GameDetection(),
            targetUiInteractor = object : TargetUiInteractor {
                override fun applyStake(
                    newStake: Double,
                    progressionMode: StakeProgressionMode,
                    fallbackX: Float?,
                    fallbackY: Float?,
                    onComplete: (Boolean) -> Unit
                ) = onComplete(true)

                override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) =
                    onComplete(true)
            }
        )
        engine1.onProgressionStateChanged = { snapshot ->
            sessionMgr.saveStrategyProgression(snapshot)
        }

        val config = StrategyConfig(
            isStrategy1Enabled = false,
            isStrategy2Enabled = false,
            isHighMultiplierLossRuleEnabled = false,
            lossProgressionEnabled = true,
            lossProgressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
            baseStake = 10.0,
            maxStake = 35.0,
            targetMultiplier = 2.00,
            isAutoBetEnabled = true
        ).withUpdatedRule(
            StrategyRuleConfig(
                ruleId = StrategyRuleId.AFTER_2_LOSSES,
                isEnabled = true,
                progressionMode = StakeProgressionMode.DOUBLE_AMOUNT,
                requiredLosses = 2,
                targetWinsToFetch = 1
            )
        )
        val limits = RiskLimits(maxSingleStake = 100.0, maxSessionLoss = 500.0, stopOnLossEnabled = false)
        engine1.startAutomation(config, limits)

        // Two consecutive low crashes activate AFTER_2_LOSSES
        engine1.handleRoundCompleted(1.20, config, limits, currentTimeMs = 1_000L)
        engine1.handleRoundCompleted(1.30, config, limits, currentTimeMs = 2_000L)
        assertTrue(engine1.strategyEngine.getState().isBettingSessionActive)
        assertEquals(10.0, engine1.strategyEngine.getState().currentCalculatedStake, 0.001)

        // Loss 1 (10 -> 20), Loss 2 (20 -> 40 clamped by maxStake 35.0)
        engine1.handleRoundCompleted(1.40, config, limits, currentTimeMs = 3_000L)
        assertEquals(20.0, engine1.strategyEngine.getState().currentCalculatedStake, 0.001)
        engine1.handleRoundCompleted(1.10, config, limits, currentTimeMs = 4_000L)
        assertEquals(35.0, engine1.strategyEngine.getState().currentCalculatedStake, 0.001)
        assertEquals(3, engine1.strategyEngine.getState().currentStakeLevel)

        // Simulate app restart with fresh AutomationEngine loading saved StrategyProgressionSnapshot
        val engine2 = AutomationEngine(
            repository = repo,
            strategyEngine = StrategyEngine(),
            riskManager = RiskManager(),
            gameDetection = GameDetection(),
            targetUiInteractor = object : TargetUiInteractor {
                override fun applyStake(
                    newStake: Double,
                    progressionMode: StakeProgressionMode,
                    fallbackX: Float?,
                    fallbackY: Float?,
                    onComplete: (Boolean) -> Unit
                ) = onComplete(true)

                override fun clickBet(fallbackX: Float?, fallbackY: Float?, onComplete: (Boolean) -> Unit) =
                    onComplete(true)
            }
        )
        val restored = sessionMgr.loadStrategyProgression()
        assertNotNull(restored)
        engine2.strategyEngine.restoreProgressionSnapshot(restored!!, config)
        engine2.startAutomation(config, limits, resetProgression = false)

        assertTrue(engine2.strategyEngine.getState().isBettingSessionActive)
        assertEquals(35.0, engine2.strategyEngine.getState().currentCalculatedStake, 0.001)
        assertEquals(3, engine2.strategyEngine.getState().currentStakeLevel)

        // Explicit user reset returns stake to baseStake and clears active progression
        engine2.resetStrategyProgression()
        assertFalse(engine2.strategyEngine.getState().isBettingSessionActive)
        assertEquals(10.0, engine2.strategyEngine.getState().currentCalculatedStake, 0.001)
        assertEquals(1, engine2.strategyEngine.getState().currentStakeLevel)
        delay(200L)
    }

    @Test
    fun `HomeScreen exposes BET-only hand controls and omits auto cash out switch`() {
        val betShortcut = FloatingShortcut(
            id = 1,
            label = "BET HAND",
            type = ShortcutType.BET,
            targetX = 500f,
            targetY = 1200f,
            isCalibrated = true
        )

        composeRule.setContent {
            ScreenEarnerTheme {
                HomeScreen(
                    gameState = GameState(monitoringStatus = MonitoringStatus.MONITORING),
                    automationState = AutomationState.RUNNING,
                    isOverlayServiceRunning = true,
                    strategyConfig = StrategyConfig(baseStake = 15.0, targetMultiplier = 2.00),
                    onToggleOverlayService = {},
                    onStartMonitoring = {},
                    onPauseMonitoring = {},
                    onNavigateToTab = {},
                    betShortcut = betShortcut
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("unified_action_hand_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("home_auto_bet_switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_action_hand_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("home_auto_cashout_switch").assertDoesNotExist()
    }

    @Test
    fun `OverlaySettingsPanel exposes BET controls and omits auto cash out switch`() {
        composeRule.setContent {
            ScreenEarnerTheme {
                OverlaySettingsPanelComposable(
                    strategyConfig = StrategyConfig(baseStake = 15.0, targetMultiplier = 2.00),
                    notificationSettings = NotificationSettings()
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("overlay_settings_auto_bet_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_settings_target_multiplier_input").assertIsDisplayed()
        composeRule.onNodeWithTag("overlay_settings_auto_cash_out_switch").assertDoesNotExist()
    }

    @Test
    fun `OverlayManagerScreen filters out Cash Out shortcuts and omits add_cashout_btn`() {
        val betShortcut = FloatingShortcut(
            id = 1,
            label = "BET HAND",
            type = ShortcutType.BET,
            targetX = 500f,
            targetY = 1200f,
            isCalibrated = true
        )
        val legacyCashShortcut = FloatingShortcut(
            id = 2,
            label = "LEGACY CASH OUT",
            type = ShortcutType.CASH_OUT,
            targetX = 720f,
            targetY = 1200f,
            isCalibrated = true
        )

        composeRule.setContent {
            ScreenEarnerTheme {
                OverlayManagerScreen(
                    shortcuts = listOf(betShortcut, legacyCashShortcut),
                    gameState = GameState(),
                    isEditMode = false,
                    isPanelExpanded = false,
                    isCalibrating = false,
                    calibratingShortcutId = null,
                    onSetEditMode = {},
                    onTogglePanel = {},
                    onStartAutomation = {},
                    onPauseAutomation = {},
                    onResumeAutomation = {},
                    onQuitAutomation = {},
                    onExitUtility = {},
                    onOpenSettings = {},
                    onAddBetShortcut = {},
                    onUpdateShortcutPos = { _, _, _ -> },
                    onSetCalibratedTarget = { _, _ -> },
                    onStartCalibration = {},
                    onSaveShortcutSettings = {},
                    onDeleteShortcut = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("add_bet_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("add_cashout_btn").assertDoesNotExist()
        composeRule.onNodeWithText("BET HAND").assertIsDisplayed()
        composeRule.onNodeWithText("LEGACY CASH OUT").assertDoesNotExist()
    }
}
