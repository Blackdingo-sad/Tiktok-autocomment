package com.example.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.app.service.FloatingWidgetService
import com.example.app.service.TikTokAutoCommentService
import com.example.app.speech.SpeechRecognitionManager
import com.example.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private lateinit var speechManager: SpeechRecognitionManager

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                speechManager.startListening()
            } else {
                Toast.makeText(this, "Cần cấp quyền Microphone để nhận diện giọng nói", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        speechManager = SpeechRecognitionManager(this)

        setContent {
            AppTheme {
                MainScreen(
                    speechManager = speechManager,
                    onRequestPermission = { checkAndRequestPermission() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onMinimizeToFloatingWidget = { enableOverlayAndMinimize() }
                )
            }
        }
    }

    private fun checkAndRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            speechManager.startListening()
        } else {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun enableOverlayAndMinimize() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Vui lòng cấp quyền Hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            FloatingWidgetService.startWidget(this)
            moveTaskToBack(true)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Settings.canDrawOverlays(this)) {
            FloatingWidgetService.startWidget(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.stopListening()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    speechManager: SpeechRecognitionManager,
    onRequestPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onMinimizeToFloatingWidget: () -> Unit
) {
    val context = LocalContext.current
    val speechState by speechManager.speechState.collectAsState()
    val recognizedText by speechManager.lastRecognizedText.collectAsState()
    val detectedNumber by speechManager.lastDetectedNumber.collectAsState()
    val commentLog by TikTokAutoCommentService.lastCommentLog.collectAsState()

    var isAccessibilityConnected by remember { mutableStateOf(TikTokAutoCommentService.isConnected(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityConnected = TikTokAutoCommentService.isConnected(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var autoCommentEnabled by remember { mutableStateOf(true) }
    var prefixAt by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("TikTok Live Auto Comment Tool") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Trạng Thái Hệ Thống", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trợ Năng (Accessibility): ")
                        Text(
                            text = if (isAccessibilityConnected) "Đã Kết Nối" else "Chưa Bật",
                            color = if (isAccessibilityConnected) Color(0xFF2E7D32) else Color.Red,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!isAccessibilityConnected) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = onOpenAccessibilitySettings) {
                            Text("Bật Dịch Vụ Trợ Năng")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("Giọng Nói: $speechState", fontSize = 14.sp)
                    if (recognizedText.isNotEmpty()) {
                        Text("Lời Nói Nhận Diện: \"$recognizedText\"", fontWeight = FontWeight.Medium)
                    }
                    if (detectedNumber != null) {
                        Text("Số Trích Xuất: $detectedNumber", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Controls Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Cấu Hình Tự Động", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tự động Comment khi đọc số")
                        Switch(
                            checked = autoCommentEnabled,
                            onCheckedChange = {
                                autoCommentEnabled = it
                                speechManager.autoCommentEnabled = it
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Thêm ký tự '@' vào đầu (@122)")
                        Switch(
                            checked = prefixAt,
                            onCheckedChange = {
                                prefixAt = it
                                speechManager.prefixAt = it
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Bật Micro & Lắng Nghe Giọng Nói")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = onMinimizeToFloatingWidget,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Thu Nhỏ Thành Nút Tròn (Floating Bubble)")
                    }
                }
            }

            // Log Card
            if (commentLog.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Nhật Ký Tự Động:", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(commentLog, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
