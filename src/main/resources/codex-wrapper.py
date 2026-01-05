#!/usr/bin/env python3
"""Wrapper script to run codex with prompt from file.

Uses 'codex exec' for non-interactive execution. The prompt instructs codex
where to write the review file directly (no -o flag needed).
"""
import subprocess
import sys
import os
import shutil
import platform

if len(sys.argv) < 3:
    print("Usage: codex-wrapper.py <prompt-file> <output-file>", file=sys.stderr)
    sys.exit(1)

prompt_file = sys.argv[1]
output_file = sys.argv[2]

if not os.path.exists(prompt_file):
    print(f"Error: Prompt file not found: {prompt_file}", file=sys.stderr)
    sys.exit(1)

# Ensure output directory exists
os.makedirs(os.path.dirname(output_file), exist_ok=True)

# Read prompt from file
with open(prompt_file, 'r', encoding='utf-8') as f:
    prompt_content = f.read()

# Find the codex executable
codex_path = shutil.which('codex')
if codex_path is None and platform.system() == 'Windows':
    # On Windows, npm installs commands as .cmd files
    codex_path = shutil.which('codex.cmd')

if codex_path is None:
    print("Error: 'codex' command not found in PATH", file=sys.stderr)
    print("Install it with: npm install -g @openai/codex", file=sys.stderr)
    sys.exit(1)

# Build command using 'codex exec' with stdin for prompt
# NOTE: Do NOT use -o flag here! The prompt already tells codex where to write
# the review file. Using -o would overwrite the file with codex's final message
# (e.g., "Review written to...") instead of the actual review content.
cmd = [
    codex_path, 'exec',
    '--full-auto',
    '-'  # Read prompt from stdin
]

print(f"Running codex exec with prompt from {prompt_file}", file=sys.stderr)
print(f"Using codex at: {codex_path}", file=sys.stderr)
print(f"Expecting codex to write review to: {output_file}", file=sys.stderr)

# On Windows, we need shell=True for .cmd files to work properly
use_shell = platform.system() == 'Windows'
result = subprocess.run(cmd, input=prompt_content, text=True, capture_output=True, shell=use_shell, encoding='utf-8')

if result.returncode != 0:
    print(f"Codex exec failed with return code {result.returncode}", file=sys.stderr)
    print(f"stderr: {result.stderr}", file=sys.stderr)
    print(f"stdout: {result.stdout[-2000:] if result.stdout else '(empty)'}", file=sys.stderr)
    sys.exit(1)

# Verify output was written by codex (as instructed in the prompt)
if os.path.exists(output_file) and os.path.getsize(output_file) > 0:
    print(f"Review successfully written to {output_file}", file=sys.stderr)
    sys.exit(0)
else:
    print("Error: Codex did not write the review file", file=sys.stderr)
    print(f"Expected output at: {output_file}", file=sys.stderr)
    print(f"stdout: {result.stdout[-2000:] if result.stdout else '(empty)'}", file=sys.stderr)
    print(f"stderr: {result.stderr[-2000:] if result.stderr else '(empty)'}", file=sys.stderr)
    sys.exit(1)
