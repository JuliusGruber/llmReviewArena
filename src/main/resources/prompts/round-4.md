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
- Round 3 reviews from all agents (provided below)

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

## The "Best of All Worlds" Approach - Round 4 Focus: Consolidation

Reviews should now be nearly identical in substance. This round focuses on **final consolidation**:

- Eliminate any remaining duplicates
- Lock in final severity levels
- Ensure the review is publication-ready

## Your Process (Work Through This Step by Step)

### Step 1: Read All Round 3 Reviews
Read the **Previous Round Reviews** section above. Reviews should be well-aligned by now.

### Step 2: Spot-Check Key Issues
You don't need to re-verify everything, but **spot-check** the most important issues:

1. Pick the top 3-5 highest-severity issues
2. Go to the code and confirm they're still valid
3. Run any relevant tests
4. Verify the evidence and fix suggestions are accurate

### Step 3: Identify Remaining Discrepancies
Look for places where reviewers still disagree:
- Different severity ratings for the same issue
- One reviewer kept an issue another removed
- Conflicting fix suggestions

For each discrepancy:
1. Go to the code
2. Verify which position is correct
3. Choose the stronger, more accurate framing

### Step 4: Produce Clean Consolidated Review
Your output should be:
- **No duplicates** - Each issue appears exactly once
- **Consistent severity** - Using the calibration from Round 3
- **Complete evidence** - File, line, impact, fix for each issue
- **Logical order** - Critical issues first, then high, medium, low

### Step 5: Quality Check
Before finalizing, verify:
- [ ] Every critical/high issue has been personally verified this round
- [ ] No speculative or vague issues remain
- [ ] The review is actionable - an author could fix every issue from this alone
- [ ] The review is concise - no redundancy or filler

## Critical Rules

- **DO NOT implement or fix anything** - Review only
- **DO spot-check important issues** - Trust but verify
- **DO resolve discrepancies with evidence** - Go to the code
- **DO produce a clean, final-quality review** - This is nearly the end

## When You're Done

Write your consolidated review to `${outputPath}`.

Commit your review file when ready.
