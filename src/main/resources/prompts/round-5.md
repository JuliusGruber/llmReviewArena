This is the final refinement round (Round ${roundNumber}) of the code review arena.

## Review Target

You are given commit hash(es) or a staged flag to review:
- **Commit 1:** `${commit1}`
- **Commit 2:** `${commit2}`
- **Staged flag:** `${stagedFlag}`

### How to interpret the review target:
- If **staged flag** is `--staged`: Review the currently staged changes using `git diff --staged`
- If **only Commit 1** is provided (Commit 2 is empty): Review the single commit using `git show ${commit1}` or `git diff ${commit1}~1..${commit1}`
- If **both Commit 1 and Commit 2** are provided: Review all changes from Commit 1 to Commit 2 (inclusive). The commits are in chronological order. Use `git log ${commit1}..${commit2}` to see commits and `git diff ${commit1}~1..${commit2}` for the full diff.

## Your Input

- The original code under review
- Round 4 reviews from all agents (provided below)

### Previous Round Reviews

The reviews from the previous round are embedded below for your reference.
If for any reason the content below is empty, you can read from: `${allReviewsPath}`

---
<#if previousReviewsContent??>
${previousReviewsContent}
<#else>
*Previous reviews not embedded. Please read from the file path above.*
</#if>
---

## The "Best of All Worlds" Approach - Final Round: The Definitive Review

This is it. After 5 rounds of cross-pollination, verification, and refinement, you must produce **the definitive review**.

The tournament has worked: multiple perspectives have been synthesized, claims have been verified, weak issues have been eliminated. Now produce a review so good that **this is the only one the author needs to read**.

## Your Process (Work Through This Step by Step)

### Step 1: Read All Round 4 Reviews
Read the **Previous Round Reviews** section above. Reviews should be nearly converged.

### Step 2: Final Verification Pass
One last time, verify the most critical issues:

1. **Critical issues**: Verify 100% of them. Go to the code. Run tests if needed.
2. **High issues**: Spot-check at least half
3. **Medium/Low**: Trust the process unless something looks wrong

### Step 3: Make Final Decisions
For any remaining disagreements or ambiguity:
- Go to the code
- Make a decisive call
- Document your reasoning in the evidence

### Step 4: Optimize for the Reader
The author will read ONE review. Make it count:

- **Lead with impact**: Critical issues first, in order of severity
- **Be concise**: Remove any remaining redundancy or filler
- **Be specific**: Every issue has file:line, evidence, and fix guidance
- **Be actionable**: The author can start fixing immediately after reading

### Step 5: Final Quality Gate
Before writing your review, verify:

- [ ] Every critical issue verified this round
- [ ] No speculation or unverified claims
- [ ] No duplicate issues
- [ ] Severity levels are accurate and consistent
- [ ] Every issue is actionable with specific guidance
- [ ] The review reads well as a standalone document

## The Standard

Aim for a review that:
- **Finds real bugs** - Not style nitpicks or theoretical concerns
- **Prioritizes correctly** - Critical means critical
- **Enables action** - The author knows exactly what to fix and why
- **Respects time** - Dense with value, no filler

## Critical Rules

- **DO NOT implement or fix anything** - Review only, even at the final round
- **DO verify critical issues personally** - This is the last chance
- **DO produce a publication-ready review** - This goes to the author
- **DO bias toward fewer, higher-impact comments** - Quality over quantity

## When You're Done

Write your final review to `${outputPath}`. Do not create any other files.

This is your best work.
