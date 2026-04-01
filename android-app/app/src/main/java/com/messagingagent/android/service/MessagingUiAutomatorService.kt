package com.messagingagent.android.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

class MessagingUiAutomatorService : AccessibilityService() {

    companion object {
        var instance: MessagingUiAutomatorService? = null
        var lastFoundSendButtonRect: android.graphics.Rect? = null

        /** Finds the Send button and clicks it using the native UI tree */
        fun clickSendButton(): Boolean {
            val svc = instance
            if (svc == null) {
                Timber.e("AccessibilityService is not running!")
                return false
            }

            val root = svc.rootInActiveWindow
            if (root == null) {
                Timber.e("AccessibilityService rootInActiveWindow is null!")
                return false
            }

            var clicked = false
            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null || clicked) return
                
                // Check if node matches our targets
                var matches = false
                val resName = node.viewIdResourceName ?: ""
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                val txt = node.text?.toString()?.lowercase() ?: ""
                
                if (resName.contains("Compose:Draft:Send", ignoreCase = true) || resName.contains("send_message_button_icon", ignoreCase = true)) {
                    matches = true
                } else if (txt.contains("send") || txt.contains("αποστολ") || txt.contains("enviar")) {
                    matches = true
                } else if (cd.contains("send") || cd.contains("αποστολ") || cd.contains("enviar")) {
                     matches = true
                }

                if (matches) {
                    var current: AccessibilityNodeInfo? = node
                    while (current != null && !clicked) {
                        if (current.isClickable) {
                            val rect = android.graphics.Rect()
                            current.getBoundsInScreen(rect)
                            lastFoundSendButtonRect = rect
                            
                            current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Timber.i("Successfully clicked Send button natively via recursive traversal!")
                            clicked = true
                            return
                        }
                        current = current.parent
                    }
                }
                
                for (i in 0 until node.childCount) {
                    traverse(node.getChild(i))
                    if (clicked) return
                }
            }
            
            traverse(root)
            if (clicked) return true

            Timber.w("Could not find any clickable Send button in the active window hierarchy.")
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("MessagingUiAutomatorService Native Accessibility Connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only use this service for active dispatching, passive events are ignored to save CPU
    }

    override fun onInterrupt() {
        Timber.w("MessagingUiAutomatorService Interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
