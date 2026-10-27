# Invoice Intel

LLM-based invoice extraction pipeline in Java/Spring Boot. Ingests Amazon.in purchase
invoices (PDF, scan, photo), removes PII, extracts GST invoice fields with an LLM,
validates them with rules, reconciles them against the Amazon order-history export, and
sends low-confidence documents to a review UI. Portfolio project, single user.

## Status

Docs phase. No application code yet. Next step: `docs/plan.md`, then the M0 skeleton.

## Source-of-truth documents

Read the relevant ones before starting any task:

| File | Answers | Change policy |
|------|---------|---------------|
| `docs/specs.md` | What and why. Requirement IDs (`PII-1`, `VAL-5`, `NFR-10`…) | Only with the owner's explicit approval; bump version + revision history |
| `docs/architecture.md` | How: modules, pipeline, data model, API, ADRs | Update in the same commit as a design change; add an ADR for new decisions |
| `docs/plan.md` | What to do next: milestones and tasks | Tick tasks as they finish |
| `docs/deployment.md` | Phase 2 Fly.io plan (not active) | Keep in sync with config/profile changes |

If code and docs disagree, stop and ask which one is right. Don't silently pick one.

## How to work

1. Take the next unchecked task in `docs/plan.md` (unless told otherwise).
2. Read the spec IDs and architecture sections the task references.
3. Implement with tests (see `.claude/rules/testing.md`).
4. Run the build and tests; they must pass.
5. Tick the task in `plan.md`, then commit (see `.claude/rules/git.md`).

One task per commit. Keep changes inside the task's scope; note other findings in
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
```

Tesseract must be installed locally for OCR: `brew install tesseract`.

## Non-negotiables

- **PII:** follow `.claude/rules/pii.md`. Never open files under `data/raw/`,
  `data/order-history/` or `data/labels/`, and never send raw invoice content to any LLM,
  including this one.
- Remote LLM calls go only through `LlmGateway` with `RedactedContent` (architecture.md §6.4).
- Money is `BigDecimal`, never `double`.
- No secrets in code, config files or commits; use env vars (`.env` is git-ignored).
- Don't push, add remotes, or deploy unless asked.

## Rules files

- `.claude/rules/pii.md`: personal data handling (always applies)
- `.claude/rules/java-style.md`: Java and Spring conventions
- `.claude/rules/testing.md`: test requirements and fixtures
- `.claude/rules/git.md`: commits and branches
- `.claude/rules/docs-workflow.md`: keeping specs/architecture/plan in sync
