# Git rules (trunk-based)

Full branching and CI rules: `docs/ci.md`.

## Branches

- `main` is the trunk. It is protected: no direct pushes, changes only via pull request
  that passes the `CI gate` check, squash merge only, linear history.
- Every change, docs included, goes on a short-lived branch cut from the latest `main`:
  `<type>/<task-id>-<short-desc>`, e.g. `feat/m1.3-upload-endpoint`, `docs/spec-v0.3`.
- One plan task per branch and PR. Keep branches small enough to merge within a day or two.
- Rebase on `main` (or use "Update branch") when the PR is behind; never merge `main` into
  a branch.
- Work that needs several PRs to be useful stays switched off with an
  `invoice-intel.features.*` flag until complete, so `main` is always releasable.
- No long-lived milestone, develop or release branches. Releases are tags on `main`.

## Commits and PRs

- Conventional Commits: `feat`, `fix`, `test`, `refactor`, `docs`, `build`, `chore`, `ci`,
  `perf`, `deps`, with an optional module scope: `feat(pii): add GSTIN checksum detector`.
- The **PR title** must be a Conventional Commit; it becomes the squash commit on `main`.
  The PR body says *why* and lists spec IDs covered, e.g. `Covers: PII-2, VAL-4`.
- Commits inside a branch can be small and messy; only the squash commit lands on `main`.
- The PR that finishes a task also ticks it in `plan.md`.
- Build and tests pass locally before opening a PR (`./gradlew build`).
- Before every commit, run `git status` and confirm nothing from `data/raw/`,
  `data/order-history/`, `data/labels/`, `.env` or key files is staged (see `pii.md`).

## Never without the owner asking

- `git push` (also blocked in `.claude/settings.json`), opening or merging PRs.
- Changing remotes, rulesets or repository settings.
- Force-push, rewriting history on `main`, deleting branches or tags.
- Lowering a CI threshold (coverage, performance) or adding a CVE to `.trivyignore`.
- Deploying (`fly deploy` or similar).
