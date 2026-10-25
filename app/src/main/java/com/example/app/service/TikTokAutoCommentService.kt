package com.example.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fills an identified TikTok Live composer and optionally activates its send control. */
class TikTokAutoCommentService : AccessibilityService() {

    private data class InputCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int,
        val commentSpecific: Boolean,
        val genericInputHint: Boolean,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceRunning.value = true
        Log.d(TAG, "TikTokAutoCommentService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!actionsEnabled) return
        event?.packageName?.toString()?.let { pkg ->
            if (pkg.isNotEmpty()) currentPackageName = pkg
        }
    }

    override fun onInterrupt() {
        _isServiceRunning.value = false
        Log.d(TAG, "TikTokAutoCommentService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        if (instance == this) instance = null
    }

    companion object {
        private const val TAG = "TikTokAutoComment"
        private const val COOLDOWN_MS = 3_000L
        private const val MAX_NODE_DEPTH = 30
        private const val MAX_COMPOSER_ANCESTORS = 5
        private var instance: TikTokAutoCommentService? = null
        @Volatile private var currentPackageName: String = ""
        @Volatile private var actionsEnabled = false

        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically", // TikTok Global
            "com.ss.android.ugc.trill", // TikTok Asia / Vietnam
            "com.ss.android.ugc.aweme"  // Douyin / TikTok China
        )

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _lastCommentLog = MutableStateFlow("")
        val lastCommentLog: StateFlow<String> = _lastCommentLog.asStateFlow()

        private var lastCommentTime = 0L

        fun isConnected(context: Context? = null): Boolean {
            if (instance != null) return true
            return context?.let(::isAccessibilityEnabledInSettings) ?: false
        }

        fun setActionsEnabled(enabled: Boolean) {
            actionsEnabled = enabled
            if (!enabled) currentPackageName = ""
        }

        fun isAccessibilityEnabledInSettings(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                if (enabledServices.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }) return true
            }
            val enabledSettings = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledSettings.contains(context.packageName)
        }

        fun isTikTokForeground(): Boolean = actionsEnabled && TIKTOK_PACKAGES.contains(currentPackageName)

        fun autoComment(text: String, prefixAt: Boolean = true, autoSend: Boolean = false): Boolean {
            if (!actionsEnabled) {
                _lastCommentLog.value = "App đã ngắt kết nối; không điền comment."
                return false
            }
            val service = instance ?: return false
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastCommentTime < COOLDOWN_MS) {
                val remaining = ((COOLDOWN_MS - (currentTime - lastCommentTime)) / 1000.0).coerceAtLeast(0.1)
                _lastCommentLog.value = "Bỏ qua số mới · đang giới hạn cập nhật ô comment, còn ${"%.1f".format(remaining)} s."
                return false
            }
            val formattedText = if (prefixAt && !text.startsWith("@")) "@$text" else text
            return service.performCommentInput(formattedText, autoSend)
        }
    }

    init {
        instance = this
    }

    private fun performCommentInput(formattedText: String, autoSend: Boolean): Boolean {
        val root = activeTikTokRoot()
        if (root == null || TIKTOK_PACKAGES.none { it == root.packageName?.toString() }) {
            _lastCommentLog.value = "Không thao tác: cửa sổ TikTok Live không ở trước màn hình."
            return false
        }
        val activePackage = root.packageName?.toString() ?: return false

        var input = findCommentInputNode(root) ?: run {
            _lastCommentLog.value = "Không tìm thấy ô comment Live của TikTok; chưa nhập số."
            return false
        }

        if (!supportsSetText(input) || !input.isFocused) {
            val focused = supportsSetText(input) &&
                performSafely(input) { it.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            if (!focused && !performSafely(input) { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) {
                _lastCommentLog.value = "Đã thấy placeholder ô comment nhưng TikTok không cho focus; chưa nhập số."
                return false
            }
            val refreshedRoot = activeTikTokRoot()
            if (refreshedRoot?.packageName?.toString() != activePackage) {
                _lastCommentLog.value = "TikTok đổi màn hình trước khi nhập; chưa điền số."
                return false
            }
            val refreshedInput = focusedTextInput(refreshedRoot)
                ?: findCommentInputNode(refreshedRoot)?.takeIf(::supportsSetText) ?: run {
                _lastCommentLog.value = "Ô comment không còn khả dụng sau khi được focus; chưa nhập số."
                return false
            }
            input = refreshedInput
        }

        val setTextArguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, formattedText)
        }
        val textEntered = performSafely(input) {
            it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArguments)
        }
        if (!textEntered) {
            _lastCommentLog.value = "Đã tìm thấy ô comment TikTok nhưng ô không nhận số."
            return false
        }

        lastCommentTime = System.currentTimeMillis()
        if (!autoSend) {
            _lastCommentLog.value = "Đã điền $formattedText vào ô comment TikTok; chưa gửi."
            return true
        }

        if (!performSafely(input) { it.refresh() } || input.text?.toString() != formattedText) {
            _lastCommentLog.value = "Đã điền số nhưng chưa xác minh được nội dung ô; chưa gửi."
            return false
        }
        val sendButton = findSendButtonNode(input)
        if (sendButton == null || !performSafely(sendButton) {
                actionsEnabled &&
                    rootInActiveWindow?.packageName?.toString() == activePackage &&
                    it.isVisibleToUser && it.isEnabled &&
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        ) {
            _lastCommentLog.value = "Đã điền $formattedText; không tìm thấy hoặc không bấm được nút Gửi."
            return false
        }

        _lastCommentLog.value = "Đã bấm Gửi $formattedText; TikTok chưa xác nhận bình luận hiển thị."
        return true
    }

    private fun findCommentInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<InputCandidate>()
        collectEditableNodes(root, candidates, 0)

        val specific = candidates.filter { it.commentSpecific }
        if (specific.isNotEmpty()) return specific.maxByOrNull { it.score }?.node

        val genericHints = candidates.filter { it.genericInputHint }
        if (genericHints.size == 1) return genericHints.single().node

        focusedTextInput(root)?.let { focused ->
            if (hasCommentComposerAncestor(focused) || hasAdjacentSendControl(focused)) return focused
        }

        // Fallback only when TikTok exposes one focused editable field and it has a send
        // control in the same compact composer row. Never choose an arbitrary EditText.
        val focused = candidates.filter { it.node.isFocused }
        if (focused.size == 1 && hasAdjacentSendControl(focused.single().node)) return focused.single().node

        // TikTok sometimes exposes only the fixed composer placeholder, not an editable node.
        // Match that exact label, then tap its nearest clickable composer ancestor to obtain focus.
        return findCommentPlaceholder(root)?.let(::nearestClickableAncestor)
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo, out: MutableList<InputCandidate>, depth: Int) {
        if (depth > MAX_NODE_DEPTH || !node.isVisibleToUser) return
        if (supportsSetText(node) && node.isEnabled) {
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val hint = nodeHint(node).lowercase()
            val idSpecific = listOf("comment", "chat", "room_chat", "roomchat").any(id::contains)
            val hintSpecific = listOf("comment", "bình luận", "binh luan", "chat").any(hint::contains)
            val genericInputHint = listOf("nhập", "nhap", "type", "write").any(hint::contains)
            val hasComposerContext = hasCommentComposerAncestor(node) ||
                (genericInputHint && hasAdjacentSendControl(node))
            val score = (if (idSpecific) 100 else 0) + (if (hintSpecific) 80 else 0) +
                (if (genericInputHint) 20 else 0) + (if (node.isFocused) 10 else 0)
            if (idSpecific || hintSpecific || hasComposerContext) {
                out += InputCandidate(node, score, commentSpecific = true, genericInputHint = genericInputHint)
            } else if (genericInputHint) {
                out += InputCandidate(node, score, commentSpecific = false, genericInputHint = true)
            } else if (node.isFocused) {
                out += InputCandidate(node, score, commentSpecific = false, genericInputHint = false)
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectEditableNodes(child, out, depth + 1)
        }
    }

    private fun focusedTextInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf {
                it.isVisibleToUser && it.isEnabled && supportsSetText(it)
            }
        } catch (_: Exception) {
            null
        }

    private fun supportsSetText(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.actionList.any {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id
        }

    private fun findCommentPlaceholder(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val labels = listOf("Nhập...", "Nhập…")
        return labels.asSequence().flatMap { label ->
            try {
                root.findAccessibilityNodeInfosByText(label).orEmpty().asSequence()
            } catch (_: Exception) {
                emptySequence()
            }
        }.firstOrNull { node ->
            node.isVisibleToUser &&
                (node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim()) in labels
        }
    }

    private fun nearestClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_COMPOSER_ANCESTORS) {
            val candidate = current ?: return null
            if (candidate.isVisibleToUser && candidate.isEnabled &&
                (candidate.isClickable || candidate.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                })
            ) return candidate
            current = candidate.parent
        }
        return null
    }

    private fun nodeHint(node: AccessibilityNodeInfo): String {
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
        return "$hint ${node.contentDescription?.toString().orEmpty()}"
    }

    private fun hasCommentComposerAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        repeat(MAX_COMPOSER_ANCESTORS) {
            if (parent == null) return false
            val metadata = "${parent.viewIdResourceName.orEmpty()} ${parent.contentDescription?.toString().orEmpty()}".lowercase()
            if (listOf("comment", "chat", "bình luận", "binh luan").any(metadata::contains)) return true
            parent = parent.parent
        }
        return false
    }

    private fun hasAdjacentSendControl(input: AccessibilityNodeInfo): Boolean = findSendButtonNode(input) != null

    private fun activeTikTokRoot(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (TIKTOK_PACKAGES.contains(activeRoot?.packageName?.toString())) return activeRoot
        return try {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.isActive }
                .mapNotNull { it.root }
                .firstOrNull { TIKTOK_PACKAGES.contains(it.packageName?.toString()) }
        } catch (_: Exception) {
            null
        }
    }

    private fun findSendButtonNode(input: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val inputBounds = Rect().also(input::getBoundsInScreen)
        var container: AccessibilityNodeInfo? = input.parent
        repeat(MAX_COMPOSER_ANCESTORS) {
            val current = container ?: return null
            val matches = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
            collectSendButtons(current, input, matches, 0)
            matches.maxByOrNull { it.second }?.let { return it.first }

            // If labels/IDs are hidden, accept only a single clickable control immediately
            // to the right of the composer input, within the same row.
            val nearby = mutableListOf<AccessibilityNodeInfo>()
            collectNearbyClickables(current, input, inputBounds, nearby, 0)
            nearby.maxByOrNull { node -> Rect().also(node::getBoundsInScreen).centerX() }?.let { return it }
            container = current.parent
        }
        return null
    }

    private fun collectSendButtons(
        node: AccessibilityNodeInfo,
        input: AccessibilityNodeInfo,
        out: MutableList<Pair<AccessibilityNodeInfo, Int>>,
        depth: Int,
    ) {
        if (depth > MAX_NODE_DEPTH || !node.isVisibleToUser) return
        if (node !== input && node.isClickable && node.isEnabled) {
            val metadata = "${node.viewIdResourceName.orEmpty()} ${node.contentDescription?.toString().orEmpty()}".lowercase()
            val matched = listOf("send", "submit", "comment_send", "chat_send", "gửi", "gui", "đăng", "dang")
                .any(metadata::contains)
            if (matched) out += node to (if (metadata.contains("send") || metadata.contains("gửi") || metadata.contains("gui")) 100 else 50)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectSendButtons(child, input, out, depth + 1)
        }
    }

    private fun collectNearbyClickables(
        node: AccessibilityNodeInfo,
        input: AccessibilityNodeInfo,
        inputBounds: Rect,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > MAX_NODE_DEPTH || !node.isVisibleToUser) return
        if (node !== input && node.isClickable && node.isEnabled) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val sameRow = bounds.bottom > inputBounds.top && bounds.top < inputBounds.bottom
            val rightOfInput = bounds.centerX() >= inputBounds.right - dp(8)
            val close = bounds.left - inputBounds.right <= dp(120)
            if (sameRow && rightOfInput && close && bounds.width() > 0 && bounds.height() > 0) out += node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectNearbyClickables(child, input, inputBounds, out, depth + 1)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inline fun performSafely(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Boolean): Boolean =
        try { action(node) } catch (_: Exception) { false }

}
