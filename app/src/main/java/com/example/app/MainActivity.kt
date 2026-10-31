package com.example.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.app.network.NetworkPingManager
import com.example.app.network.TelemetryState
import com.example.app.service.FloatingWidgetService
import com.example.app.service.TikTokAutoCommentService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MainScreen(
                onOpenAccessibilitySettings = { openAccessibilitySettings() },
                onMinimizeToFloatingWidget = { enableOverlayAndMinimize() },
                onShutdownApp = { shutdownApp() }
            )
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

    private fun shutdownApp() {
        FloatingWidgetService.stopWidget(this)
        finishAndRemoveTask()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Settings.canDrawOverlays(this)) {
            FloatingWidgetService.startWidget(this)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onMinimizeToFloatingWidget: () -> Unit,
    onShutdownApp: () -> Unit
) {
    val context = LocalContext.current
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

    // Theme Mode: Dark (Chủ thể Đen Xanh) vs Light (Basic Slate/White)
    var isDarkMode by remember { mutableStateOf(true) }

    // Boost Placebo Switches
    var boostBuffer by remember { mutableStateOf(true) }
    var boostNetwork by remember { mutableStateOf(true) }
    var boostFrames by remember { mutableStateOf(true) }

    val telemetryState by remember {
        NetworkPingManager.observeTelemetry(
            getBoostBuffer = { boostBuffer },
            getBoostNetwork = { boostNetwork },
            getBoostFrames = { boostFrames }
        )
    }.collectAsState(initial = TelemetryState(18, "ĐANG TỐI ƯU ⚡", 15))

    // Color definitions
    val bgColor = if (isDarkMode) Color(0xFF0F172A) else Color(0xFFE2E8F0) // Slightly darker slate in Light Mode
    val cardBg = if (isDarkMode) Color(0xFF1E293B) else Color(0xFFFFFFFF)
    val telemetryBg = if (isDarkMode) Color(0xFF1E2836) else Color(0xFFF1F5F9)
    val textColor = if (isDarkMode) Color.White else Color(0xFF0F172A)
    val subTextColor = if (isDarkMode) Color.LightGray else Color(0xFF475569)

    // Accent Colors
    val greenAccent = if (isDarkMode) Color(0xFF00E676) else Color(0xFF15803D)
    val blueAccent = if (isDarkMode) Color(0xFF00B0FF) else Color(0xFF0369A1)
    val pinkAccent = if (isDarkMode) Color(0xFFFF4081) else Color(0xFFBE123C)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TikTok Live Auto Comment Tool", color = textColor) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bgColor)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(innerPadding)
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Latency & Ping Dashboard Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = telemetryBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TỐI ƯU TỐC ĐỘ XỬ LÝ",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = greenAccent
                        )
                        Text(
                            text = telemetryState.dynamicStatus,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = if (isDarkMode) Color(0xFFFFD600) else Color(0xFFB45309)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Ping Server:", fontSize = 11.sp, color = subTextColor)
                            Text("${telemetryState.pingMs} ms", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = greenAccent)
                            Text("Mạng Mượt", fontSize = 10.sp, color = Color.Gray)
                        }

                        Column {
                            Text("Độ Trễ ASR:", fontSize = 11.sp, color = subTextColor)
                            Text(if (boostBuffer) "0.08 s" else "0.18 s", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = blueAccent)
                            Text("Đã Tối Ưu", fontSize = 10.sp, color = Color.Gray)
                        }

                        Column {
                            Text("Băng Thông:", fontSize = 11.sp, color = subTextColor)
                            Text(if (boostNetwork) "+${telemetryState.boostPercent}% Fast" else "Tiêu Chuẩn", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = pinkAccent)
                            Text("Tuyến Ưu Tiên", fontSize = 10.sp, color = Color.Gray)
                        }
                    }
                }
            }

            // System Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Trạng Thái Hệ Thống", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textColor)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trợ Năng (Accessibility): ", color = textColor, fontSize = 13.sp)
                        Text(
                            text = if (isAccessibilityConnected) "Đã Kết Nối" else "Chưa Bật",
                            color = if (isAccessibilityConnected) greenAccent else Color.Red,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    if (!isAccessibilityConnected) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Bật Dịch Vụ Trợ Năng", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Boost Controls Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("🚀 TỐI ƯU TỐC ĐỘ PHẢN HỒI", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = greenAccent)
                    Text("Bật/tắt các tùy chọn hỗ trợ tăng tốc phản hồi comment", fontSize = 11.sp, color = subTextColor)

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tối Ưu Đệm Âm Thanh", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textColor)
                            Text("Giảm thời gian đệm giọng nói khi nhận diện số", fontSize = 10.sp, color = subTextColor)
                        }
                        Switch(
                            checked = boostBuffer,
                            onCheckedChange = { boostBuffer = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tối Ưu Tuyến Mạng TikTok", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textColor)
                            Text("Ưu tiên luồng kết nối gửi bình luận lên Server", fontSize = 10.sp, color = subTextColor)
                        }
                        Switch(
                            checked = boostNetwork,
                            onCheckedChange = { boostNetwork = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bỏ Qua Khung Hình Thừa", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textColor)
                            Text("Tăng tốc phản hồi gõ chữ và bấm gửi", fontSize = 10.sp, color = subTextColor)
                        }
                        Switch(
                            checked = boostFrames,
                            onCheckedChange = { boostFrames = it }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    // Theme Toggle Switch: Dark vs Light
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Giao Diện Tối (Dark Mode)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textColor)
                            Text("Chuyển đổi tông màu Đen Xanh / Đen Trắng Basic", fontSize = 10.sp, color = subTextColor)
                        }
                        Switch(
                            checked = isDarkMode,
                            onCheckedChange = { isDarkMode = it }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = onMinimizeToFloatingWidget,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Thu Nhỏ Thành Nút Tròn (Floating Bubble)", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = onShutdownApp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Text("Tắt hoàn toàn ứng dụng", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // Log Card
            if (commentLog.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardBg)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Nhật Ký Tự Động:", fontWeight = FontWeight.Bold, color = textColor, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(commentLog, fontSize = 13.sp, color = subTextColor)
                    }
                }
            }
        }
    }
}
