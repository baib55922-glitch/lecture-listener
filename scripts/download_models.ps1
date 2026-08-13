# 下载 Vosk 中文模型脚本
# 用法：
#   .\scripts\download_models.ps1            # 仅下载小模型并解包到 assets（推荐，开箱即用）
#   .\scripts\download_models.ps1 -Large     # 额外下载大模型到 large-model\ 目录（可选，用于 adb 推送）
param([switch]$Large)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$base = "https://alphacephei.com/vosk/models"
$small = "vosk-model-small-cn-0.22"
$big = "vosk-model-cn-0.22"

# ---- 小模型：解包到 app\src\main\assets\models\ ----
$assets = Join-Path $root "app\src\main\assets\models"
New-Item -ItemType Directory -Force -Path $assets | Out-Null

$smallZip = Join-Path $env:TEMP "$small.zip"
if (-not (Test-Path "$assets\$small")) {
    Write-Host "下载 $small.zip ..."
    Invoke-WebRequest -Uri "$base/$small.zip" -OutFile $smallZip
    Write-Host "解压到 $assets ..."
    Expand-Archive -Path $smallZip -DestinationPath $assets -Force
    Remove-Item $smallZip -ErrorAction SilentlyContinue
    Write-Host "小模型就绪：$assets\$small"
} else {
    Write-Host "小模型已存在，跳过。"
}

# ---- 大模型（可选）：下载到 large-model\，用 adb 推送到手机 ----
if ($Large) {
    $bigDir = Join-Path $root "large-model"
    New-Item -ItemType Directory -Force -Path $bigDir | Out-Null
    $bigZip = Join-Path $env:TEMP "$big.zip"
    if (-not (Test-Path "$bigDir\$big")) {
        Write-Host "下载 $big.zip （约 1.3GB，请耐心等待）..."
        Invoke-WebRequest -Uri "$base/$big.zip" -OutFile $bigZip
        Write-Host "解压 ..."
        Expand-Archive -Path $bigZip -DestinationPath $bigDir -Force
        Remove-Item $bigZip -ErrorAction SilentlyContinue
    }
    Write-Host "大模型就绪：$bigDir\$big"
    Write-Host "可选：adb push `"$bigDir\$big`" /sdcard/Android/data/com.lecturelistener/files/models/"
}
