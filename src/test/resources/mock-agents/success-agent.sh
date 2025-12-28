#!/bin/bash
# Mock agent that writes a valid review
# Usage: success-agent.sh -p <prompt> -o <output-file>
# The @output placeholder in command is replaced with actual output path

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -o) OUTPUT_FILE="$2"; shift 2 ;;
        -p) shift 2 ;;  # Ignore prompt file
        *) shift ;;
    esac
done

# Ensure parent directory exists
mkdir -p "$(dirname "$OUTPUT_FILE")"

cat > "$OUTPUT_FILE" << 'EOF'
# Summary
Mock review generated successfully.

## High-risk issues (must fix)
None identified in this mock review.

## Medium / low-risk issues
- Example issue for testing

## Suggested patches
No patches suggested.

## Test suggestions
Add tests for the mock functionality.

## Questions for the author
None.
EOF

exit 0
