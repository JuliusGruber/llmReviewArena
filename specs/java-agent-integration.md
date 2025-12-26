# Java Agent Integration Options

## Decision

**Option 1: ProcessBuilder with Headless CLI Modes** was selected as the implementation approach.

This aligns with the core spec principles:
- Process orchestration, not API orchestration
- Local CLI agents only
- Filesystem as communication layer
- No REST / No Model APIs

---

## All Evaluated Options

### Option 1: ProcessBuilder with Headless CLI Modes ✅ **Selected**

All three CLIs support headless/JSON output modes suitable for subprocess orchestration:

| CLI | Headless Command | JSON Output |
|-----|------------------|-------------|
| **Claude CLI** | `claude -p "prompt"` | `--output-format json` |
| **Codex CLI** | `codex exec "prompt"` | `--json` (JSONL stream) |
| **Gemini CLI** | `gemini -p "prompt"` | `--output-format json` |

**Java Implementation Pattern:**
```java
ProcessBuilder pb = new ProcessBuilder(
    "claude", "-p", prompt,
    "--output-format", "json",
    "--allowedTools", "Read,Write,Edit"
);
pb.directory(new File(".arena/rounds/round-0/claude"));
Process p = pb.start();
// Read stdout, parse JSON
```

**Pros:**
- Matches "No REST / No Model APIs" principle
- Full subprocess lifecycle control
- Filesystem communication
- Model-agnostic design

**Cons:**
- Must handle process management manually
- Stdout parsing and error handling required

---

### Option 2: Claude Code SDK for Java (Third-Party)

A community JVM SDK exists that wraps subprocess management:

```xml
<dependency>
  <groupId>net.cyclingbits</groupId>
  <artifactId>claude-code-sdk-java</artifactId>
  <version>1.2.0</version>
</dependency>
```

**Pros:** Java-native API, handles process lifecycle, typed errors
**Cons:** Only supports Claude CLI (not Codex/Gemini), third-party maintained

**Not selected:** Vendor lock-in to Claude, dependency on third-party maintenance.

---

### Option 3: Direct Model API SDKs

Available SDKs for direct API calls (bypassing CLIs):

| Provider | SDK Status |
|----------|------------|
| Anthropic | Beta Java SDK |
| OpenAI | Official Java SDK |
| Google | Vertex AI SDK |

**Not selected:** Violates spec principles:
- Explicitly breaks "No REST / No Model APIs"
- Loses tool-use capabilities (file access, shell execution)
- Agents can't write to filesystem directly
- No subprocess isolation per round

---

### Option 4: Hybrid Approach

Use third-party SDK for Claude + ProcessBuilder for others.

**Not selected:** Inconsistent abstraction, mixed dependencies.

---

## CLI Programmatic API Summary

| Tool | Official SDK | Java Support | Notes |
|------|--------------|--------------|-------|
| **Claude Code** | TypeScript, Python | Third-party JVM SDK | No official Java SDK |
| **Codex CLI** | TypeScript only | None | Open issue requesting SDK |
| **Gemini CLI** | None | None | Headless mode only |

---

## Headless Mode Reference

| Feature | Claude CLI | Codex CLI | Gemini CLI |
|---------|------------|-----------|------------|
| Non-interactive flag | `-p` / `--print` | `exec` subcommand | `-p` / `--prompt` |
| JSON output | `--output-format json` | `--json` (JSONL) | `--output-format json` |
| Auto-approve tools | `--dangerously-skip-permissions` | `--full-auto` | `--yolo` |
| Session resume | `--resume <id>` | `--threadID <id>` | Not supported |
| Streaming | `--output-format stream-json` | Native JSONL | `--output-format stream-json` |

---

## Sources

- [Claude Code SDK for Java (GitHub)](https://github.com/cyclingbits/claude-code-sdk-java)
- [Claude Code Headless Mode Docs](https://code.claude.com/docs/en/headless)
- [Codex SDK Documentation](https://developers.openai.com/codex/sdk/)
- [Codex Headless Mode](https://deepwiki.com/openai/codex/4.2-headless-execution-mode-(codex-exec))
- [Gemini CLI Headless Mode](https://google-gemini.github.io/gemini-cli/docs/cli/headless.html)
