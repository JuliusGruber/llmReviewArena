# Java Agent Integration Options

## Technology Stack

| Component | Choice |
|-----------|--------|
| Language | Java 21 LTS |
| Build System | Maven |
| Process Management | ProcessBuilder |

## Decision

**Option 1: ProcessBuilder with Headless CLI Modes** was selected as the implementation approach.

This aligns with the core spec principles:
- Process orchestration, not API orchestration
- Local CLI agents only
- Filesystem as communication layer
- No REST / No Model APIs

---

## Output Model

The orchestrator uses **filesystem-based communication only**:

| Output Type | Source | Used By Orchestrator |
|-------------|--------|---------------------|
| `review.md` file | Agent writes to specified path | **Yes** - authoritative output |
| Stdout | Process output stream | **No** - ignored (logging only) |

The orchestrator:
1. Waits for process exit (`process.waitFor()`)
2. Checks if output file exists and is non-empty
3. Reads file content for the review

No stdout parsing is required.

---

## All Evaluated Options

### Option 1: ProcessBuilder with Headless CLI Modes ✅ **Selected**

All three CLIs support headless modes suitable for subprocess orchestration:

| CLI | Headless Command |
|-----|------------------|
| **Claude CLI** | `claude -p @prompt.txt` |
| **Codex CLI** | `codex exec @prompt.txt` |
| **Gemini CLI** | `gemini -p @prompt.txt` |

> **Note:** Prompts are passed via file reference (`@prompt.txt`) for robustness with large or complex prompts, avoiding shell escaping issues.

**Java Implementation Pattern:**
```java
// Write prompt to temporary file for robustness with large/complex prompts
Path promptFile = workDir.resolve("prompt.txt");
Files.writeString(promptFile, prompt);

ProcessBuilder pb = new ProcessBuilder(
    "claude", "-p", "@" + promptFile.toString(),
    "--allowedTools", "Read,Write,Edit"
);
pb.directory(projectRoot);
Process p = pb.start();

// Wait for completion, then check output file
p.waitFor();
Path reviewFile = Path.of(".arena/rounds/round-0/claude/review.md");
if (Files.exists(reviewFile) && Files.size(reviewFile) > 0) {
    String review = Files.readString(reviewFile);
}
```

**Pros:**
- Matches "No REST / No Model APIs" principle
- Full subprocess lifecycle control
- Filesystem communication
- Model-agnostic design

**Cons:**
- Must handle process management manually

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
| Auto-approve tools | `--dangerously-skip-permissions` | `--full-auto` | `--yolo` |
| Session resume | `--resume <id>` | `--threadID <id>` | Not supported |

---

## Sources

- [Claude Code SDK for Java (GitHub)](https://github.com/cyclingbits/claude-code-sdk-java)
- [Claude Code Headless Mode Docs](https://code.claude.com/docs/en/headless)
- [Codex SDK Documentation](https://developers.openai.com/codex/sdk/)
- [Codex Headless Mode](https://deepwiki.com/openai/codex/4.2-headless-execution-mode-(codex-exec))
- [Gemini CLI Headless Mode](https://google-gemini.github.io/gemini-cli/docs/cli/headless.html)
