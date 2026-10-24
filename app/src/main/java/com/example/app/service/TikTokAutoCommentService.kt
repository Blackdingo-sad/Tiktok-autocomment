package com.example.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TikTokAutoCommentService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceRunning.value = true
        Log.d(TAG, "TikTokAutoCommentService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { pkg ->
            if (pkg.isNotEmpty()) {
                currentPackageName = pkg
            }
        }
    }

    override fun onInterrupt() {
        _isServiceRunning.value = false
        Log.d(TAG, "TikTokAutoCommentService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        private const val TAG = "TikTokAutoComment"
        private var instance: TikTokAutoCommentService? = null
        private var currentPackageName: String = ""

        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically", // TikTok Global
            "com.ss.android.ugc.trill", // TikTok Asia / Vietnam
            "com.ss.android.ugc.aweme"  // Douyin / TikTok China
        )

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _lastCommentLog = MutableStateFlow<String>("")
        val lastCommentLog: StateFlow<String> = _lastCommentLog.asStateFlow()

        private var lastCommentTime = 0L
        private const val COOLDOWN_MS = 3000L // 3s cooldown to prevent TikTok spam flag

        fun isConnected(context: Context? = null): Boolean {
            if (instance != null) return true
            if (context != null) {
                return isAccessibilityEnabledInSettings(context)
            }
            return false
        }

        fun isAccessibilityEnabledInSettings(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                for (service in enabledServices) {
                    if (service.resolveInfo?.serviceInfo?.packageName == context.packageName) {
                        return true
                    }
                }
            }

            val enabledSettings = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            return enabledSettings.contains(context.packageName)
        }

        fun isTikTokForeground(): Boolean {
            return TIKTOK_PACKAGES.contains(currentPackageName)
        }

        fun autoComment(text: String, prefixAt: Boolean = true): Boolean {
            val service = instance ?: return false
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCommentTime < COOLDOWN_MS) {
                val remaining = (COOLDOWN_MS - (currentTime - lastCommentTime)) / 1000
                _lastCommentLog.value = "Chờ $remaining s (Cooldown) trước khi comment tiếp..."
                return false
            }

            val formattedText = if (prefixAt && !text.startsWith("@")) "@$text" else text
            lastCommentTime = currentTime
            return service.performCommentInput(formattedText)
        }
    }

    init {
        instance = this
    }

    private fun performCommentInput(formattedText: String): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            _lastCommentLog.value = "Không tìm thấy cửa sổ ứng dụng active."
            return false
        }

        val activePackage = rootNode.packageName?.toString() ?: currentPackageName
        if (activePackage.isNotEmpty() && !TIKTOK_PACKAGES.contains(activePackage)) {
            _lastCommentLog.value = "Ứng dụng hiện tại không phải TikTok (Đang mở: $activePackage)."
            return false
        }

        // Find comment edit text node
        val inputNode = findCommentInputNode(rootNode)
        if (inputNode == null) {
            _lastCommentLog.value = "Đã mở TikTok nhưng chưa tìm thấy khung nhập comment Live."
            return false
        }

        // Set text directly into memory without opening virtual keyboard
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formattedText)
        }
        val textSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (!textSuccess) {
            _lastCommentLog.value = "Lỗi khi nhập văn bản vào khung comment."
            return false
        }

        // Delay slightly and find send button
        Handler(Looper.getMainLooper()).postDelayed({
            val currentRoot = rootInActiveWindow ?: rootNode
            val sendButton = findSendButtonNode(currentRoot, inputNode)
            if (sendButton != null) {
                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                _lastCommentLog.value = "Đã tự động gửi comment: $formattedText"
            } else {
                _lastCommentLog.value = "Đã nhập '$formattedText' (Chưa bấm gửi, hãy kiểm tra nút Gửi TikTok)."
            }
        }, 300)

        return true
    }

    private fun findCommentInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // First check by class EditText
        if (node.className == "android.widget.EditText" || node.isEditable) {
            return node
        }

        val textHint = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (textHint.contains("nhập", ignoreCase = true) ||
            textHint.contains("bình luận", ignoreCase = true) ||
            textHint.contains("comment", ignoreCase = true) ||
            textHint.contains("thêm", ignoreCase = true)
        ) {
            if (node.isEditable) return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findCommentInputNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSendButtonNode(root: AccessibilityNodeInfo, inputNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        // 1. Direct search by text label "Gửi" / "Send"
        val directMatch = searchByText(root)
        if (directMatch != null) return directMatch

        // 2. Fallback: Search siblings of inputNode inside its parent layout
        if (inputNode != null) {
            val parent = inputNode.parent
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    if (sibling != inputNode && sibling.isClickable) {
                        return sibling
                    }
                }
            }
        }
        return null
    }

    private fun searchByText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (node.isClickable && (
                text.equals("Gửi", ignoreCase = true) ||
                text.equals("Send", ignoreCase = true) ||
                text.contains("send", ignoreCase = true) ||
                text.contains("gửi", ignoreCase = true)
            )
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchByText(child)
            if (found != null) return found
        }
        return null
    }
}
