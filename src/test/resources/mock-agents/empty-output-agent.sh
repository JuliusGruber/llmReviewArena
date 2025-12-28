#!/bin/bash
# Mock agent that creates empty output
# Usage: empty-output-agent.sh -p <prompt> -o <output-file>

while [[ $# -gt 0 ]]; do
    case $1 in
        -o) OUTPUT_FILE="$2"; shift 2 ;;
        *) shift ;;
    esac
done

mkdir -p "$(dirname "$OUTPUT_FILE")"
touch "$OUTPUT_FILE"
exit 0
