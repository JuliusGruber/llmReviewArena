#!/usr/bin/env python3
"""Wrapper script to run codex with prompt from file.

Uses 'codex exec' for non-interactive execution with --output-last-message
to write the review directly to the output file.
"""
import subprocess
import sys
import os

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

# Build command using 'codex exec' with stdin for prompt
# -o writes the last message directly to the output file
cmd = [
    'codex', 'exec',
    '--full-auto',
    '-o', output_file,
    '-'  # Read prompt from stdin
]

print(f"Running codex exec with prompt from {prompt_file}", file=sys.stderr)
result = subprocess.run(cmd, input=prompt_content, text=True, capture_output=True)

if result.returncode != 0:
    print(f"Codex exec failed with return code {result.returncode}", file=sys.stderr)
    print(f"stderr: {result.stderr}", file=sys.stderr)
    print(f"stdout: {result.stdout[-2000:] if result.stdout else '(empty)'}", file=sys.stderr)
    sys.exit(1)

# Verify output was written
if os.path.exists(output_file) and os.path.getsize(output_file) > 0:
    print(f"Review written to {output_file}", file=sys.stderr)
    sys.exit(0)
else:
    print("Error: No review content written to output file", file=sys.stderr)
    print(f"stdout: {result.stdout[-2000:] if result.stdout else '(empty)'}", file=sys.stderr)
    sys.exit(1)
