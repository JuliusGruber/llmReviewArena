# Claude Code on the Web - Build and Test Environment Analysis

## Executive Summary

This document analyzes what's required to enable build and test runs for the llmReviewArena project in Claude Code on the Web. The analysis is based on official documentation from https://code.claude.com/docs and live environment testing.

**Current Status: BUILD AND TESTS ARE FULLY OPERATIONAL**

The existing SessionStart hook successfully configures Maven proxy settings, enabling dependency resolution through Anthropic's security proxy.

---

## 1. Claude Code on the Web Overview

### 1.1 What Is It?

Claude Code on the Web runs coding tasks asynchronously on Anthropic-managed VMs in the cloud. Key characteristics:

- **Isolated VMs**: Each session runs in a secure, isolated virtual machine
- **Repository Cloning**: Repositories are cloned from GitHub to the VM
- **Network Proxy**: All outbound traffic passes through a security proxy with authentication
- **Limited Network Access**: By default, only allowlisted domains are accessible

### 1.2 Availability

- Pro, Max, Team premium, and Enterprise premium users
- GitHub integration required (GitLab/Bitbucket not supported)

### 1.3 Default Cloud Environment

Pre-installed tools include:
- **Java**: OpenJDK 21 with Maven and Gradle
- **Node.js**: With npm, yarn, pnpm, bun
- **Python 3.x**: With pip and poetry
- **Go, Rust, Ruby, PHP, C++**
- **Databases**: PostgreSQL 16, Redis 7.0

---

## 2. Network Architecture

### 2.1 Security Proxy

All outbound network traffic is routed through Anthropic's security proxy:

```
https_proxy=http://<container_id>:<jwt_token>@<proxy_host>:<proxy_port>
```

The proxy:
- Uses JWT tokens for authentication (with expiration)
- Filters traffic to allowlisted domains only (in "limited" mode)
- Handles rate limiting and abuse prevention

### 2.2 Default Allowed Domains (Relevant to Java/Maven)

| Purpose | Domains |
|---------|---------|
| Maven Central | `maven.org`, `repo1.maven.org` |
| GitHub | `github.com`, `api.github.com` |
| Google Cloud | `googleapis.com`, `storage.googleapis.com` |

### 2.3 Network Configuration for Maven

Maven requires explicit proxy configuration because it doesn't automatically use system proxy environment variables. Two approaches are needed:

1. **Maven Settings XML** (`~/.m2/settings.xml`) - For HTTP/HTTPS proxy configuration
2. **MAVEN_OPTS** - For transport layer configuration

---

## 3. SessionStart Hooks

### 3.1 What Are SessionStart Hooks?

