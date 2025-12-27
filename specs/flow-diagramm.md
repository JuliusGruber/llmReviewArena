# CLI Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          review-arena CLI Flow                               │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLI INVOCATION                                  │
│  review-arena [options] <ref1> [ref2]                                       │
│  review-arena --staged                                                       │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           ARGUMENT PARSING                                   │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ CLI args    │→ │ Env vars     │→ │ Config file  │→ │ Built-in defaults│  │
│  │ (highest)   │  │              │  │ arena.yaml   │  │ (lowest)         │  │
│  └─────────────┘  └──────────────┘  └──────────────┘  └──────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           WORKSPACE SETUP                                    │
│  .arena/                                                                     │
│  ├── task.md              ← rubric + constraints                            │
│  ├── target/              ← checked-out diff/code                           │
│  └── rounds/              ← created per round                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        ROUND 0: INDEPENDENT REVIEWS                          │
│                                                                              │
│   ┌────────────┐      ┌────────────┐      ┌────────────┐                    │
│   │   Claude   │      │   Codex    │      │   Gemini   │   ← Parallel       │
│   │   Process  │      │   Process  │      │   Process  │     execution      │
│   └─────┬──────┘      └─────┬──────┘      └─────┬──────┘                    │
│         │                   │                   │                            │
│         ▼                   ▼                   ▼                            │
│   ┌────────────┐      ┌────────────┐      ┌────────────┐                    │
│   │ review.md  │      │ review.md  │      │ review.md  │                    │
│   └────────────┘      └────────────┘      └────────────┘                    │
│                              │                                               │
│                              ▼                                               │
│                    ┌──────────────────┐                                     │
│                    │  all_reviews.md  │  ← Combined output                  │
│                    └──────────────────┘                                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ROUND 1-N: CROSS-POLLINATION LOOP                         │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  For each round (fresh agent processes):                             │   │
│  │                                                                       │   │
│  │  INPUT: all_reviews.md (from previous round)                         │   │
│  │         task.md + original code                                       │   │
│  │                                                                       │   │
│  │   ┌────────────┐      ┌────────────┐      ┌────────────┐             │   │
│  │   │   Claude   │      │   Codex    │      │   Gemini   │             │   │
│  │   │  (fresh)   │      │  (fresh)   │      │  (fresh)   │             │   │
│  │   └─────┬──────┘      └─────┬──────┘      └─────┬──────┘             │   │
│  │         │                   │                   │                     │   │
│  │         ▼                   ▼                   ▼                     │   │
│  │   ┌────────────┐      ┌────────────┐      ┌────────────┐             │   │
│  │   │ review.md  │      │ review.md  │      │ review.md  │             │   │
│  │   │ (improved) │      │ (improved) │      │ (improved) │             │   │
│  │   └────────────┘      └────────────┘      └────────────┘             │   │
│  │                              │                                        │   │
│  │                              ▼                                        │   │
│  │                    ┌──────────────────┐                              │   │
│  │                    │  all_reviews.md  │                              │   │
│  │                    └──────────────────┘                              │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                     │                                        │
│              ┌──────────────────────┴──────────────────────┐                │
│              │               STOP WHEN:                     │                │
│              │  • Max rounds reached (--rounds N)           │                │
│              └──────────────────────────────────────────────┘                │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         FINAL OUTPUT GENERATION                              │
│                                                                              │
│  .arena/rounds/final/                                                        │
│  ├── side_by_side.md       ← Final reviews aligned by section               │
│  ├── issue_matrix.md       ← Issue tracking (which agent found what)        │
│  ├── suggested_patches/    ← Extracted diff snippets                        │
│  ├── questions.md          ← Questions for PR author                        │
│  └── champion_review.md    ← Synthesized final review (optional)            │
└─────────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            EXIT CODES                                        │
│  0 = Success    1 = Error    2 = Invalid args    3 = Git error              │
│  4 = Agent error             5 = Config error                               │
└─────────────────────────────────────────────────────────────────────────────┘


┌─────────────────────────────────────────────────────────────────────────────┐
│                      AGENT PROCESS LIFECYCLE                                 │
│                                                                              │
│    ┌───────┐     ┌───────────────┐     ┌───────────┐     ┌──────────┐      │
│    │ START │ ──▶ │ FEED PROMPT   │ ──▶ │ AGENT     │ ──▶ │ CAPTURE  │      │
│    │process│     │ (via stdin)   │     │ WORKS     │     │ OUTPUT   │      │
│    └───────┘     └───────────────┘     └───────────┘     └────┬─────┘      │
│                                                                │            │
│                                              ┌─────────────────┘            │
│                                              ▼                              │
│                                        ┌──────────┐     ┌──────────────┐   │
│                                        │   KILL   │ ──▶ │ VALIDATE     │   │
│                                        │ PROCESS  │     │ OUTPUT       │   │
│                                        └──────────┘     └──────────────┘   │
│                                                                             │
│   Timeouts: agent_timeout_ms (5min default), round_timeout_ms (15min)      │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Key Design Principles

| Principle | Description |
|-----------|-------------|
| **No REST/APIs** | Only process orchestration via CLI subprocess calls |
| **Filesystem as communication** | Agents share work via markdown files |
| **Ephemeral agents** | Fresh process each round (no context carryover) |
| **Linear complexity** | Single `all_reviews.md` avoids N×N pairwise comparisons |
