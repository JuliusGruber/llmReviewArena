# LLM Review Arena Specification

## Overview

A **process-orchestrated agent arena** - not an API-orchestrated multi-model system.

## Core Principles

| Principle | Description |
|-----------|-------------|
| No REST / No Model APIs | System does not use REST endpoints or model APIs |
| Local CLI Agents Only | All agents run as local command-line processes |
| Fully Terminal-Based | Entire system operates within the terminal |
| Agents-Only Execution | All work is performed by agents, not direct API calls |

## Supported CLI Agents

The arena orchestrates the following CLI agents as processes:

- **Claude CLI** - Anthropic's Claude Code CLI
- **Codex CLI** - OpenAI's Codex CLI
- **Gemini CLI** - Google's Gemini CLI

## Architecture

The system uses **process orchestration** to manage and coordinate multiple CLI agents, spawning them as subprocesses and managing their inputs/outputs through the terminal.
