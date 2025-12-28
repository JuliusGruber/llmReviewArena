@echo off
REM Mock agent that creates empty output
REM Usage: empty-output-agent.bat -p <prompt> -o <output-file>

:parse
if "%~1"=="" goto :endparse
if "%~1"=="-o" (
    set "OUTPUT_FILE=%~2"
    shift
    shift
    goto :parse
)
shift
goto :parse
:endparse

for %%F in ("%OUTPUT_FILE%") do mkdir "%%~dpF" 2>nul
type nul > "%OUTPUT_FILE%"
exit /b 0
