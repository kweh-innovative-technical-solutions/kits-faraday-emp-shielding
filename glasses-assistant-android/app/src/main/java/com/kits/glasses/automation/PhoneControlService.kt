package com.kits.glasses.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kits.glasses.brain.PhoneCommand

/**
 * The assistive automation surface: it reads the current screen into text and
 * performs a single planned [PhoneCommand] per voice turn.
 *
 * The foreground service reaches it through the static [instance], which is set
 * only while the system has the service bound and enabled. A null [instance]
 * means "accessibility not enabled" — the single most common first-run failure.
 */
class PhoneControlService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneControlService? = null
            private set

        /** True when the user has enabled the service in Settings. */
        val isEnabled: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive. We read the screen on demand in snapshotScreen(), not per event.
    }

    override fun onInterrupt() {
        // No-op: we hold no long-running feedback to interrupt.
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Flatten the active window into a compact, model-friendly text snapshot. */
    fun snapshotScreen(): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        val sb = StringBuilder()
        sb.append("app_package: ")
            .append(root.packageName?.toString() ?: "unknown")
            .append('\n')
        collectText(root, sb, 0)
        return sb.toString().take(MAX_SNAPSHOT_CHARS)
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > MAX_DEPTH) return
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val label = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() -> desc
            else -> null
        }
        if (label != null) {
            if (node.isClickable) sb.append("[clickable] ")
            sb.append(label).append('\n')
        }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb, depth + 1)
        }
    }

    /** Execute one planned command. Returns true on a successful action. */
    fun perform(cmd: PhoneCommand): Boolean = when (cmd.action.lowercase()) {
        "open_app" -> cmd.target?.let { openApp(it) } ?: false
        "tap" -> cmd.target?.let { tapByText(it) } ?: false
        "type" -> cmd.target?.let { typeText(it) } ?: false
        "scroll" -> scroll(cmd.target)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "none", "speak" -> true // Nothing to do on-screen; the spoken reply is the action.
        else -> false
    }

    private fun openApp(name: String): Boolean {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val target = name.trim().lowercase()
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(target)
        } ?: return false
        val launchIntent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(launchIntent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: return false
        // Climb to the nearest clickable ancestor; many labels sit inside a clickable row.
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        return (clickable ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun typeText(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun scroll(direction: String?): Boolean {
        val scrollable = findScrollable(rootInActiveWindow) ?: return false
        val action = if (direction?.trim()?.lowercase() == "up") {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return scrollable.performAction(action)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }
}

private const val MAX_DEPTH = 40
private const val MAX_SNAPSHOT_CHARS = 6000
