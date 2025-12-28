@echo off
REM Mock agent that takes too long (for timeout testing)
timeout /t 30 /nobreak > nul
exit /b 0
