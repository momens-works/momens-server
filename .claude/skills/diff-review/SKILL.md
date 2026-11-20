---
name: diff-review
description: Use before opening or updating a PR in momens-server to review the current branch diff against develop or main, checking formatting, conventions, docs, secrets, tests, and API contract compatibility.
---

# diff-review

Use this project-local skill before opening a PR or when asked to review the current
branch against `develop` or `main`.

Default base branch: `origin/develop`.
Use `origin/main` only when the user specifically asks for release/main comparison.

## Workflow

1. Read:
   - `AGENTS.md`
   - `docs/rules/git.md`
   - `docs/rules/code-conventions.md`
   - `docs/rules/documentation.md`
   - `docs/spec/api-response-error-codes.md` if API behavior changed
2. Run the helper script:

   ```bash
   bash .claude/skills/diff-review/scripts/check_diff.sh origin/develop
   ```

   Use `origin/main` instead of `origin/develop` if requested.
3. Inspect the actual diff:

   ```bash
   git log --oneline origin/develop..HEAD
   git rev-list --left-right --count origin/develop...HEAD
   MERGE_BASE="$(git merge-base origin/develop HEAD)"
   git diff --stat "$MERGE_BASE"
   git diff --name-status "$MERGE_BASE"
   git diff "$MERGE_BASE"
   ```

   Comparing the merge base to the worktree includes branch commits plus staged and
   unstaged tracked changes without treating newer base-branch commits as deletions. Treat
   a non-zero base-only count as a branch-update blocker before PR. Inspect untracked files
   separately from `git status --short`.

4. Review for:
   - branch and commit convention drift
   - diff scope that does not match the linked Momens task
   - docs and changed code comments against `docs/rules/documentation.md`
   - unsettled decisions that were silently resolved
   - secret or local-only file leakage
   - API response/error body compatibility
   - unnecessary refactors or unrelated churn
   - missing or insufficient tests

## Momens Task Alignment

Before creating or updating a PR:

- Map each meaningful diff group to the requested Momens task.
- Fetch the task through the Momens MCP and confirm its label, title, current status,
  scope, and completion criteria.
- If another task's deliverable appears in the diff, call it out before creating the PR.
- Do not silently absorb another task's scope just because it is technically adjacent.
- If the extra work should stay, mention the related Momens task in the PR body or task
  updates so its status remains explicit.
- A branch label or `Fixes MOM-<number>` does not automatically link or complete the task.
- Do not mark the task `done` while reviewing or opening the PR. Update it to `done`
  only after the PR is actually merged.

## Commit Convention Check

Before creating or updating a PR, inspect:

```bash
git log --oneline origin/develop..HEAD
```

Validate every commit message against the format defined in `docs/rules/git.md`. That
file is the single source of truth, and the format must be followed exactly as specified
there. Do not rely on or reference any commit message examples or formatting described
in this skill or any other agent documentation.

## Validation Commands

For documentation and comment changes, also run the applicable checks in
`docs/rules/documentation.md`.

Prefer this order:

```bash
./gradlew check
./gradlew bootJar
```

If the environment blocks Gradle cache or dependency access, rerun with an appropriate
project-local `GRADLE_USER_HOME` or request approval for network access.

## Output

Use review style:

1. Findings first, ordered by severity, with file/line references when possible.
2. Then test/validation results.
3. Then residual risks or follow-up suggestions.

If there are no issues, say so clearly and still report validation coverage.
