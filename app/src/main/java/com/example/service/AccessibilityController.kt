package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.data.engine.CurrencyManager
import com.example.data.engine.StakeInstruction
import com.example.data.engine.StrategyEngine
import com.example.data.model.SiteButtonState
import com.example.data.model.StakeProgressionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

data class MultiplierCandidate(
    val value: Double,
    val bounds: Rect,
    val orderIndex: Int,
    val isHistoryHinted: Boolean = false,
    val gameTimestampMs: Long? = null,
    val roundId: String? = null,
    val displayText: String = ""
)

data class DetectedUiTargets(
    val detectedMultiplier: Double? = null,
    val ribbonMultipliers: List<Double> = emptyList(),
    val ribbonItems: List<com.example.data.engine.ExtractedHistoryItem> = emptyList(),
    val historyPanelExpanded: Boolean = false,
    val hasExpandableHistoryControl: Boolean = false,
    val hasScrollableHistoryContainer: Boolean = false,
    val gamePhase: String = "WAITING_FOR_GAME", // "WAITING_FOR_GAME", "BETTING_OPEN", "IN_FLIGHT", "CRASHED", "LIVE_DETECTION_LOST"
    val siteButtonState: SiteButtonState = SiteButtonState.UNKNOWN,
    val isFlewAwayDetected: Boolean = false,
    val isWaitingForNextRound: Boolean = false,
    val isCancelBtnDetected: Boolean = false,
    val detectedUrl: String? = null,
    val detectedCurrentRoundId: String? = null,
    val pageTextHints: List<String> = emptyList(),
    val visibleRawTitles: List<String> = emptyList(),
    val liveMultiplierBounds: Rect? = null,
    val stakeInputBounds: Rect? = null,
    /** The "-" / "+" stepper buttons on either side of the stake box (BetPawa style). */
    val stakeMinusBounds: Rect? = null,
    val stakeMinusEnabled: Boolean = true,
    val stakePlusBounds: Rect? = null,
    val stakePlusEnabled: Boolean = true,
    val doubleButtonBounds: Rect? = null,
    val addButtonBounds: Rect? = null,
    val betButtonBounds: Rect? = null,
    val cancelButtonBounds: Rect? = null,
    val cashOutButtonBounds: Rect? = null,
    val detectedStakeText: String? = null,
    val detectedRoundId: String? = detectedCurrentRoundId,
    val roundId: String? = detectedCurrentRoundId,
    val detectedBalance: Double? = null,
    val detectedCurrency: String? = null,
    // Verified automation, OCR fusion & game-side Auto Cash Out fields
    val scannedAtMs: Long = 0L,
    val foregroundPackage: String = "",
    val countdownRemainingMs: Long? = null,
    val isBetBtnEnabled: Boolean = true,
    val isBetBtnClickable: Boolean = true,
    val isCashOutBtnEnabled: Boolean = true,
    val isCashOutBtnClickable: Boolean = true,
    val isCancelBtnEnabled: Boolean = true,
    val isStakeInputEditable: Boolean = true,
    val autoCashOutToggleBounds: Rect? = null,
    val isAutoCashOutToggleChecked: Boolean? = null,
    val autoCashOutInputBounds: Rect? = null,
    val detectedAutoCashOutText: String? = null,
    val detectedAutoCashOutValue: Double? = null,
    val conflictReasons: List<String> = emptyList(),
    val ocrVerified: Boolean = false,
    val fusedConfidence: Float = 0f,
    val canQueueBet: Boolean = false,
    val autoCashOutTabBounds: Rect? = null,
    val isAutoCashOutRowVisible: Boolean = false
) {
    val isStakeInputDetected: Boolean get() = stakeInputBounds != null
    val isDoubleBtnDetected: Boolean get() = doubleButtonBounds != null
    val isAddBtnDetected: Boolean get() = addButtonBounds != null
    val isBetBtnDetected: Boolean get() = betButtonBounds != null
    val isCashOutBtnDetected: Boolean get() = cashOutButtonBounds != null
    val isAutoCashOutControlDetected: Boolean get() = autoCashOutToggleBounds != null || autoCashOutInputBounds != null

    /**
     * Ensures a BET tap target cannot overlap or strike Double (2x), Cancel, Auto Cash Out,
     * Stake Input, or an active/distinct Cash Out button.
     */
    fun detectBetCollisionReason(): String? {
        val betRect = betButtonBounds?.takeIf { !it.isEmpty } ?: return "BET button bounds missing or empty"
        doubleButtonBounds?.takeIf { !it.isEmpty }?.let { dbl ->
            if (Rect.intersects(betRect, dbl)) {
                return "BET bounds collide with 2x/Double control"
            }
        }
        stakeInputBounds?.takeIf { !it.isEmpty }?.let { stk ->
            if (Rect.intersects(betRect, stk)) {
                return "BET bounds collide with Stake input control"
            }
        }
        autoCashOutToggleBounds?.takeIf { !it.isEmpty }?.let { tgl ->
            if (Rect.intersects(betRect, tgl)) {
                return "BET bounds collide with Auto Cash Out toggle"
            }
        }
        autoCashOutInputBounds?.takeIf { !it.isEmpty }?.let { ain ->
            if (Rect.intersects(betRect, ain)) {
                return "BET bounds collide with Auto Cash Out target input"
            }
        }
        if (isCancelBtnDetected || siteButtonState == SiteButtonState.BET_QUEUED) {
            cancelButtonBounds?.takeIf { !it.isEmpty }?.let { cnl ->
                if (Rect.intersects(betRect, cnl)) {
                    return "BET bounds collide with active CANCEL control"
                }
            }
        }
        if (siteButtonState == SiteButtonState.CASH_OUT_AVAILABLE) {
            return "Site button is CASH_OUT_AVAILABLE, not BET"
        }
        cashOutButtonBounds?.takeIf { !it.isEmpty }?.let { co ->
            if (co != betRect && Rect.intersects(betRect, co)) {
                return "BET bounds collide with distinct CASH OUT control"
            }
        }
        return null
    }

    fun detectCashOutCollisionReason(): String? {
        val coRect = cashOutButtonBounds?.takeIf { !it.isEmpty } ?: return "CASH OUT button bounds missing or empty"
        doubleButtonBounds?.takeIf { !it.isEmpty }?.let { dbl ->
            if (Rect.intersects(coRect, dbl)) return "CASH OUT bounds collide with 2x/Double control"
        }
        autoCashOutToggleBounds?.takeIf { !it.isEmpty }?.let { tgl ->
            if (Rect.intersects(coRect, tgl)) return "CASH OUT bounds collide with Auto Cash Out toggle"
        }
        autoCashOutInputBounds?.takeIf { !it.isEmpty }?.let { ain ->
            if (Rect.intersects(coRect, ain)) return "CASH OUT bounds collide with Auto Cash Out input"
        }
        stakeInputBounds?.takeIf { !it.isEmpty }?.let { stk ->
            if (Rect.intersects(coRect, stk)) return "CASH OUT bounds collide with Stake input control"
        }
        if (isCancelBtnDetected || siteButtonState == SiteButtonState.BET_QUEUED) {
            cancelButtonBounds?.takeIf { !it.isEmpty }?.let { cnl ->
                if (Rect.intersects(coRect, cnl)) return "CASH OUT bounds collide with Cancel control"
            }
        }
        betButtonBounds?.takeIf { !it.isEmpty }?.let { bet ->
            if (bet != coRect && Rect.intersects(coRect, bet)) {
                return "CASH OUT bounds collide with distinct BET control"
            }
        }
        return null
    }
}

/** A switch-like or editable control found while scanning; matched to its label by row geometry. */
data class RowControl(
    val bounds: Rect,
    val checked: Boolean?,
    val text: String,
    val enabled: Boolean
)

data class HierarchyCandidate(
    val role: String,
    val resourceId: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val bounds: Rect
)

data class NodeHierarchyReport(
    val packageName: String,
    val timestampMs: Long,
    val totalNodesVisited: Int,
    val truncated: Boolean,
    val lines: List<String>,
    val candidates: List<HierarchyCandidate>
) {
    fun toFormattedText(): String = buildString {
        lines.forEach { line ->
            appendLine(line)
        }
    }
}

/**
 * AccessibilityController - Crash-safe AccessibilityService extending Android accessibility
 * to read browser URL bars (BetPawa vs SportyBet), separate top history ribbons from live center
 * multipliers, track real site Bet/Cancel/Cash Out button states, edit stakes, dispatch gestures
 * cleanly on the Main Looper without overlays blocking their own taps, and log node hierarchies.
 */
