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
│  project-root/            ← agents run here (working directory)             │
│  ├── .arena/                                                                │
│  │   ├── task.md          ← rubric + git range to review                    │
│  │   └── rounds/          ← review outputs per round                        │
│  └── <project files>      ← full source tree accessible                     │
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
│                         FINAL SYNTHESIS (Claude)                             │
│                                                                              │
│  After cross-pollination completes:                                          │
│                                                                              │
│   INPUT: round-N/all_reviews.md (final combined reviews)                     │
│                                                                              │
│                    ┌────────────────────┐                                   │
│                    │   Claude Process   │  ← Always Claude (required)       │
│                    │   (synthesizer)    │                                   │
│                    └─────────┬──────────┘                                   │
│                              │                                               │
│                              ▼                                               │
│                    ┌────────────────────┐                                   │
│                    │ champion_review.md │                                   │
│                    └────────────────────┘                                   │
│                                                                              │
│  OUTPUT FILES:                                                               │
│  .arena/rounds/                                                              │
│  ├── round-N/all_reviews.md    ← Combined final reviews from all agents     │
│  ├── round-N/<agent>/review.md ← Individual agent reviews from final round  │
│  └── final/                                                                  │
│      ├── prompt.md             ← Persisted synthesis prompt (for debugging) │
│      └── champion_review.md    ← Synthesized final review                   │
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
│   Timeouts: agent_timeout_ms (10min default), round_timeout_ms (15min)     │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Key Design Principles

| Principle | Description |
|-----------|-------------|
| **No REST/APIs** | Only process orchestration via CLI subprocess calls |
| **Filesystem as communication** | Agents share work via markdown files |
| **Ephemeral agents** | Fresh process each round (no context carryover) |
| **Linear complexity** | Single `all_reviews.md` avoids N×N pairwise comparisons |
