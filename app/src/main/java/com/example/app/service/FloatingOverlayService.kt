package com.example.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.app.R
import com.example.app.speech.SpeechRecognitionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: FrameLayout
    private lateinit var bubbleCard: FrameLayout
    private lateinit var iconView: ImageView
    private lateinit var statusText: TextView

    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var speechManager: SpeechRecognitionManager

    private val handler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { shrinkToIdleState() }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isRunning = false
    private var isShrunk = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        _isServiceActive.value = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        speechManager = SpeechRecognitionManager(this)

        startForegroundService()
        setupFloatingView()
    }

    private fun startForegroundService() {
        val channelId = "floating_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "TikTok Live Bubble Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TikTok Auto Comment Bubble")
            .setContentText("Bong bóng nổi đang hoạt động")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    private fun setupFloatingView() {
        val sizeDp = 60
        val density = resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()

        layoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        overlayView = FrameLayout(this)

        bubbleCard = FrameLayout(this).apply {
            background = createCircleDrawable(Color.parseColor("#1976D2")) // Primary blue
            elevation = 12f * density
        }

        iconView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
        }

        statusText = TextView(this).apply {
            text = "OFF"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        }

        val iconParams = FrameLayout.LayoutParams(
            (28 * density).toInt(),
            (28 * density).toInt()
        ).apply {
            gravity = Gravity.CENTER
        }

        bubbleCard.addView(iconView, iconParams)
        bubbleCard.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        statusText.visibility = View.GONE

        overlayView.addView(bubbleCard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        overlayView.setOnTouchListener(createTouchListener())
        windowManager.addView(overlayView, layoutParams)

        resetIdleTimer()
    }

    private fun createCircleDrawable(colorInt: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorInt)
            setStroke(3, Color.WHITE)
        }
    }

    private fun createTouchListener() = View.OnTouchListener { _, event ->
        resetIdleTimer()
        if (isShrunk) {
            expandFromIdleState()
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                windowManager.updateViewLayout(overlayView, layoutParams)
                true
            }
            MotionEvent.ACTION_UP -> {
                val diffX = abs(event.rawX - initialTouchX)
                val diffY = abs(event.rawY - initialTouchY)
                if (diffX < 10 && diffY < 10) {
                    toggleRunningState()
                }
                true
            }
            else -> false
        }
    }

    private fun toggleRunningState() {
        isRunning = !isRunning
        if (isRunning) {
            speechManager.startListening()
            bubbleCard.background = createCircleDrawable(Color.parseColor("#388E3C")) // Green active
            iconView.setImageResource(android.R.drawable.ic_media_pause)
            statusText.visibility = View.GONE
            iconView.visibility = View.VISIBLE
        } else {
            speechManager.stopListening()
            bubbleCard.background = createCircleDrawable(Color.parseColor("#1976D2")) // Blue idle
            iconView.setImageResource(android.R.drawable.ic_media_play)
            statusText.visibility = View.GONE
            iconView.visibility = View.VISIBLE
        }
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, 5000) // 5 seconds idle -> shrink
    }

    private fun shrinkToIdleState() {
        if (isShrunk) return
        isShrunk = true

        val density = resources.displayMetrics.density
        val shrunkSizePx = (40 * density).toInt()

        layoutParams.width = shrunkSizePx
        layoutParams.height = shrunkSizePx
        overlayView.alpha = 0.6f

        windowManager.updateViewLayout(overlayView, layoutParams)
    }

    private fun expandFromIdleState() {
        if (!isShrunk) return
        isShrunk = false

        val density = resources.displayMetrics.density
        val normalSizePx = (60 * density).toInt()

        layoutParams.width = normalSizePx
        layoutParams.height = normalSizePx
        overlayView.alpha = 1.0f

        windowManager.updateViewLayout(overlayView, layoutParams)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
        handler.removeCallbacks(idleRunnable)
        if (::speechManager.isInitialized) {
            speechManager.stopListening()
        }
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        private var instance: FloatingOverlayService? = null

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        fun isRunning(): Boolean = instance != null

        fun stopService(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }
    }
}
