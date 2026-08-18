@echo off
REM Windows wrapper for run.sh (requires Git Bash).
setlocal
where bash >/dev/null 2>&1
if errorlevel 1 (
  echo Git Bash is required. Install Git for Windows.
  exit /b 1
)
bash "%~dp0run.sh" %*
