@echo off
setlocal
cd /d "%~dp0"

where python >nul 2>nul
if errorlevel 1 goto use_py
python -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>nul
if errorlevel 1 goto use_py
if /i "%~1"=="--check" goto check_python
python -m mkvoice_studio gui
exit /b %errorlevel%

:check_python
python -c "import tkinter; import mkvoice_studio.gui; print('launcher-ok')"
exit /b %errorlevel%

:use_py
where py >nul 2>nul
if errorlevel 1 goto missing_python
py -3 -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>nul
if errorlevel 1 goto missing_python
if /i "%~1"=="--check" goto check_py
py -3 -m mkvoice_studio gui
exit /b %errorlevel%

:check_py
py -3 -c "import tkinter; import mkvoice_studio.gui; print('launcher-ok')"
exit /b %errorlevel%

:missing_python
echo Python 3 was not found. Install Python 3.10 or newer, then run this file again.
pause
exit /b 1
