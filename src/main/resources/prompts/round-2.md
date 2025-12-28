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

You are reviewing an already strong set of reviews from: `${allReviewsPath}`

Your task:
- Increase precision and usefulness.
- Eliminate vague or speculative comments.
- Ensure every high-risk issue includes:
  - concrete evidence (file, function, behavior)
  - why it matters
  - how to fix or mitigate it

Focus especially on:
- subtle correctness bugs
- edge cases
- security implications
- design flaws that will cause future bugs

If multiple reviews mention the same issue:
- consolidate it
- choose the strongest framing
- remove duplication

Write a refined, high-signal review to `${outputPath}`.
