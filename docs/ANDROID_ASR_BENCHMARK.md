# Android ASR latency and model comparison

Updated: 2026-10-25

## Current pipeline

The Android app captures TikTok playback audio through `AudioPlaybackCaptureConfiguration`, filtered to the supported TikTok package UIDs. It does not fall back to the device microphone. Audio is converted to mono 16 kHz PCM, read in 512-sample blocks (32 ms), and passed to Silero VAD. When VAD closes a segment, the app runs the local Zipformer-VI INT8 `OfflineRecognizer`, parses the recognized text, and optionally fills the TikTok comment composer.

The checked-out asset filenames and sizes match the published `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09` package, though file hashes have not been compared. See [model asset and license notes](MODEL_ASSETS.md).

Current tuning in `TikTokPlaybackCaptureService.kt`:

- VAD minimum silence: 200 ms.
- ASR thread count: half the reported processors, clamped to 1–4.
- ASR input is a finalized VAD segment, not partial streaming hypotheses.
- Auto-send is a separate setting and is off by default.

Reducing VAD silence can reduce delay, but risks cutting pauses inside Vietnamese number phrases. The ASR decode time, number parsing, Android Accessibility actions, TikTok moderation, and comment distribution are separate contributors; an Accessibility click is not evidence that TikTok displayed the comment.

## A/B candidate

Sherpa-ONNX lists `sherpa-onnx-moonshine-base-vi-quantized-2026-02-27` as a Vietnamese model with Android real-time/streaming examples: [Sherpa-ONNX model catalogue](https://k2-fsa.github.io/sherpa/onnx/index.html). The app's current Sherpa-ONNX 1.13.8 Android AAR includes `OfflineMoonshineModelConfig`. This confirms API availability, not speed or accuracy on the target phone. The Moonshine package includes its own `LICENSE`; review it before downloading or packaging weights. See [model asset and license notes](MODEL_ASSETS.md).

For this app, the first comparison should feed the *same in-memory VAD segment* to Zipformer and Moonshine, with sending disabled. Report separately:

1. VAD endpoint delay (audio ending to finalized segment).
2. Model initialization time, measured separately from warm inference.
3. ASR decode time for each segment.
4. Number parsing time and extracted number (valid target range: 99–999).
5. Accessibility fill/click dispatch time, if that action is enabled.
6. Exact-number agreement against a human transcript for each sample.

Use the same audio and thread settings, warm up both models, alternate which model runs first, and report sample count plus P50/P95. Keep audio in memory and do not persist or upload it. Do not send comments during the benchmark. Since the Moonshine Android API is exposed as an offline recognizer, this comparison measures decoding of finalized segments; it does not establish lower first-partial-result latency.

## Results and limits

No device latency measurements or model A/B results are available yet. On 2026-10-25, after the phone was reported plugged in, `adb devices -l` still returned only the device-list header. Windows listed generic USB composite devices but no Android ADB device. The app was therefore not installed or profiled on a phone in this session.

The available evidence confirms model/API availability only. It does not establish that Moonshine is faster or more accurate than the packaged Zipformer on the user's phone. QNN/NPU acceleration is device-specific and is not a universal optimization for all Android devices.

## Next steps

1. Connect the phone with USB debugging enabled and approve the computer's RSA prompt; verify that `adb devices -l` shows a device in `device` state.
2. Add a benchmark-only path that runs both recognizers on each same VAD segment and displays/logs timings without sending.
3. Run a labeled sample of Vietnamese number speech on the target phone; compare P50/P95 decode latency and exact-number accuracy.
4. Keep Zipformer as the default unless measurements show Moonshine improves the speed/accuracy tradeoff on the target devices.
