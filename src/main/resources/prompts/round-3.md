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

Reviews are converging. Previous reviews available at: `${allReviewsPath}`

Your task is to refine further:
- Remove any remaining vague or speculative comments
- Strengthen evidence for each issue
- Ensure severity levels are calibrated correctly
- Consolidate duplicate issues into single, authoritative entries

Focus on:
- Actionable feedback the author can implement immediately
- Clear file/line references for every issue
- Realistic fix suggestions

Write a refined review to `${outputPath}`.
