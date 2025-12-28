This is Round ${roundNumber}.

## Review Target

You are given commit hash(es) or a staged flag to review:
- **Commit 1:** `${commit1}`
- **Commit 2:** `${commit2}`
- **Staged flag:** `${stagedFlag}`

### How to interpret the review target:
- If **staged flag** is `--staged`: Review the currently staged changes using `git diff --staged`
- If **only Commit 1** is provided (Commit 2 is empty): Review the single commit using `git show ${commit1}` or `git diff ${commit1}~1..${commit1}`
- If **both Commit 1 and Commit 2** are provided: Review all changes from Commit 1 to Commit 2 (inclusive). The commits are in chronological order. Use `git log ${commit1}..${commit2}` to see commits and `git diff ${commit1}~1..${commit2}` for the full diff.

Reviews are nearly converged. Previous reviews available at: `${allReviewsPath}`

Your task:
- Identify and merge any remaining duplicate issues
- Finalize severity levels (critical vs medium vs low)
- Ensure each issue has concrete evidence and a clear fix
- Remove any low-value nitpicks that distract from real issues

Produce a clean, consolidated review.

Write your review to `${outputPath}`.