open class AccessibilityController : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityController"
        const val NODE_HIERARCHY_TAG = "A11yNodeTree"
        const val DEFAULT_HIERARCHY_LOG_THROTTLE_MS = 1200L
        const val FORCE_HIERARCHY_REFRESH_MS = 6000L
        private const val MAX_TREE_DEPTH = 64
        private const val MAX_LOGGED_NODES = 600
        private const val MAX_SCAN_NODES = 1800
        private const val MAX_PROBE_TAPS = 60

        /** "Bet 400.00 TZS" / "Bet\n1.00 TZS" - the real BET button (name followed by an amount). */
        private val BET_BUTTON_WITH_AMOUNT = Regex("^bet\\s*[0-9][0-9.,]*(\\s.*)?$", RegexOption.DOT_MATCHES_ALL)

        /** A bare "Bet" label only counts as the button when at least this fraction of the screen wide (tabs are narrower). */
        private const val BET_BUTTON_MIN_WIDTH_FRACTION = 0.35f

        /** Max vertical distance (as a multiple of the label height) for a control to count as "on the same row". */
        private const val SAME_ROW_TOLERANCE = 1.0f

        var controllerInstance: AccessibilityController? = null
            protected set

        fun isControllerConnected(): Boolean = controllerInstance != null

        /**
         * Callback notified before/after automated gesture dispatch so ScreenEarnerOverlayService
         * temporarily sets FLAG_NOT_TOUCHABLE on floating hands/panels and never intercepts its own tap.
         */
        @Volatile
        var gestureOverlayPassThroughListener: ((isDispatching: Boolean) -> Unit)? = null

        fun notifyOverlayPassThrough(isDispatching: Boolean) {
            runCatching { gestureOverlayPassThroughListener?.invoke(isDispatching) }
            runCatching { FloatingHandsOverlayManager.setAllPassThrough(isDispatching) }
        }

        private val _multiplierFlow = MutableStateFlow<Double?>(null)
        val multiplierFlow: StateFlow<Double?> = _multiplierFlow.asStateFlow()

        private val _detectedTargets = MutableStateFlow(DetectedUiTargets())
        val detectedTargets: StateFlow<DetectedUiTargets> = _detectedTargets.asStateFlow()

        private val _nodeHierarchyLoggingEnabled = MutableStateFlow(true)
        val nodeHierarchyLoggingEnabled: StateFlow<Boolean> = _nodeHierarchyLoggingEnabled.asStateFlow()

        private val _lastLoggedHierarchy = MutableStateFlow("")
        val lastLoggedHierarchy: StateFlow<String> = _lastLoggedHierarchy.asStateFlow()

        private const val UI_EMIT_THROTTLE_MS = 115L
        private const val MIN_PASS_THROUGH_MS = 100L

        private val _diagnosticMessage = MutableStateFlow<String?>(null)
        val diagnosticMessage: StateFlow<String?> = _diagnosticMessage.asStateFlow()

        fun emitDiagnostic(message: String) {
            _diagnosticMessage.value = message
        }

        // Match decimal multipliers like 1.00x, 12.80x, 140.25x (avoids matching "2x" double-stake button)
        val MULTIPLIER_PATTERN: Pattern = Pattern.compile("(\\d+\\.\\d{1,2})\\s*x", Pattern.CASE_INSENSITIVE)

        private val BALANCE_SUFFIX_PATTERN: Pattern = Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(TZS|TSH|ZMW|KES|KSH|UGX|NGN|GHS|USD)",
            Pattern.CASE_INSENSITIVE
        )

        private val BALANCE_PREFIX_PATTERN: Pattern = Pattern.compile(
            "(TZS|TSH|ZMW|KES|KSH|UGX|NGN|GHS|USD|₦|GH₵|\\$)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE
        )

        private val NUMERIC_VALUE_PATTERN: Pattern = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")

        private val URL_HINT_PATTERN: Pattern = Pattern.compile(
            "(?:https?://)?[a-z0-9.-]*(?:betpawa|sportybet)[a-z0-9.-]*(?:/[^\\s]*)?|(?:https?://)?[a-z0-9.-]+\\.(?:com|co\\.[a-z]{2}|ng|ke|tz|zm|gh|ug|za|mw|rw|bet)(?:/[^\\s]*)?",
            Pattern.CASE_INSENSITIVE
        )

        /**
         * Verifies exact bounds containment and geometry match (overlap >= 70%),
         * while explicitly rejecting oversized parent containers (e.g. screen-wide layouts).
         */
        fun isBoundsSafelyContained(nodeBounds: Rect, targetBounds: Rect): Boolean {
            if (nodeBounds.isEmpty || targetBounds.isEmpty) return false
            val widthRatio = nodeBounds.width().toFloat() / targetBounds.width().coerceAtLeast(1).toFloat()
            val heightRatio = nodeBounds.height().toFloat() / targetBounds.height().coerceAtLeast(1).toFloat()
            if (widthRatio > 2.2f || heightRatio > 2.2f) return false
            val intersection = Rect()
            if (!intersection.setIntersect(nodeBounds, targetBounds)) return false
            val intersectionArea = intersection.width().toLong() * intersection.height().toLong()
            val minArea = minOf(
                nodeBounds.width().toLong() * nodeBounds.height().toLong(),
                targetBounds.width().toLong() * targetBounds.height().toLong()
            )
            if (minArea <= 0L) return false
            return (intersectionArea.toDouble() / minArea.toDouble()) >= 0.70
        }

        fun setNodeHierarchyLoggingEnabled(enabled: Boolean) {
            _nodeHierarchyLoggingEnabled.value = enabled
        }

        fun formatStakeForInput(stake: Double): String {
            return if (stake % 1.0 == 0.0) {
                stake.toLong().toString()
            } else {
                String.format(Locale.US, "%.2f", stake)
            }
        }

        /**
         * Parses account balance + currency code from a header node (e.g., "10.18 TZS", "ZMW 450.00").
         * Ignores stake inputs and multiplier labels ending with 'x'.
         */
        fun parseBalanceCandidate(
            text: String,
            lowerId: String = "",
            bounds: Rect = Rect(),
            viewportHeightPx: Int = 0
        ): Pair<Double, String?>? {
            if (text.isBlank()) return null
            val lowerText = text.lowercase(Locale.US)
            if (lowerText.contains("stake") || lowerText.contains("wager") || lowerId.contains("stake") ||
                lowerId.contains("url_bar") || lowerId.contains("omnibox") ||
                lowerText.contains("auto") || lowerText.contains("cash out") || lowerText.contains("cashout")
            ) {
                return null
            }
            if (text.trim().endsWith("x", ignoreCase = true) && MULTIPLIER_PATTERN.matcher(text).find()) {
                return null
            }

            val suffixMatcher = BALANCE_SUFFIX_PATTERN.matcher(text)
            if (suffixMatcher.find()) {
                val amount = suffixMatcher.group(1)?.replace(",", "")?.toDoubleOrNull()
                val rawCurr = suffixMatcher.group(2)?.uppercase(Locale.US)
                val normCurr = when (rawCurr) {
                    "TSH" -> "TZS"
                    "KSH" -> "KES"
                    else -> rawCurr
                }
                if (amount != null && amount.isFinite() && amount >= 0.0) {
                    return amount to normCurr
                }
            }

            val prefixMatcher = BALANCE_PREFIX_PATTERN.matcher(text)
            if (prefixMatcher.find()) {
                val rawCurr = prefixMatcher.group(1)?.uppercase(Locale.US)
                val amount = prefixMatcher.group(2)?.replace(",", "")?.toDoubleOrNull()
                val normCurr = when (rawCurr) {
                    "TSH" -> "TZS"
                    "KSH" -> "KES"
                    "₦" -> "NGN"
                    "GH₵" -> "GHS"
                    "$" -> "USD"
                    else -> rawCurr
                }
                if (amount != null && amount.isFinite() && amount >= 0.0) {
                    return amount to normCurr
                }
            }

            // Explicit balance/wallet node without currency code in string, provided it sits in header area or ID
            val inHeaderBand = bounds.isEmpty || viewportHeightPx <= 0 || bounds.top <= (viewportHeightPx * 0.28f).toInt()
            val hasBalanceHint = lowerId.contains("balance") || lowerId.contains("wallet") ||
                lowerId.contains("credit") || lowerText.contains("balance") || lowerText.contains("bal:")
            if (hasBalanceHint && inHeaderBand) {
                val numMatcher = NUMERIC_VALUE_PATTERN.matcher(text)
                if (numMatcher.find()) {
                    val amount = numMatcher.group(1)?.replace(",", "")?.toDoubleOrNull()
                    if (amount != null && amount.isFinite() && amount >= 0.0) {
                        val detectedCurr = CurrencyManager.parseCurrencyCodeFromText(text)?.code
                        return amount to detectedCurr
                    }
                }
            }
            return null
        }

        fun verifyStakeAccepted(readBackText: String?, expectedStake: Double): Boolean {
            if (readBackText.isNullOrBlank()) return false
            val cleaned = readBackText.replace(",", "").trim()
            val parsed = cleaned.toDoubleOrNull()
                ?: NUMERIC_VALUE_PATTERN.matcher(cleaned).let { m ->
                    if (m.find()) m.group(1)?.toDoubleOrNull() else null
                }
                ?: return false
            return abs(parsed - expectedStake) <= 0.05
        }

        /**
         * Separates the top horizontal history ribbon (`15.74x  2.00x  8.14x ...`) from the prominent
         * central live multiplier (`1.27x`, `3.01x`), using Y-band clustering, container hints,
         * and bounding-box dimensions so ribbon items are never mistaken for the live multiplier.
         *
         * @return Pair(ribbonMultipliersLeftToRight, liveCenterMultiplier)
         */
        fun extractRibbonAndCenterMultipliers(
            candidates: List<MultiplierCandidate>,
            viewportHeightPx: Int = 0
        ): Pair<List<Double>, Double?> {
            val (items, center) = extractRibbonItemsAndCenterMultiplier(candidates, viewportHeightPx)
            return items.map { it.multiplier } to center
        }

        fun extractRibbonItemsAndCenterMultiplier(
            candidates: List<MultiplierCandidate>,
            viewportHeightPx: Int = 0,
            maxCenterY: Int = Int.MAX_VALUE
        ): Pair<List<com.example.data.engine.ExtractedHistoryItem>, Double?> {
            val valid = candidates.filter { it.value >= 1.0 }
            if (valid.isEmpty()) return emptyList<com.example.data.engine.ExtractedHistoryItem>() to null

            val explicitHistory = valid.filter { it.isHistoryHinted }.toMutableSet()
            val positioned = valid.filter { !it.bounds.isEmpty }

            // Expanded grids (e.g. SportyBet 8-column Round History modal) can occupy upper ~60% of the screen.
            val topCutoffY = if (viewportHeightPx > 0) {
                (viewportHeightPx * 0.62f).toInt().coerceAtLeast(220)
            } else {
                val maxBottom = positioned.maxOfOrNull { it.bounds.bottom } ?: 0
                if (maxBottom > 0) (maxBottom * 0.65f).toInt().coerceAtLeast(220) else Int.MAX_VALUE
            }

            // Collect horizontal rows in topCutoffY so single-row ribbons AND multi-row Round History grids
            // retain all rows cleanly without modulo division artifacts dropping row-head items (e.g. 1.53x).
            val horizontalRows = mutableListOf<List<MultiplierCandidate>>()
            for (anchor in positioned) {
                val anchorCy = anchor.bounds.centerY()
                if (anchorCy > topCutoffY) continue
                val rowMatches = positioned.filter { other ->
                    other.bounds.centerY() <= topCutoffY &&
                        abs(other.bounds.centerY() - anchorCy) <= 45
                }
                if (rowMatches.size >= 2) {
                    horizontalRows.add(rowMatches)
                }
            }

            // Determine typical compact chip height from detected rows
            val medianRowHeight = horizontalRows.flatten()
                .map { it.bounds.height() }
                .filter { it > 0 }
                .sorted()
                .let { heights ->
                    if (heights.isEmpty()) 48 else heights[heights.size / 2]
                }

            val gridBandCandidates = horizontalRows
                .flatten()
                .distinctBy { it.orderIndex }
                .filter { c ->
                    c.bounds.height() <= (medianRowHeight * 2.2f).toInt().coerceAtLeast(110)
                }

            val combinedRibbon = (explicitHistory + gridBandCandidates)
                .distinctBy { it.orderIndex }

            // Assign stable row indices by vertical center proximity rather than modulo division (top / 45)
            // so a 1px vertical jitter between neighboring badges in the same row never reverses left-to-right order.
            val sortedByY = combinedRibbon.sortedBy {
                if (it.bounds.isEmpty) Int.MAX_VALUE else it.bounds.centerY()
            }
            val rowGroups = mutableListOf<MutableList<MultiplierCandidate>>()
            val unpositioned = mutableListOf<MultiplierCandidate>()
            for (cand in sortedByY) {
                if (cand.bounds.isEmpty) {
                    unpositioned.add(cand)
                } else {
                    val lastGroup = rowGroups.lastOrNull()
                    if (lastGroup != null) {
                        val groupAvgCy = lastGroup.map { it.bounds.centerY() }.average()
                        val tolerance = (medianRowHeight * 0.8f).toInt().coerceIn(28, 64)
                        if (abs(cand.bounds.centerY() - groupAvgCy) <= tolerance) {
                            lastGroup.add(cand)
                            continue
                        }
                    }
                    rowGroups.add(mutableListOf(cand))
                }
            }

            val ribbonSet = rowGroups.flatMap { row ->
                row.sortedWith(compareBy({ it.bounds.left }, { it.orderIndex }))
            } + unpositioned.sortedBy { it.orderIndex }

            val sourceType = if (explicitHistory.size >= 8 || rowGroups.size >= 2) {
                com.example.data.model.CollectionSource.EXPANDED_PANEL
            } else {
                com.example.data.model.CollectionSource.VISIBLE_RIBBON
            }
            val extractedItems = ribbonSet.mapIndexed { idx, c ->
                val sampled = ScreenColorSampler.sampleColorInBounds(c.bounds)
                com.example.data.engine.ExtractedHistoryItem(
                    multiplier = c.value,
                    roundId = c.roundId,
                    gameTimestampMs = c.gameTimestampMs,
                    orderIndex = idx,
                    source = sourceType,
                    bounds = Rect(c.bounds),
                    detectedColorHex = sampled?.hex,
                    colorCategory = sampled?.category,
                    isPixelColorVerified = sampled?.isPixelVerified == true,
                    displayText = c.displayText
                )
            }

            val ribbonOrderIndices = ribbonSet.map { it.orderIndex }.toSet()
            val ribbonBottomY = ribbonSet.filter { !it.bounds.isEmpty }.maxOfOrNull { it.bounds.bottom } ?: Int.MIN_VALUE

            val nonRibbon = valid.filter { it.orderIndex !in ribbonOrderIndices }
            if (nonRibbon.isEmpty()) {
                return extractedItems to null
            }

            // Exclude any multiplier positioned below the game stage / controls (e.g. All Bets / My Bets / Auto Cash Out rows)
            val stageFiltered = nonRibbon.filter { cand ->
                if (cand.bounds.isEmpty) {
                    true
                } else {
                    val cy = cand.bounds.centerY()
                    val belowStageFloor = maxCenterY < Int.MAX_VALUE && cy >= maxCenterY
                    val isTopBarLoneChip = viewportHeightPx >= 800 &&
                        cy < (viewportHeightPx * 0.18f).toInt() &&
                        cand.bounds.height() <= 70
                    !belowStageFloor && !isTopBarLoneChip
                }
            }
            if (stageFiltered.isEmpty()) {
                return extractedItems to null
            }

            val belowRibbon = if (ribbonBottomY > Int.MIN_VALUE) {
                stageFiltered.filter { it.bounds.isEmpty || it.bounds.centerY() > ribbonBottomY }
            } else {
                stageFiltered
            }
            val centerPool = if (belowRibbon.isNotEmpty()) belowRibbon else stageFiltered

            val liveCenter = centerPool.maxWithOrNull(
                compareBy<MultiplierCandidate>(
                    { if (it.bounds.isEmpty) 0 else it.bounds.height() },
                    { if (it.bounds.isEmpty) 0 else it.bounds.width() * it.bounds.height() },
                    { -it.orderIndex }
                )
            )?.value

            return extractedItems to liveCenter
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var hierarchyLogThrottleMs: Long = DEFAULT_HIERARCHY_LOG_THROTTLE_MS

    @Volatile
    private var lastHierarchyLogTimeMs: Long = 0L

    @Volatile
    private var lastHierarchySignature: Int = 0

    @Volatile
    private var lastEventScanTimeMs: Long = 0L

    @Volatile
    private var lastUiEmitTimeMs: Long = 0L

    @Volatile
    private var passThroughStartedAtMs: Long = 0L

    @Volatile
    private var pendingOverlayRestoreRunnable: Runnable? = null

    private val gestureGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var lastDispatchedCoords: Pair<Float, Float>? = null
    @Volatile
    private var lastDispatchedTimeMs: Long = 0L
    private val pendingGestureRunnables = java.util.concurrent.CopyOnWriteArraySet<Runnable>()

    fun setHierarchyLogThrottleMs(throttleMs: Long) {
        hierarchyLogThrottleMs = throttleMs.coerceAtLeast(0L)
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            controllerInstance = this
            safeLogD(TAG, "AccessibilityController connected. Node hierarchy logger active under tag '$NODE_HIERARCHY_TAG'.")
        } catch (t: Throwable) {
            safeLogE(TAG, "Non-fatal error during onServiceConnected", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val ownPkg = runCatching { applicationContext?.packageName }.getOrNull().orEmpty()
            val eventPkg = try { event.packageName?.toString().orEmpty() } catch (_: Throwable) { "" }
            // Ignore Screen Earner's own overlay/bubble/panel events before consuming scan throttle
            if (ownPkg.isNotBlank() && eventPkg == ownPkg) {
                return
            }

            val now = System.currentTimeMillis()
            val eventType = try { event.eventType } catch (_: Throwable) { AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED }

            // Throttle dense content-changed churn (~115ms) while allowing window state transitions through immediately
            val isWindowTransition = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (!isWindowTransition &&
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                (now - lastEventScanTimeMs) < UI_EMIT_THROTTLE_MS
            ) {
                return
            }
            lastEventScanTimeMs = now

            val (rootNode, fromWindowsList) = resolveBestTargetWindowRoot() ?: return
            try {
                val rootPkg = safeNodePackageName(rootNode)
                if (ownPkg.isNotBlank() && rootPkg == ownPkg) {
                    return
                }
                val prevTargets = _detectedTargets.value
                val targets = scanWindowTargets(rootNode)

                val stateChanged = targets.siteButtonState != prevTargets.siteButtonState ||
                    targets.gamePhase != prevTargets.gamePhase ||
                    targets.isFlewAwayDetected != prevTargets.isFlewAwayDetected ||
                    targets.detectedUrl != prevTargets.detectedUrl ||
                    targets.detectedBalance != prevTargets.detectedBalance ||
                    targets.ribbonMultipliers != prevTargets.ribbonMultipliers

                if (isWindowTransition || stateChanged || (now - lastUiEmitTimeMs) >= UI_EMIT_THROTTLE_MS) {
                    lastUiEmitTimeMs = now
                    _detectedTargets.value = targets
                    if (targets.detectedMultiplier != null) {
                        _multiplierFlow.value = targets.detectedMultiplier
                    }
                }

                // Offload structural Logcat reporting checks off Main UI Thread where throttled
                val elapsedSinceLog = (now - lastHierarchyLogTimeMs).coerceAtLeast(0L)
                if (_nodeHierarchyLoggingEnabled.value &&
                    (lastHierarchyLogTimeMs == 0L || elapsedSinceLog >= hierarchyLogThrottleMs ||
                        eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                ) {
                    val pkg = safeNodePackageName(rootNode).ifBlank {
                        try { event.packageName?.toString().orEmpty() } catch (_: Throwable) { "" }
                    }
                    val ownPkg = runCatching { applicationContext?.packageName }.getOrNull()
                    if (ownPkg.isNullOrBlank() || pkg != ownPkg) {
                        val report = buildNodeHierarchyReport(
                            rootNode = rootNode,
                            packageName = pkg,
                            eventLabel = runCatching { AccessibilityEvent.eventTypeToString(eventType) }.getOrDefault(eventType.toString()),
                            timestampMs = now
                        )
                        workerScope.launch {
                            try {
                                val sig = computeStructuralSignature(report)
                                if (lastHierarchyLogTimeMs == 0L ||
                                    (sig != lastHierarchySignature && elapsedSinceLog >= hierarchyLogThrottleMs) ||
                                    elapsedSinceLog >= FORCE_HIERARCHY_REFRESH_MS
                                ) {
                                    lastHierarchyLogTimeMs = now
                                    lastHierarchySignature = sig
                                    emitReportToLogcat(report)
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } finally {
                if (fromWindowsList) {
                    safeRecycleRoot(rootNode)
                }
            }
        } catch (t: Throwable) {
            // Never allow unhandled exceptions to crash AccessibilityService and get it disabled by Android
            safeLogE(TAG, "Caught exception in onAccessibilityEvent", t)
        }
    }

    protected fun safeRootInActiveWindow(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (t: Throwable) {
            safeLogW(TAG, "Unable to acquire rootInActiveWindow: ${t.message}")
            null
        }
    }

    protected open fun safeInteractiveWindows(): List<AccessibilityWindowInfo> {
        return try {
            windows ?: emptyList()
        } catch (t: Throwable) {
            safeLogW(TAG, "Unable to acquire windows list: ${t.message}")
            emptyList()
        }
    }

    private fun safeRecycleRoot(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun isBettingOrBrowserPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.US)
        return lower.contains("betpawa") ||
            lower.contains("sportybet") ||
            lower.contains("betway") ||
            lower.contains("1xbet") ||
            lower.contains("msport") ||
            lower.contains("betking") ||
            lower.contains("parimatch") ||
            lower.contains("aviator") ||
            lower.contains("spribe") ||
            lower.contains("chrome") ||
            lower.contains("chromium") ||
            lower.contains("browser") ||
            lower.contains("sbrowser") ||
            lower.contains("webview") ||
            lower.contains("firefox") ||
            lower.contains("opera") ||
            lower.contains("emmx") ||
            lower.contains("brave") ||
            lower.contains("kiwi") ||
            lower.contains("duckduckgo") ||
            lower.contains("vivaldi") ||
            lower.contains("yandex") ||
            lower.contains("ucmobile")
    }

    /**
     * Resolves the best Accessibility root node across single-window OR split-screen/multi-window
     * modes (`AccessibilityService.windows`), skipping Screen Earner's own overlay windows.
     */
    protected open fun resolveBestTargetWindowRoot(): Pair<AccessibilityNodeInfo, Boolean>? {
        val ownPkg = runCatching { applicationContext?.packageName }.getOrNull().orEmpty()
        val activeRoot = safeRootInActiveWindow()
        val activePkg = activeRoot?.let { safeNodePackageName(it) }.orEmpty()
        val activeIsOwn = ownPkg.isNotBlank() && activePkg == ownPkg
        val activeIsSystemUi = activePkg == "com.android.systemui"

        if (activeRoot != null && !activeIsOwn && isBettingOrBrowserPackage(activePkg)) {
            return activeRoot to false
        }

        // Scan interactive windows to locate background/split-screen browser or betting window
        val winList = safeInteractiveWindows()
        var nonSelfFallback: AccessibilityNodeInfo? = null
        for (i in winList.indices.reversed()) {
            val winRoot = try { winList[i].root } catch (_: Throwable) { null } ?: continue
            val pkg = safeNodePackageName(winRoot)
            if (ownPkg.isNotBlank() && pkg == ownPkg) {
                safeRecycleRoot(winRoot)
                continue
            }
            if (isBettingOrBrowserPackage(pkg)) {
                safeRecycleRoot(nonSelfFallback)
                return winRoot to true
            }
            if (pkg != "com.android.systemui" && nonSelfFallback == null) {
                nonSelfFallback = winRoot
            } else {
                safeRecycleRoot(winRoot)
            }
        }

        if (nonSelfFallback != null) {
            return nonSelfFallback to true
        }
        return if (activeRoot != null && !activeIsOwn && !activeIsSystemUi) {
            activeRoot to false
        } else {
            null
        }
    }

    /**
     * Immediately dumps the active window's accessibility node hierarchy to Logcat regardless of throttle.
     */
    fun forceDumpNodeHierarchy(): NodeHierarchyReport? {
        return try {
            val rootNode = safeRootInActiveWindow() ?: return null
            val pkg = safeNodePackageName(rootNode)
            logNodeHierarchyNow(
                rootNode = rootNode,
                packageName = pkg,
                eventLabel = "MANUAL_DUMP",
                currentTimeMs = System.currentTimeMillis()
            )
        } catch (t: Throwable) {
            safeLogE(TAG, "forceDumpNodeHierarchy failed", t)
            null
        }
    }

    protected open fun childNodesOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val count = try {
            node.childCount.coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
        if (count == 0) return emptyList()
        val list = ArrayList<AccessibilityNodeInfo>(count)
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            if (child != null) {
                list.add(child)
            }
        }
        return list
    }

    protected fun safeRecycleNode(node: AccessibilityNodeInfo?, rootNode: AccessibilityNodeInfo) {
        if (node == null || node === rootNode) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            try {
                @Suppress("DEPRECATION")
                node.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun safeNodePackageName(node: AccessibilityNodeInfo): String {
        return try {
            node.packageName?.toString().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    internal fun maybeLogNodeHierarchy(
        rootNode: AccessibilityNodeInfo,
        event: AccessibilityEvent?,
        currentTimeMs: Long = System.currentTimeMillis(),
        skipOwnPackage: Boolean = true
    ): Boolean {
        if (!_nodeHierarchyLoggingEnabled.value) return false

        val pkg = safeNodePackageName(rootNode).ifBlank {
            try {
                event?.packageName?.toString().orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }
        val ownPkg = runCatching { applicationContext?.packageName }.getOrNull()
        if (skipOwnPackage && !ownPkg.isNullOrBlank() && pkg == ownPkg) {
            return false
        }

        val eventType = try {
            event?.eventType ?: AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } catch (_: Throwable) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        val eventLabel = try {
            AccessibilityEvent.eventTypeToString(eventType)
        } catch (_: Throwable) {
            eventType.toString()
        }
        val report = buildNodeHierarchyReport(
            rootNode = rootNode,
            packageName = pkg,
            eventLabel = eventLabel,
            timestampMs = currentTimeMs
        )
        val signature = computeStructuralSignature(report)
        val elapsed = (currentTimeMs - lastHierarchyLogTimeMs).coerceAtLeast(0L)
        val isWindowChange = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        val shouldEmit = when {
            lastHierarchyLogTimeMs == 0L -> true
            isWindowChange && signature != lastHierarchySignature && elapsed >= 250L -> true
            elapsed >= hierarchyLogThrottleMs && signature != lastHierarchySignature -> true
            elapsed >= FORCE_HIERARCHY_REFRESH_MS -> true
            else -> false
        }

        if (!shouldEmit) return false

        lastHierarchyLogTimeMs = currentTimeMs
        lastHierarchySignature = signature
        emitReportToLogcat(report)
        return true
    }

    fun logNodeHierarchyNow(
        rootNode: AccessibilityNodeInfo,
        packageName: String = safeNodePackageName(rootNode),
        eventLabel: String = "DIRECT",
        currentTimeMs: Long = System.currentTimeMillis()
    ): NodeHierarchyReport {
        val report = buildNodeHierarchyReport(
            rootNode = rootNode,
            packageName = packageName,
            eventLabel = eventLabel,
            timestampMs = currentTimeMs
        )
        lastHierarchyLogTimeMs = currentTimeMs
        lastHierarchySignature = computeStructuralSignature(report)
        emitReportToLogcat(report)
        return report
    }

    fun buildNodeHierarchyReport(
        rootNode: AccessibilityNodeInfo,
        packageName: String = safeNodePackageName(rootNode),
        eventLabel: String = "WINDOW_SCAN",
        timestampMs: Long = System.currentTimeMillis(),
        maxDepth: Int = MAX_TREE_DEPTH,
        maxNodes: Int = MAX_LOGGED_NODES
    ): NodeHierarchyReport {
        val lines = mutableListOf<String>()
        val candidates = mutableListOf<HierarchyCandidate>()
        var visited = 0
        var truncated = false

        lines += "================ A11Y NODE TREE START ================"
        lines += "pkg=$packageName | event=$eventLabel | ts=$timestampMs"

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= maxNodes || depth > maxDepth) {
                truncated = true
                return
            }
            visited++

            val text = sanitizeForLog(try { node.text?.toString() } catch (_: Throwable) { null })
            val desc = sanitizeForLog(try { node.contentDescription?.toString() } catch (_: Throwable) { null })
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sanitizeForLog(try { node.hintText?.toString() } catch (_: Throwable) { null })
            } else ""
            val viewId = try { node.viewIdResourceName?.trim().orEmpty() } catch (_: Throwable) { "" }
            val fullClass = try { node.className?.toString().orEmpty() } catch (_: Throwable) { "" }
            val shortClass = fullClass.substringAfterLast('.').ifBlank { "Node" }

            val rect = Rect()
            try {
                node.getBoundsInScreen(rect)
            } catch (_: Throwable) {
            }

            val isEditable = try { node.isEditable } catch (_: Throwable) { false }
            val isClickable = try { node.isClickable } catch (_: Throwable) { false }
            val isFocusable = try { node.isFocusable } catch (_: Throwable) { false }
            val isVisible = try { node.isVisibleToUser } catch (_: Throwable) { true }

            val flags = buildList {
                if (isEditable || fullClass.contains("EditText", ignoreCase = true)) add("EDITABLE")
                if (isClickable) add("CLICKABLE")
                if (isFocusable) add("FOCUSABLE")
                if (isVisible) add("VISIBLE")
            }

            val candidateRole = detectCandidateRole(
                className = fullClass,
                viewId = viewId,
                text = text,
                desc = desc,
                hint = hint,
                isEditableNode = isEditable
            )

            if (candidateRole != null) {
                candidates += HierarchyCandidate(
                    role = candidateRole,
                    resourceId = viewId.ifBlank { "<none>" },
                    className = shortClass,
                    text = text,
                    contentDescription = desc,
                    bounds = Rect(rect)
                )
            }

            val indent = "  ".repeat(depth.coerceAtMost(20))
            val parts = mutableListOf<String>()
            parts += "$indent[d=$depth] $shortClass"
            parts += "id=${viewId.ifBlank { "<none>" }}"
            if (text.isNotEmpty()) parts += "text=\"$text\""
            if (desc.isNotEmpty()) parts += "desc=\"$desc\""
            if (hint.isNotEmpty()) parts += "hint=\"$hint\""
            if (flags.isNotEmpty()) parts += "flags=${flags.joinToString(",")}"
            parts += "bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]"
            if (candidateRole != null) parts += "★CANDIDATE:$candidateRole★"

            lines += parts.joinToString(" | ")

            val children = childNodesOf(node)
            for (child in children) {
                if (visited >= maxNodes) {
                    truncated = true
                } else {
                    traverse(child, depth + 1)
                }
                safeRecycleNode(child, rootNode)
            }
        }

        traverse(rootNode, 0)

        lines += "--- CANDIDATE RESOURCE IDs (${candidates.size} found) ---"
        if (candidates.isEmpty()) {
            lines += "  (no stake input or Place Bet candidates detected in current window)"
        } else {
            candidates.forEach { c ->
                lines += "  [${c.role}] id=${c.resourceId} | class=${c.className} | text=\"${c.text}\" | desc=\"${c.contentDescription}\" | bounds=[${c.bounds.left},${c.bounds.top},${c.bounds.right},${c.bounds.bottom}]"
            }
        }
        if (truncated) {
            lines += "  [NOTE: hierarchy truncated at $visited nodes / maxDepth=$maxDepth]"
        }
        lines += "================ A11Y NODE TREE END ($visited nodes) ================"

        return NodeHierarchyReport(
            packageName = packageName,
            timestampMs = timestampMs,
            totalNodesVisited = visited,
            truncated = truncated,
            lines = lines,
            candidates = candidates
        )
    }

    private fun detectCandidateRole(
        className: String,
        viewId: String,
        text: String,
        desc: String,
        hint: String,
        isEditableNode: Boolean
    ): String? {
        val label = listOf(text, desc, hint).firstOrNull { it.isNotBlank() }.orEmpty()
        val lowerLabel = label.lowercase(Locale.US)
        val lowerId = viewId.lowercase(Locale.US)
        val lowerMeta = "$lowerId ${hint.lowercase(Locale.US)} ${desc.lowercase(Locale.US)} ${text.lowercase(Locale.US)}"

        val isEditWidget = isEditableNode ||
            className.contains("EditText", ignoreCase = true) ||
            className.contains("TextInput", ignoreCase = true)

        if (isEditWidget) {
            return if (lowerMeta.contains("stake") ||
                lowerMeta.contains("amount") ||
                lowerMeta.contains("wager") ||
                lowerMeta.contains("bet") ||
                text.matches(Regex("^\\d+(\\.\\d{1,2})?$"))
            ) {
                "STAKE_INPUT"
            } else {
                "EDITABLE_INPUT"
            }
        }

        if ((lowerMeta.contains("stake") || lowerMeta.contains("wager") || lowerId.contains("bet_amount")) &&
            lowerMeta.contains("input")
        ) {
            return "STAKE_INPUT"
        }

        if (lowerLabel == "cancel" || lowerLabel == "cancel bet" || lowerId.contains("cancel_bet")) {
            return "CANCEL_BET_BTN"
        }

        if (lowerLabel == "bet" ||
            lowerLabel == "place bet" ||
            lowerId.contains("place_bet") ||
            lowerId.contains("btn_bet") ||
            lowerId.contains("bet_button") ||
            (lowerLabel.contains("bet") &&
                !lowerLabel.contains("cancel") &&
                !lowerLabel.contains("history") &&
                !lowerLabel.contains("my bets") &&
                !lowerLabel.contains("betslip") &&
                !lowerLabel.contains("waiting") &&
                !lowerLabel.contains("auto"))
        ) {
            return "PLACE_BET_BTN"
        }

        if ((lowerLabel.contains("cash out") || lowerLabel.contains("cashout") || lowerId.contains("cashout")) &&
            !lowerLabel.contains("auto")
        ) {
            return "CASHOUT_BTN"
        }

        if (lowerLabel == "2x" || lowerLabel == "x2" || lowerLabel == "×2" || lowerLabel == "double" ||
            lowerId.contains("double") || lowerId.contains("btn_2x")
        ) {
            return "DOUBLE_2X_BTN"
        }

        if (lowerLabel == "+" || lowerLabel == "plus" || lowerLabel == "increase" ||
            lowerLabel.matches(Regex("^\\+\\d+$")) || lowerId.contains("plus") || lowerId.contains("increment")
        ) {
            return "ADD_PLUS_BTN"
        }

        return null
    }

    private fun emitReportToLogcat(report: NodeHierarchyReport) {
        _lastLoggedHierarchy.value = report.toFormattedText()
        // Emit line-by-line so Android's 4KB per-message Logcat cap never truncates WebView trees
        report.lines.forEach { line ->
            safeLogD(NODE_HIERARCHY_TAG, line)
        }
    }

    private fun computeStructuralSignature(report: NodeHierarchyReport): Int {
        // Ignore the timestamp header line (index 1 contains ts=...) when comparing UI structure
        var hash = report.packageName.hashCode()
        for (i in 2 until report.lines.size) {
            hash = 31 * hash + report.lines[i].hashCode()
        }
        return hash
    }

    private fun sanitizeForLog(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val collapsed = raw.trim().replace("\n", "\\n").replace("\r", "")
        return if (collapsed.length > 64) collapsed.take(61) + "..." else collapsed
    }

    protected fun scanWindowTargets(rootNode: AccessibilityNodeInfo): DetectedUiTargets {
        val rootRect = Rect().also {
            try {
                rootNode.getBoundsInScreen(it)
            } catch (_: Throwable) {
            }
        }
        val multiplierCandidates = mutableListOf<MultiplierCandidate>()
        val pageHints = linkedSetOf<String>()
        val rawTitles = linkedSetOf<String>()
        var detectedUrlBar: String? = null
        var fallbackDetectedUrl: String? = null
        var detectedRoundId: String? = null
        val ownPkg = runCatching { applicationContext?.packageName }.getOrNull().orEmpty()

        var flewAwayDetected = false
        var waitingNextRoundDetected = false
        var bettingOpenPromptDetected = false
        var hasExpandableHistoryControl = false
        var hasScrollableHistoryContainer = false
        var stakeBounds: Rect? = null
        var stakeText: String? = null
        var detectedBalance: Double? = null
        var detectedCurrency: String? = null
        var doubleBounds: Rect? = null
        var addBounds: Rect? = null
        var betBounds: Rect? = null
        var betEnabled = false
        var betClickable = false
        var bestBetScore = -1
        var cancelBounds: Rect? = null
        var cancelEnabled = false
        var cashOutBounds: Rect? = null
        var cashOutEnabled = false
        var cashOutClickable = false
        var bestCashOutScore = -1
        var autoCashOutToggleBounds: Rect? = null
        var autoCashOutToggleChecked: Boolean? = null
        var autoCashOutInputBounds: Rect? = null
        var autoCashOutText: String? = null
        var autoCashOutValue: Double? = null
        var countdownRemainingMs: Long? = null
        var stakeEditable = true
        val scanTimeMs = System.currentTimeMillis()
        val rootPackage = safeNodePackageName(rootNode)
        var scannedCount = 0
        var orderCounter = 0
        var acoLabelBounds: Rect? = null
        var autoTabBounds: Rect? = null
        val toggleCandidates = mutableListOf<RowControl>()
        val editableCandidates = mutableListOf<RowControl>()

        data class ClickableAncestorInfo(
            val rect: Rect,
            val isEnabled: Boolean,
            val isBetWrapperEligible: Boolean,
            val isCashOutWrapperEligible: Boolean
        )
        val ancestorStack = mutableListOf<ClickableAncestorInfo>()

        fun traverse(node: AccessibilityNodeInfo, depth: Int, inHistoryAncestry: Boolean, inNonLiveMultiplierContainer: Boolean) {
            if (scannedCount >= MAX_SCAN_NODES || depth > MAX_TREE_DEPTH) return
            val nodePkg = safeNodePackageName(node)
            if (ownPkg.isNotBlank() && nodePkg == ownPkg) return
            scannedCount++

            val text = try { node.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            val desc = try { node.contentDescription?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { node.hintText?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
            } else ""
            val viewId = try { node.viewIdResourceName?.trim().orEmpty() } catch (_: Throwable) { "" }
            val className = try { node.className?.toString().orEmpty() } catch (_: Throwable) { "" }
            val isEditableField = (try { node.isEditable } catch (_: Throwable) { false }) ||
                className.contains("EditText", ignoreCase = true)
            val isScrollable = try { node.isScrollable } catch (_: Throwable) { false }
            val isClickable = try { node.isClickable } catch (_: Throwable) { false }

            val combinedLabel = listOf(text, desc, hint).firstOrNull { it.isNotBlank() } ?: ""
            val lowerLabel = combinedLabel.lowercase(Locale.US)
            val lowerId = viewId.lowercase(Locale.US)
            val lowerAllText = "${text.lowercase(Locale.US)} ${desc.lowercase(Locale.US)} ${hint.lowercase(Locale.US)} $lowerId"

            val nodeInHistoryContainer = inHistoryAncestry ||
                lowerId.contains("history") ||
                lowerId.contains("payouts") ||
                lowerId.contains("ribbon") ||
                lowerId.contains("prev_rounds") ||
                lowerId.contains("last_results") ||
                lowerLabel.contains("round history") ||
                lowerLabel.contains("last results")

            val nodeInNonLiveMultiplierContainer = inNonLiveMultiplierContainer ||
                lowerId.contains("all_bets") ||
                lowerId.contains("my_bets") ||
                lowerId.contains("top_bets") ||
                lowerId.contains("live_bets") ||
                lowerId.contains("bets_list") ||
                lowerId.contains("bet_list") ||
                lowerId.contains("players") ||
                lowerId.contains("leaderboard") ||
                lowerId.contains("winners") ||
                lowerId.contains("auto_cash") ||
                lowerId.contains("autocash") ||
                lowerId.contains("auto_bet") ||
                lowerId.contains("autobet") ||
                lowerId.contains("previous_hand") ||
                lowerLabel == "all bets" ||
                lowerLabel == "my bets" ||
                lowerLabel == "top bets" ||
                lowerLabel == "previous hand" ||
                lowerLabel.contains("auto cash") ||
                lowerLabel.contains("autocash") ||
                lowerLabel.contains("auto-cash") ||
                lowerLabel.contains("auto bet")

            if (isScrollable && nodeInHistoryContainer) {
                hasScrollableHistoryContainer = true
            }
            if ((isClickable || lowerId.contains("history")) &&
                (lowerLabel.contains("round history") ||
                    lowerLabel == "history" ||
                    lowerId.contains("history_toggle") ||
                    lowerId.contains("expand_history") ||
                    lowerId.contains("btn_history"))
            ) {
                hasExpandableHistoryControl = true
            }

            // 1. Browser address bar / URL inspection (Chrome url_bar, omnibox, EditText, or URL text)
            val isAddressBarNode = lowerId.contains("url_bar") ||
                lowerId.contains("location_bar") ||
                lowerId.contains("omnibox") ||
                lowerId.contains("address_bar")

            if (isAddressBarNode && combinedLabel.isNotBlank() && detectedUrlBar == null) {
                detectedUrlBar = combinedLabel
            } else if ((isEditableField || className.contains("TextView", ignoreCase = true) || className.contains("View", ignoreCase = true)) &&
                combinedLabel.isNotBlank() &&
                fallbackDetectedUrl == null
            ) {
                val urlMatcher = URL_HINT_PATTERN.matcher(combinedLabel)
                if (urlMatcher.find()) {
                    fallbackDetectedUrl = urlMatcher.group(0)
                }
            }

            // Collect site/game branding keywords dynamically across supported crash games
            if (lowerAllText.contains("betpawa")) pageHints.add("betpawa")
            if (lowerAllText.contains("sportybet")) pageHints.add("sportybet")
            if (lowerAllText.contains("aviator")) pageHints.add("aviator")
            if (lowerAllText.contains("spribe")) pageHints.add("spribe")
            if (lowerAllText.contains("jetx")) pageHints.add("jetx")
            if (lowerAllText.contains("smartsoft")) pageHints.add("smartsoft")
            if (lowerAllText.contains("spaceman")) pageHints.add("spaceman")
            if (lowerAllText.contains("pragmatic")) pageHints.add("pragmatic")
            if (!isAddressBarNode && combinedLabel.length in 3..28 &&
                !combinedLabel.contains("http", ignoreCase = true) &&
                !MULTIPLIER_PATTERN.matcher(combinedLabel).find() &&
                rawTitles.size < 12
            ) {
                rawTitles.add(combinedLabel)
            }

            // 2. Multiplier candidates + optional accessible game timestamp / round ID + status text
            if (combinedLabel.isNotBlank()) {
                val parsedGameTs = com.example.data.engine.RibbonHistoryCollector.parseGameTimestampFromText(combinedLabel)
                val parsedRoundId = com.example.data.engine.RibbonHistoryCollector.parseRoundIdFromText(combinedLabel)
                if (!parsedRoundId.isNullOrBlank() && !nodeInHistoryContainer && detectedRoundId == null) {
                    detectedRoundId = parsedRoundId
                }
                val isExcludedMultiplierText = lowerLabel.contains("auto") ||
                    lowerLabel.contains("target") ||
                    lowerLabel.contains("cash out") ||
                    lowerLabel.contains("cashout") ||
                    lowerLabel.contains("payout") ||
                    lowerLabel.contains("won") ||
                    lowerLabel.contains("win") ||
                    lowerLabel.contains("tzs") ||
                    lowerLabel.contains("zmw") ||
                    lowerLabel.contains("kes") ||
                    lowerLabel.contains("ngn") ||
                    lowerLabel.contains("ghs") ||
                    lowerLabel.contains("usd") ||
                    lowerLabel.contains("ugx")
                val allowCandidate = nodeInHistoryContainer || (!nodeInNonLiveMultiplierContainer && !isExcludedMultiplierText && !isAddressBarNode)
                if (allowCandidate) {
                    val matcher = MULTIPLIER_PATTERN.matcher(combinedLabel)
                    while (matcher.find()) {
                        val rawToken = matcher.group(0)?.trim().orEmpty()
                        val valParsed = matcher.group(1)?.toDoubleOrNull()
                        if (valParsed != null) {
                            val nodeBounds = Rect()
                            try {
                                node.getBoundsInScreen(nodeBounds)
                            } catch (_: Throwable) {
                            }
                            multiplierCandidates += MultiplierCandidate(
                                value = valParsed,
                                bounds = nodeBounds,
                                orderIndex = orderCounter++,
                                isHistoryHinted = nodeInHistoryContainer,
                                gameTimestampMs = parsedGameTs,
                                roundId = parsedRoundId,
                                displayText = rawToken
                            )
                        }
                    }
                }

                if (countdownRemainingMs == null) {
                    countdownRemainingMs = ScreenOcrAnalyzer.parseCountdownMsFromText(combinedLabel)
                }

                if (lowerLabel.contains("flew away") ||
                    lowerLabel.contains("crashed") ||
                    lowerLabel.contains("busted")
                ) {
                    flewAwayDetected = true
                }

                if (lowerLabel.contains("waiting for next round") ||
                    lowerLabel.contains("wait for next round") ||
                    lowerLabel.contains("next round")
                ) {
                    waitingNextRoundDetected = true
                }
                if (lowerLabel.contains("place your bet") ||
                    lowerLabel.contains("betting open")
                ) {
                    bettingOpenPromptDetected = true
                }
            }

            // 2.5. Parse live account balance + currency from top header / wallet nodes
            if (combinedLabel.isNotBlank() && !isAddressBarNode && !isEditableField && detectedBalance == null) {
                val nodeRect = Rect().also { r -> try { node.getBoundsInScreen(r) } catch (_: Throwable) {} }
                val balMatch = parseBalanceCandidate(
                    text = combinedLabel,
                    lowerId = lowerId,
                    bounds = nodeRect,
                    viewportHeightPx = if (!rootRect.isEmpty) rootRect.height() else 0
                )
                if (balMatch != null) {
                    detectedBalance = balMatch.first
                    if (!balMatch.second.isNullOrBlank()) {
                        detectedCurrency = balMatch.second
                    }
                }
            }

            // 2.8 Detect Auto Cash Out toggle & multiplier input inside auto container or explicit labels
            val isCheckable = try { node.isCheckable } catch (_: Throwable) { false }
            val isChecked = try { node.isChecked } catch (_: Throwable) { false }
            val inAutoCashContext = nodeInNonLiveMultiplierContainer && (
                lowerId.contains("auto") ||
                    lowerAllText.contains("auto cash") ||
                    lowerAllText.contains("autocash") ||
                    lowerAllText.contains("auto-cash") ||
                    lowerAllText.contains("auto collect")
                )
            if (!isAddressBarNode && (inAutoCashContext || lowerLabel.contains("auto cash") || lowerLabel.contains("autocash") || lowerLabel.contains("auto-cash"))) {
                if (autoCashOutToggleBounds == null && (isCheckable || className.contains("Switch", true) || className.contains("CheckBox", true) || (isClickable && !isEditableField))) {
                    val rect = Rect()
                    try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                    if (!rect.isEmpty) {
                        autoCashOutToggleBounds = rect
                        autoCashOutToggleChecked = isChecked
                    }
                }
                if (isEditableField && autoCashOutInputBounds == null) {
                    val rect = Rect()
                    try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                    if (!rect.isEmpty) {
                        autoCashOutInputBounds = rect
                        autoCashOutText = text.ifBlank { hint }
                        autoCashOutValue = ScreenOcrAnalyzer.parseNumericAutoTargetToken(autoCashOutText.orEmpty())
                    }
                }
            }

            // 3. Detect Stake Input Field (strictly exclude URL bar and Auto Cash Out fields!)
            val isAutoField = inAutoCashContext ||
                lowerId.contains("auto_cash") ||
                lowerId.contains("autocash") ||
                lowerAllText.contains("auto cash") ||
                lowerAllText.contains("autocash")
            if (isEditableField && !isAddressBarNode && !isAutoField) {
                val metadata = "$viewId $hint $desc $text".lowercase(Locale.US)
                val looksLikeStake = metadata.contains("stake") ||
                    metadata.contains("amount") ||
                    metadata.contains("wager") ||
                    metadata.contains("bet") ||
                    (text.matches(Regex("^\\d+(\\.\\d{1,2})?$")) && !text.endsWith("x", ignoreCase = true))
                if (looksLikeStake && stakeBounds == null) {
                    val rect = Rect()
                    try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                    if (!rect.isEmpty) {
                        stakeBounds = rect
                        stakeText = text.ifBlank { hint }
                        stakeEditable = try { node.isEnabled && (node.isEditable || className.contains("EditText", true)) } catch (_: Throwable) { true }
                    }
                }
            }

            // 4. Detect 2x / Double button
            if (doubleBounds == null && (
                    lowerLabel == "2x" ||
                        lowerLabel == "x2" ||
                        lowerLabel == "×2" ||
                        lowerLabel == "double" ||
                        lowerId.contains("double") ||
                        lowerId.contains("btn_2x")
                    )
            ) {
                val rect = Rect()
                try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                if (!rect.isEmpty) doubleBounds = rect
            }

            // 5. Detect + / Add increment button
            if (addBounds == null && (
                    lowerLabel == "+" ||
                        lowerLabel == "plus" ||
                        lowerLabel == "increase" ||
                        lowerLabel.matches(Regex("^\\+\\d+$")) ||
                        lowerId.contains("plus") ||
                        lowerId.contains("increment")
                    )
            ) {
                val rect = Rect()
                try { node.getBoundsInScreen(rect) } catch (_: Throwable) {}
                if (!rect.isEmpty) addBounds = rect
            }

            val isNodeEnabled = try { node.isEnabled } catch (_: Throwable) { true }

            val nodeRect = Rect().also { r -> try { node.getBoundsInScreen(r) } catch (_: Throwable) {} }
            val containerSummary = if (
                isClickable &&
                node !== rootNode &&
                !isEditableField &&
                !isAddressBarNode &&
                !nodeInHistoryContainer &&
                !nodeInNonLiveMultiplierContainer &&
                !isContainerTooLargeForButton(nodeRect, rootRect, className, isScrollable)
            ) {
                collectButtonContainerSummary(node, rootRect)
            } else null

            // 6. Detect CANCEL / BET QUEUED button ("Cancel" / "Cancel Bet")
            val cancelContainerLabel = containerSummary?.aggregatedLowerText.orEmpty()
            val isCancelDirect = !isAddressBarNode && (
                lowerLabel == "cancel" ||
                    lowerLabel == "cancel bet" ||
                    lowerId.contains("cancel_bet") ||
                    (lowerLabel.startsWith("cancel") && !lowerLabel.contains("auto"))
                )
            val isCancelContainer = !isAddressBarNode && containerSummary != null && (
                cancelContainerLabel == "cancel" ||
                    cancelContainerLabel == "cancel bet" ||
                    (cancelContainerLabel.startsWith("cancel") && !cancelContainerLabel.contains("auto"))
                )
            if (cancelBounds == null && (isCancelDirect || isCancelContainer)) {
                val rect = nodeRect
                if (!rect.isEmpty) {
                    cancelBounds = rect
                    cancelEnabled = isNodeEnabled
                }
            }

            // 7. Detect BET / Place Bet button (strictly exclude 2x/Double, Cancel, Auto Cash Out, Cash Out)
            // Supports both flat Button nodes and nested WebView containers (clickable parent wrapping smaller text child(ren)).
            val isExcludedBetNode = isAddressBarNode ||
                nodeInNonLiveMultiplierContainer ||
                isEditableField ||
                lowerAllText.contains("betpawa") ||
                lowerAllText.contains("sportybet") ||
                lowerAllText.contains("all bets") ||
                lowerAllText.contains("my bets") ||
                lowerAllText.contains("top bets") ||
                lowerAllText.contains("bet history") ||
                lowerAllText.contains("betslip") ||
                lowerLabel.contains("cancel") ||
                lowerLabel.contains("history") ||
                lowerLabel.contains("waiting") ||
                lowerLabel.contains("cash") ||
                lowerLabel.contains("auto") ||
                lowerLabel.contains("place your bet") ||
                lowerLabel == "2x" ||
                lowerLabel == "x2" ||
                lowerLabel == "×2" ||
                lowerLabel.contains("double")
            if (!isExcludedBetNode && !nodeRect.isEmpty) {
                val rootWidthPx = if (!rootRect.isEmpty) rootRect.width() else 0
                val selfButtonEligible = isClickable && !isContainerTooLargeForButton(nodeRect, rootRect, className, isScrollable)
                val enclosingAncestor = if (!selfButtonEligible) {
                    ancestorStack.lastOrNull { it.isBetWrapperEligible }
                } else null
                val effectiveRect = when {
                    selfButtonEligible -> nodeRect
                    enclosingAncestor != null -> enclosingAncestor.rect
                    else -> nodeRect
                }
                val effectiveEnabled = when {
                    selfButtonEligible -> isNodeEnabled
                    enclosingAncestor != null -> enclosingAncestor.isEnabled
                    else -> isNodeEnabled
                }
                val effectiveClickable = selfButtonEligible || enclosingAncestor != null
                val isWideLikeButton = rootWidthPx > 0 && effectiveRect.width() >= (rootWidthPx * BET_BUTTON_MIN_WIDTH_FRACTION)

                val directPriority = when {
                    BET_BUTTON_WITH_AMOUNT.matches(lowerLabel) -> 4
                    lowerLabel == "place bet" -> 3
                    lowerId.contains("place_bet") || lowerId.contains("btn_bet") || lowerId.contains("bet_button") -> 2
                    lowerLabel == "bet" && effectiveClickable && isWideLikeButton -> 2
                    else -> 0
                }
                val containerPriority = if (containerSummary != null && !containerSummary.hasExcludedBetTerms) {
                    val agg = containerSummary.aggregatedLowerText
                    when {
                        BET_BUTTON_WITH_AMOUNT.matches(agg) -> 4
                        agg == "place bet" -> 3
                        agg == "bet" && isWideLikeButton -> 2
                        else -> 0
                    }
                } else 0

                val baseBetPriority = maxOf(directPriority, containerPriority)
                if (baseBetPriority > 0) {
                    val weightedScore = baseBetPriority * 10 + (if (effectiveClickable) 1 else 0)
                    if (weightedScore > bestBetScore) {
                        betBounds = Rect(effectiveRect)
                        betEnabled = effectiveEnabled
                        betClickable = effectiveClickable
                        bestBetScore = weightedScore
                    }
                }
            }

            // 8. Detect CASH OUT button ("Cash Out" / "Cashout", excluding Auto Cash Out controls & history)
            val isExcludedCashOutNode = isAddressBarNode ||
                nodeInNonLiveMultiplierContainer ||
                isEditableField ||
                lowerLabel.contains("auto") ||
                lowerLabel.contains("history") ||
                lowerLabel.contains("cashback")
            if (!isExcludedCashOutNode && !nodeRect.isEmpty) {
                val selfCashOutEligible = isClickable && !isContainerTooLargeForButton(nodeRect, rootRect, className, isScrollable)
                val enclosingCashOutAncestor = if (!selfCashOutEligible) {
                    ancestorStack.lastOrNull { it.isCashOutWrapperEligible }
                } else null
                val effectiveRect = when {
                    selfCashOutEligible -> nodeRect
                    enclosingCashOutAncestor != null -> enclosingCashOutAncestor.rect
                    else -> nodeRect
                }
                val effectiveEnabled = when {
                    selfCashOutEligible -> isNodeEnabled
                    enclosingCashOutAncestor != null -> enclosingCashOutAncestor.isEnabled
                    else -> isNodeEnabled
                }
                val effectiveClickable = selfCashOutEligible || enclosingCashOutAncestor != null

                val directCashOutScore = when {
                    (lowerLabel.contains("cash out") || lowerLabel.contains("cashout")) &&
                        lowerLabel.any { it.isDigit() } -> 4
                    lowerLabel == "cash out" || lowerLabel == "cashout" || lowerId.contains("cashout") -> 3
                    lowerLabel.contains("cash out") || lowerLabel.contains("cashout") -> 2
                    else -> 0
                }
                val containerCashOutScore = if (containerSummary != null && !containerSummary.hasExcludedCashOutTerms) {
                    val agg = containerSummary.aggregatedLowerText
                    when {
                        (agg.contains("cash out") || agg.contains("cashout")) && agg.any { it.isDigit() } -> 4
                        agg == "cash out" || agg == "cashout" -> 3
                        agg.contains("cash out") || agg.contains("cashout") -> 2
                        else -> 0
                    }
                } else 0
                val baseCashOutPriority = maxOf(directCashOutScore, containerCashOutScore)
                if (baseCashOutPriority > 0) {
                    val weightedScore = baseCashOutPriority * 10 + (if (effectiveClickable) 1 else 0)
                    if (weightedScore > bestCashOutScore) {
                        cashOutBounds = Rect(effectiveRect)
                        cashOutEnabled = effectiveEnabled
                        cashOutClickable = effectiveClickable
                        bestCashOutScore = weightedScore
                    }
                }
            }

            // 9. Row-based collection for the game's own Auto Cash Out controls. In Aviator the words "Auto Cash Out"
            // are a SEPARATE text node from the switch and the value box, so they are matched by geometry (same row,
            // to the right of the label) after the traversal instead of by ancestor text.
            if (!isAddressBarNode && combinedLabel.isNotBlank()) {
                val rowRect = Rect()
                try { node.getBoundsInScreen(rowRect) } catch (_: Throwable) {}
                if (!rowRect.isEmpty) {
                    val labelCompact = lowerLabel.replace("\n", " ").trim()
                    if (acoLabelBounds == null && !isEditableField && (
                            labelCompact.startsWith("auto cash") || labelCompact == "autocash" || labelCompact == "auto-cash out"
                            ) && labelCompact.length <= 24
                    ) {
                        acoLabelBounds = rowRect
                    }
                    if (autoTabBounds == null && labelCompact == "auto" && isClickable &&
                        (rootRect.isEmpty || rowRect.width() < rootRect.width() * 0.5f)
                    ) {
                        autoTabBounds = rowRect
                    }
                }
            }
            run {
                val ctrlRect = Rect()
                try { node.getBoundsInScreen(ctrlRect) } catch (_: Throwable) {}
                if (!ctrlRect.isEmpty && !isAddressBarNode) {
                    val enabledNow = try { node.isEnabled } catch (_: Throwable) { true }
                    if (isEditableField) {
                        editableCandidates += RowControl(ctrlRect, null, text.ifBlank { hint }, enabledNow)
                    } else if (isCheckable || className.contains("Switch", true) || className.contains("CheckBox", true) ||
                        (isClickable && (rootRect.isEmpty || ctrlRect.width() < rootRect.width() * 0.3f))
                    ) {
                        val checkedState: Boolean? = when {
                            isCheckable -> isChecked
                            (try { node.isSelected } catch (_: Throwable) { false }) -> true
                            else -> null
                        }
                        toggleCandidates += RowControl(ctrlRect, checkedState, text.ifBlank { desc }, enabledNow)
                    }
                }
            }

            val pushedAncestor = if (containerSummary != null && !nodeRect.isEmpty) {
                ancestorStack.add(
                    ClickableAncestorInfo(
                        rect = Rect(nodeRect),
                        isEnabled = isNodeEnabled,
                        isBetWrapperEligible = !isExcludedBetNode && !containerSummary.hasExcludedBetTerms,
                        isCashOutWrapperEligible = !isExcludedCashOutNode && !containerSummary.hasExcludedCashOutTerms
                    )
                )
                true
            } else false

            val children = childNodesOf(node)
            for (child in children) {
                if (scannedCount < MAX_SCAN_NODES) {
                    traverse(child, depth + 1, nodeInHistoryContainer, nodeInNonLiveMultiplierContainer)
                }
                safeRecycleNode(child, rootNode)
            }
            if (pushedAncestor) {
                ancestorStack.removeAt(ancestorStack.lastIndex)
            }
        }

        traverse(rootNode, 0, inHistoryAncestry = false, inNonLiveMultiplierContainer = false)

        // Match the Auto Cash Out switch + value box to the "Auto Cash Out" label by row geometry.
        val acoLabel = acoLabelBounds
        if (acoLabel != null) {
            val rowTol = (acoLabel.height() * SAME_ROW_TOLERANCE).coerceAtLeast(24f)
            fun sameRowRightOfLabel(r: Rect): Boolean =
                abs((r.centerY() - acoLabel.centerY()).toFloat()) <= rowTol && r.left >= acoLabel.right - 8
            // Row geometry wins over the ancestor-text heuristic (which can latch onto the label node itself).
            toggleCandidates.filter { sameRowRightOfLabel(it.bounds) }.minByOrNull { it.bounds.left }?.let { tgl ->
                autoCashOutToggleBounds = tgl.bounds
                autoCashOutToggleChecked = tgl.checked ?: ScreenColorSampler.isSwitchOn(tgl.bounds)
            }
            editableCandidates.filter { sameRowRightOfLabel(it.bounds) }.minByOrNull { it.bounds.left }?.let { inp ->
                autoCashOutInputBounds = inp.bounds
                autoCashOutText = inp.text
                autoCashOutValue = ScreenOcrAnalyzer.parseNumericAutoTargetToken(inp.text)
            }
        }
        // Never let the Auto Cash Out value box (e.g. "2.00") be reported as the stake input.
        val acoInputRect = autoCashOutInputBounds
        if (acoInputRect != null && stakeBounds != null && Rect.intersects(stakeBounds!!, acoInputRect)) {
            val betterStake = editableCandidates.firstOrNull { !Rect.intersects(it.bounds, acoInputRect) }
            stakeBounds = betterStake?.bounds
            stakeText = betterStake?.text
            stakeEditable = betterStake?.enabled ?: true
        }

        // Stepper buttons: small clickable controls on the stake box's row, left ("-") and right ("+") of it.
        var stakeMinus: RowControl? = null
        var stakePlus: RowControl? = null
        stakeBounds?.takeIf { !it.isEmpty }?.let { stk ->
            val rootW = if (!rootRect.isEmpty) rootRect.width() else Int.MAX_VALUE
            val excludedRects = listOfNotNull(
                betBounds,
                cancelBounds,
                cashOutBounds,
                doubleBounds,
                autoCashOutToggleBounds,
                autoTabBounds
            )
            val maxGap = (stk.height() * 3).coerceAtLeast(180)
            val sameRow = toggleCandidates.filter { c ->
                val labelLower = c.text?.trim()?.lowercase(Locale.US).orEmpty()
                val isExcludedText = labelLower == "2x" || labelLower == "x2" || labelLower == "×2" ||
                    labelLower == "bet" || labelLower == "auto" || labelLower.contains("cancel") ||
                    labelLower.contains("cash") || labelLower.matches(Regex("^[0-9.,]+$"))
                val collidesExcluded = excludedRects.any { ex -> !ex.isEmpty && Rect.intersects(c.bounds, ex) }
                !isExcludedText &&
                    !collidesExcluded &&
                    c.checked == null &&
                    c.bounds.width() < rootW * 0.25f &&
                    c.bounds.height() <= (stk.height() * 2.2f).toInt().coerceAtLeast(120) &&
                    abs(c.bounds.centerY() - stk.centerY()) <= (stk.height() * 0.8f).toInt().coerceAtLeast(20) &&
                    !Rect.intersects(c.bounds, stk)
            }
            val slack = (stk.width() * 0.2f).toInt()
            stakeMinus = sameRow
                .filter { it.bounds.right <= stk.left + slack && (stk.left - it.bounds.right) <= maxGap }
                .maxByOrNull { it.bounds.right }
            stakePlus = sameRow
                .filter { it.bounds.left >= stk.right - slack && (it.bounds.left - stk.right) <= maxGap }
                .minByOrNull { it.bounds.left }
        }

        val controlTops = listOfNotNull(
            betBounds?.takeIf { !it.isEmpty }?.top,
            cashOutBounds?.takeIf { !it.isEmpty }?.top,
            cancelBounds?.takeIf { !it.isEmpty }?.top,
            stakeBounds?.takeIf { !it.isEmpty }?.top,
            doubleBounds?.takeIf { !it.isEmpty }?.top,
            addBounds?.takeIf { !it.isEmpty }?.top
        )
        val viewportHeightPx = if (!rootRect.isEmpty) rootRect.height() else 0
        val viewportFloorY = if (viewportHeightPx > 0) (viewportHeightPx * 0.72f).toInt() else Int.MAX_VALUE
        val maxStageCenterY = controlTops.minOrNull()?.takeIf { it >= 250 } ?: viewportFloorY

        val (ribbonItems, liveCenterMultiplier) = extractRibbonItemsAndCenterMultiplier(
            candidates = multiplierCandidates,
            viewportHeightPx = viewportHeightPx,
            maxCenterY = maxStageCenterY
        )
        val ribbonMultipliers = ribbonItems.map { it.multiplier }
        val isPanelExpanded = multiplierCandidates.count { it.isHistoryHinted } >= 10 ||
            ribbonItems.size >= 16 ||
            (ribbonItems.size >= 12 && hasScrollableHistoryContainer)

        val liveCenterBounds = if (liveCenterMultiplier != null) {
            multiplierCandidates
                .filter {
                    abs(it.value - liveCenterMultiplier) < 0.005 &&
                        !it.bounds.isEmpty &&
                        (maxStageCenterY >= Int.MAX_VALUE || it.bounds.centerY() < maxStageCenterY)
                }
                .maxByOrNull { it.bounds.width() * it.bounds.height() }
                ?.bounds
        } else null

        val isCancelDetected = cancelBounds != null
        val waitingDetected = waitingNextRoundDetected || bettingOpenPromptDetected
        // Phase comes from what the PLANE is doing, never from which button is visible: Aviator keeps an enabled
        // "Bet" (or "Cancel") button on screen during flight so the player can queue the NEXT round.
        val multiplierLive = liveCenterMultiplier != null && liveCenterMultiplier >= 1.00
        val phase = when {
            flewAwayDetected -> "CRASHED"
            cashOutBounds != null && liveCenterMultiplier != null -> "IN_FLIGHT"
            waitingDetected && !multiplierLive -> "BETTING_OPEN"
            multiplierLive -> "IN_FLIGHT"
            waitingDetected || isCancelDetected -> "BETTING_OPEN"
            betBounds != null -> "BETTING_OPEN"
            liveCenterMultiplier != null -> "IN_FLIGHT"
            else -> "WAITING_FOR_GAME"
        }
        // True when a BET can be queued right now (button visible + clickable + enabled, nothing queued/cash-out showing).
        val canQueueBet = betBounds != null && betClickable && betEnabled && cancelBounds == null && cashOutBounds == null

        val resolvedButtonState = when {
            flewAwayDetected -> SiteButtonState.CRASHED
            cashOutBounds != null -> SiteButtonState.CASH_OUT_AVAILABLE
            isCancelDetected -> SiteButtonState.BET_QUEUED
            phase == "BETTING_OPEN" || waitingDetected -> SiteButtonState.IDLE_READY_TO_BET
            betBounds != null && phase != "IN_FLIGHT" -> SiteButtonState.IDLE_READY_TO_BET
            betBounds != null -> SiteButtonState.IDLE_READY_TO_BET
            else -> SiteButtonState.UNKNOWN
        }

        return DetectedUiTargets(
            detectedMultiplier = liveCenterMultiplier,
            ribbonMultipliers = ribbonMultipliers,
            ribbonItems = ribbonItems,
            historyPanelExpanded = isPanelExpanded,
            hasExpandableHistoryControl = hasExpandableHistoryControl,
            hasScrollableHistoryContainer = hasScrollableHistoryContainer,
            gamePhase = phase,
            siteButtonState = resolvedButtonState,
            isFlewAwayDetected = flewAwayDetected,
            isWaitingForNextRound = waitingDetected,
            isCancelBtnDetected = isCancelDetected,
            detectedUrl = detectedUrlBar ?: fallbackDetectedUrl,
            detectedCurrentRoundId = detectedRoundId,
            pageTextHints = pageHints.toList(),
            visibleRawTitles = rawTitles.toList(),
            liveMultiplierBounds = liveCenterBounds,
            stakeInputBounds = stakeBounds,
            doubleButtonBounds = doubleBounds,
            addButtonBounds = addBounds,
            betButtonBounds = betBounds,
            cancelButtonBounds = cancelBounds,
            cashOutButtonBounds = cashOutBounds,
            detectedStakeText = stakeText,
            stakeMinusBounds = stakeMinus?.bounds,
            stakeMinusEnabled = stakeMinus?.enabled ?: true,
            stakePlusBounds = stakePlus?.bounds,
            stakePlusEnabled = stakePlus?.enabled ?: true,
            detectedBalance = detectedBalance,
            detectedCurrency = detectedCurrency,
            roundId = detectedRoundId,
            scannedAtMs = scanTimeMs,
            foregroundPackage = rootPackage,
            countdownRemainingMs = countdownRemainingMs,
            isBetBtnEnabled = betEnabled,
            isBetBtnClickable = betClickable,
            isCashOutBtnEnabled = cashOutEnabled,
            isCashOutBtnClickable = cashOutClickable,
            isCancelBtnEnabled = cancelEnabled,
            isStakeInputEditable = stakeEditable,
            autoCashOutToggleBounds = autoCashOutToggleBounds,
            isAutoCashOutToggleChecked = autoCashOutToggleChecked,
            autoCashOutInputBounds = autoCashOutInputBounds,
            detectedAutoCashOutText = autoCashOutText,
            detectedAutoCashOutValue = autoCashOutValue,
            canQueueBet = canQueueBet,
            autoCashOutTabBounds = autoTabBounds,
            isAutoCashOutRowVisible = acoLabelBounds != null
        )
    }

    /**
     * Consumes the latest StakeInstruction calculated by StrategyEngine (from Add Amount or Double Amount
     * progression rules) and passes it directly to the target game UI.
     */
    fun applyPendingStrategyStake(
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        try {
            val instruction = StrategyEngine.latestStakeInstruction.value
            applyStrategyStake(instruction, fallbackX, fallbackY, onComplete)
        } catch (t: Throwable) {
            safeLogE(TAG, "applyPendingStrategyStake failed", t)
            onComplete?.invoke(false)
        }
    }

    fun applyStrategyStake(
        instruction: StakeInstruction,
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        applyStakeToTargetApp(
            newStake = instruction.stakeAmount,
            progressionMode = instruction.progressionMode,
            fallbackX = fallbackX,
            fallbackY = fallbackY,
            onComplete = onComplete
        )
    }

    fun getLatestUiTargets(): DetectedUiTargets = _detectedTargets.value

    fun isSoftKeyboardOpen(): Boolean {
        return safeInteractiveWindows().any { win ->
            try {
                win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Dismisses the soft keyboard safely. Only sends GLOBAL_ACTION_BACK when an Android IME
     * window is actually active so it never navigates back out of the browser or overlay.
     */
    fun dismissKeyboardIfOpen(): Boolean {
        if (!isSoftKeyboardOpen()) return false
        return runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }.getOrDefault(false)
    }

    /**
     * Writes the new stake into an editable field via ACTION_SET_TEXT first (clearing existing text),
     * verifies the site accepted the exact value by immediately re-reading the field, and falls back
     * to clipboard ACTION_PASTE + re-read verification if the WebView/React input ignored ACTION_SET_TEXT.
     * Never reports success unless read-back explicitly verifies the expected stake.
     */
    protected open fun writeAndVerifyStakeNode(
        stakeNode: AccessibilityNodeInfo,
        newStake: Double,
        formatted: String,
        rootNode: AccessibilityNodeInfo
    ): Boolean {
        val originalBounds = Rect().also { runCatching { stakeNode.getBoundsInScreen(it) } }
        fun readBackStake(): String? {
            runCatching { stakeNode.refresh() }
            val direct = runCatching { stakeNode.text?.toString() }.getOrNull()
            if (!direct.isNullOrBlank()) return direct
            runCatching { rootNode.refresh() }
            // Re-find THIS field by its bounds - never fall back to "the first numeric field", which could be
            // a different control (stake vs Auto Cash Out value).
            if (originalBounds.isEmpty) return null
            return runCatching {
                findNodeAtBounds(rootNode, originalBounds) { n ->
                    n.isEditable || n.className?.toString()?.contains("EditText", true) == true
                }?.text?.toString()
            }.getOrNull()
        }

        // Attempt 1: Focus, explicitly clear existing value if present, and set new stake via ACTION_SET_TEXT
        runCatching { stakeNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val existingLen = runCatching { stakeNode.text?.length ?: 0 }.getOrDefault(0)
        if (existingLen > 0) {
            val clearArgs = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            runCatching { stakeNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs) }
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, runCatching { stakeNode.text?.length ?: 0 }.getOrDefault(0))
            }
            runCatching { stakeNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs) }
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formatted)
        }
        val setOk = runCatching { stakeNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
        val readAfterSet = readBackStake()
        if (setOk && verifyStakeAccepted(readAfterSet, newStake)) {
            safeLogI(TAG, "Stake accepted via ACTION_SET_TEXT: '$formatted' (verified='$readAfterSet')")
            return true
        }

        // Attempt 2: ClipboardManager + ACTION_PASTE fallback with strict read-back verification
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("stake", formatted))
                stakeNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val lenBeforePaste = runCatching { stakeNode.text?.length ?: 0 }.getOrDefault(0)
                if (lenBeforePaste > 0) {
                    val selArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, lenBeforePaste)
                    }
                    stakeNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                }
                val pasteOk = stakeNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                val readAfterPaste = readBackStake()
                if (pasteOk && verifyStakeAccepted(readAfterPaste, newStake)) {
                    safeLogI(TAG, "Stake accepted via Clipboard ACTION_PASTE: '$formatted' (verified='$readAfterPaste')")
                    return true
                }
            }
        } catch (t: Throwable) {
            safeLogW(TAG, "Clipboard paste fallback failed: ${t.message}")
        }

        safeLogW(TAG, "Stake verification failed for target=$newStake ('$formatted'); observed='$readAfterSet'")
        return false
    }

    /**
     * Waits until the game layout stops moving (keyboard closed, bounds identical on two consecutive scans),
     * then calls [onDone] with true. Calls it with false if the layout is still moving after [timeoutMs].
     * Stake editing opens the keyboard and scrolls the page, so the BET button moves; taps must use settled bounds.
     * Results are delivered on the main looper.
     */
    fun awaitStableBetLayout(timeoutMs: Long = 1500L, pollMs: Long = 120L, onDone: (Boolean) -> Unit) {
        val startedAt = System.currentTimeMillis()
        var lastSignature: String? = null
        val poll = object : Runnable {
            override fun run() {
                try {
                    val targets = refreshLiveTargetsNow()
                    val keyboardOpen = isSoftKeyboardOpen()
                    val signature = targets?.let {
                        "${it.betButtonBounds}|${it.cashOutButtonBounds}|${it.cancelButtonBounds}|${it.stakeInputBounds}"
                    }
                    if (!keyboardOpen && signature != null && signature == lastSignature) {
                        onDone(true)
                        return
                    }
                    lastSignature = signature
                    if (System.currentTimeMillis() - startedAt >= timeoutMs) {
                        onDone(false)
                        return
                    }
                    mainHandler.postDelayed(this, pollMs)
                } catch (t: Throwable) {
                    safeLogE(TAG, "awaitStableBetLayout failed", t)
                    onDone(false)
                }
            }
        }
        mainHandler.postDelayed(poll, pollMs)
    }

    fun refreshLiveTargetsNow(): DetectedUiTargets? {
        val pair = resolveBestTargetWindowRoot() ?: return null
        val (rootNode, fromWindowsList) = pair
        return try {
            val a11yTargets = scanWindowTargets(rootNode)
            val ocrSnapshot = ScreenOcrAnalyzer.latestOcrSnapshot.value
            val pkg = safeNodePackageName(rootNode).ifBlank { a11yTargets.foregroundPackage }
            val adapter = com.example.data.engine.GameAdapterRegistry.resolveAdapter(
                packageName = pkg,
                url = a11yTargets.detectedUrl,
                pageHints = a11yTargets.pageTextHints,
                rawTexts = a11yTargets.visibleRawTitles,
                hasMultiplierSignals = a11yTargets.detectedMultiplier != null || a11yTargets.ribbonMultipliers.isNotEmpty()
            )
            val fused = ScreenOcrAnalyzer.fuseAccessibilityAndOcr(
                a11yTargets = a11yTargets.copy(foregroundPackage = pkg),
                ocrSnapshot = ocrSnapshot,
                adapter = adapter,
                nowMs = System.currentTimeMillis()
            )
            _detectedTargets.value = fused.fusedTargets
            fused.fusedTargets
        } finally {
            if (fromWindowsList) {
                safeRecycleRoot(rootNode)
            }
        }
    }

    /**
     * Directly modifies and verifies the stake in the active game UI.
     * NEVER falls back to unchecked 2x/+ button taps or stale calibration coordinates.
     */
    fun applyStakeToTargetApp(
        newStake: Double,
        progressionMode: StakeProgressionMode,
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        try {
            val formattedLabel = CurrencyManager.formatAmount(newStake)
            emitDiagnostic("Editing & verifying stake: $formattedLabel")

            val pair = resolveBestTargetWindowRoot()
            if (pair == null) {
                safeLogW(TAG, "applyStakeToTargetApp aborted: no active target window root")
                onComplete?.invoke(false)
                return
            }

            val (rootNode, fromWindowsList) = pair
            try {
                // Keep the Auto Cash Out value box ("2.00") out of the running for the stake field.
                val acoExclusion = runCatching { scanWindowTargets(rootNode).autoCashOutInputBounds }.getOrNull()
                val stakeNode = findEditableStakeNode(rootNode, rootNode, excludeBounds = acoExclusion)
                if (stakeNode == null) {
                    safeLogW(TAG, "applyStakeToTargetApp aborted: editable stake input node not found")
                    onComplete?.invoke(false)
                    return
                }
                val formatted = StrategyEngine.formatStakeAmount(newStake)
                val initialStakeTxt = runCatching { stakeNode.text?.toString().orEmpty() }.getOrDefault("")
                if (verifyStakeAccepted(initialStakeTxt, newStake)) {
                    safeRecycleNode(stakeNode, rootNode)
                    refreshLiveTargetsNow()
                    emitDiagnostic("Stake verified at $formattedLabel.")
                    onComplete?.invoke(true)
                    return
                }
                val accepted = writeAndVerifyStakeNode(stakeNode, newStake, formatted, rootNode)
                safeRecycleNode(stakeNode, rootNode)
                if (accepted) {
                    refreshLiveTargetsNow()
                    emitDiagnostic("Stake updated and verified: $formattedLabel.")
                    onComplete?.invoke(true)
                } else {
                    safeLogW(TAG, "applyStakeToTargetApp aborted: read-back mismatch after editing")
                    onComplete?.invoke(false)
                }
            } finally {
                if (fromWindowsList) {
                    safeRecycleRoot(rootNode)
                }
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "applyStakeToTargetApp failed", t)
            onComplete?.invoke(false)
        }
    }

    /**
     * Makes sure the GAME's own Auto Cash Out is armed at [targetMultiplier], the way a person would:
     *  1. If the "Auto Cash Out" row is not on screen, tap the game's "Auto" tab first (the row only exists there).
     *  2. Read the switch + value box by row geometry (see scanWindowTargets).
     *  3. If the value already equals the target and the switch is on (or its state cannot be read), touch nothing.
     *  4. If the switch is verifiably OFF, turn it on and read it back. If that cannot be verified, STOP and leave
     *     the value box untouched - the value box is only ever edited after the switch is verified ON.
     *  5. Edit the value box only when it differs from the target, then verify by read-back.
     * A switch whose state cannot be read at all (plain web div, no screen-color capture) is never toggled blindly.
     */
    fun configureGameAutoCashOut(
        enabled: Boolean,
        targetMultiplier: Double,
        mode: String = "ENSURE",
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        if (mode == "OFF") {
            onComplete?.invoke(true, null)
            return
        }
        try {
            attemptConfigureAutoCashOut(enabled, targetMultiplier, mode, 0, onComplete)
        } catch (t: Throwable) {
            safeLogE(TAG, "configureGameAutoCashOut failed", t)
            onComplete?.invoke(false, t.message ?: "Auto Cash Out configuration error")
        }
    }

    private fun resolveAutoCashSwitchState(targets: DetectedUiTargets): Boolean? =
        targets.isAutoCashOutToggleChecked ?: ScreenColorSampler.isSwitchOn(targets.autoCashOutToggleBounds)

    /**
     * Finds the site's MINIMUM stake the way a person would: press the "-" button via dispatchTouchGesture
     * until the stake stops going down (or the button turns disabled). Returns the floor, or null if it
     * could not be established (button missing, stake unreadable, tap failed, bet active, or still falling
     * after [MAX_PROBE_TAPS] taps). Call only between rounds with no bet placed.
     */
    fun probeMinStake(onComplete: (Double?) -> Unit) {
        try {
            stepMinStakeProbe(0, null, onComplete)
        } catch (t: Throwable) {
            safeLogE(TAG, "probeMinStake failed", t)
            onComplete(null)
        }
    }

    private fun parseStakeNumber(raw: String?): Double? =
        raw?.replace(",", "")?.let { Regex("[0-9]+(?:[.][0-9]+)?").find(it) }?.value?.toDoubleOrNull()

    private fun stepMinStakeProbe(tap: Int, last: Double?, onComplete: (Double?) -> Unit) {
        val pair = resolveBestTargetWindowRoot()
        if (pair == null) {
            onComplete(null)
            return
        }
        val (root, recycle) = pair
        try {
            val scan = scanWindowTargets(root)
            if (scan.isCancelBtnDetected || scan.cashOutButtonBounds != null) {
                onComplete(null)
                return
            }
            val current = parseStakeNumber(scan.detectedStakeText)
            val minus = scan.stakeMinusBounds
            if (current == null || minus == null || minus.isEmpty) {
                onComplete(null)
                return
            }
            val atFloor = !scan.stakeMinusEnabled || (last != null && current >= last - 1e-9)
            if (atFloor) {
                onComplete(current)
                return
            }
            if (tap >= MAX_PROBE_TAPS) {
                onComplete(null)
                return
            }
            dispatchTouchGestureWithDebounce(
                x = minus.centerX().toFloat(),
                y = minus.centerY().toFloat(),
                minDebounceMs = 0L
            ) { tapped ->
                if (!tapped) {
                    onComplete(null)
                    return@dispatchTouchGestureWithDebounce
                }
                mainHandler.postDelayed({ stepMinStakeProbe(tap + 1, current, onComplete) }, 140L)
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "stepMinStakeProbe failed", t)
            onComplete(null)
        } finally {
            if (recycle) safeRecycleRoot(root)
        }
    }

    private data class ButtonContainerSummary(
        val aggregatedLowerText: String,
        val hasExcludedBetTerms: Boolean,
        val hasExcludedCashOutTerms: Boolean
    )

    private fun isContainerTooLargeForButton(
        rect: Rect,
        rootRect: Rect,
        className: String,
        isScrollable: Boolean
    ): Boolean {
        if (rect.isEmpty) return true
        if (isScrollable || className.contains("WebView", ignoreCase = true) || className.contains("ScrollView", ignoreCase = true)) {
            return true
        }
        if (!rootRect.isEmpty && rootRect.width() > 0 && rootRect.height() > 0) {
            if (rect == rootRect) return true
            if (rect.width() >= (rootRect.width() * 0.96f) && rect.height() >= (rootRect.height() * 0.40f)) {
                return true
            }
        }
        return false
    }

    private fun collectButtonContainerSummary(
        container: AccessibilityNodeInfo,
        rootRect: Rect
    ): ButtonContainerSummary? {
        val parts = mutableListOf<String>()
        var clickableDescendants = 0
        var hasEditableDescendant = false
        var visited = 0

        fun collect(n: AccessibilityNodeInfo, depth: Int) {
            if (visited >= 24 || depth > 4 || hasEditableDescendant || clickableDescendants >= 2) return
            visited++
            val cls = runCatching { n.className?.toString().orEmpty() }.getOrDefault("")
            val viewId = runCatching { n.viewIdResourceName?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
            val isAddressBar = viewId.contains("url_bar") || viewId.contains("omnibox") || viewId.contains("location_bar")
            if (isAddressBar) {
                hasEditableDescendant = true
                return
            }
            if (depth > 0) {
                val editable = runCatching { n.isEditable }.getOrDefault(false) || cls.contains("EditText", ignoreCase = true)
                if (editable) {
                    hasEditableDescendant = true
                    return
                }
                if (runCatching { n.isClickable }.getOrDefault(false)) {
                    clickableDescendants++
                    if (clickableDescendants >= 2) return
                }
            }
            val text = runCatching { n.text?.toString()?.trim().orEmpty() }.getOrDefault("")
            val desc = runCatching { n.contentDescription?.toString()?.trim().orEmpty() }.getOrDefault("")
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { n.hintText?.toString()?.trim().orEmpty() }.getOrDefault("")
            } else ""
            val piece = listOf(text, desc, hint).firstOrNull { it.isNotBlank() }
            if (!piece.isNullOrBlank()) {
                parts += piece
            }
            for (child in childNodesOf(n)) {
                collect(child, depth + 1)
                safeRecycleNode(child, container)
            }
        }

        collect(container, 0)
        if (hasEditableDescendant || clickableDescendants >= 2 || parts.isEmpty()) return null
        val aggregated = parts.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.US)
        if (aggregated.isBlank()) return null

        val hasExcludedBetTerms = aggregated.contains("betpawa") ||
            aggregated.contains("sportybet") ||
            aggregated.contains("all bets") ||
            aggregated.contains("my bets") ||
            aggregated.contains("top bets") ||
            aggregated.contains("bet history") ||
            aggregated.contains("betslip") ||
            aggregated.contains("cancel") ||
            aggregated.contains("history") ||
            aggregated.contains("waiting") ||
            aggregated.contains("cash") ||
            aggregated.contains("auto") ||
            aggregated.contains("place your bet") ||
            aggregated == "2x" ||
            aggregated == "x2" ||
            aggregated == "×2" ||
            aggregated.contains("double")

        val hasExcludedCashOutTerms = aggregated.contains("auto") ||
            aggregated.contains("history") ||
            aggregated.contains("cashback")

        return ButtonContainerSummary(
            aggregatedLowerText = aggregated,
            hasExcludedBetTerms = hasExcludedBetTerms,
            hasExcludedCashOutTerms = hasExcludedCashOutTerms
        )
    }

    /** Finds a node whose screen bounds equal [target] and that satisfies [accept]. */
    private fun findNodeAtBounds(
        node: AccessibilityNodeInfo,
        target: Rect,
        depth: Int = 0,
        visited: IntArray = intArrayOf(0),
        accept: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_SCAN_NODES) return null
        visited[0]++
        val r = Rect()
        runCatching { node.getBoundsInScreen(r) }
        if (r == target && runCatching { accept(node) }.getOrDefault(false)) return node
        for (child in childNodesOf(node)) {
            val found = findNodeAtBounds(child, target, depth + 1, visited, accept)
            if (found != null) return found
        }
        return null
    }

    private fun isExcludedClickableButtonCandidate(
        node: AccessibilityNodeInfo,
        nodeRect: Rect,
        rootRect: Rect,
        isCashOut: Boolean
    ): Boolean {
        val text = runCatching { node.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val desc = runCatching { node.contentDescription?.toString()?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val viewId = runCatching { node.viewIdResourceName?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val cls = runCatching { node.className?.toString().orEmpty() }.getOrDefault("")
        val isEditable = runCatching { node.isEditable }.getOrDefault(false) || cls.contains("EditText", ignoreCase = true)
        val isScrollable = runCatching { node.isScrollable }.getOrDefault(false)
        if (isEditable || isContainerTooLargeForButton(nodeRect, rootRect, cls, isScrollable)) return true
        val combined = "$text $desc $viewId"
        if (viewId.contains("url_bar") || viewId.contains("omnibox") || viewId.contains("location_bar")) return true
        if (combined.contains("auto") || combined.contains("history") || combined.contains("betpawa") || combined.contains("sportybet")) {
            return true
        }
        if (isCashOut) {
            if (text == "2x" || text == "x2" || text == "×2" || combined.contains("cashback")) return true
        } else {
            if (text == "2x" || text == "x2" || text == "×2" || combined.contains("double") ||
                combined.contains("cancel") || combined.contains("cash") || combined.contains("all bets") || combined.contains("my bets")
            ) {
                return true
            }
            val rootWidth = if (!rootRect.isEmpty) rootRect.width() else 0
            if (text == "bet" && rootWidth > 0 && nodeRect.width() < (rootWidth * BET_BUTTON_MIN_WIDTH_FRACTION)) {
                return true
            }
        }
        return false
    }

    /**
     * Resolves a verified clickable+enabled node (and its real bounds) backing [targetBounds]:
     *  1. Exact bounds match (`r == targetBounds`).
     *  2. Smallest valid enclosing clickable parent wrapping a smaller child text label (`r.contains(targetBounds)`).
     *  3. Largest valid clickable child contained inside [targetBounds] (`targetBounds.contains(r)`).
     * Returns null when no clickable+enabled node exists (preventing blind taps on inert text labels).
     */
    private fun findClickableButtonBounds(
        rootNode: AccessibilityNodeInfo,
        targetBounds: Rect,
        isCashOut: Boolean
    ): Rect? {
        if (targetBounds.isEmpty) return null
        val rootRect = Rect().also { runCatching { rootNode.getBoundsInScreen(it) } }
        var exactMatch: Rect? = null
        var bestEnclosing: Rect? = null
        var bestEnclosingArea = Long.MAX_VALUE
        var bestContained: Rect? = null
        var bestContainedArea = -1L
        var visited = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (exactMatch != null || depth > MAX_TREE_DEPTH || visited >= MAX_SCAN_NODES) return
            visited++
            val clickable = runCatching { node.isClickable }.getOrDefault(false)
            val enabled = runCatching { node.isEnabled }.getOrDefault(false)
            val r = Rect().also { runCatching { node.getBoundsInScreen(it) } }

            if (node !== rootNode && clickable && enabled && !r.isEmpty &&
                !isExcludedClickableButtonCandidate(node, r, rootRect, isCashOut)
            ) {
                val summary = collectButtonContainerSummary(node, rootRect)
                val summaryValid = summary == null || (if (isCashOut) !summary.hasExcludedCashOutTerms else !summary.hasExcludedBetTerms)
                if (summaryValid) {
                    val area = r.width().toLong() * r.height().toLong()
                    when {
                        r == targetBounds -> {
                            exactMatch = Rect(r)
                            return
                        }
                        r.contains(targetBounds) && area < bestEnclosingArea -> {
                            bestEnclosing = Rect(r)
                            bestEnclosingArea = area
                        }
                        targetBounds.contains(r) && area > bestContainedArea -> {
                            bestContained = Rect(r)
                            bestContainedArea = area
                        }
                    }
                }
            }

            for (child in childNodesOf(node)) {
                visit(child, depth + 1)
                safeRecycleNode(child, rootNode)
            }
        }

        visit(rootNode, 0)
        return exactMatch ?: bestEnclosing ?: bestContained
    }

    private fun tapBoundsWithGesture(
        bounds: Rect,
        onTapped: (Boolean) -> Unit
    ) {
        if (bounds.isEmpty) {
            onTapped(false)
            return
        }
        dispatchTouchGesture(bounds.centerX().toFloat(), bounds.centerY().toFloat()) { ok -> onTapped(ok) }
    }

    private fun attemptConfigureAutoCashOut(
        enabled: Boolean,
        targetMultiplier: Double,
        mode: String,
        attempt: Int,
        onComplete: ((Boolean, String?) -> Unit)?
    ) {
        val pair = resolveBestTargetWindowRoot()
        if (pair == null) {
            onComplete?.invoke(false, "Game window unavailable for Auto Cash Out verification.")
            return
        }
        val (rootNode, fromWindowsList) = pair
        var handedOff = false
        try {
            val scan = scanWindowTargets(rootNode)
            val target = targetMultiplier.coerceAtLeast(1.01)

            // 1. Open the Auto tab when the Auto Cash Out row is not visible.
            if (!scan.isAutoCashOutRowVisible || scan.autoCashOutToggleBounds == null) {
                val tab = scan.autoCashOutTabBounds
                if (attempt < 3 && (tab != null || attempt > 0)) {
                    handedOff = true
                    val proceed = Runnable { attemptConfigureAutoCashOut(enabled, targetMultiplier, mode, attempt + 1, onComplete) }
                    if (attempt == 0 && tab != null) {
                        tapBoundsWithGesture(tab) { mainHandler.postDelayed(proceed, 380L) }
                    } else {
                        mainHandler.postDelayed(proceed, 300L)
                    }
                    return
                }
                onComplete?.invoke(
                    false,
                    if (tab == null) "Game Auto Cash Out row not found (no Auto tab or switch visible)."
                    else "Game Auto Cash Out row did not open after tapping the Auto tab."
                )
                return
            }

            val toggleBounds = scan.autoCashOutToggleBounds
            val inputBounds = scan.autoCashOutInputBounds
            val currentValue = scan.detectedAutoCashOutValue
                ?: scan.detectedAutoCashOutText?.let { ScreenOcrAnalyzer.parseNumericAutoTargetToken(it) }
            val valueMatches = currentValue != null && abs(currentValue - target) < 0.005
            val switchState = resolveAutoCashSwitchState(scan)

            // Turning the game's Auto Cash Out OFF.
            if (!enabled) {
                if (switchState != true) {
                    onComplete?.invoke(true, null)
                    return
                }
                handedOff = true
                tapBoundsWithGesture(toggleBounds) {
                    mainHandler.postDelayed({
                        val after = refreshLiveTargetsNow()
                        val stillOn = after?.let { resolveAutoCashSwitchState(it) } == true
                        onComplete?.invoke(!stillOn, if (stillOn) "Game Auto Cash Out did not switch OFF." else null)
                    }, 320L)
                }
                return
            }

            // 3. Already armed by the user at the right value: leave it alone.
            if (valueMatches && (switchState == true || (mode != "ENSURE" && switchState != false) || switchState == null)) {
                refreshLiveTargetsNow()
                onComplete?.invoke(true, null)
                return
            }

            // Switch state cannot be read and the value differs: never guess (a blind tap could turn it OFF).
            if (switchState == null) {
                onComplete?.invoke(
                    false,
                    "Cannot read the Auto Cash Out switch state (turn on screen-color capture, or set it by hand). " +
                        "Multiplier box left untouched."
                )
                return
            }

            fun editValueThenFinish(root: AccessibilityNodeInfo, inputRect: Rect?) {
                if (inputRect == null) {
                    onComplete?.invoke(false, "Game Auto Cash Out multiplier input not found.")
                    return
                }
                val inputNode = findNodeAtBounds(root, inputRect) { n -> n.isEditable || n.className?.toString()?.contains("EditText", true) == true }
                if (inputNode == null) {
                    onComplete?.invoke(false, "Game Auto Cash Out multiplier input is not editable.")
                    return
                }
                val formatted = String.format(Locale.US, "%.2f", target)
                val ok = writeAndVerifyStakeNode(inputNode, target, formatted, root)
                refreshLiveTargetsNow()
                onComplete?.invoke(ok, if (ok) null else "Game Auto Cash Out target mismatch (could not verify ${formatted}x).")
            }

            if (switchState == true) {
                // 5. Switch already on, only the value differs.
                editValueThenFinish(rootNode, inputBounds)
                return
            }

            // 4. Switch verifiably OFF: turn it on and read it back before touching the value box.
            handedOff = true
            tapBoundsWithGesture(toggleBounds) { _ ->
                mainHandler.postDelayed({
                    val pair2 = resolveBestTargetWindowRoot()
                    if (pair2 == null) {
                        onComplete?.invoke(false, "Game window lost while enabling Auto Cash Out.")
                        return@postDelayed
                    }
                    val (root2, recycle2) = pair2
                    try {
                        val scan2 = scanWindowTargets(root2)
                        if (resolveAutoCashSwitchState(scan2) != true) {
                            onComplete?.invoke(false, "Failed to switch game Auto Cash Out ON. Multiplier box left untouched.")
                            return@postDelayed
                        }
                        val v2 = scan2.detectedAutoCashOutValue
                            ?: scan2.detectedAutoCashOutText?.let { ScreenOcrAnalyzer.parseNumericAutoTargetToken(it) }
                        if (v2 != null && abs(v2 - target) < 0.005) {
                            refreshLiveTargetsNow()
                            onComplete?.invoke(true, null)
                        } else {
                            editValueThenFinish(root2, scan2.autoCashOutInputBounds)
                        }
                    } finally {
                        if (recycle2) safeRecycleRoot(root2)
                    }
                }, 320L)
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "attemptConfigureAutoCashOut failed", t)
            onComplete?.invoke(false, t.message ?: "Auto Cash Out configuration error")
        } finally {
            // Roots handed to a delayed continuation are not recycled here; they are never reused after hand-off.
            if (!handedOff && fromWindowsList) {
                safeRecycleRoot(rootNode)
            }
        }
    }

    protected fun findInteractiveButtonNode(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        depth: Int = 0,
        visited: IntArray = intArrayOf(0),
        excludedTerms: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_SCAN_NODES) return null
        visited[0]++

        val text = runCatching { node.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val desc = runCatching { node.contentDescription?.toString()?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val viewId = runCatching { node.viewIdResourceName?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val isClickable = runCatching { node.isClickable }.getOrDefault(false)
        val isEnabled = runCatching { node.isEnabled }.getOrDefault(true)
        val combined = "$text $desc $viewId"
        val isAddressBar = viewId.contains("url_bar") || viewId.contains("omnibox") || viewId.contains("location_bar")
        val isExcluded = isAddressBar || excludedTerms.any { term -> combined.contains(term) }

        if (isClickable && isEnabled && !isExcluded && keywords.any { text == it || desc == it || text.contains(it) || desc.contains(it) }) {
            return node
        }

        val children = childNodesOf(node)
        for (child in children) {
            val match = findInteractiveButtonNode(child, keywords, depth + 1, visited, excludedTerms)
            if (match != null) {
                return match
            }
        }
        return null
    }

    /**
     * Simple BET click.
     * Prefer the coordinates the user placed with the Action Hand (fallbackX/Y).
     * Only fall back to a live accessibility scan when no hand coordinates are available.
     * Removes the previous multi-pass OCR/collision/layout-settle gates that blocked auto-bets.
     */
    fun clickBetTarget(
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        try {
            // 1. Prefer user-placed hand coordinates (the verified position).
            if (fallbackX != null && fallbackY != null &&
                fallbackX.isFinite() && fallbackY.isFinite() &&
                fallbackX > 0f && fallbackY > 0f
            ) {
                safeLogI(TAG, "clickBetTarget using hand coordinates ($fallbackX, $fallbackY)")
                AppLogManager.i("Gesture", "BET click via hand", details = "($fallbackX, $fallbackY)")
                FloatingHandsOverlayManager.pulsePassThroughAll(280L)
                dispatchTouchGesture(fallbackX, fallbackY) { ok ->
                    if (ok) AppLogManager.success("Gesture", "BET gesture dispatched", coordinates = fallbackX to fallbackY)
                    else AppLogManager.e("Gesture", "BET gesture failed", coordinates = fallbackX to fallbackY)
                    onComplete?.invoke(ok)
                }
                return
            }

            // 2. No hand coords -> light live scan (still no heavy OCR conflict / multi-settle gates).
            val pair = resolveBestTargetWindowRoot()
            if (pair == null) {
                safeLogW(TAG, "clickBetTarget aborted: no active window root and no hand coords")
                AppLogManager.e("Gesture", "BET click aborted", details = "no window root and no hand coords")
                onComplete?.invoke(false)
                return
            }
            val (root, shouldRecycle) = pair
            var clickX: Float? = null
            var clickY: Float? = null
            try {
                val targets = scanWindowTargets(root)
                _detectedTargets.value = targets
                val bounds = targets.betButtonBounds?.takeIf { !it.isEmpty }
                if (bounds != null && targets.isBetBtnDetected && targets.isBetBtnClickable && targets.isBetBtnEnabled) {
                    clickX = bounds.centerX().toFloat()
                    clickY = bounds.centerY().toFloat()
                }
            } finally {
                if (shouldRecycle) safeRecycleRoot(root)
            }

            if (clickX != null && clickY != null) {
                safeLogI(TAG, "clickBetTarget using scanned BET bounds ($clickX, $clickY)")
                AppLogManager.i("Gesture", "BET click via scan", details = "($clickX, $clickY)")
                FloatingHandsOverlayManager.pulsePassThroughAll(280L)
                dispatchTouchGesture(clickX, clickY) { ok ->
                    if (ok) AppLogManager.success("Gesture", "BET gesture dispatched", coordinates = clickX to clickY)
                    else AppLogManager.e("Gesture", "BET gesture failed", coordinates = clickX to clickY)
                    onComplete?.invoke(ok)
                }
            } else {
                safeLogW(TAG, "clickBetTarget aborted: no hand coords and clickable BET button not found on screen")
                AppLogManager.e("Gesture", "BET click aborted", details = "no hand coords and BET button not found")
                onComplete?.invoke(false)
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "clickBetTarget failed", t)
            onComplete?.invoke(false)
        }
    }

    /**
     * Simple CASH OUT click.
     * Prefer the coordinates the user placed with the Action Hand (fallbackX/Y).
     * Only fall back to a live accessibility scan when no hand coordinates are available.
     */
    fun clickCashOutTarget(
        fallbackX: Float? = null,
        fallbackY: Float? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        try {
            if (fallbackX != null && fallbackY != null &&
                fallbackX.isFinite() && fallbackY.isFinite() &&
                fallbackX > 0f && fallbackY > 0f
            ) {
                safeLogI(TAG, "clickCashOutTarget using hand coordinates ($fallbackX, $fallbackY)")
                AppLogManager.i("Gesture", "CASH OUT click via hand", details = "($fallbackX, $fallbackY)")
                FloatingHandsOverlayManager.pulsePassThroughAll(280L)
                dispatchTouchGesture(fallbackX, fallbackY) { ok ->
                    if (ok) AppLogManager.success("Gesture", "CASH OUT gesture dispatched", coordinates = fallbackX to fallbackY)
                    else AppLogManager.e("Gesture", "CASH OUT gesture failed", coordinates = fallbackX to fallbackY)
                    onComplete?.invoke(ok)
                }
                return
            }

            val pair = resolveBestTargetWindowRoot()
            if (pair == null) {
                safeLogW(TAG, "clickCashOutTarget aborted: no active window root and no hand coords")
                AppLogManager.e("Gesture", "CASH OUT aborted", details = "no window root and no hand coords")
                onComplete?.invoke(false)
                return
            }
            val (root, shouldRecycle) = pair
            var clickX: Float? = null
            var clickY: Float? = null
            try {
                val targets = scanWindowTargets(root)
                _detectedTargets.value = targets
                val bounds = targets.cashOutButtonBounds?.takeIf { !it.isEmpty }
                if (bounds != null && targets.isCashOutBtnDetected && targets.isCashOutBtnClickable && targets.isCashOutBtnEnabled) {
                    clickX = bounds.centerX().toFloat()
                    clickY = bounds.centerY().toFloat()
                }
            } finally {
                if (shouldRecycle) safeRecycleRoot(root)
            }

            if (clickX != null && clickY != null) {
                safeLogI(TAG, "clickCashOutTarget using scanned CASH OUT bounds ($clickX, $clickY)")
                AppLogManager.i("Gesture", "CASH OUT click via scan", details = "($clickX, $clickY)")
                FloatingHandsOverlayManager.pulsePassThroughAll(280L)
                dispatchTouchGesture(clickX, clickY) { ok ->
                    if (ok) AppLogManager.success("Gesture", "CASH OUT gesture dispatched", coordinates = clickX to clickY)
                    else AppLogManager.e("Gesture", "CASH OUT gesture failed", coordinates = clickX to clickY)
                    onComplete?.invoke(ok)
                }
            } else {
                safeLogW(TAG, "clickCashOutTarget aborted: no hand coords and clickable CASH OUT button not found")
                AppLogManager.e("Gesture", "CASH OUT aborted", details = "no hand coords and CASH OUT button not found")
                onComplete?.invoke(false)
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "clickCashOutTarget failed", t)
            onComplete?.invoke(false)
        }
    }

    fun awaitStableLayout(timeoutMs: Long = 1200L, intervalMs: Long = 120L): Rect? {
        val start = System.currentTimeMillis()
        var lastBounds: Rect? = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            val targets = refreshLiveTargetsNow()
            val validCurrent = targets?.takeIf {
                it.isBetBtnDetected &&
                    it.isBetBtnEnabled &&
                    it.isBetBtnClickable &&
                    it.siteButtonState == SiteButtonState.IDLE_READY_TO_BET &&
                    it.betButtonBounds != null &&
                    !it.betButtonBounds.isEmpty
            }?.betButtonBounds
            if (validCurrent != null && validCurrent == lastBounds) {
                return validCurrent
            }
            lastBounds = validCurrent
            try { Thread.sleep(intervalMs) } catch (_: Throwable) {}
        }
        val finalTargets = refreshLiveTargetsNow()
        return finalTargets?.takeIf {
            it.isBetBtnDetected &&
                it.isBetBtnEnabled &&
                it.isBetBtnClickable &&
                it.siteButtonState == SiteButtonState.IDLE_READY_TO_BET &&
                it.betButtonBounds != null &&
                !it.betButtonBounds.isEmpty
        }?.betButtonBounds
    }

    fun awaitStableCashOutLayout(timeoutMs: Long = 1200L, intervalMs: Long = 120L): Rect? {
        val start = System.currentTimeMillis()
        var lastBounds: Rect? = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            val targets = refreshLiveTargetsNow()
            val validCurrent = targets?.takeIf {
                it.isCashOutBtnDetected &&
                    it.isCashOutBtnEnabled &&
                    it.isCashOutBtnClickable &&
                    it.siteButtonState == SiteButtonState.CASH_OUT_AVAILABLE &&
                    it.cashOutButtonBounds != null &&
                    !it.cashOutButtonBounds.isEmpty
            }?.cashOutButtonBounds
            if (validCurrent != null && validCurrent == lastBounds) {
                return validCurrent
            }
            lastBounds = validCurrent
            try { Thread.sleep(intervalMs) } catch (_: Throwable) {}
        }
        val finalTargets = refreshLiveTargetsNow()
        return finalTargets?.takeIf {
            it.isCashOutBtnDetected &&
                it.isCashOutBtnEnabled &&
                it.isCashOutBtnClickable &&
                it.siteButtonState == SiteButtonState.CASH_OUT_AVAILABLE &&
                it.cashOutButtonBounds != null &&
                !it.cashOutButtonBounds.isEmpty
        }?.cashOutButtonBounds
    }

    private fun findTabNode(node: AccessibilityNodeInfo, tabName: String): AccessibilityNodeInfo? {
        val visited = IntArray(1)
        var tabNode: AccessibilityNodeInfo? = null
        fun search(n: AccessibilityNodeInfo) {
            if (tabNode != null || visited[0]++ > MAX_SCAN_NODES) return
            val text = runCatching { n.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
            val desc = runCatching { n.contentDescription?.toString()?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
            val isClickable = runCatching { n.isClickable }.getOrDefault(false)
            if ((text == tabName || desc == tabName) && isClickable) {
                tabNode = n
                return
            }
            for (child in childNodesOf(n)) {
                search(child)
            }
        }
        search(node)
        return tabNode
    }

    private fun findAutoCashOutToggleNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        visited: IntArray = intArrayOf(0),
        inAutoContext: Boolean = false
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_SCAN_NODES) return null
        visited[0]++

        val text = runCatching { node.text?.toString()?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val desc = runCatching { node.contentDescription?.toString()?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val viewId = runCatching { node.viewIdResourceName?.trim()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val cls = runCatching { node.className?.toString().orEmpty() }.getOrDefault("")
        val meta = "$viewId $desc $text"
        val nowInAuto = inAutoContext || meta.contains("auto cash") || meta.contains("autocash") ||
            meta.contains("auto-cash") || meta.contains("auto_cash") || meta.contains("auto collect")
        val isSwitchOrCheck = (runCatching { node.isCheckable }.getOrDefault(false) ||
            cls.contains("Switch", true) || cls.contains("CheckBox", true)) &&
            runCatching { node.isEnabled }.getOrDefault(true)
        if (nowInAuto && isSwitchOrCheck) {
            return node
        }
        for (child in childNodesOf(node)) {
            val found = findAutoCashOutToggleNode(child, depth + 1, visited, nowInAuto)
            if (found != null) {
                if (found !== child) safeRecycleNode(child, node)
                return found
            }
            safeRecycleNode(child, node)
        }
        return null
    }

    private fun findEditableAutoCashOutNode(
        node: AccessibilityNodeInfo,
        rootNode: AccessibilityNodeInfo,
        depth: Int = 0,
        visited: IntArray = intArrayOf(0),
        inAutoContext: Boolean = false
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_SCAN_NODES) return null
        visited[0]++

        val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
        val desc = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }.getOrDefault("")
        val viewId = runCatching { node.viewIdResourceName?.trim().orEmpty() }.getOrDefault("")
        val cls = runCatching { node.className?.toString().orEmpty() }.getOrDefault("")
        val meta = "$viewId $desc $text".lowercase(Locale.US)
        val nowInAuto = inAutoContext || meta.contains("auto cash") || meta.contains("autocash") ||
            meta.contains("auto-cash") || meta.contains("auto_cash") || meta.contains("auto collect")
        val isEditable = (runCatching { node.isEditable }.getOrDefault(false)) || cls.contains("EditText", true)
        val isAddressBar = viewId.lowercase(Locale.US).let { it.contains("url_bar") || it.contains("omnibox") || it.contains("location_bar") }
        if (isEditable && !isAddressBar && nowInAuto) {
            return node
        }
        for (child in childNodesOf(node)) {
            val found = findEditableAutoCashOutNode(child, rootNode, depth + 1, visited, nowInAuto)
            if (found != null) {
                if (found !== child) safeRecycleNode(child, rootNode)
                return found
            }
            safeRecycleNode(child, rootNode)
        }
        return null
    }

    private fun findEditableStakeNode(
        node: AccessibilityNodeInfo,
        rootNode: AccessibilityNodeInfo,
        depth: Int = 0,
        visitedCount: IntArray = intArrayOf(0),
        inAutoContext: Boolean = false,
        excludeBounds: Rect? = null
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visitedCount[0] >= MAX_SCAN_NODES) return null
        visitedCount[0]++

        val text = try { node.text?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
        val desc = try { node.contentDescription?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { node.hintText?.toString()?.trim().orEmpty() } catch (_: Throwable) { "" }
        } else ""
        val viewId = try { node.viewIdResourceName?.trim().orEmpty() } catch (_: Throwable) { "" }
        val lowerId = viewId.lowercase(Locale.US)
        val metadata = "$viewId $hint $desc $text".lowercase(Locale.US)
        val nowInAuto = inAutoContext || lowerId.contains("auto_cash") || lowerId.contains("autocash") ||
            metadata.contains("auto cash") || metadata.contains("autocash") || metadata.contains("auto-cash")

        val className = try { node.className?.toString().orEmpty() } catch (_: Throwable) { "" }
        val isEditableField = (try { node.isEditable } catch (_: Throwable) { false }) ||
            className.contains("EditText", ignoreCase = true)
        val isExcludedByBounds = excludeBounds != null && !excludeBounds.isEmpty && isEditableField && run {
            val r = Rect()
            runCatching { node.getBoundsInScreen(r) }
            !r.isEmpty && Rect.intersects(r, excludeBounds)
        }
        if (isEditableField && !nowInAuto && !isExcludedByBounds) {
            val isAddressBar = lowerId.contains("url_bar") || lowerId.contains("omnibox") || lowerId.contains("location_bar")
            if (!isAddressBar && (
                    metadata.contains("stake") ||
                        metadata.contains("amount") ||
                        metadata.contains("wager") ||
                        metadata.contains("bet") ||
                        (text.matches(Regex("^\\d+(\\.\\d{1,2})?$")) && !text.endsWith("x", ignoreCase = true))
                    )
            ) {
                return node
            }
        }

        val children = childNodesOf(node)
        for (child in children) {
            val match = findEditableStakeNode(child, rootNode, depth + 1, visitedCount, nowInAuto, excludeBounds)
            if (match != null) {
                if (match !== child) safeRecycleNode(child, rootNode)
                return match
            }
            safeRecycleNode(child, rootNode)
        }
        return null
    }

    private fun canPerformGesturesSafely(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val info = serviceInfo
            if (info == null) {
                // Config XML declares android:canPerformGestures="true"; in synthetic tests serviceInfo may be null
                true
            } else {
                (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0 ||
                    true // Fallback if system hasn't populated capabilities yet, verified inside dispatchGesture try/catch
            }
        } catch (_: Throwable) {
            true
        }
    }

    protected open fun performPlatformDispatchGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback,
        handler: Handler
    ): Boolean {
        return dispatchGesture(gesture, callback, handler)
    }

    /**
     * Dispatches a touch gesture click at specified screen coordinates strictly on the Main Looper,
     * verifying Android N+ gesture capability and making ScreenEarner overlay windows temporarily
     * pass-through (`FLAG_NOT_TOUCHABLE`) so floating hands never block their own tap target.
     */
    fun dispatchTouchGesture(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        dispatchTouchGestureWithDebounce(x, y, 300L, onComplete)
    }

    private fun dispatchTouchGestureWithDebounce(
        x: Float,
        y: Float,
        minDebounceMs: Long,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !canPerformGesturesSafely()) {
            safeLogW(TAG, "Cannot dispatch gesture: API < N or gesture capability missing.")
            try {
                onComplete?.invoke(false)
            } catch (t: Throwable) {
                safeLogE(TAG, "onComplete(false) callback failed", t)
            }
            return
        }

        val metrics = runCatching { resources.displayMetrics }.getOrNull()
        val screenWidth = metrics?.widthPixels?.toFloat() ?: 0f
        val screenHeight = metrics?.heightPixels?.toFloat() ?: 0f

        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f || (x == 0f && y == 0f) ||
            (screenWidth > 0f && x > screenWidth) ||
            (screenHeight > 0f && y > screenHeight)
        ) {
            safeLogW(TAG, "Ignoring invalid gesture coordinates ($x, $y) bounds=[0..${screenWidth.toInt()}, 0..${screenHeight.toInt()}]")
            try {
                onComplete?.invoke(false)
            } catch (t: Throwable) {
                safeLogE(TAG, "onComplete(false) callback failed", t)
            }
            return
        }

        val now = System.currentTimeMillis()
        val last = lastDispatchedCoords
        if (minDebounceMs > 0L && last != null && last.first == x && last.second == y && (now - lastDispatchedTimeMs) < minDebounceMs) {
            safeLogW(TAG, "Ignoring duplicate gesture at ($x, $y) within debounce window")
            try {
                onComplete?.invoke(false)
            } catch (t: Throwable) {
                safeLogE(TAG, "onComplete(false) callback failed", t)
            }
            return
        }
        lastDispatchedCoords = x to y
        lastDispatchedTimeMs = now

        val scheduledGeneration = gestureGeneration.get()
        mainHandler.post {
            try {
                if (scheduledGeneration != gestureGeneration.get()) {
                    try {
                        onComplete?.invoke(false)
                    } catch (cbErr: Throwable) {
                        safeLogE(TAG, "onComplete(false) callback failed", cbErr)
                    }
                    return@post
                }

                val passThroughListener = gestureOverlayPassThroughListener
                passThroughStartedAtMs = System.currentTimeMillis()
                notifyOverlayPassThrough(true)

                // Safety watchdog so overlays never remain stuck untouchable if OS drops gesture callback
                pendingOverlayRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
                val restoreWatchdog = Runnable {
                    try {
                        notifyOverlayPassThrough(false)
                    } catch (_: Throwable) {
                    }
                }
                pendingOverlayRestoreRunnable = restoreWatchdog
                mainHandler.postDelayed(restoreWatchdog, 600L)

                val dispatchDelayMs = if (passThroughListener != null || FloatingHandsOverlayManager.hasVisibleHands()) 45L else 0L
                val gestureRunnable = object : Runnable {
                    override fun run() {
                        pendingGestureRunnables.remove(this)
                        try {
                            if (scheduledGeneration != gestureGeneration.get()) {
                                finishGesturePassThrough()
                                try {
                                    onComplete?.invoke(false)
                                } catch (cbErr: Throwable) {
                                    safeLogE(TAG, "onComplete(false) callback failed", cbErr)
                                }
                                return
                            }

                            val path = Path().apply {
                                moveTo(x, y)
                            }
                            val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
                            val gesture = GestureDescription.Builder()
                                .addStroke(stroke)
                                .build()

                            val dispatched = performPlatformDispatchGesture(gesture, object : GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) {
                                    super.onCompleted(gestureDescription)
                                    finishGesturePassThrough()
                                    safeLogI(TAG, "Gesture successfully dispatched to ($x,$y)")
                                    try {
                                        onComplete?.invoke(true)
                                    } catch (t: Throwable) {
                                        safeLogE(TAG, "onComplete(true) callback failed", t)
                                    }
                                }

                                override fun onCancelled(gestureDescription: GestureDescription?) {
                                    super.onCancelled(gestureDescription)
                                    finishGesturePassThrough()
                                    safeLogE(TAG, "Gesture dispatch cancelled at ($x,$y)")
                                    try {
                                        onComplete?.invoke(false)
                                    } catch (t: Throwable) {
                                        safeLogE(TAG, "onComplete(false) callback failed", t)
                                    }
                                }
                            }, mainHandler)

                            if (!dispatched) {
                                finishGesturePassThrough()
                                safeLogW(TAG, "dispatchGesture returned false at ($x, $y)")
                                try {
                                    onComplete?.invoke(false)
                                } catch (t: Throwable) {
                                    safeLogE(TAG, "onComplete(false) callback failed", t)
                                }
                            }
                        } catch (t: Throwable) {
                            finishGesturePassThrough()
                            safeLogE(TAG, "Exception dispatching gesture at ($x, $y)", t)
                            try {
                                onComplete?.invoke(false)
                            } catch (cbErr: Throwable) {
                                safeLogE(TAG, "onComplete(false) callback failed", cbErr)
                            }
                        }
                    }
                }
                pendingGestureRunnables.add(gestureRunnable)
                mainHandler.postDelayed(gestureRunnable, dispatchDelayMs)
            } catch (t: Throwable) {
                finishGesturePassThrough()
                safeLogE(TAG, "Failed to post gesture onto Main Looper", t)
                try {
                    onComplete?.invoke(false)
                } catch (cbErr: Throwable) {
                    safeLogE(TAG, "onComplete(false) callback failed", cbErr)
                }
            }
        }
    }

    /**
     * Section 14: Controlled accessibility ribbon & expanded-panel history collection.
     * Inspects accessible history nodes, optionally clicks an accessible history toggle or scrolls
     * a scrollable history container with bounded loop protection, and reports exact partial/complete status.
     */
    fun collectAccessibleRibbonHistory(
        onStatusUpdate: (com.example.data.model.HistoryCollectionStatus, String) -> Unit,
        onBatchExtracted: (
            items: List<com.example.data.engine.ExtractedHistoryItem>,
            isExhausted: Boolean,
            covers30Min: Boolean,
            source: com.example.data.model.CollectionSource
        ) -> Unit
    ) {
        workerScope.launch {
            try {
                onStatusUpdate(
                    com.example.data.model.HistoryCollectionStatus.COLLECTING_VISIBLE_HISTORY,
                    "Inspecting active game window for ribbon & history nodes..."
                )
                val pair = resolveBestTargetWindowRoot()
                if (pair == null) {
                    onStatusUpdate(
                        com.example.data.model.HistoryCollectionStatus.HISTORY_UNAVAILABLE,
                        "No active browser or Aviator window accessible."
                    )
                    return@launch
                }

                val (initialRoot, shouldRecycleInitial) = pair
                try {
                    val initialTargets = scanWindowTargets(initialRoot)
                    if (initialTargets.ribbonItems.isEmpty() && !initialTargets.hasExpandableHistoryControl) {
                        onStatusUpdate(
                            com.example.data.model.HistoryCollectionStatus.HISTORY_UNAVAILABLE,
                            "No game ribbon or history controls exposed in the active window."
                        )
                        return@launch
                    }

                    if (initialTargets.ribbonItems.isNotEmpty()) {
                        onBatchExtracted(
                            initialTargets.ribbonItems,
                            false,
                            false,
                            if (initialTargets.historyPanelExpanded) {
                                com.example.data.model.CollectionSource.EXPANDED_PANEL
                            } else {
                                com.example.data.model.CollectionSource.VISIBLE_RIBBON
                            }
                        )
                    }

                    // If panel is collapsed and an expandable control exists, attempt controlled ACTION_CLICK
                    if (!initialTargets.historyPanelExpanded && initialTargets.hasExpandableHistoryControl) {
                        val expandNode = findInteractiveButtonNode(initialRoot, listOf("round history", "history", "last results"))
                        if (expandNode != null) {
                            onStatusUpdate(
                                com.example.data.model.HistoryCollectionStatus.EXPANDING_HISTORY,
                                "Expanding accessible game history panel..."
                            )
                            val clicked = runCatching { expandNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
                            if (clicked) {
                                kotlinx.coroutines.delay(450L)
                            }
                        }
                    }

                    // Attempt bounded scrolling on any accessible scrollable history container
                    var exhausted = false
                    var covers30Min = false
                    var scrollCount = 0
                    val maxScrolls = 8
                    val nowMs = System.currentTimeMillis()
                    val cutoff30m = nowMs - com.example.data.engine.RibbonHistoryCollector.ROLLING_WINDOW_MS

                    while (scrollCount < maxScrolls) {
                        val loopPair = resolveBestTargetWindowRoot() ?: break
                        val (loopRoot, recycleLoopRoot) = loopPair
                        try {
                            val scrollableNode = findScrollableHistoryNode(loopRoot)
                            val scan = scanWindowTargets(loopRoot)
                            val oldestGameTs = scan.ribbonItems.mapNotNull { it.gameTimestampMs }.minOrNull()
                            if (oldestGameTs != null && oldestGameTs <= cutoff30m) {
                                covers30Min = true
                                break
                            }
                            if (scrollableNode == null) {
                                break
                            }
                            onStatusUpdate(
                                com.example.data.model.HistoryCollectionStatus.SCROLLING_HISTORY,
                                "Scrolling accessible history (${scrollCount + 1}/$maxScrolls)..."
                            )
                            val didScroll = runCatching {
                                scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            }.getOrDefault(false)
                            if (!didScroll) {
                                exhausted = true
                                break
                            }
                            scrollCount++
                            kotlinx.coroutines.delay(320L)
                            val afterPair = resolveBestTargetWindowRoot()
                            if (afterPair != null) {
                                val (afterRoot, recycleAfter) = afterPair
                                try {
                                    val afterScan = scanWindowTargets(afterRoot)
                                    if (afterScan.ribbonItems.isNotEmpty()) {
                                        onBatchExtracted(
                                            afterScan.ribbonItems,
                                            false,
                                            false,
                                            com.example.data.model.CollectionSource.SCROLLED_HISTORY
                                        )
                                    }
                                    val oldestAfter = afterScan.ribbonItems.mapNotNull { it.gameTimestampMs }.minOrNull()
                                    if (oldestAfter != null && oldestAfter <= cutoff30m) {
                                        covers30Min = true
                                        break
                                    }
                                } finally {
                                    if (recycleAfter) safeRecycleRoot(afterRoot)
                                }
                            }
                        } finally {
                            if (recycleLoopRoot) safeRecycleRoot(loopRoot)
                        }
                    }

                    if (exhausted || covers30Min) {
                        onStatusUpdate(
                            com.example.data.model.HistoryCollectionStatus.HISTORY_COMPLETE,
                            "Collected accessible history up to available boundary."
                        )
                    } else {
                        onStatusUpdate(
                            com.example.data.model.HistoryCollectionStatus.HISTORY_PARTIAL,
                            if (initialTargets.hasExpandableHistoryControl || initialTargets.hasScrollableHistoryContainer) {
                                "Partial history collected; 30m timestamps not fully exposed by current UI view."
                            } else {
                                "Partial history collected from visible ribbon only (history panel not expandable)."
                            }
                        )
                    }
                } finally {
                    if (shouldRecycleInitial) safeRecycleRoot(initialRoot)
                }
            } catch (t: Throwable) {
                safeLogE(TAG, "collectAccessibleRibbonHistory failed", t)
                onStatusUpdate(
                    com.example.data.model.HistoryCollectionStatus.HISTORY_ERROR,
                    "History collection encountered an accessibility error: ${t.message ?: "Unknown"}"
                )
            }
        }
    }

    private fun findScrollableHistoryNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        visited: IntArray = intArrayOf(0)
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || visited[0] >= MAX_SCAN_NODES) return null
        visited[0]++

        val viewId = runCatching { node.viewIdResourceName?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val desc = runCatching { node.contentDescription?.toString()?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        val isScrollable = runCatching { node.isScrollable }.getOrDefault(false)
        if (isScrollable && (viewId.contains("history") || desc.contains("history") || viewId.contains("payout"))) {
            return node
        }
        val children = childNodesOf(node)
        for (child in children) {
            val found = findScrollableHistoryNode(child, depth + 1, visited)
            if (found != null) return found
        }
        return null
    }

    /**
     * Diagnostic mode: dispatches a synthetic tap at user-selected screen coordinates without
     * starting or modifying any betting strategy or game state machine (Section 2, Req 12).
     */
    fun testTapCoordinate(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        safeLogI(TAG, "Diagnostic coordinate test tap requested at ($x, $y)")
        dispatchTouchGesture(x, y) { success ->
            safeLogI(TAG, "Diagnostic test tap at ($x, $y) result: $success")
            try {
                onComplete?.invoke(success)
            } catch (t: Throwable) {
                safeLogE(TAG, "Diagnostic test tap onComplete failed", t)
            }
        }
    }

    /**
     * Immediately halts any queued automation gestures and restores overlay touchability,
     * called when a Session Limit (Stop if Profit Reaches / Stop if Loss Reaches) or Emergency Stop fires.
     */
    fun haltAllAutomationGestures(reason: String = "Session limit reached") {
        try {
            gestureGeneration.incrementAndGet()
            pendingGestureRunnables.forEach { runnable ->
                mainHandler.removeCallbacks(runnable)
            }
            pendingGestureRunnables.clear()
            finishGesturePassThrough()
            safeLogW(TAG, "Halted all automation gestures: $reason")
        } catch (t: Throwable) {
            safeLogE(TAG, "Failed cleanly halting gestures", t)
        }
    }

    private fun finishGesturePassThrough() {
        pendingOverlayRestoreRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingOverlayRestoreRunnable = null
        val elapsed = (System.currentTimeMillis() - passThroughStartedAtMs).coerceAtLeast(0L)
        val remaining = (MIN_PASS_THROUGH_MS - elapsed).coerceAtLeast(0L)
        mainHandler.postDelayed({
            try {
                if (pendingGestureRunnables.isEmpty()) {
                    notifyOverlayPassThrough(false)
                }
            } catch (_: Throwable) {
            }
        }, remaining)
    }

    override fun onInterrupt() {
        try {
            pendingGestureRunnables.forEach { mainHandler.removeCallbacks(it) }
            pendingGestureRunnables.clear()
            finishGesturePassThrough()
            safeLogW(TAG, "AccessibilityController interrupted.")
        } catch (t: Throwable) {
            safeLogE(TAG, "Caught exception in onInterrupt", t)
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        return try {
            pendingGestureRunnables.forEach { mainHandler.removeCallbacks(it) }
            pendingGestureRunnables.clear()
            finishGesturePassThrough()
            if (controllerInstance == this) {
                controllerInstance = null
            }
            super.onUnbind(intent)
        } catch (t: Throwable) {
            safeLogE(TAG, "Caught exception in onUnbind", t)
            false
        }
    }

    override fun onDestroy() {
        try {
            workerScope.cancel()
            pendingGestureRunnables.forEach { mainHandler.removeCallbacks(it) }
            pendingGestureRunnables.clear()
            finishGesturePassThrough()
            super.onDestroy()
            if (controllerInstance == this) {
                controllerInstance = null
            }
        } catch (t: Throwable) {
            safeLogE(TAG, "Caught exception in onDestroy", t)
        }
    }

    private fun safeLogD(tag: String, message: String) {
        try {
            Log.d(tag, message)
        } catch (_: RuntimeException) {
            // Pure JVM unit tests without Android Log mock
        }
    }

    private fun safeLogI(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: RuntimeException) {
        }
    }

    private fun safeLogW(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: RuntimeException) {
        }
    }

    private fun safeLogE(tag: String, message: String, error: Throwable? = null) {
        try {
            if (error != null) {
                Log.e(tag, message, error)
            } else {
                Log.e(tag, message)
            }
        } catch (_: RuntimeException) {
        }
    }
}
