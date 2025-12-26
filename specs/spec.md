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

## Core Abstraction: AgentProcess

At the heart of the system is a small, powerful abstraction:

```
AgentProcess
├── name              (claude, codex, gemini)
├── command           (shell command to start it)
├── working_directory (isolated per agent)
├── stdin             (prompt injection)
├── stdout            (captured logs + responses)
├── lifecycle         (start / stop / restart)
```

### Agent Configuration

Agents are defined via YAML configuration:

```yaml
agents:
  claude:
    command: ["claude", "chat", "--dangerously-allow-file-access"]

  codex:
    command: ["codex"]

  gemini:
    command: ["gemini", "chat"]
```

> **Note:** Agent working directories are set dynamically per round (see [Arena Filesystem](#arena-filesystem)).

### Design Benefits

This abstraction keeps the arena:

- **Model-agnostic** - No coupling to specific LLM providers
- **Future-proof** - New CLI agents can be added via configuration
- **Extensible** - Compatible with any CLI agent, including custom MCP-based ones

## Agent Execution Model

### Ephemeral Agents (Recommended)

The recommended execution model uses **ephemeral agents** - stateless, short-lived processes that are created fresh for each round:

1. **Start** agent process
2. **Feed** it a prompt (via stdin)
3. **Let it work** (agent executes autonomously)
4. **Capture output** (from stdout)
5. **Kill** process

This approach ensures:
- Clean state for each evaluation round
- No cross-contamination between tasks
- Predictable, reproducible behavior
- Simple resource management

## Arena Filesystem

Since agents are local and tool-enabled, the **filesystem becomes the shared communication layer** - not tokens.

### Directory Structure

```
.arena/
├── task.md                    # Current task definition
├── rounds/
│   ├── round-0/
│   │   ├── claude/
│   │   │   └── solution.md
│   │   ├── codex/
│   │   │   └── solution.md
│   │   └── gemini/
│   │       └── solution.md
│   ├── round-1/
│   │   └── ...
│   └── final/
└── evaluation/
    └── README.md
```

### Agent Capabilities

Each agent operates in its round-specific working directory (e.g., `.arena/rounds/round-0/claude/`) and can:

- **Write actual files** - Create solutions, code, documentation
- **Run tests** - Execute and validate their work
- **Inspect previous rounds** - Learn from prior attempts
- **Diff other agents' output** - Compare approaches across agents

This filesystem-based communication is a **major advantage over API-only systems**, enabling rich, tool-augmented collaboration.
