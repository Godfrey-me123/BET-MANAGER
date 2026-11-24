package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AutoTouchDriver(private val service: AccessibilityService) {

    private fun findRoots(): List<AccessibilityNodeInfo> {
        val windowsList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            service.windows.mapNotNull { it.root }
        } else {
            emptyList()
        }
        val activeRoot = service.rootInActiveWindow
        return (windowsList + listOfNotNull(activeRoot))
            .filter { it.packageName != service.packageName }
    }

    fun findNode(query: String, editableOnly: Boolean = false): AccessibilityNodeInfo? {
        val q = query.trim().lowercase()
        val roots = findRoots()
        var partial: AccessibilityNodeInfo? = null

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let(queue::add)
                }
                if (editableOnly && !n.isEditable) continue
                if (!n.isVisibleToUser) continue
                if (n.isEnabled == false) continue

                val candidates = listOfNotNull(
                    n.text?.toString(),
                    n.contentDescription?.toString(),
                    n.hintText?.toString(),
                    n.viewIdResourceName?.substringAfter(":id/"),
                ).map { it.trim().lowercase() }

                if (candidates.any { it == q }) return n
                if (partial == null && candidates.any { it.contains(q) }) {
                    partial = n
                }
            }
        }
        return partial
    }

    suspend fun waitForNode(query: String, editableOnly: Boolean = false, timeoutMs: Long = 3000): AccessibilityNodeInfo? {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= until) {
            findNode(query, editableOnly)?.let { return it }
            delay(250)
        }
        return null
    }

    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return tap(r.exactCenterX(), r.exactCenterY())
    }

    suspend fun clickQuery(query: String): Boolean {
        val node = waitForNode(query, editableOnly = false, timeoutMs = 2000) ?: return false
        return clickNode(node)
    }

    suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }
            override fun onCancelled(g: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!accepted && cont.isActive) cont.resume(false)
    }

    fun findStakeInputField(): AccessibilityNodeInfo? {
        val roots = findRoots()
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let(queue::add)
                }
                if (n.isEditable && n.isVisibleToUser) {
                    val hint = n.hintText?.toString().orEmpty()
                    val parentText = n.parent?.text?.toString()?.lowercase().orEmpty()
                    if (!parentText.contains("auto cash") && !hint.lowercase().contains("cash out")) {
                        return n
                    }
                }
            }
        }
        return null
    }

    fun findMultiplierInputField(): AccessibilityNodeInfo? {
        val roots = findRoots()
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let(queue::add)
                }
                if (n.isEditable && n.isVisibleToUser) {
                    val parentText = n.parent?.text?.toString()?.lowercase().orEmpty()
                    val hint = n.hintText?.toString().orEmpty()
                    if (parentText.contains("auto cash") || hint.lowercase().contains("cash out")) {
                        return n
                    }
                }
            }
        }
        return null
    }

    suspend fun setStakeText(targetStake: Double): Boolean {
        for (attempt in 1..2) {
            val field = findStakeInputField() ?: waitForNode("stake", editableOnly = true, timeoutMs = 1000)
            if (field == null) {
                delay(200)
                continue
            }
            clickNode(field)
            delay(200)

            val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: field
            val formatted = String.format(java.util.Locale.US, "%.2f", targetStake)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formatted)
            }
            if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }

            for (poll in 1..5) {
                delay(100)
                val currentText = field.text?.toString()?.toDoubleOrNull() ?: focused.text?.toString()?.toDoubleOrNull()
                if (currentText != null && kotlin.math.abs(currentText - targetStake) <= 0.05) {
                    closeKeyboard()
                    return true
                }
            }
        }
        return false
    }

    fun closeKeyboard() {
        runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
}
