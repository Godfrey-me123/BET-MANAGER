package com.example

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.SiteButtonState
import com.example.service.AccessibilityController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.IdentityHashMap

/**
 * Layouts taken from real BetPawa Aviator screenshots (1080x2400, Chrome):
 *  - "Bet" / "Auto" TABS sit above the stake box and the big green "Bet 400.00 TZS" button.
 *  - Mid-flight the Bet button stays enabled (it queues the next round).
 *  - The Auto tab reveals "Auto bet" / "Auto Cash Out" + switch + "2.00" value box on one row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AviatorLayoutDetectionTest {

    private class TestController(
        private val tree: IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>,
        private val activeRoot: AccessibilityNodeInfo? = null
    ) : AccessibilityController() {
        val dispatchedGestures = mutableListOf<android.accessibilityservice.GestureDescription>()

        override fun childNodesOf(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = tree[node] ?: emptyList()

        override fun resolveBestTargetWindowRoot(): Pair<AccessibilityNodeInfo, Boolean>? {
            return activeRoot?.let { it to false }
        }

        override fun performPlatformDispatchGesture(
            gesture: android.accessibilityservice.GestureDescription,
            callback: GestureResultCallback,
            handler: android.os.Handler
        ): Boolean {
            dispatchedGestures.add(gesture)
            callback.onCompleted(gesture)
            return true
        }

        fun scan(root: AccessibilityNodeInfo) = scanWindowTargets(root)
    }

    private fun node(
        className: String,
        text: String? = null,
        bounds: Rect,
        clickable: Boolean = false,
        editable: Boolean = false
    ): AccessibilityNodeInfo = AccessibilityNodeInfo().apply {
        packageName = "com.android.chrome"
        this.className = className
        this.text = text
        isEnabled = true
        isClickable = clickable
        isEditable = editable
        isFocusable = clickable || editable
        isVisibleToUser = true
        @Suppress("DEPRECATION")
        setBoundsInScreen(bounds)
    }

    private val betButtonRect = Rect(460, 1200, 1033, 1425)
    private val betTabRect = Rect(259, 1088, 540, 1156)
    private val autoTabRect = Rect(540, 1088, 822, 1156)
    private val stakeRect = Rect(110, 1200, 350, 1270)

    private class Layout(
        val root: AccessibilityNodeInfo,
        val controller: TestController,
        val betButton: AccessibilityNodeInfo
    )

    private fun layout(multiplier: String, withAutoRow: Boolean): Layout {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val live = node("android.widget.TextView", multiplier, Rect(290, 700, 800, 870))
        val betTab = node("android.widget.Button", "Bet", betTabRect, clickable = true)
        val autoTab = node("android.widget.Button", "Auto", autoTabRect, clickable = true)
        val stake = node("android.widget.EditText", "400.00", stakeRect, editable = true)
        val betButton = node("android.widget.Button", "Bet\n400.00 TZS", betButtonRect, clickable = true)
        val kids = mutableListOf(live, betTab, autoTab, stake, betButton)
        if (withAutoRow) {
            kids += node("android.widget.TextView", "Auto bet", Rect(50, 1495, 190, 1540))
            kids += node("android.widget.Switch", null, Rect(200, 1485, 300, 1550), clickable = true)
            kids += node("android.widget.TextView", "Auto Cash Out", Rect(446, 1495, 680, 1540))
            kids += node("android.widget.Switch", null, Rect(693, 1485, 793, 1550), clickable = true)
            kids += node("android.widget.EditText", "2.00", Rect(810, 1485, 975, 1550), editable = true)
        }
        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply { put(root, kids) }
        return Layout(root, TestController(tree), betButton)
    }

    @Test
    fun `picks the green Bet button not the Bet tab`() {
        val l = layout("8.72x", withAutoRow = false)
        val scan = l.controller.scan(l.root)
        assertEquals(betButtonRect, scan.betButtonBounds)
    }

    @Test
    fun `plane flying with Bet button visible is IN_FLIGHT not BETTING_OPEN`() {
        val l = layout("8.72x", withAutoRow = false)
        val scan = l.controller.scan(l.root)
        assertEquals("IN_FLIGHT", scan.gamePhase)
        assertEquals(SiteButtonState.IDLE_READY_TO_BET, scan.siteButtonState)
    }

    @Test
    fun `Bet button without a live multiplier is BETTING_OPEN and can queue a bet`() {
        val l = layout("", withAutoRow = false)
        val scan = l.controller.scan(l.root)
        assertEquals("BETTING_OPEN", scan.gamePhase)
        assertTrue(scan.canQueueBet)
    }

    @Test
    fun `Auto Cash Out switch and value box are matched by row and never reported as stake`() {
        val l = layout("46.27x", withAutoRow = true)
        val scan = l.controller.scan(l.root)
        assertTrue(scan.isAutoCashOutRowVisible)
        assertEquals(Rect(693, 1485, 793, 1550), scan.autoCashOutToggleBounds)
        assertEquals(Rect(810, 1485, 975, 1550), scan.autoCashOutInputBounds)
        assertEquals(2.00, scan.detectedAutoCashOutValue!!, 0.001)
        assertEquals(stakeRect, scan.stakeInputBounds)
    }

    @Test
    fun `Auto tab is reported when the Auto Cash Out row is hidden`() {
        val l = layout("46.27x", withAutoRow = false)
        val scan = l.controller.scan(l.root)
        assertFalse(scan.isAutoCashOutRowVisible)
        assertNotNull(scan.autoCashOutTabBounds)
        assertEquals(autoTabRect, scan.autoCashOutTabBounds)
    }

    @Test
    fun `nested WebView clickable parent wrapping single child Bet text resolves parent bounds and dispatches tap`() {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val betTab = node("android.view.View", text = null, bounds = betTabRect, clickable = true)
        val betTabLabel = node("android.widget.TextView", "Bet", Rect(350, 1100, 450, 1140), clickable = false)
        val stake = node("android.widget.EditText", "400.00", stakeRect, editable = true)
        val parentBtn = node("android.view.View", text = null, bounds = betButtonRect, clickable = true)
        val innerLabel = node("android.widget.TextView", "Bet\n400.00 TZS", Rect(640, 1270, 850, 1340), clickable = false)

        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(betTab, stake, parentBtn))
            put(betTab, listOf(betTabLabel))
            put(parentBtn, listOf(innerLabel))
        }
        val controller = TestController(tree, activeRoot = root)

        val scan = controller.scan(root)
        assertEquals(betButtonRect, scan.betButtonBounds)
        assertTrue(scan.isBetBtnClickable)
        assertTrue(scan.canQueueBet)

        var clicked: Boolean? = null
        controller.clickBetTarget { ok -> clicked = ok }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertEquals(true, clicked)
        assertEquals(1, controller.dispatchedGestures.size)
    }

    @Test
    fun `nested WebView clickable parent with separate sibling Bet and amount TextViews resolves parent container`() {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val parentBtn = node("android.view.View", text = null, bounds = betButtonRect, clickable = true)
        val titleNode = node("android.widget.TextView", "Bet", Rect(660, 1230, 830, 1280), clickable = false)
        val amountNode = node("android.widget.TextView", "400.00 TZS", Rect(600, 1295, 890, 1365), clickable = false)
        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(parentBtn))
            put(parentBtn, listOf(titleNode, amountNode))
        }
        val controller = TestController(tree, activeRoot = root)

        val scan = controller.scan(root)
        assertEquals(betButtonRect, scan.betButtonBounds)
        assertEquals(SiteButtonState.IDLE_READY_TO_BET, scan.siteButtonState)
        assertTrue(scan.isBetBtnClickable)
        assertTrue(scan.canQueueBet)

        var clicked: Boolean? = null
        controller.clickBetTarget { ok -> clicked = ok }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertEquals(true, clicked)
        assertEquals(1, controller.dispatchedGestures.size)
    }

    @Test
    fun `nested WebView clickable parent for Cash Out resolves parent container and dispatches tap`() {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val live = node("android.widget.TextView", "2.45x", Rect(290, 700, 800, 870))
        val cashOutParent = node("android.view.View", text = null, bounds = betButtonRect, clickable = true)
        val cashLabel = node("android.widget.TextView", "Cash Out", Rect(640, 1230, 860, 1280), clickable = false)
        val payoutLabel = node("android.widget.TextView", "980.00 TZS", Rect(600, 1295, 890, 1365), clickable = false)
        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(live, cashOutParent))
            put(cashOutParent, listOf(cashLabel, payoutLabel))
        }
        val controller = TestController(tree, activeRoot = root)

        val scan = controller.scan(root)
        assertEquals("IN_FLIGHT", scan.gamePhase)
        assertEquals(SiteButtonState.CASH_OUT_AVAILABLE, scan.siteButtonState)
        assertEquals(betButtonRect, scan.cashOutButtonBounds)
        assertTrue(scan.isCashOutBtnClickable)
    }

    @Test
    fun `unclickable Bet and Cash Out text labels without clickable parent are blocked and never tapped blindly`() {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val inertBetLabel = node("android.widget.TextView", "Bet\n400.00 TZS", Rect(600, 1250, 900, 1360), clickable = false)
        val nonClickableWrapper = node("android.widget.LinearLayout", text = null, bounds = betButtonRect, clickable = false)
        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(nonClickableWrapper))
            put(nonClickableWrapper, listOf(inertBetLabel))
        }
        val controller = TestController(tree, activeRoot = root)

        val scan = controller.scan(root)
        assertFalse(scan.isBetBtnClickable)
        assertFalse(scan.canQueueBet)

        var betClicked: Boolean? = null
        controller.clickBetTarget { ok -> betClicked = ok }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(false, betClicked)
        assertEquals(0, controller.dispatchedGestures.size)

        // Same safety check for unclickable Cash Out text banner
        inertBetLabel.text = "Cash Out\n800.00 TZS"
        val cashScan = controller.scan(root)
        assertFalse(cashScan.isCashOutBtnClickable)
    }

    @Test
    fun `stake stepper minus and plus buttons are found on the stake row`() {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val minus = node("android.widget.Button", null, Rect(55, 1195, 115, 1275), clickable = true)
        val stake = node("android.widget.EditText", "1.00", Rect(130, 1200, 340, 1270), editable = true)
        val plus = node("android.widget.Button", null, Rect(350, 1195, 410, 1275), clickable = true)
        val chip = node("android.widget.Button", "50", Rect(40, 1295, 225, 1350), clickable = true)
        val betBtn = node("android.widget.Button", "Bet\n1.00 TZS", Rect(460, 1200, 1033, 1425), clickable = true)
        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(minus, stake, plus, chip, betBtn))
        }
        val scan = TestController(tree).scan(root)
        assertEquals(Rect(55, 1195, 115, 1275), scan.stakeMinusBounds)
        assertEquals(Rect(350, 1195, 410, 1275), scan.stakePlusBounds)
    }

    @Test
    fun `stepper detection excludes 2x double button and detects disabled minus button`() {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val minus = node("android.widget.Button", "-", Rect(55, 1195, 115, 1275), clickable = true).apply {
            isEnabled = false
        }
        val stake = node("android.widget.EditText", "10.00", Rect(130, 1200, 340, 1270), editable = true)
        val doubleBtn = node("android.widget.Button", "2x", Rect(350, 1195, 430, 1275), clickable = true)
        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(minus, stake, doubleBtn))
        }
        val scan = TestController(tree).scan(root)
        assertEquals(Rect(55, 1195, 115, 1275), scan.stakeMinusBounds)
        assertFalse(scan.stakeMinusEnabled)
        assertEquals(null, scan.stakePlusBounds)
    }

    @Test
    fun `probeMinStake taps minus button repeatedly via dispatchTouchGesture until stake stops dropping`() {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val minus = node("android.widget.Button", "-", Rect(55, 1195, 115, 1275), clickable = true)
        val stake = node("android.widget.EditText", "100.00", Rect(130, 1200, 340, 1270), editable = true)
        val plus = node("android.widget.Button", "+", Rect(350, 1195, 410, 1275), clickable = true)
        val betBtn = node("android.widget.Button", "Bet\n100.00 TZS", Rect(460, 1200, 1033, 1425), clickable = true)
        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(minus, stake, plus, betBtn))
        }
        val stakeSteps = listOf("50.00", "10.00", "10.00")
        var stepIdx = 0
        val controller = object : AccessibilityController() {
            val tapCount get() = taps
            private var taps = 0
            override fun childNodesOf(node: AccessibilityNodeInfo) = tree[node] ?: emptyList()
            override fun resolveBestTargetWindowRoot(): Pair<AccessibilityNodeInfo, Boolean> = root to false
            override fun performPlatformDispatchGesture(
                gesture: android.accessibilityservice.GestureDescription,
                callback: GestureResultCallback,
                handler: android.os.Handler
            ): Boolean {
                taps++
                val nextText = stakeSteps.getOrElse(stepIdx++) { "10.00" }
                stake.text = nextText
                betBtn.text = "Bet\n$nextText TZS"
                callback.onCompleted(gesture)
                return true
            }
        }

        var learnedMin: Double? = null
        var completed = false
        controller.probeMinStake { min ->
            learnedMin = min
            completed = true
        }
        repeat(10) {
            org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }

        assertTrue(completed)
        assertEquals(10.0, learnedMin!!, 0.001)
        assertEquals(3, controller.tapCount)
    }

    @Test
    fun `probeMinStake returns current stake immediately without tapping when minus is disabled`() {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val minus = node("android.widget.Button", "-", Rect(55, 1195, 115, 1275), clickable = true).apply {
            isEnabled = false
        }
        val stake = node("android.widget.EditText", "1.00", Rect(130, 1200, 340, 1270), editable = true)
        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(minus, stake))
        }
        val controller = TestController(tree, activeRoot = root)

        var learnedMin: Double? = null
        controller.probeMinStake { min -> learnedMin = min }
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(1.0, learnedMin!!, 0.001)
        assertEquals(0, controller.dispatchedGestures.size)
    }

    @Test
    fun `probeMinStake aborts safely with null when a bet is already queued or active`() {
        val root = node("android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400))
        val minus = node("android.widget.Button", "-", Rect(55, 1195, 115, 1275), clickable = true)
        val stake = node("android.widget.EditText", "10.00", Rect(130, 1200, 340, 1270), editable = true)
        val cancelBtn = node("android.widget.Button", "Cancel", Rect(460, 1200, 1033, 1425), clickable = true)
        val tree = IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>().apply {
            put(root, listOf(minus, stake, cancelBtn))
        }
        val controller = TestController(tree, activeRoot = root)

        var learnedMin: Double? = -1.0
        controller.probeMinStake { min -> learnedMin = min }
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(null, learnedMin)
        assertEquals(0, controller.dispatchedGestures.size)
    }
}
