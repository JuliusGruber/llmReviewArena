#!/usr/bin/env python3
"""Wrapper script to run codex with prompt from file.

Codex's -q (quiet) mode requires the prompt as a positional argument,
but command-line length limits make this impractical for large prompts.
This wrapper uses --project-doc to pass the full prompt as context,
with a short summary prompt for the -q argument.

Since codex -q outputs to stdout (not to a file), this wrapper captures
stdout and writes it to the specified output file.
"""
import subprocess
import sys
import os
import json
import tempfile

if len(sys.argv) < 3:
    print("Usage: codex-wrapper.py <prompt-file> <output-file>", file=sys.stderr)
    sys.exit(1)

prompt_file = sys.argv[1]
output_file = sys.argv[2]

if not os.path.exists(prompt_file):
    print(f"Error: Prompt file not found: {prompt_file}", file=sys.stderr)
    sys.exit(1)

# Use a short prompt with --project-doc for the full context
short_prompt = "Execute the code review task defined in the project doc. Output ONLY the review content in markdown format."

# Use a temp file to capture stdout to avoid encoding/buffering issues
stdout_file = tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.txt', encoding='utf-8')
stdout_file.close()

# Build command - redirect stdout to temp file
cmd = f'codex -q "{short_prompt}" --project-doc "{prompt_file}" --full-auto > "{stdout_file.name}" 2>&1'

result = subprocess.run(cmd, shell=True)

# Read the captured output
try:
    with open(stdout_file.name, 'r', encoding='utf-8', errors='replace') as f:
        stdout_content = f.read()
finally:
    os.unlink(stdout_file.name)

print(f"Captured {len(stdout_content)} bytes of output", file=sys.stderr)

# Extract the final message content from the JSONL output
# Codex outputs JSONL where the last message contains the review
review_content = ""
for line in stdout_content.strip().split('\n'):
    if not line:
        continue
    try:
        obj = json.loads(line)
        if obj.get('type') == 'message' and obj.get('role') == 'assistant':
            content = obj.get('content', [])
            for item in content:
                if item.get('type') == 'output_text':
                    review_content = item.get('text', '')
    except json.JSONDecodeError:
        continue

# Write the review to the output file
if review_content:
    os.makedirs(os.path.dirname(output_file), exist_ok=True)
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write(review_content)
    print(f"Review written to {output_file}", file=sys.stderr)
    sys.exit(0)
else:
    print("Error: No review content found in codex output", file=sys.stderr)
    # Print last 2000 chars for debugging
    print("Last 2000 chars of stdout:", stdout_content[-2000:], file=sys.stderr)
    sys.exit(1)
