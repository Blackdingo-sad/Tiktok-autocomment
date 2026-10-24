package com.example.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.app.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private var rootContainer: FrameLayout? = null
    private var popupControlView: LinearLayout? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var isPopupExpanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupFloatingView()
        _isWidgetRunning.value = true
        instance = this
    }

    private fun setupFloatingView() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = resources.displayMetrics.density

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        val container = FrameLayout(this)

        // 1. Floating Circle View
        val bubbleSizePx = (60 * density).toInt()
        val bubbleView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E88E5"))
            }
        }

        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            val padding = (12 * density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        bubbleView.addView(iconView)

        // 2. Compact Control Popup Card
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bgCard = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(Color.parseColor("#263238"))
            }
            background = bgCard
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            visibility = View.GONE

            val titleText = TextView(this@FloatingWidgetService).apply {
                text = "TikTok Live Control"
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            addView(titleText)

            val statusText = TextView(this@FloatingWidgetService).apply {
                text = "Trạng thái: Đang lắng nghe"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
            }
            addView(statusText)

            // Button 1: Pause / Resume
            val btnPause = Button(this@FloatingWidgetService).apply {
                text = "Tạm Dừng / Tiếp Tục"
                textSize = 11f
                setOnClickListener {
                    _isPaused.value = !_isPaused.value
                    statusText.text = if (_isPaused.value) "Trạng thái: Đã Tạm Dừng" else "Trạng thái: Đang Lắng Nghe"
                }
            }
            addView(btnPause)

            // Button 2: Open Full App
            val btnOpenApp = Button(this@FloatingWidgetService).apply {
                text = "Mở App Cài Đặt"
                textSize = 11f
                setOnClickListener {
                    togglePopup(container)
                    openMainActivity()
                }
            }
            addView(btnOpenApp)

            // Button 3: Stop / Close
            val btnClose = Button(this@FloatingWidgetService).apply {
                text = "Tắt Nút Nổi"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#D32F2F"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    stopSelf()
                }
            }
            addView(btnClose)
        }

        popupControlView = popupView

        container.addView(popupView)
        container.addView(bubbleView)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = true

        bubbleView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                    }
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    windowManager.updateViewLayout(container, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        v.performClick()
                        togglePopup(container)
                    }
                    true
                }
                else -> false
            }
        }

        rootContainer = container
        windowManager.addView(rootContainer, layoutParams)
    }

    private fun togglePopup(container: FrameLayout) {
        isPopupExpanded = !isPopupExpanded
        popupControlView?.visibility = if (isPopupExpanded) View.VISIBLE else View.GONE
        windowManager.updateViewLayout(container, layoutParams)
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        rootContainer?.let {
            windowManager.removeView(it)
        }
        _isWidgetRunning.value = false
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        private var instance: FloatingWidgetService? = null
        private val _isWidgetRunning = MutableStateFlow(false)
        val isWidgetRunning: StateFlow<Boolean> = _isWidgetRunning.asStateFlow()

        private val _isPaused = MutableStateFlow(false)
        val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

        fun isRunning(): Boolean = instance != null

        fun stopWidget(context: Context) {
            context.stopService(Intent(context, FloatingWidgetService::class.java))
        }

        fun startWidget(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                context.startService(Intent(context, FloatingWidgetService::class.java))
            }
        }
    }
}