Hooks that execute when a Claude Code session starts. They're configured in `.claude/settings.json`:

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/session-start.sh"
          }
        ]
      }
    ]
  }
}
```

### 3.2 Hook Matchers

- `startup` - New session starts
- `resume` - Session resumed from previous state
- `clear` - `/clear` command invoked
- `compact` - Context compaction occurs

### 3.3 Key Environment Variables

| Variable | Purpose |
|----------|---------|
| `CLAUDE_CODE_REMOTE` | Set to `"true"` in web environment |
| `CLAUDE_PROJECT_DIR` | Absolute path to project root |
| `CLAUDE_ENV_FILE` | Path to persist environment variables for session |
| `https_proxy` / `HTTP_PROXY` | System proxy URL with credentials |

### 3.4 Persisting Environment Variables

SessionStart hooks can persist variables for the entire session:

```bash
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo 'export MY_VAR="value"' >> "$CLAUDE_ENV_FILE"
fi
```

---

## 4. Current Implementation Analysis

### 4.1 Existing Hook: `.claude/hooks/session-start.sh`

The repository already has a working SessionStart hook that:

1. **Detects remote environment** via `CLAUDE_CODE_REMOTE`
2. **Parses proxy credentials** from `https_proxy` environment variable
3. **Generates Maven settings.xml** with proxy configuration
4. **Sets MAVEN_OPTS** via `CLAUDE_ENV_FILE` to use wagon transport

### 4.2 Configuration in `.claude/settings.json`

```json
{
  "hooks": {
    "SessionStart": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/session-start.sh"
          }
        ]
      }
    ]
  }
}
```

### 4.3 Verified Working

Live testing confirms:
- **Maven compile**: SUCCESS
- **Maven test**: SUCCESS
- **Dependency resolution**: Works through proxy

---

## 5. What's Already Done (No Implementation Needed)

| Requirement | Status | Implementation |
|-------------|--------|----------------|
| SessionStart hook configuration | DONE | `.claude/settings.json` |
| Maven proxy configuration | DONE | `session-start.sh` generates `~/.m2/settings.xml` |
| Proxy credential parsing | DONE | Script parses `https_proxy` for host/port/user/password |
| Environment persistence | DONE | Uses `CLAUDE_ENV_FILE` for `MAVEN_OPTS` |
| Remote-only execution | DONE | Checks `CLAUDE_CODE_REMOTE != "true"` |

---

## 6. Potential Improvements (Not Required)

While the current implementation works, here are optional enhancements:

### 6.1 Pre-download Dependencies

Add to `session-start.sh` to speed up first build:
```bash
mvn dependency:go-offline -q
```

**Trade-off**: Increases session startup time but speeds up subsequent builds.

### 6.2 Build Caching

Not currently possible - VMs are ephemeral. Each session starts fresh.

### 6.3 Custom Maven Repository Mirrors

If using private artifact repositories, add mirror configuration to `settings.xml`.

### 6.4 Integration Test Support

Current tests work. For integration tests requiring external services:
- Verify target domains are in the allowlist
- Or request "full" network access in environment settings

---

## 7. Troubleshooting Guide

### 7.1 Common Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| "Connection refused" | Proxy not configured | Ensure `session-start.sh` runs before Maven |
| "407 Proxy Authentication Required" | Credential parsing failed | Check `https_proxy` format |
| "Unknown host" | Domain not allowlisted | Add domain to environment settings or use "full" network |
| Dependencies not downloading | `settings.xml` not generated | Verify hook runs with `CLAUDE_CODE_REMOTE=true` |

### 7.2 Debugging

Check hook execution in session startup output:
```
SessionStart:startup hook success: Configuring Maven proxy...
```

Verify `settings.xml` was created:
```bash
cat ~/.m2/settings.xml
```

---

## 8. Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    Claude Code on the Web                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────┐    ┌──────────────────────────────────┐    │
│  │   GitHub Repo   │───▶│  Anthropic VM (Isolated)         │    │
│  │  llmReviewArena │    │                                   │    │
│  └─────────────────┘    │  1. Clone repository              │    │
│                         │  2. Run SessionStart hook         │    │
│                         │     └─▶ session-start.sh          │    │
│                         │         └─▶ Generate settings.xml │    │
│                         │         └─▶ Set MAVEN_OPTS        │    │
│                         │  3. Execute Claude tasks          │    │
│                         │     └─▶ mvn compile               │    │
│                         │     └─▶ mvn test                  │    │
│                         │  4. Push changes to branch        │    │
│                         │                                   │    │
│                         └────────────┬──────────────────────┘    │
│                                      │                           │
│                                      ▼                           │
│                         ┌──────────────────────────────────┐    │
│                         │      Security Proxy              │    │
│                         │  - JWT Authentication            │    │
│                         │  - Domain Allowlist              │    │
│                         │  - Rate Limiting                 │    │
│                         └────────────┬──────────────────────┘    │
│                                      │                           │
└──────────────────────────────────────┼───────────────────────────┘
                                       │
                                       ▼
                         ┌──────────────────────────────────┐
                         │       External Services          │
                         │  - Maven Central (repo1.maven.org)│
                         │  - GitHub (github.com)           │
                         └──────────────────────────────────┘
```

---

## 9. Conclusion

**The llmReviewArena project is fully configured for build and test runs in Claude Code on the Web.**

The existing `SessionStart` hook correctly:
1. Detects the remote environment
2. Parses proxy credentials from environment variables
3. Generates Maven proxy configuration
4. Persists necessary environment variables

**No further implementation is required for basic build and test functionality.**

---

## 10. References

- [Claude Code on the Web Documentation](https://code.claude.com/docs/en/claude-code-on-the-web)
- [Claude Code Hooks Documentation](https://code.claude.com/docs/en/hooks)
- [Maven Proxy Configuration](https://maven.apache.org/guides/mini/guide-proxies.html)

---

*Analysis Date: 2026-01-11*
*Environment: Claude Code on the Web (cloud_default)*
*Java Version: OpenJDK 21.0.9*
*Maven Version: Apache Maven 3.9.11*
