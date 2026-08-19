@echo off
REM Windows wrapper: runs the POSIX script through Git Bash.
setlocal
set SCRIPT_DIR=%~dp0
where bash >nul 2>&1
if errorlevel 1 (
  echo Git Bash is required. Install Git for Windows or run scripts/install-plugin from Git Bash.
  exit /b 1
)
bash "%SCRIPT_DIR%install-plugin" %*
