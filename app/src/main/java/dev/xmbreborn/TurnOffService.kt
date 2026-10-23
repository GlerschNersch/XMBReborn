package dev.xmbreborn

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Lets Users > Turn Off System lock the screen the way the power button does. Android only lets
 * an accessibility service do that without device-admin rights, so this one watches nothing (its
 * config asks for no events and no window content) and just performs the global action.
 */
class TurnOffService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        /** Set while the user has the service switched on in Android's accessibility settings. */
        @Volatile var instance: TurnOffService? = null
            private set

        /** Runs [action] (an AccessibilityService.GLOBAL_ACTION_*); false if the service is off. */
        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) == true
    }
}
