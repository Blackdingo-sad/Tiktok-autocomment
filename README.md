# TikTok Live Auto Comment Tool (Android)

Ứng dụng Android nhận audio playback của TikTok Live, nhận dạng tiếng Việt cục bộ và trích số trong khoảng 99–999. Mặc định app chỉ điền số vào ô comment; tùy chọn tự gửi riêng biệt và mặc định tắt.

## Chuẩn bị model

Trọng số ASR/VAD không nằm trong Git repo. Trước khi build lần đầu, tải model bằng PowerShell:

```powershell
.\scripts\Download-AndroidModels.ps1 -AcceptNonCommercialLicense
```

Model Zipformer upstream được ghi giấy phép CC-BY-NC-ND-4.0. Lệnh tải yêu cầu bạn xác nhận điều khoản; không dùng model này cho mục đích thương mại nếu chưa có quyền riêng từ chủ sở hữu. Chi tiết và nguồn: [Model assets and licenses](docs/MODEL_ASSETS.md).

## Chạy bằng Android Studio

1. Mở thư mục repo này trong Android Studio và đợi Gradle Sync hoàn tất.
2. Chọn cấu hình `app`, kết nối điện thoại Android 10 trở lên rồi nhấn **Run**.
3. Cấp quyền Accessibility cho app để nhận diện ô nhập TikTok.
4. Mở TikTok Live. Trong app, nhấn **Bắt đầu nghe audio TikTok Live** và chấp nhận hộp thoại capture của Android.

App chỉ yêu cầu audio playback từ UID TikTok qua Android Playback Capture; không đổi sang thu micro nếu TikTok hoặc thiết bị chặn capture. Android vẫn có thể hiển thị quyền Microphone do API capture playback yêu cầu.

Để build APK debug từ PowerShell sau khi chuẩn bị model:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK được tạo trong `app\build\outputs\apk\debug\` (ARM64 và ARMv7). Không bật tự gửi nếu chỉ muốn kiểm tra nhận dạng/điền. TikTok có thể lọc comment; thao tác nhấn nút gửi không chứng minh comment đã xuất hiện.

## Tài liệu

- [Đo tốc độ và so sánh ASR Android](docs/ANDROID_ASR_BENCHMARK.md)
