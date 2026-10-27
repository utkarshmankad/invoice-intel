# Git rules

## Commits

- Conventional Commits: `feat`, `fix`, `test`, `refactor`, `docs`, `build`, `chore`, with an
  optional module scope: `feat(pii): add GSTIN checksum detector`.
- Subject ≤ 72 characters, imperative mood. Body says *why* and lists spec IDs covered,
  e.g. `Covers: PII-2, VAL-4`.
- One `plan.md` task per commit. The commit that finishes a task also ticks it in `plan.md`.
- Build and tests pass before committing.
- Before every commit, run `git status` and confirm nothing from `data/raw/`,
  `data/order-history/`, `data/labels/`, `.env` or key files is staged (see `pii.md`).

## Branches

- `main` stays buildable.
- Docs-only changes may go straight to `main`.
- Code work happens on a branch per milestone, e.g. `m1-ingest-text`, merged to `main` when
  the milestone's tasks are done and the build is green.

## Never without the owner asking

- `git push`, adding or changing remotes, force-push, rewriting history on `main`.
- Deleting branches or tags.
- Deploying (`fly deploy` or similar).
