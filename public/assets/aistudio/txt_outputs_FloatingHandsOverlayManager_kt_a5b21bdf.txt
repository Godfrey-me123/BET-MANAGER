package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.example.data.database.ScreenEarnerRepository
import com.example.data.engine.AutomationEngine
import com.example.data.model.FloatingShortcut
import com.example.data.model.RiskLimits
import com.example.data.model.ShortcutType
import com.example.data.model.SiteButtonState
import com.example.data.model.StrategyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

enum class UnifiedHandVisualState(
    val shortLabel: String,
    val badgeColorHex: String,
    val strokeColorHex: String,
    val isActionable: Boolean
) {
    BET_READY("BET\nTAP", "#16A34A", "#DCFCE7", true),
    CASH_OUT_READY("CASH\nOUT", "#EA580C", "#FFEDD5", true),
    BET_QUEUED_LOCKED("QUEUED\nWAIT", "#475569", "#CBD5E1", false),
    BLOCKED_CONFLICT("LOCKED\nCHECK", "#DC2626", "#FEE2E2", false),
    WAIT_IDLE("WAIT\nREADY", "#334155", "#E2E8F0", false)
}

/**
 * Manages the single unified draggable floating action hand for Predict Manager.
 * - Label, color, and action adapt automatically from verified Accessibility + OCR state:
 *   BET when waiting to place a bet; CASH OUT only when an active bet exists and Cash Out is available.
 * - Single tap performs the displayed action when manual operation is selected.
 * - Double-tap or long-press opens the Dedicated Hand Settings overlay without firing a bet/cash-out.
 * - Dragging repositions the hand cleanly without triggering accidental actions.
 */
