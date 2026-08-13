# 下载 Paraformer 中文识别模型脚本（供 adb 推送或本地备份）
# 用法：.\scripts\download_models.ps1
# 下载完成后：
#   adb push .\large-model\paraformer-zh /sdcard/Android/data/com.lecturelistener/files/models/paraformer-zh/
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "large-model\paraformer-zh"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$base = "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en/resolve/main"
$files = @("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")

foreach ($f in $files) {
    $target = Join-Path $dest $f
    if ((Test-Path $target) -and ((Get-Item $target).Length -gt 0)) {
        Write-Host "已存在，跳过：$f"
        continue
    }
    Write-Host "下载 $f ..."
    Invoke-WebRequest -Uri "$base/$f" -OutFile $target
}

Write-Host "完成。模型目录：$dest"
Write-Host "adb 推送命令："
Write-Host "  adb push `"$dest`" /sdcard/Android/data/com.lecturelistener/files/models/paraformer-zh/"
