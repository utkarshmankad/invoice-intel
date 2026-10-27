# Invoice Intel

LLM-based invoice extraction pipeline in Java/Spring Boot. Ingests Amazon.in purchase
invoices (PDF, scan, photo), removes PII, extracts GST invoice fields with an LLM,
validates them with rules, reconciles them against the Amazon order-history export, and
sends low-confidence documents to a review UI. Portfolio project, single user.

## Status

Docs phase complete; no application code yet. Next: milestone M0 in `docs/plan.md` (start with M0.1).

## Source-of-truth documents

Read the relevant ones before starting any task:

| File | Answers | Change policy |
|------|---------|---------------|
| `docs/specs.md` | What and why. Requirement IDs (`PII-1`, `VAL-5`, `NFR-10`…) | Only with the owner's explicit approval; bump version + revision history |
| `docs/architecture.md` | How: modules, pipeline, data model, API, ADRs | Update in the same commit as a design change; add an ADR for new decisions |
| `docs/plan.md` | What to do next: milestones and tasks | Tick tasks as they finish |
| `docs/ci.md` | Branching, merge gates, CI thresholds, Gradle task names CI depends on | Thresholds only loosened with the owner's approval |
| `docs/deployment.md` | Phase 2 Fly.io plan (not active) | Keep in sync with config/profile changes |

If code and docs disagree, stop and ask which one is right. Don't silently pick one.

## How to work

Trunk-based development: `main` is protected and only changes through squash-merged pull
requests that pass the `CI gate` check (`docs/ci.md`).

1. Take the next unchecked task in `docs/plan.md` (unless told otherwise).
2. Create a short-lived branch from the latest `main`: `<type>/<task-id>-<short-desc>`.
3. Read the spec IDs and architecture sections the task references.
4. Implement with tests (see `.claude/rules/testing.md`). Keep merged line coverage ≥ 85%.
5. Run `./gradlew build` and `scripts/ci/pii-guard.sh`; both must pass.
6. Tick the task in `plan.md` and commit on the branch (see `.claude/rules/git.md`).
7. The owner pushes and opens the PR; CI must be green before squash merge.

One task per branch and PR. Keep changes inside the task's scope; note other findings in
`plan.md` under "Follow-ups" instead of fixing them on the side.

## Stack (details: architecture.md §3)

Java 25 · Spring Boot 4.x (fallback 3.5 + Java 21, ADR-01) · Gradle Kotlin DSL multi-module
· Spring AI · PDFBox 3 · Tess4J · OpenCV · DJL/ONNX (NER) · Google Tink · JobRunr ·
Postgres + Flyway · MinIO/S3 · Thymeleaf + HTMX · Testcontainers · WireMock · ArchUnit.

## Modules (details: architecture.md §4)

`common` · `storage` · `ingest` · `text` · `pii` · `extract` · `validate` · `reconcile`
· `pipeline` · `app` (main, API, UI) · `eval` (picocli CLI). Base package `dev.invoiceintel.<module>`.
Dependency rules are enforced by ArchUnit; don't add cross-module dependencies that
architecture.md §4.1 doesn't allow.

## Commands (once the skeleton exists)

```bash
docker compose up -d                                         # Postgres + MinIO on 127.0.0.1
./gradlew build                                              # compile + all tests
./gradlew :app:bootRun --args='--spring.profiles.active=local'
./gradlew :eval:run --args="run --dataset data/labels --strategy TEXT"
scripts/ci/pii-guard.sh                                      # same PII check CI runs
```

CI-only checks (CodeQL, Trivy, smoke, Gatling) and how to run them locally: `docs/ci.md` §6.

Tesseract must be installed locally for OCR: `brew install tesseract`.

## Non-negotiables

- **PII:** follow `.claude/rules/pii.md`. Never open files under `data/raw/`,
  `data/order-history/` or `data/labels/`, and never send raw invoice content to any LLM,
  including this one.
- Remote LLM calls go only through `LlmGateway` with `RedactedContent` (architecture.md §6.4).
- Money is `BigDecimal`, never `double`.
- No secrets in code, config files or commits; use env vars (`.env` is git-ignored).
- Never commit to `main` directly; always work on a task branch.
- Don't push, open/merge PRs, change repo settings, lower CI thresholds or deploy unless asked.
- Don't rename Gradle tasks or report paths that CI uses without updating `ci.yml` and `docs/ci.md`.

## Rules files

- `.claude/rules/pii.md`: personal data handling (always applies)
- `.claude/rules/java-style.md`: Java and Spring conventions
- `.claude/rules/testing.md`: test requirements and fixtures
- `.claude/rules/git.md`: trunk-based branches, commits and PRs
- `.claude/rules/docs-workflow.md`: keeping specs/architecture/plan in sync
