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
- Round 1 reviews from all agents (provided below)

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

## The "Best of All Worlds" Approach - Round 2 Focus: Precision & Evidence

Reviews are starting to converge. Your job is to increase **precision and verifiability**.

The tournament method works because each reviewer brings different perspectives. By Round 2, the obvious issues have been found. Now we need to:
- **Sharpen evidence** for every claim
- **Eliminate speculation** that couldn't be verified
- **Add concrete proof** (test results, code traces, actual behavior)

## Your Process (Work Through This Step by Step)

### Step 1: Read All Round 1 Reviews
Read the **Previous Round Reviews** section above. Create a mental list of every issue raised.

### Step 2: Verify and Strengthen Each Issue
For **every issue** in the combined reviews:

1. **Go to the exact location** - Open the file, find the line
2. **Verify it's real** - Run the code path, check the logic, run tests
3. **Gather evidence**:
   - What test proves this is a bug?
   - What input triggers the edge case?
   - What's the actual vs expected behavior?
4. **Assess if it's actionable** - Can the author fix this with the information provided?

Actively use your tools:
- Run tests: `mvn test`, `npm test`, `pytest`, etc.
- Check test coverage for the changed code
- Trace function calls to understand impact
- Look for similar patterns elsewhere in codebase

### Step 3: Eliminate Weak Claims
Remove or downgrade issues that:
- You couldn't reproduce or verify
- Are speculative ("this might cause..." without evidence)
- Are stylistic preferences disguised as bugs
- Are handled elsewhere in the code

### Step 4: Focus on What Matters Most
Prioritize issues by actual impact:
- **Subtle correctness bugs** - Logic errors that pass tests but fail in production
- **Edge cases** - Null handling, empty collections, boundary conditions
- **Security implications** - Input validation, data exposure, auth bypass
- **Design flaws** - Patterns that will cause bugs as code evolves

### Step 5: Synthesize with Maximum Evidence
Write a review where every high-risk issue includes:
- **Concrete evidence**: file, function, line number
- **Why it matters**: actual impact, not theoretical
- **How to verify**: test case or reproduction steps
- **How to fix**: specific guidance (without implementing it yourself)

## Critical Rules

- **DO NOT implement or fix anything** - Review only
- **DO NOT include unverified claims** - If you can't prove it, don't include it
- **DO consolidate duplicates** - Same issue mentioned by multiple reviewers = one entry with strongest evidence
- **DO remove noise** - Fewer high-confidence issues beats many speculative ones

## When You're Done

Write your refined, evidence-backed review to `${outputPath}`.

Commit your review file when ready.
