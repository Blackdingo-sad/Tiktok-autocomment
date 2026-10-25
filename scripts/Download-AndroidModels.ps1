param(
    [switch]$AcceptNonCommercialLicense
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$modelDirectory = Join-Path $repoRoot "app\src\main\assets\model"
$requiredFiles = @(
    "encoder.int8.onnx",
    "decoder.onnx",
    "joiner.int8.onnx",
    "tokens.txt",
    "silero_vad.onnx"
)

$missingFiles = @($requiredFiles | Where-Object { -not (Test-Path -LiteralPath (Join-Path $modelDirectory $_)) })
if ($missingFiles.Count -eq 0) {
    Write-Host "All local ASR/VAD model files already exist."
    exit 0
}

if (-not $AcceptNonCommercialLicense) {
    Write-Host "The Zipformer model card declares CC-BY-NC-ND-4.0 (non-commercial, no derivatives)."
    Write-Host "Read docs/MODEL_ASSETS.md. Do not use or redistribute it commercially without separate permission."
    $answer = Read-Host "Type ACCEPT-NONCOMMERCIAL to download the model"
    if ($answer -cne "ACCEPT-NONCOMMERCIAL") {
        throw "Download cancelled; no model files were written."
    }
}

$tempRoot = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
$workDirectory = [IO.Path]::GetFullPath((Join-Path $tempRoot ("tiktok-autocomment-model-" + [Guid]::NewGuid().ToString("N"))))
if (-not $workDirectory.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use a temporary path outside TEMP."
}

New-Item -ItemType Directory -Path $workDirectory | Out-Null
try {
    New-Item -ItemType Directory -Path $modelDirectory -Force | Out-Null
    $missingAsrFiles = @($missingFiles | Where-Object { $_ -ne "silero_vad.onnx" })

    if ($missingAsrFiles.Count -gt 0) {
        $archive = Join-Path $workDirectory "zipformer-vi.tar.bz2"
        $extractDirectory = Join-Path $workDirectory "extract"
        New-Item -ItemType Directory -Path $extractDirectory | Out-Null
        Invoke-WebRequest `
            -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-vi-30M-int8-2026-02-09.tar.bz2" `
            -OutFile $archive
        & tar.exe -xjf $archive -C $extractDirectory
        if ($LASTEXITCODE -ne 0) { throw "Could not extract the Zipformer archive (tar exit code $LASTEXITCODE)." }

        $sourceDirectory = Get-ChildItem -LiteralPath $extractDirectory -Directory -Recurse |
            Where-Object { (Test-Path -LiteralPath (Join-Path $_.FullName "encoder.int8.onnx")) } |
            Select-Object -First 1
        if (-not $sourceDirectory) { throw "Archive does not contain encoder.int8.onnx." }

        foreach ($file in $missingAsrFiles) {
            $source = Join-Path $sourceDirectory.FullName $file
            if (-not (Test-Path -LiteralPath $source)) { throw "Archive is missing $file." }
            Copy-Item -LiteralPath $source -Destination (Join-Path $modelDirectory $file)
        }
    }

    if ($missingFiles -contains "silero_vad.onnx") {
        Invoke-WebRequest `
            -Uri "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" `
            -OutFile (Join-Path $modelDirectory "silero_vad.onnx")
    }

    $stillMissing = @($requiredFiles | Where-Object { -not (Test-Path -LiteralPath (Join-Path $modelDirectory $_)) })
    if ($stillMissing.Count -gt 0) { throw "Model setup incomplete: $($stillMissing -join ', ')" }
    Write-Host "ASR/VAD model files are ready in app\src\main\assets\model."
}
finally {
    if (Test-Path -LiteralPath $workDirectory) {
        $resolvedWorkDirectory = [IO.Path]::GetFullPath($workDirectory)
        if ($resolvedWorkDirectory.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolvedWorkDirectory -Recurse -Force
        }
    }
}
