This is Round ${roundNumber} of a multi-round code review arena.

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

- The original code under review (as specified above)
- Reviews from other agents (provided below)

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

## The "Best of All Worlds" Approach

You are participating in a **cross-pollination tournament**. The goal is to weave together a true hybrid "best of all worlds" review by:

1. **Learning from competitors** - Each reviewer has different blind spots and strengths based on their training and approach
2. **Verifying claims** - Don't blindly accept what others found; verify it yourself
3. **Synthesizing insights** - Combine the strongest ideas into something better than any individual review

This approach breaks "local optima" - when you see radically different perspectives, you can escape suboptimal conclusions you might have reached alone.

## Your Process (Work Through This Step by Step)

### Step 1: Read All Competing Reviews
Read the **Previous Round Reviews** section above carefully. Note each distinct issue raised by any reviewer.

### Step 2: Verify Each Point
For **every issue** raised by competing reviewers:

1. **Go to the code** - Read the actual file and line referenced
2. **Verify the claim** - Is this actually a problem? Run tests, check behavior, trace the logic
3. **Assess severity** - Do you agree with their severity rating? Why or why not?
4. **Check for false positives** - Some claims may be incorrect or based on misunderstanding

Use your tools actively:
- Run `git diff` to see the actual changes
- Read the relevant source files
- Grep for usages to understand impact
- Check if "bugs" are actually handled elsewhere

### Step 3: Identify What Was Missed
After verifying others' findings, look for what NO reviewer caught:
- Edge cases
- Security implications
- Performance issues
- Design problems that will cause future bugs

### Step 4: Synthesize Your Review
Produce a review that:
- Keeps verified, high-value insights from all reviewers
- Removes noise, speculation, and false positives you disproved
- Adds issues you discovered that others missed
- Uses the strongest framing and evidence for each issue

## Critical Rules

- **DO NOT implement or fix anything** - You are a reviewer, not an implementer
- **DO NOT reference other reviewers by name** - Write as if this is the only review
- **DO NOT argue defensively** - If another reviewer is right, adopt their insight
- **DO verify before accepting** - Never include an issue you haven't personally confirmed

## When You're Done

Once you have worked through all points and synthesized your review, write it to `${outputPath}`. Do not create any other files.
