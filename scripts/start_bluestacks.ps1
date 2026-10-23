param([int]$AdbPort = 5555, [int]$ServerPort = 8765)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$companion = Join-Path $env:LOCALAPPDATA 'Programs\GeminiOCR\GeminiOCR.exe'
if (!(Test-Path -LiteralPath $companion)) { $companion = Join-Path $projectRoot 'dist\GeminiOCR.exe' }
if (!(Test-Path -LiteralPath $companion)) { throw 'Chưa có GeminiOCR.exe. Cài bộ cài Windows trước.' }
Start-Process -FilePath $companion -ArgumentList '--no-browser' -WindowStyle Hidden
$adbCandidates = @((Join-Path $projectRoot '.tools\android-sdk\platform-tools\adb.exe'),
    (Join-Path $env:ProgramFiles 'BlueStacks_nxt\HD-Adb.exe'))
$adb = $adbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$adb) { throw 'Không tìm thấy ADB. Mở BlueStacks và bật Android Debug Bridge.' }
$serial = "127.0.0.1:$AdbPort"
& $adb connect $serial
if ($LASTEXITCODE -ne 0) { throw 'Chưa kết nối được BlueStacks.' }
& $adb -s $serial reverse "tcp:$ServerPort" "tcp:$ServerPort"
if ($LASTEXITCODE -ne 0) { throw 'Chưa khôi phục được cổng. Kiểm tra cổng ADB trong BlueStacks.' }
Write-Host 'Đã mở Companion và khôi phục kết nối BlueStacks. Quay lại Gemini OCR để bật dịch.'
