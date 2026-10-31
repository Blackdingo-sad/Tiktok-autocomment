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
import android.view.animation.AnticipateInterpolator
import android.view.animation.OvershootInterpolator
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

        // 1. Floating Circle View (The Sphere / Bubble trigger)
        val bubbleSizePx = (50 * density).toInt()
        val bubbleView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0F172A"))
            }
        }

        val iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            val padding = (10 * density).toInt()
            setPadding(padding, padding, padding, padding)
            setColorFilter(Color.parseColor("#00E676"))
        }
        bubbleView.addView(iconView)

        // 2. Compact Control Popup Card (No duplicate icon, placed cleanly below bubble)
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bgCard = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(Color.parseColor("#0F172A"))
            }
            background = bgCard
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)

            val lp = FrameLayout.LayoutParams(
                (180 * density).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (56 * density).toInt()
            }
            layoutParams = lp
            visibility = View.GONE

            val titleText = TextView(this@FloatingWidgetService).apply {
                text = "⚡ TikTok Speed Boost"
                setTextColor(Color.parseColor("#00E676"))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            addView(titleText)

            val statusText = TextView(this@FloatingWidgetService).apply {
                text = "Trạng thái: Đang Tăng Tốc ⚡"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10f
                setPadding(0, (2 * density).toInt(), 0, (8 * density).toInt())
            }
            addView(statusText)

            // Button 1: Toggle Boost
            val btnBoost = Button(this@FloatingWidgetService).apply {
                text = "Bật / Tắt Boost"
                textSize = 10f
                setPadding(0, 0, 0, 0)
                val btnLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (32 * density).toInt()
                ).apply {
                    bottomMargin = (4 * density).toInt()
                }
                layoutParams = btnLp
                setOnClickListener {
                    _isBoostActive.value = !_isBoostActive.value
                    statusText.text = if (_isBoostActive.value) "Trạng thái: Đang Tăng Tốc ⚡" else "Trạng thái: Tắt Boost"
                }
            }
            addView(btnBoost)

            // Button 2: Open Full App Settings
            val btnOpenApp = Button(this@FloatingWidgetService).apply {
                text = "Mở App Cài Đặt"
                textSize = 10f
                setPadding(0, 0, 0, 0)
                val btnLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (32 * density).toInt()
                ).apply {
                    bottomMargin = (4 * density).toInt()
                }
                layoutParams = btnLp
                setOnClickListener {
                    togglePopup(container, density)
                    openMainActivity()
                }
            }
            addView(btnOpenApp)

            // Button 3: Close Floating Widget
            val btnClose = Button(this@FloatingWidgetService).apply {
                text = "Tắt Nút Nổi"
                textSize = 10f
                setPadding(0, 0, 0, 0)
                val btnLp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (32 * density).toInt()
                )
                layoutParams = btnLp
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
                        togglePopup(container, density)
                    }
                    true
                }
                else -> false
            }
        }

        rootContainer = container
        windowManager.addView(rootContainer, layoutParams)
    }

    private fun togglePopup(container: FrameLayout, density: Float) {
        val popupView = popupControlView ?: return
        isPopupExpanded = !isPopupExpanded

        popupView.pivotX = 25f * density
        popupView.pivotY = 0f

        if (isPopupExpanded) {
            popupView.scaleX = 0f
            popupView.scaleY = 0f
            popupView.alpha = 0f
            popupView.visibility = View.VISIBLE
            popupView.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(260)
                .setInterpolator(OvershootInterpolator(1.3f))
                .start()
        } else {
            popupView.animate()
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(AnticipateInterpolator(1.2f))
                .withEndAction { popupView.visibility = View.GONE }
                .start()
        }
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

        private val _isBoostActive = MutableStateFlow(true)
        val isBoostActive: StateFlow<Boolean> = _isBoostActive.asStateFlow()

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
