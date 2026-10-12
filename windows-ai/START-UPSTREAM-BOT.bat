@echo off
setlocal
cd /d "%~dp0"

where py >nul 2>&1
if errorlevel 1 (
  echo ERROR: Python Launcher was not found. Install Python 3.11 x64 first.
  pause
  exit /b 1
)

if not exist ".venv-upstream\Scripts\python.exe" (
  py -3.11 -m venv .venv-upstream
  if errorlevel 1 goto :failed
)

call ".venv-upstream\Scripts\activate.bat"
if errorlevel 1 goto :failed

python run_upstream_bot.py --setup-only
if errorlevel 1 goto :failed

python -c "import cv2,numpy,pyautogui,ultralytics,openvino,win32gui" >nul 2>&1
if errorlevel 1 (
  echo Installing upstream dependencies. This may take several minutes...
  python run_upstream_bot.py --install
  if errorlevel 1 goto :failed
)

echo.
echo Starting upstream bot. It can send real mouse and keyboard input.
echo Make sure BlueStacks and Brawl Stars are open and the configuration is correct.
python run_upstream_bot.py
set RESULT=%ERRORLEVEL%
echo.
echo Bot exited with code %RESULT%.
pause
exit /b %RESULT%

:failed
echo.
echo Setup or startup failed. Read the error above.
pause
exit /b 1
