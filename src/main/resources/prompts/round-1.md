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

You are given:
- the original code under review (as specified above)
- a file containing reviews from other agents: `${allReviewsPath}`

Your task:
1. Read all competing reviews carefully.
2. Identify:
   - issues they missed
   - incorrect or weak claims
   - places where an issue is mentioned but not actionable
3. Produce a strictly better review by:
   - keeping the strongest insights
   - removing noise or speculation
   - adding missing high-impact issues
   - improving prioritization and clarity

Important:
- Do NOT reference other reviewers by name.
- Do NOT argue defensively.
- Act as if you want the best possible review to exist, regardless of authorship.

Write a complete, standalone review to `${outputPath}` using the same structure as before.
