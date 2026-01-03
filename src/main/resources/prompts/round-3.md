This is Round ${roundNumber} of the code review arena.

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
- Round 2 reviews from all agents (provided below)

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

## The "Best of All Worlds" Approach - Round 3 Focus: Refinement & Calibration

Reviews are converging. The major issues have been identified and evidence gathered. Now we need **surgical refinement**:

- Are severity levels calibrated correctly?
- Is every issue truly actionable?
- Have we removed all false positives?

## Your Process (Work Through This Step by Step)

### Step 1: Read All Round 2 Reviews
Read the **Previous Round Reviews** section above. The reviews should now be evidence-backed and fairly aligned.

### Step 2: Re-verify Remaining Issues
Even at Round 3, verify each issue yourself:

1. **Check the code again** - Has your understanding changed?
2. **Run relevant tests** - Do they pass? Do they cover this case?
3. **Question severity ratings**:
   - Is this "critical" really blocking, or just important?
   - Is this "medium" actually low-risk in practice?
   - Could this "low" issue actually cause real problems?

### Step 3: Calibrate Severity Levels
Apply consistent criteria:

| Severity | Criteria |
|----------|----------|
| **Critical** | Blocks release. Data loss, security breach, crash in happy path |
| **High** | Must fix before merge. Correctness bug, security weakness, major perf issue |
| **Medium** | Should fix. Edge case bug, minor perf issue, maintainability concern |
| **Low** | Nice to fix. Style, minor improvement, defensive hardening |

### Step 4: Ensure Actionability
For each issue, verify the author can act on it immediately:
- Is the location precise? (file:line)
- Is the problem clear? (what's wrong)
- Is the fix obvious? (how to address it)
- Is the evidence convincing? (why they should care)

Remove or rewrite issues that fail this test.

### Step 5: Final Consolidation
- Merge any remaining duplicates
- Remove low-value nitpicks that distract from real issues
- Ensure the review reads as a coherent document, not a checklist

## Critical Rules

- **DO NOT implement or fix anything** - Review only
- **DO verify everything yourself** - Don't trust previous rounds blindly
- **DO remove speculation** - Only verified, actionable issues remain
- **DO calibrate honestly** - Not everything is critical

## When You're Done

Write your refined, calibrated review to `${outputPath}`.

Commit your review file when ready.
