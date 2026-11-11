@echo off
setlocal
if not exist "E:\GeminiOCR-build\personal-video\python312\pythonw.exe" exit /b 1
start "" /b "E:\GeminiOCR-build\personal-video\python312\pythonw.exe" "%~dp0scripts\run_personal_video.py"
endlocal
