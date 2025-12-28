@echo off
REM Mock agent that writes a valid review
REM Usage: success-agent.bat -p <prompt> -o <output-file>
REM The @output placeholder in command is replaced with actual output path

setlocal enabledelayedexpansion

REM Parse arguments
:parse
if "%~1"=="" goto :endparse
if "%~1"=="-o" (
    set "OUTPUT_FILE=%~2"
    shift
    shift
    goto :parse
)
if "%~1"=="-p" (
    shift
    shift
    goto :parse
)
shift
goto :parse
:endparse

REM Create parent directory if needed
for %%F in ("%OUTPUT_FILE%") do mkdir "%%~dpF" 2>nul

(
echo # Summary
echo Mock review generated successfully.
echo.
echo ## High-risk issues ^(must fix^)
echo None identified in this mock review.
echo.
echo ## Medium / low-risk issues
echo - Example issue for testing
echo.
echo ## Suggested patches
echo No patches suggested.
echo.
echo ## Test suggestions
echo Add tests for the mock functionality.
echo.
echo ## Questions for the author
echo None.
) > "%OUTPUT_FILE%"

exit /b 0