class FloatingHandsOverlayManager(
    private val context: Context,
    private val repository: ScreenEarnerRepository,
    private val automationEngine: AutomationEngine,
    var onOpenHandSettings: (() -> Unit)? = null
) {
    companion object {
        private const val DOUBLE_TAP_WINDOW_MS = 280L
        private const val LONG_PRESS_THRESHOLD_MS = 500L
        private val activeManagers = CopyOnWriteArraySet<FloatingHandsOverlayManager>()

        fun setAllPassThrough(isPassThrough: Boolean) {
            activeManagers.forEach { it.setPassThroughState(isPassThrough) }
        }

        fun pulsePassThroughAll(durationMs: Long = 220L) {
            activeManagers.forEach { it.setPassThroughTemporarily(durationMs) }
        }

        fun hasVisibleHands(): Boolean = activeManagers.any { it.hasActiveViews() }

        /**
         * Live center of the visible Action Hand (user-placed click target).
         * Prefer this over DB shortcuts so a just-dragged hand works immediately.
         */
        fun getLiveHandCenter(): Pair<Float, Float>? {
            for (manager in activeManagers) {
                val center = manager.readLiveCenter()
                if (center != null) return center
            }
            return null
        }

        /**
         * Resolves the unified hand's visual state & valid action strictly from verified live state.
         * Never exposes BET or CASH OUT when unsafe, conflicting, or invalid for the current round phase.
         */
        fun resolveUnifiedHandVisualState(
            detection: GameDetectionState,
            isRoundBetActive: Boolean,
            config: StrategyConfig
        ): UnifiedHandVisualState {
            val targets = detection.uiTargets
            val phase = targets.gamePhase.ifBlank { detection.gamePhase }
            val btnState = targets.siteButtonState
            val hasConflict = targets.conflictReasons.isNotEmpty()

            if (!detection.isGameDetected || detection.liveDetectionLost) {
                return UnifiedHandVisualState.WAIT_IDLE
            }
            if (hasConflict) {
                return UnifiedHandVisualState.BLOCKED_CONFLICT
            }

            if (isRoundBetActive &&
                (phase == "IN_FLIGHT" || btnState == SiteButtonState.CASH_OUT_AVAILABLE) &&
                btnState == SiteButtonState.CASH_OUT_AVAILABLE &&
                targets.isCashOutBtnDetected &&
                targets.isCashOutBtnEnabled &&
                targets.detectCashOutCollisionReason() == null
            ) {
                return UnifiedHandVisualState.CASH_OUT_READY
            }

            if (!isRoundBetActive &&
                (phase == "BETTING_OPEN" || btnState == SiteButtonState.IDLE_READY_TO_BET) &&
                btnState == SiteButtonState.IDLE_READY_TO_BET &&
                targets.isBetBtnDetected &&
                targets.isBetBtnEnabled &&
                targets.detectBetCollisionReason() == null
            ) {
                return UnifiedHandVisualState.BET_READY
            }

            if ((isRoundBetActive || btnState == SiteButtonState.BET_QUEUED || targets.isCancelBtnDetected) &&
                phase == "BETTING_OPEN"
            ) {
                return UnifiedHandVisualState.BET_QUEUED_LOCKED
            }

            return UnifiedHandVisualState.WAIT_IDLE
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistMutex = Mutex()

    private var unifiedHandView: TextView? = null
    private var unifiedLayoutParams: WindowManager.LayoutParams? = null
    private var stateObserverJob: Job? = null
    private var persistedShortcutId: Long? = null
    private var pendingRestoreRunnable: Runnable? = null

    @Volatile
    private var latestVisualState: UnifiedHandVisualState = UnifiedHandVisualState.WAIT_IDLE

    @Volatile
    private var latestConfigSnapshot: StrategyConfig = StrategyConfig()

    @Volatile
    private var latestLimitsSnapshot: RiskLimits = RiskLimits()

    fun hasActiveViews(): Boolean = unifiedHandView != null

    /** Current center of the floating hand on screen, or null if not visible. */
    fun readLiveCenter(): Pair<Float, Float>? {
        val view = unifiedHandView ?: return null
        val lp = unifiedLayoutParams ?: return null
        if (!view.isAttachedToWindow) return null
        val size = dpToPx(64)
        val cx = lp.x + size / 2f
        val cy = lp.y + size / 2f
        return if (cx > 0f && cy > 0f) cx to cy else null
    }

    init {
        activeManagers.add(this)
    }

    private val overlayWindowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    fun showOrToggleUnifiedHand(existingShortcut: FloatingShortcut? = null) {
        mainHandler.post {
            if (unifiedHandView != null) {
                removeUnifiedHand()
                Toast.makeText(context, "Action Hand hidden", Toast.LENGTH_SHORT).show()
                return@post
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Toast.makeText(
                    context,
                    "Enable 'Display over other apps' first to use the floating Action Hand.",
                    Toast.LENGTH_LONG
                ).show()
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    )
                }
                return@post
            }

            val handSizePx = dpToPx(64)
            val dm = context.resources.displayMetrics
            val maxVisibleX = (dm.widthPixels - handSizePx).coerceAtLeast(24)
            val maxVisibleY = (dm.heightPixels - handSizePx - 80).coerceAtLeast(120)

            val rawX = existingShortcut?.visualX?.takeIf { it > 0f }?.minus(handSizePx / 2f)?.toInt()
                ?: (dm.widthPixels * 0.72f).toInt()
            val rawY = existingShortcut?.visualY?.takeIf { it > 0f }?.minus(handSizePx / 2f)?.toInt()
                ?: (dm.heightPixels * 0.58f).toInt()

            val startX = rawX.coerceIn(16, maxVisibleX)
            val startY = rawY.coerceIn(120, maxVisibleY)

            if (existingShortcut != null && existingShortcut.id > 0L) {
                persistedShortcutId = existingShortcut.id
            }

            val params = WindowManager.LayoutParams(
                handSizePx,
                handSizePx,
                overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = startX
                y = startY
            }

            val handView = createUnifiedHandView()
            attachUnifiedTouchListener(handView, params)

            runCatching {
                windowManager.addView(handView, params)
                unifiedHandView = handView
                unifiedLayoutParams = params
                startStateObserver()
                Toast.makeText(
                    context,
                    "Action Hand active · Tap to act, Double-tap or Long-press for Hand Settings",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    "Could not show Action Hand overlay: ${err.message ?: "permission required"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Backward-compatible delegate so any caller invokes the single Unified Action Hand.
     */
    fun showOrToggleHand(
        type: ShortcutType = ShortcutType.BET,
        existingShortcut: FloatingShortcut? = null,
        defaultTargetMultiplier: Double = 2.00
    ) {
        showOrToggleUnifiedHand(existingShortcut)
    }

    private fun startStateObserver() {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            combine(
                ScreenEarnerAccessibilityService.detectionState,
                automationEngine.isRoundBetActiveState,
                repository.strategyConfig,
                repository.riskLimits
            ) { detection, isBetActive, cfg, lim ->
                val resolvedConfig = cfg ?: StrategyConfig()
                val resolvedLimits = lim ?: RiskLimits()
                latestConfigSnapshot = resolvedConfig
                latestLimitsSnapshot = resolvedLimits
                resolveUnifiedHandVisualState(
                    detection = detection,
                    isRoundBetActive = isBetActive || automationEngine.gameDetection.gameState.value.isBetPlaced,
                    config = resolvedConfig
                )
            }.collect { visual ->
                latestVisualState = visual
                mainHandler.post {
                    unifiedHandView?.let { renderHandVisual(it, visual, latestConfigSnapshot) }
                }
            }
        }
    }

    private fun renderHandVisual(
        view: TextView,
        visual: UnifiedHandVisualState,
        config: StrategyConfig
    ) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(visual.badgeColorHex))
            setStroke(dpToPx(3), Color.parseColor(visual.strokeColorHex))
        }
        val modeHint = when (visual) {
            UnifiedHandVisualState.BET_READY -> if (config.isAutoBetEnabled) "BET\nAUTO" else "BET\nTAP"
            UnifiedHandVisualState.CASH_OUT_READY -> if (config.isAutoCashOutEnabled) "CASH\nAUTO" else "CASH\nOUT"
            else -> visual.shortLabel
        }
        view.background = bg
        view.text = modeHint
        view.alpha = if (visual.isActionable) 1.0f else 0.85f
    }

    fun setPassThroughState(isPassThrough: Boolean) {
        mainHandler.post {
            if (unifiedHandView == null) return@post
            pendingRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
            pendingRestoreRunnable = null
            applyPassThroughFlags(isPassThrough)
        }
    }

    fun setPassThroughTemporarily(durationMs: Long = 220L) {
        mainHandler.post {
            if (unifiedHandView == null) return@post
            pendingRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
            applyPassThroughFlags(true)
            val restore = Runnable {
                applyPassThroughFlags(false)
            }
            pendingRestoreRunnable = restore
            mainHandler.postDelayed(restore, durationMs.coerceAtLeast(100L))
        }
    }

    private fun applyPassThroughFlags(isPassThrough: Boolean) {
        val view = unifiedHandView ?: return
        val lp = unifiedLayoutParams ?: return
        val nextFlags = if (isPassThrough) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (lp.flags != nextFlags) {
            lp.flags = nextFlags
            if (view.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
        }
    }

    fun removeUnifiedHand() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        mainHandler.post {
            unifiedHandView?.let { view ->
                runCatching { windowManager.removeView(view) }
            }
            unifiedHandView = null
            unifiedLayoutParams = null
        }
    }

    fun removeHand(type: ShortcutType) {
        removeUnifiedHand()
    }

    fun removeAllHands() {
        removeUnifiedHand()
        activeManagers.remove(this)
    }

    private fun createUnifiedHandView(): TextView {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(UnifiedHandVisualState.WAIT_IDLE.badgeColorHex))
            setStroke(dpToPx(3), Color.parseColor(UnifiedHandVisualState.WAIT_IDLE.strokeColorHex))
        }

        return TextView(context).apply {
            text = UnifiedHandVisualState.WAIT_IDLE.shortLabel
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
            elevation = 14f
            contentDescription = "Unified Action Hand"
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachUnifiedTouchListener(
        view: TextView,
        params: WindowManager.LayoutParams
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressTriggered = false
        var lastTapUpAtMs = 0L
        var longPressRunnable: Runnable? = null
        var pendingSingleTapRunnable: Runnable? = null
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(12f)

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressTriggered = false
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    val lpRunnable = Runnable {
                        if (!isDragging) {
                            longPressTriggered = true
                            pendingSingleTapRunnable?.let { mainHandler.removeCallbacks(it) }
                            pendingSingleTapRunnable = null
                            lastTapUpAtMs = 0L
                            triggerHandSettings()
                        }
                    }
                    longPressRunnable = lpRunnable
                    mainHandler.postDelayed(lpRunnable, LONG_PRESS_THRESHOLD_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingSingleTapRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingSingleTapRunnable = null
                        lastTapUpAtMs = 0L
                    }
                    if (isDragging) {
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable = null

                    if (isDragging) {
                        val centerX = params.x + (dpToPx(64) / 2f)
                        val centerY = params.y + (dpToPx(64) / 2f)
                        persistHandPosition(centerX, centerY)
                        lastTapUpAtMs = 0L
                        return@setOnTouchListener true
                    }

                    if (longPressTriggered) {
                        return@setOnTouchListener true
                    }

                    val nowUp = SystemClock.uptimeMillis()
                    if (lastTapUpAtMs > 0L && (nowUp - lastTapUpAtMs) <= DOUBLE_TAP_WINDOW_MS) {
                        pendingSingleTapRunnable?.let { mainHandler.removeCallbacks(it) }
                        pendingSingleTapRunnable = null
                        lastTapUpAtMs = 0L
                        triggerHandSettings()
                    } else {
                        lastTapUpAtMs = nowUp
                        val tapRunnable = Runnable {
                            lastTapUpAtMs = 0L
                            executeCurrentSingleTapAction()
                        }
                        pendingSingleTapRunnable = tapRunnable
                        mainHandler.postDelayed(tapRunnable, DOUBLE_TAP_WINDOW_MS)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    pendingSingleTapRunnable?.let { mainHandler.removeCallbacks(it) }
                    lastTapUpAtMs = 0L
                    true
                }

                else -> false
            }
        }
    }

    private fun triggerHandSettings() {
        val callback = onOpenHandSettings
        if (callback != null) {
            callback.invoke()
        } else {
            runCatching {
                context.startService(
                    Intent(context, ScreenEarnerOverlayService::class.java).apply {
                        action = ScreenEarnerOverlayService.ACTION_SHOW_HAND_SETTINGS
                    }
                )
            }
        }
    }

    private fun executeCurrentSingleTapAction() {
        val config = latestConfigSnapshot
        val limits = latestLimitsSnapshot
        when (val visual = latestVisualState) {
            UnifiedHandVisualState.BET_READY -> {
                automationEngine.executeVerifiedManualBet(config, limits) { _, msg ->
                    mainHandler.post {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            UnifiedHandVisualState.CASH_OUT_READY -> {
                automationEngine.executeVerifiedManualCashOut(config, limits) { _, msg ->
                    mainHandler.post {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            UnifiedHandVisualState.BET_QUEUED_LOCKED -> {
                Toast.makeText(
                    context,
                    "Bet already queued — waiting for round takeoff.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            UnifiedHandVisualState.BLOCKED_CONFLICT -> {
                Toast.makeText(
                    context,
                    "Blocked: conflicting screen/OCR signals detected.",
                    Toast.LENGTH_SHORT
                ).show()
            }

            UnifiedHandVisualState.WAIT_IDLE -> {
                Toast.makeText(
                    context,
                    "No valid BET or CASH OUT action available right now. Double-tap for Hand Settings.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun persistHandPosition(centerX: Float, centerY: Float) {
        val activeSite = com.example.data.engine.GameDetection.resolveSiteKey(
            automationEngine.gameDetection.gameState.value.platformName
        )
        scope.launch {
            persistMutex.withLock {
                val allExisting = runCatching { repository.shortcuts.first() }.getOrDefault(emptyList())
                val knownId = persistedShortcutId?.takeIf { it > 0L }
                val matchedExisting = allExisting.firstOrNull { knownId != null && it.id == knownId }
                    ?: allExisting.firstOrNull { it.label == "ACTION HAND" }

                // User-placed hand position IS the click target. Keep visual + target in sync.
                val shortcut = FloatingShortcut(
                    id = matchedExisting?.id ?: 0L,
                    label = "ACTION HAND",
                    type = ShortcutType.BET,
                    panelId = 1,
                    visualX = centerX,
                    visualY = centerY,
                    targetX = centerX,
                    targetY = centerY,
                    targetMultiplier = latestConfigSnapshot.targetMultiplier,
                    targetPlatform = activeSite,
                    backgroundColorHex = "#16A34A",
                    isEnabled = true,
                    isCalibrated = true
                )

                val savedId = if (shortcut.id > 0L) {
                    repository.updateShortcut(shortcut)
                    shortcut.id
                } else {
                    repository.insertShortcut(shortcut)
                }
                if (savedId > 0L) {
                    persistedShortcutId = savedId
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
