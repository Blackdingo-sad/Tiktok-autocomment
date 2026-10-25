# Android model assets

The Git repository intentionally excludes model weight files. They are downloaded into `app/src/main/assets/model/` locally and packaged into the APK by Gradle.

## Zipformer-VI

The local filenames and sizes currently present match the files published for `sherpa-onnx-zipformer-vi-30M-int8-2026-02-09` (encoder, decoder, joiner and tokens). The Sherpa model page identifies its upstream as [hynt/Zipformer-30M-RNNT-6000h](https://huggingface.co/hynt/Zipformer-30M-RNNT-6000h). The model card declares **CC-BY-NC-ND-4.0**. That license is non-commercial and does not permit derivatives; do not package or distribute these weights for commercial use without separate permission from the rights holder.

The downloader gets the published archive from the [Sherpa-ONNX release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models), but downloading from that mirror does not change the upstream model license. The model is not checked into this repository. To prepare a local build, run:

```powershell
.\scripts\Download-AndroidModels.ps1 -AcceptNonCommercialLicense
```

The script asks for explicit confirmation unless the switch is provided. It downloads only missing files. `app/src/main/assets/model/` is git-ignored so local weights are not accidentally pushed.

## Silero VAD

The setup script also fetches `silero_vad.onnx` from the Sherpa-ONNX model release. Its license and attribution should be checked against the upstream release before redistributing the model outside a local development build.

## Moonshine comparison candidate

Sherpa-ONNX documents the Vietnamese Moonshine v2 quantized model and includes a `LICENSE` file in its package. Review that package license and confirm permission for the intended distribution before adding Moonshine weights or packaging them in an APK. The candidate is not downloaded or bundled by this repository at this time.
