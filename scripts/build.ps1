# 在 Windows 上构建/测试（自动处理中文路径）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/build.ps1 -Target assembleDebug
#   powershell -ExecutionPolicy Bypass -File scripts/build.ps1 -Target testDebugUnitTest
#   powershell -ExecutionPolicy Bypass -File scripts/build.ps1 -Target both

param(
    [ValidateSet('assembleDebug', 'testDebugUnitTest', 'both')]
    [string]$Target = 'assembleDebug',

    [string]$GradleBin = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$ascii = $repo -notmatch '[^\x00-\x7F]'

if (-not $GradleBin) {
    $candidate = Join-Path $repo 'gradlew.bat'
    if (Test-Path $candidate) { $GradleBin = $candidate } else { $GradleBin = 'gradle' }
}

$tasks = switch ($Target) {
    'both' { @(':app:assembleDebug', ':app:testDebugUnitTest') }
    default { @(":app:$Target") }
}

if ($ascii) {
    Write-Host "路径为 ASCII，直接构建：$repo"
    & $GradleBin -p $repo @tasks --console=plain
    exit $LASTEXITCODE
}

Write-Host "检测到非 ASCII 路径，复制到临时英文目录构建。"
$tempRoot = Join-Path $env:TEMP 'runway-assistant-build'
if (Test-Path $tempRoot) { Remove-Item $tempRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

robocopy $repo $tempRoot /E /XD build .gradle /NFL /NDL /NJH /NJS /NP | Out-Null

& $GradleBin -p $tempRoot @tasks --console=plain
$code = $LASTEXITCODE

# 回填 APK
$apkSrc = Join-Path $tempRoot 'app\build\outputs\apk\debug\app-debug.apk'
$apkDstDir = Join-Path $repo 'app\build\outputs\apk\debug'
if (Test-Path $apkSrc) {
    New-Item -ItemType Directory -Force -Path $apkDstDir | Out-Null
    Copy-Item $apkSrc (Join-Path $apkDstDir 'app-debug.apk') -Force
    Write-Host "APK 已回填：$apkDstDir"
}

exit $code
