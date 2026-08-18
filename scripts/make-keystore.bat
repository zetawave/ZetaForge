@echo off
REM Windows wrapper: runs the POSIX script through Git Bash.
setlocal
where bash >nul 2>&1
if errorlevel 1 (
  echo Git Bash is required. Install Git for Windows.
  exit /b 1
)
bash "%~dp0make-keystore" %*
