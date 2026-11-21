package com.example.service

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.example.data.engine.GameAdapter
import com.example.data.engine.GameAdapterRegistry
import com.example.data.model.SiteButtonState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class OcrTextElement(
    val text: String,
    val bounds: Rect,
    val confidence: Float = 0.85f
)

data class OcrFrameSnapshot(
    val elements: List<OcrTextElement>,
    val fullText: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val capturedAtMs: Long,
    val processingTimeMs: Long = 0L,
    val averageConfidence: Float = 0.85f
)

data class OcrControlCandidate(
    val role: String,
    val label: String,
    val bounds: Rect,
    val numericValue: Double? = null,
    val isChecked: Boolean? = null,
    val confidence: Float = 0.85f
)

data class OcrParsedScreenState(
    val detectedGameId: String? = null,
    val detectedGameName: String? = null,
    val roundPhase: String? = null,
    val siteButtonState: SiteButtonState = SiteButtonState.UNKNOWN,
    val detectedMultiplier: Double? = null,
    val countdownRemainingMs: Long? = null,
    val betControl: OcrControlCandidate? = null,
    val cashOutControl: OcrControlCandidate? = null,
    val cancelControl: OcrControlCandidate? = null,
    val doubleControl: OcrControlCandidate? = null,
    val stakeControl: OcrControlCandidate? = null,
    val stakeValue: Double? = null,
    val stakeText: String? = null,
    val autoCashOutToggle: OcrControlCandidate? = null,
    val autoCashOutEnabled: Boolean? = null,
    val autoCashOutInput: OcrControlCandidate? = null,
    val autoCashOutTarget: Double? = null,
    val averageConfidence: Float = 0f,
    val isUncertain: Boolean = false,
    val uncertaintyReason: String? = null,
    val capturedAtMs: Long = 0L
)

data class FusedScreenState(
    val fusedTargets: DetectedUiTargets,
    val ocrParsed: OcrParsedScreenState? = null,
    val hasConflict: Boolean = false,
    val isOcrUncertain: Boolean = false,
    val isStale: Boolean = false,
    val conflictReasons: List<String> = emptyList(),
    val verificationSummary: String = "Accessibility only"
) {
    val isReliableForBet: Boolean
        get() = !hasConflict && !isStale && !isOcrUncertain
}

/**
 * Extends ScreenCaptureProjectionService & AccessibilityController with throttled on-device ML Kit OCR
 * and cross-checked Accessibility+OCR fusion. Never creates a second MediaProjection stream.
 */
object ScreenOcrAnalyzer {
    private const val TAG = "ScreenOcrAnalyzer"
    private const val OCR_THROTTLE_MS = 260L
    const val DEFAULT_MAX_FRAME_AGE_MS = 2_000L
    const val DEFAULT_MIN_OCR_CONFIDENCE = 0.55f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isBusy = AtomicBoolean(false)
    private val captureGeneration = AtomicLong(0L)

    fun nextCaptureGeneration(): Long {
        val next = captureGeneration.incrementAndGet()
        _latestOcrSnapshot.value = null
        isBusy.set(false)
        return next
    }

    fun currentCaptureGeneration(): Long = captureGeneration.get()
    private val lastScheduledAtMs = AtomicLong(0L)

    @Volatile
    private var recognizer: TextRecognizer? = null

    private val _latestOcrSnapshot = MutableStateFlow<OcrFrameSnapshot?>(null)
    val latestOcrSnapshot: StateFlow<OcrFrameSnapshot?> = _latestOcrSnapshot.asStateFlow()

    private val _lastOcrError = MutableStateFlow<String?>(null)
    val lastOcrError: StateFlow<String?> = _lastOcrError.asStateFlow()

    @Volatile
    var onOcrSnapshotUpdated: ((OcrFrameSnapshot) -> Unit)? = null

    private fun getOrCreateRecognizer(): TextRecognizer? {
        recognizer?.let { return it }
        return runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
                recognizer = it
            }
        }.onFailure { err ->
            _lastOcrError.value = "ML Kit OCR unavailable: ${err.message}"
        }.getOrNull()
    }

    /**
     * Called directly from ScreenCaptureProjectionService's existing ImageReader callback BEFORE
     * ScreenColorSampler recycles any previous bitmap. Copies the bitmap synchronously, processes
     * OCR asynchronously on Dispatchers.Default, and recycles its private copy in finally.
     */
    fun onCapturedFrame(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val now = System.currentTimeMillis()
        if (now - lastScheduledAtMs.get() < OCR_THROTTLE_MS) return
        if (!isBusy.compareAndSet(false, true)) return

        val copy = try {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) {
            isBusy.set(false)
            _lastOcrError.value = "Frame copy failed: ${t.message}"
            return
        }
        if (copy == null) {
            isBusy.set(false)
            return
        }
        lastScheduledAtMs.set(now)

        val gen = captureGeneration.get()
        scope.launch {
            val startedElapsed = SystemClock.elapsedRealtime()
            try {
                val client = getOrCreateRecognizer()
                if (client == null) {
                    isBusy.set(false)
                    return@launch
                }
                val image = InputImage.fromBitmap(copy, 0)
                client.process(image)
                    .addOnSuccessListener { result ->
                        if (gen != captureGeneration.get()) {
                            if (!copy.isRecycled) runCatching { copy.recycle() }
                            isBusy.set(false)
                            return@addOnSuccessListener
                        }
                        val elapsed = (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(1L)
                        val snapshot = toFrameSnapshot(
                            visionText = result,
                            frameWidth = copy.width,
                            frameHeight = copy.height,
                            capturedAtMs = now,
                            processingTimeMs = elapsed
                        )
                        _latestOcrSnapshot.value = snapshot
                        _lastOcrError.value = null
                        onOcrSnapshotUpdated?.invoke(snapshot)
                        if (!copy.isRecycled) runCatching { copy.recycle() }
                        isBusy.set(false)
                    }
                    .addOnFailureListener { err ->
                        _lastOcrError.value = err.message ?: "OCR recognition failed"
                        if (!copy.isRecycled) runCatching { copy.recycle() }
                        isBusy.set(false)
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "OCR processing error: ${t.message}")
                _lastOcrError.value = t.message
                if (!copy.isRecycled) runCatching { copy.recycle() }
                isBusy.set(false)
            }
        }
    }

    fun injectTestOcrSnapshot(snapshot: OcrFrameSnapshot?) {
        _latestOcrSnapshot.value = snapshot
    }

    fun release() {
        nextCaptureGeneration()
        runCatching { recognizer?.close() }
        recognizer = null
        _latestOcrSnapshot.value = null
        isBusy.set(false)
    }

    private fun toFrameSnapshot(
        visionText: Text,
        frameWidth: Int,
        frameHeight: Int,
        capturedAtMs: Long,
        processingTimeMs: Long
    ): OcrFrameSnapshot {
        val elements = mutableListOf<OcrTextElement>()
        val confidences = mutableListOf<Float>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: block.boundingBox ?: continue
                val txt = line.text.trim()
                if (txt.isBlank()) continue
                val lineConf = line.confidence ?: 0.85f
                elements += OcrTextElement(
                    text = txt,
                    bounds = Rect(box),
                    confidence = lineConf.coerceIn(0f, 1f)
                )
                confidences += lineConf.coerceIn(0f, 1f)
            }
        }
        val avgConf = if (confidences.isEmpty()) 0f else confidences.average().toFloat()
        return OcrFrameSnapshot(
            elements = elements,
            fullText = visionText.text,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            capturedAtMs = capturedAtMs,
            processingTimeMs = processingTimeMs,
            averageConfidence = avgConf
        )
    }

    fun parseCandidateMultiplier(rawText: String): Double? {
        val clean = rawText.trim().replace(',', '.')
        val withX = Regex("""(?:^|[@xX\s])(\d{1,4}\.\d{1,2})\s*[xX]?""").find(clean)
        val v1 = withX?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (v1 != null && v1 >= 1.00) return v1

        val pureDec = Regex("""^(\d{1,4}\.\d{1,2})$""").find(clean)
        val v2 = pureDec?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (v2 != null && v2 >= 1.00) return v2
        return null
    }

    fun parseOcrSnapshot(
        snapshot: OcrFrameSnapshot,
        adapter: GameAdapter? = null,
        a11yTargets: DetectedUiTargets? = null,
        minConfidence: Float = DEFAULT_MIN_OCR_CONFIDENCE
    ): OcrParsedScreenState {
        if (snapshot.elements.isEmpty()) {
            return OcrParsedScreenState(
                averageConfidence = 0f,
                isUncertain = true,
                uncertaintyReason = "OCR extracted no visible text elements",
                capturedAtMs = snapshot.capturedAtMs
            )
        }
        if (snapshot.averageConfidence > 0f && snapshot.averageConfidence < minConfidence) {
            return OcrParsedScreenState(
                averageConfidence = snapshot.averageConfidence,
                isUncertain = true,
                uncertaintyReason = String.format(
                    Locale.US,
                    "OCR confidence %.2f below minimum %.2f",
                    snapshot.averageConfidence,
                    minConfidence
                ),
                capturedAtMs = snapshot.capturedAtMs
            )
        }

        val resolvedAdapter = adapter ?: GameAdapterRegistry.resolveAdapter(
            packageName = "",
            url = null,
            pageHints = emptyList(),
            rawTexts = snapshot.elements.map { it.text },
            hasMultiplierSignals = true
        )
        val gameId = resolvedAdapter?.gameId
        val isJetX = gameId == "jetx"

        var betCandidate: OcrControlCandidate? = null
        var cashOutCandidate: OcrControlCandidate? = null
        var cancelCandidate: OcrControlCandidate? = null
        var doubleCandidate: OcrControlCandidate? = null
        var stakeCandidate: OcrControlCandidate? = null
        var stakeVal: Double? = null
        var autoToggleCandidate: OcrControlCandidate? = null
        var autoInputCandidate: OcrControlCandidate? = null
        var autoTargetVal: Double? = null
        var countdownMs: Long? = null
        var liveMult: Double? = null
        var crashedMult: Double? = null
        var sawFlewAway = false
        var sawWaitNextRound = false
        var uncertaintyReason: String? = null

        val lowerElements = snapshot.elements.map { it to it.text.trim().lowercase(Locale.US) }

        for ((el, lower) in lowerElements) {
            if (lower.contains("flew away") || lower.contains("crashed") || lower.contains("busted")) {
                sawFlewAway = true
                val embedded = parseCandidateMultiplier(el.text)
                if (embedded != null && embedded >= 1.0) {
                    crashedMult = embedded
                }
            }
            if (lower.contains("waiting for next round") ||
                lower.contains("place your bet") ||
                lower.contains("betting open") ||
                lower.contains("starting in") ||
                lower.contains("next round in")
            ) {
                sawWaitNextRound = true
            }

            if (countdownMs == null) {
                countdownMs = parseCountdownMsFromText(el.text)
            }

            // Control role detection (strictly mutually exclusive; never classify 2x / Cancel / Auto as BET)
            when {
                lower == "2x" || lower == "x2" || lower == "×2" || lower == "double" -> {
                    if (doubleCandidate == null) {
                        doubleCandidate = OcrControlCandidate("DOUBLE", el.text, Rect(el.bounds), confidence = el.confidence)
                    }
                }
                lower == "cancel" || lower == "cancel bet" || (lower.startsWith("cancel") && !lower.contains("auto")) -> {
                    if (cancelCandidate == null) {
                        cancelCandidate = OcrControlCandidate("CANCEL", el.text, Rect(el.bounds), confidence = el.confidence)
                    }
                }
                (lower.contains("cash out") || lower.contains("cashout") || (isJetX && lower == "collect")) &&
                    !lower.contains("auto") && !lower.contains("history") -> {
                    val existing = cashOutCandidate
                    if (existing == null || el.confidence > existing.confidence) {
                        cashOutCandidate = OcrControlCandidate("CASH_OUT", el.text, Rect(el.bounds), confidence = el.confidence)
                    }
                }
                lower.contains("auto cash") || lower.contains("autocash") || lower.contains("auto-cash") ||
                    (isJetX && (lower == "auto" || lower.contains("auto collect"))) -> {
                    if (autoToggleCandidate == null) {
                        val explicitOn = when {
                            lower.contains(" off") || lower.endsWith("off") -> false
                            lower.contains(" on") || lower.endsWith("on") -> true
                            else -> null
                        }
                        autoToggleCandidate = OcrControlCandidate(
                            role = "AUTO_CASHOUT_TOGGLE",
                            label = el.text,
                            bounds = Rect(el.bounds),
                            isChecked = explicitOn,
                            confidence = el.confidence
                        )
                    }
                    // Look for inline target like "Auto Cash Out 2.50x"
                    parseNumberFromField(el.text)?.takeIf { it >= 1.01 }?.let { inlineTarget ->
                        if (autoTargetVal == null) {
                            autoTargetVal = inlineTarget
                            autoInputCandidate = OcrControlCandidate(
                                role = "AUTO_CASHOUT_INPUT",
                                label = el.text,
                                bounds = Rect(el.bounds),
                                numericValue = inlineTarget,
                                confidence = el.confidence
                            )
                        }
                    }
                }
                isPureBetLabel(lower, isJetX) -> {
                    val existing = betCandidate
                    if (existing == null || el.confidence > existing.confidence) {
                        betCandidate = OcrControlCandidate("BET", el.text, Rect(el.bounds), confidence = el.confidence)
                    }
                }
            }

            // Live center multiplier detection (exclude currency/auto/ribbon top area & bottom buttons)
            val isInTopRibbon = snapshot.frameHeight > 0 && el.bounds.centerY() < (snapshot.frameHeight * 0.18f)
            val isBottomControls = snapshot.frameHeight > 0 && el.bounds.centerY() > (snapshot.frameHeight * 0.82f)
            if (!isInTopRibbon && !isBottomControls &&
                !lower.contains("auto") &&
                !lower.contains("cash") &&
                !lower.contains("bet") &&
                !lower.contains("balance") &&
                !lower.contains("total")
            ) {
                val candidate = parseCandidateMultiplier(el.text)
                if (candidate != null && candidate >= 1.00) {
                    if (liveMult == null || candidate > liveMult) {
                        liveMult = candidate
                    }
                }
            }
        }

        // Anchor-based stake value & Auto Cash Out value detection:
        // 1) Inside Accessibility stakeInputBounds if known
        val knownStakeRect = a11yTargets?.stakeInputBounds?.takeIf { !it.isEmpty }
        if (knownStakeRect != null) {
            val stakeElements = snapshot.elements.filter { el ->
                rectsCloseOrOverlapping(el.bounds, knownStakeRect, tolerancePx = 36)
            }
            val parsedInBox = stakeElements.firstNotNullOfOrNull { parseNumericStakeToken(it.text) }
            if (parsedInBox != null) {
                val firstEl = stakeElements.first { parseNumericStakeToken(it.text) != null }
                stakeVal = parsedInBox
                stakeCandidate = OcrControlCandidate(
                    role = "STAKE_INPUT",
                    label = firstEl.text,
                    bounds = Rect(firstEl.bounds),
                    numericValue = parsedInBox,
                    confidence = firstEl.confidence
                )
            }
        }

        // 2) Inside Accessibility autoCashOutInputBounds or adjacent to Auto Cash Out toggle
        val knownAutoRect = a11yTargets?.autoCashOutInputBounds?.takeIf { !it.isEmpty }
            ?: a11yTargets?.autoCashOutToggleBounds?.takeIf { !it.isEmpty }
            ?: autoToggleCandidate?.bounds
        if (autoTargetVal == null && knownAutoRect != null) {
            val nearbyAutoNum = snapshot.elements.filter { el ->
                el.bounds.centerY() in (knownAutoRect.top - 60)..(knownAutoRect.bottom + 60) &&
                    el.bounds.centerX() >= knownAutoRect.left - 40 &&
                    el.bounds.centerX() <= knownAutoRect.right + 420 &&
                    !el.text.lowercase(Locale.US).contains("auto")
            }.firstNotNullOfOrNull { el ->
                parseNumericAutoTargetToken(el.text)?.let { v -> el to v }
            }
            if (nearbyAutoNum != null) {
                val (el, v) = nearbyAutoNum
                autoTargetVal = v
                autoInputCandidate = OcrControlCandidate(
                    role = "AUTO_CASHOUT_INPUT",
                    label = el.text,
                    bounds = Rect(el.bounds),
                    numericValue = v,
                    confidence = el.confidence
                )
            }
        }

        // Fallback stake numeric match next to "Stake" / "Bet" label or "+" / "-" buttons if stake still null
        if (stakeCandidate == null) {
            val stakeLabelEl = lowerElements.firstOrNull { (_, l) ->
                l == "stake" || l.startsWith("stake:") || l == "amount" || l.startsWith("bet amount")
            }?.first
            if (stakeLabelEl != null) {
                val nearbyNum = snapshot.elements.filter { el ->
                    el !== stakeLabelEl &&
                        abs(el.bounds.centerY() - stakeLabelEl.bounds.centerY()) <= 90 &&
                        el.bounds.centerX() >= stakeLabelEl.bounds.left - 60
                }.firstNotNullOfOrNull { el ->
                    parseNumericStakeToken(el.text)?.let { v -> el to v }
                }
                if (nearbyNum != null) {
                    val (el, v) = nearbyNum
                    stakeVal = v
                    stakeCandidate = OcrControlCandidate(
                        role = "STAKE_INPUT",
                        label = el.text,
                        bounds = Rect(el.bounds),
                        numericValue = v,
                        confidence = el.confidence
                    )
                }
            }
        }

        // Check ambiguous overlapping Bet controls or conflicting phase cues in OCR alone
        if (betCandidate != null && cashOutCandidate != null && Rect.intersects(betCandidate.bounds, cashOutCandidate.bounds)) {
            uncertaintyReason = "OCR detected overlapping BET and CASH OUT labels in the same control region"
        }
        if (betCandidate != null && doubleCandidate != null && Rect.intersects(betCandidate.bounds, doubleCandidate.bounds)) {
            uncertaintyReason = "OCR BET candidate overlaps 2x/Double control"
        }
        if (betCandidate != null && cancelCandidate != null && Rect.intersects(betCandidate.bounds, cancelCandidate.bounds)) {
            uncertaintyReason = "OCR BET candidate overlaps CANCEL control"
        }

        val resolvedBtnState = when {
            uncertaintyReason != null -> SiteButtonState.UNKNOWN
            sawFlewAway -> SiteButtonState.CRASHED
            cashOutCandidate != null -> SiteButtonState.CASH_OUT_AVAILABLE
            cancelCandidate != null -> SiteButtonState.BET_QUEUED
            betCandidate != null -> SiteButtonState.IDLE_READY_TO_BET
            else -> SiteButtonState.UNKNOWN
        }

        val resolvedPhase = when {
            uncertaintyReason != null -> null
            sawFlewAway -> "CRASHED"
            cashOutCandidate != null || (liveMult != null && !sawWaitNextRound && countdownMs == null) -> "IN_FLIGHT"
            sawWaitNextRound || countdownMs != null || cancelCandidate != null || betCandidate != null -> "BETTING_OPEN"
            else -> null
        }

        val hasMinimalContext = resolvedPhase != null || betCandidate != null || cashOutCandidate != null || cancelCandidate != null
        val uncertain = uncertaintyReason != null || (!hasMinimalContext && snapshot.elements.isNotEmpty())
        val finalUncertainty = uncertaintyReason ?: if (!hasMinimalContext) {
            "OCR frame lacked unambiguous game phase or control markers"
        } else null

        return OcrParsedScreenState(
            detectedGameId = resolvedAdapter?.gameId?.takeIf { it != "unknown" },
            detectedGameName = resolvedAdapter?.displayedGameName?.takeIf { it != "Unknown" },
            roundPhase = resolvedPhase,
            siteButtonState = resolvedBtnState,
            detectedMultiplier = if (sawFlewAway) (crashedMult ?: liveMult) else liveMult,
            countdownRemainingMs = countdownMs,
            betControl = betCandidate,
            cashOutControl = cashOutCandidate,
            cancelControl = cancelCandidate,
            doubleControl = doubleCandidate,
            stakeControl = stakeCandidate,
            stakeValue = stakeVal,
            stakeText = stakeCandidate?.label,
            autoCashOutToggle = autoToggleCandidate,
            autoCashOutEnabled = autoToggleCandidate?.isChecked,
            autoCashOutInput = autoInputCandidate,
            autoCashOutTarget = autoTargetVal,
            averageConfidence = snapshot.averageConfidence,
            isUncertain = uncertain,
            uncertaintyReason = finalUncertainty,
            capturedAtMs = snapshot.capturedAtMs
        )
    }

    fun fuseAccessibilityAndOcr(
        a11yTargets: DetectedUiTargets,
        ocrSnapshot: OcrFrameSnapshot?,
        adapter: GameAdapter? = null,
        nowMs: Long = System.currentTimeMillis(),
        maxOcrAgeMs: Long = DEFAULT_MAX_FRAME_AGE_MS,
        minOcrConfidence: Float = DEFAULT_MIN_OCR_CONFIDENCE
    ): FusedScreenState {
        if (ocrSnapshot == null) {
            return FusedScreenState(
                fusedTargets = a11yTargets,
                ocrParsed = null,
                hasConflict = false,
                isOcrUncertain = false,
                isStale = false,
                conflictReasons = emptyList(),
                verificationSummary = "Accessibility only (no OCR frame)"
            )
        }

        val ageMs = (nowMs - ocrSnapshot.capturedAtMs).coerceAtLeast(0L)
        if (ageMs > maxOcrAgeMs) {
            val staleTargets = a11yTargets.copy(
                ocrVerified = false
            )
            return FusedScreenState(
                fusedTargets = staleTargets,
                ocrParsed = null,
                hasConflict = false,
                isOcrUncertain = false,
                isStale = true,
                conflictReasons = emptyList(),
                verificationSummary = "OCR frame expired (${ageMs}ms); relying on live Accessibility"
            )
        }

        val parsed = parseOcrSnapshot(ocrSnapshot, adapter, a11yTargets, minOcrConfidence)
        val conflicts = mutableListOf<String>()

        if (parsed.isUncertain) {
            return FusedScreenState(
                fusedTargets = a11yTargets.copy(
                    ocrVerified = false
                ),
                ocrParsed = parsed,
                hasConflict = false,
                isOcrUncertain = true,
                isStale = false,
                conflictReasons = emptyList(),
                verificationSummary = "OCR uncertain: ${parsed.uncertaintyReason ?: "low clarity"}; using live Accessibility"
            )
        }

        // Cross-check 1: Round phase agreement when Accessibility is confident in a live phase
        if (a11yTargets.gamePhase != "WAITING_FOR_GAME" &&
            a11yTargets.gamePhase != "LIVE_DETECTION_LOST" &&
            parsed.roundPhase != null &&
            a11yTargets.gamePhase != parsed.roundPhase
        ) {
            conflicts += "Phase conflict: A11y=${a11yTargets.gamePhase} vs OCR=${parsed.roundPhase}"
        }

        // Cross-check 2: Stake reading cross-validation
        val a11yStakeVal = parseNumericStakeToken(a11yTargets.detectedStakeText.orEmpty())
        val ocrStakeVal = parsed.stakeValue
        if (a11yStakeVal != null && ocrStakeVal != null && abs(a11yStakeVal - ocrStakeVal) > 0.01) {
            conflicts += "Stake reading conflict: A11y=${a11yStakeVal} vs OCR=${ocrStakeVal}"
        }

        // Cross-check 3: Auto Cash Out target cross-validation
        val a11yAutoVal = a11yTargets.detectedAutoCashOutValue
            ?: parseNumericAutoTargetToken(a11yTargets.detectedAutoCashOutText.orEmpty())
        val ocrAutoVal = parsed.autoCashOutTarget
        if (a11yAutoVal != null && ocrAutoVal != null && abs(a11yAutoVal - ocrAutoVal) > 0.01) {
            conflicts += "Auto Cash Out target conflict: A11y=${a11yAutoVal} vs OCR=${ocrAutoVal}"
        }

        // Cross-check 4: Active button role conflict (e.g. A11y thinks IDLE/BET while OCR sees CANCEL or CASH OUT)
        if (a11yTargets.siteButtonState == SiteButtonState.IDLE_READY_TO_BET &&
            (parsed.siteButtonState == SiteButtonState.BET_QUEUED || parsed.siteButtonState == SiteButtonState.CASH_OUT_AVAILABLE)
        ) {
            conflicts += "Button state conflict: A11y=IDLE_READY_TO_BET vs OCR=${parsed.siteButtonState}"
        }

        // Fill only MISSING secondary fields from reliable OCR — never invent BET click targets from OCR alone
        // unless bounds are non-overlapping, high-confidence, and phase matches.
        val resolvedStakeText = a11yTargets.detectedStakeText?.takeIf { it.isNotBlank() }
            ?: parsed.stakeText
        val resolvedStakeBounds = a11yTargets.stakeInputBounds
            ?: parsed.stakeControl?.bounds
        val resolvedCountdownMs = a11yTargets.countdownRemainingMs ?: parsed.countdownRemainingMs
        val resolvedAutoToggleBounds = a11yTargets.autoCashOutToggleBounds ?: parsed.autoCashOutToggle?.bounds
        val resolvedAutoChecked = a11yTargets.isAutoCashOutToggleChecked ?: parsed.autoCashOutEnabled
        val resolvedAutoInputBounds = a11yTargets.autoCashOutInputBounds ?: parsed.autoCashOutInput?.bounds
        val resolvedAutoText = a11yTargets.detectedAutoCashOutText?.takeIf { it.isNotBlank() }
            ?: parsed.autoCashOutTarget?.let { String.format(Locale.US, "%.2f", it) }
        val resolvedAutoValue = a11yTargets.detectedAutoCashOutValue ?: parsed.autoCashOutTarget
        val resolvedBetBounds = a11yTargets.betButtonBounds
        val resolvedCashOutBounds = a11yTargets.cashOutButtonBounds
        val resolvedCancelBounds = a11yTargets.cancelButtonBounds ?: parsed.cancelControl?.bounds
        val resolvedDoubleBounds = a11yTargets.doubleButtonBounds ?: parsed.doubleControl?.bounds

        val fusedPhase = when {
            parsed.roundPhase == "CRASHED" -> "CRASHED"
            parsed.roundPhase == "IN_FLIGHT" -> "IN_FLIGHT"
            a11yTargets.gamePhase == "WAITING_FOR_GAME" && parsed.roundPhase != null -> parsed.roundPhase
            else -> a11yTargets.gamePhase
        }
        val resolvedMultiplier = parsed.detectedMultiplier
            ?: a11yTargets.detectedMultiplier

        val fusedTargets = a11yTargets.copy(
            gamePhase = fusedPhase,
            detectedMultiplier = resolvedMultiplier,
            countdownRemainingMs = resolvedCountdownMs,
            stakeInputBounds = resolvedStakeBounds,
            detectedStakeText = resolvedStakeText,
            doubleButtonBounds = resolvedDoubleBounds,
            betButtonBounds = resolvedBetBounds,
            cancelButtonBounds = resolvedCancelBounds,
            isCancelBtnDetected = a11yTargets.isCancelBtnDetected || parsed.cancelControl != null,
            cashOutButtonBounds = resolvedCashOutBounds,
            autoCashOutToggleBounds = resolvedAutoToggleBounds,
            isAutoCashOutToggleChecked = resolvedAutoChecked,
            autoCashOutInputBounds = resolvedAutoInputBounds,
            detectedAutoCashOutText = resolvedAutoText,
            detectedAutoCashOutValue = resolvedAutoValue,
            conflictReasons = conflicts,
            ocrVerified = conflicts.isEmpty(),
            fusedConfidence = parsed.averageConfidence
        )

        return FusedScreenState(
            fusedTargets = fusedTargets,
            ocrParsed = parsed,
            hasConflict = conflicts.isNotEmpty(),
            isOcrUncertain = false,
            isStale = false,
            conflictReasons = conflicts,
            verificationSummary = if (conflicts.isEmpty()) {
                "Verified A11y + OCR (${String.format(Locale.US, "%.0f%%", parsed.averageConfidence * 100f)})"
            } else {
                "CONFLICT: ${conflicts.first()}"
            }
        )
    }

    private fun isPureBetLabel(lower: String, isJetX: Boolean): Boolean {
        if (lower.contains("cancel") ||
            lower.contains("auto") ||
            lower.contains("history") ||
            lower.contains("cash") ||
            lower.contains("all bets") ||
            lower.contains("my bets") ||
            lower.contains("top bets") ||
            lower.contains("place your bet") ||
            lower.contains("betpawa") ||
            lower.contains("sportybet") ||
            lower.contains("double") ||
            lower == "2x" ||
            lower == "x2"
        ) return false
        return lower == "bet" ||
            lower == "place bet" ||
            lower.startsWith("bet ") ||
            (isJetX && lower == "place your bet")
    }

    fun parseCountdownMsFromText(text: String): Long? {
        val clean = text.trim().lowercase(Locale.US)
        if (clean.isBlank() || clean.length > 48) return null
        // Match explicit "4.5s", "3s", "in 4.2 s", or timer "00:04"
        Regex("""(?:starting\s+in|next\s+round\s+in|betting\s+closes?\s+in|in)?\s*(\d+(?:\.\d{1,2})?)\s*s\b""")
            .find(clean)
            ?.groupValues?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.1..30.0 }
            ?.let { secs -> return (secs * 1000.0).toLong() }

        Regex("""\b00:0?(\d{1,2})\b""").find(clean)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 1..30 }
            ?.let { secs -> return secs * 1000L }

        return null
    }

    fun parseNumericStakeToken(raw: String): Double? {
        val cleaned = raw.trim().replace(",", "")
        if (cleaned.isBlank()) return null
        // Exclude multiplier tokens ending with x unless clearly no x
        if (cleaned.endsWith("x", ignoreCase = true)) return null
        val match = Regex("""(?:^|\s)(\d+(?:\.\d{1,2})?)(?:\s|$)""").find(cleaned)
            ?: return null
        return match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun parseNumericAutoTargetToken(raw: String): Double? {
        val cleaned = raw.trim().replace(",", "")
        if (cleaned.isBlank()) return null
        val match = Regex("""(\d+(?:\.\d{1,2})?)\s*x?""", RegexOption.IGNORE_CASE).find(cleaned)
            ?: return null
        return match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.01 }
    }

    private fun parseNumberFromField(raw: String): Double? {
        val cleaned = raw.replace(",", "")
        val match = Regex("""(\d+(?:\.\d{1,2})?)""").find(cleaned) ?: return null
        return match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun rectsCloseOrOverlapping(a: Rect, b: Rect, tolerancePx: Int): Boolean {
        val expanded = Rect(
            b.left - tolerancePx,
            b.top - tolerancePx,
            b.right + tolerancePx,
            b.bottom + tolerancePx
        )
        return Rect.intersects(a, expanded)
    }
}
