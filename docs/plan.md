# Invoice Intel — Plan

> Status: v0.1 · Last updated: 2026-10-28
>
> Execution order for `specs.md` and `architecture.md`. One task = one short-lived branch =
> one squash-merged PR (trunk-based, `docs/ci.md`). Tick `[x]` in the PR that finishes the task. New work found along the way goes
> under **Follow-ups**, not into the current task.
>
> Task format: `ID` · what to do · *refs* · **Done when**.
>
> `[owner]` marks tasks only the owner can do (real data, real API keys, approvals,
> recordings, releases). The scheduled milestone runner (`docs/runner.md`) does every other
> task and stops to ask when it reaches an `[owner]` task.

---

## Milestone overview

| Milestone | Goal | Depends on |
|-----------|------|------------|
| D | Owner data preparation (runs in parallel) | — |
| M0 | Buildable skeleton, local stack, CI build contract | — |
| M1 | Ingest and text extraction | M0 |
| M2 | PII detection, tokens, redaction, PII gate | M1 |
| M3 | LLM extraction and pipeline wiring | M2 |
| M4 | Validation rules and decisions | M3 |
| M5 | Eval harness and first baseline | M4, D3–D5 |
| M6 | Order-history import and reconciliation | M4, D2 |
| M7 | Review UI | M4 |
| M8 | Case study, redacted samples, release | M5, M6, M7 |

Branches are per task, not per milestone: `<type>/<task-id>-<short-desc>` (e.g.
`feat/m1.3-upload-endpoint`). Every PR must pass the `CI gate` check before squash merge.

**Critical path:** M0 → M1 → M2 → M3 → M4 → M5. M6 and M7 can run in either order after M4.

---

## D — Owner data preparation (manual, owner only)

Claude does not touch real data (`.claude/rules/pii.md`). These tasks run alongside M0–M4.

- [ ] **D1** · [owner] · Request the Amazon.in data export ("Request Your Data") · *R4, R5* · **Done when** request submitted (export can take days).
- [ ] **D2** · [owner] · Save the order-history CSV to `data/order-history/`; write down its column names (not values) in `config/order-history-mapping.yml` · *REC-1, R4* · **Done when** mapping file lists every column.
- [ ] **D3** · [owner] · Download ~20 invoices spread over 2013–2026, Amazon Retail + third-party, at least 2 credit notes, at least 3 pre-GST · *§4.3, R1* · **Done when** files are in `data/raw/`.
- [ ] **D4** · [owner] · Add 3–5 phone photos or printed-and-scanned invoices · *R2* · **Done when** files are in `data/raw/`.
- [ ] **D5** · [owner] · Hand-label the first 20 invoices (schema fields + PII spans) using the label format from M5.1 · *EVAL-3* · **Done when** 20 label files in `data/labels/`.
- [ ] **D6** · [owner] · Grow the corpus to 150–300 invoices · *§4.3* · **Done when** before M5.6.

---

## M0 — Skeleton

- [x] **M0.1** · Verify Spring Boot 4 compatibility of Spring AI, JobRunr, springdoc, DJL, Tess4J; pick Boot 4 + Java 25 or Boot 3.5 + Java 21; record versions in `architecture.md` ADR-01 · *A1, ADR-01* · **Done when** ADR-01 states the chosen versions with the date checked.
- [x] **M0.2** · Gradle multi-module skeleton: all modules from architecture §4, version catalog, Java toolchain, empty `app` Spring Boot main · *§4* · **Done when** `./gradlew build` passes.
- [x] **M0.3** · Spotless + google-java-format; `.editorconfig` · *java-style.md* · **Done when** `./gradlew spotlessCheck` runs in `build`.
- [x] **M0.4** · ArchUnit test module with the dependency rules from §4.1 (placeholder rules for empty modules) · *§4.1, NFR-9* · **Done when** a deliberate bad dependency fails the build (then removed).
- [x] **M0.5** · `compose.yaml` (Postgres, SeaweedFS S3 + bucket init; ADR-11), all bound to `127.0.0.1`; `.env.example` · *NFR-2, NFR-7, §14* · **Done when** `docker compose up -d` gives a reachable DB and two buckets.
- [x] **M0.6** · Profiles `local` / `test`; typed `@ConfigurationProperties` skeleton under `invoice-intel.*`; Flyway V1 with `document` table; Actuator health · *§10, NFR-12* · **Done when** app starts locally and `/actuator/health` is UP.
- [x] **M0.7** · Spring Security: single admin user from env, form login for UI, HTTP Basic for `/api/**` · *NFR-2, §11* · **Done when** everything except health requires login; anonymous `/api/**` gets 401 (test).
- [x] **M0.8** · Structured JSON logging with `documentId`/`stage` MDC; PII-mask backstop filter (pattern list stub) · *NFR-6, PII-8* · **Done when** log test shows masked phone/email.
- [x] **M0.9** · Multi-stage `Dockerfile` (Temurin + `tesseract-ocr`) · *ADR-07, NFR-10* · **Done when** image builds and starts with `local` profile.
- [x] **M0.10** · GitHub repo, trunk-based ruleset, CI and nightly workflows, Dependabot, PII guard, smoke/coverage/perf scripts · *NFR-9, NFR-13, ci.md* · **Done when** files exist (this task was done before M0.1).
- [x] **M0.11** · `Makefile` (or script) for `up`, `run`, `test`, `down` · *NFR-7* · **Done when** `make up run` starts the stack.
- [x] **M0.12** · Test build contract: `integrationTest` source set and task; JaCoCo `jacocoUnitReport`, `jacocoIntegrationReport`, `jacocoMergedReport` (CSV + HTML) and `jacocoMergedCoverageVerification` (line ≥ `minCoverage`, default 85) · *NFR-13, ci.md §3* · **Done when** `unit-tests`, `integration-tests` and `coverage` jobs pass on a PR.
- [x] **M0.13** · SpotBugs + FindSecBugs (`spotbugsMain`), PIT plugin configured for `pii` and `validate`, Gradle dependency locking · *NFR-13* · **Done when** `static-analysis` job passes and `./gradlew :pii:pitest` runs.
- [x] **M0.14** · `compose.ci.yaml`: app container from `invoice-intel:ci` (2 GB limit), WireMock LLM stub with 800 ms latency, throwaway keys · *NFR-10, NFR-13, ci.md §4* · **Done when** `container` and `smoke` jobs pass.
- [ ] **M0.15** · `perf` module: Gatling Java DSL, `thresholds.conf`, health/auth simulation first (pipeline scenarios added in M4.10) · *NFR-3, NFR-13, ci.md §5* · **Done when** `performance` job runs and its assertions gate the PR.
- [ ] **M0.16** · First fully green `CI gate` on a PR with all jobs running (none skipped except PR-only ones on `main`) · *NFR-13* · **Done when** green run linked in the Decisions log.

---

## M1 — Ingest and text

- [ ] **M1.1** · Synthetic invoice generator (test fixtures): GST tax invoice, credit note, pre-GST invoice; fake names/addresses, valid-format fake GSTIN/PAN; outputs digital PDF · *testing.md* · **Done when** generator produces 3 fixture types used by tests, plus the smoke fixture at `app/src/integrationTest/resources/fixtures/smoke/gst-tax-invoice.pdf`.
- [ ] **M1.2** · `storage`: `BlobStore` interface + S3 implementation; Testcontainers SeaweedFS test · *NFR-11, §9.1* · **Done when** put/get/delete tested.
- [ ] **M1.3** · `ingest`: upload endpoint, SHA-256 dedupe, Tika MIME check, raw blob write, `document` row · *ING-1..4, §6.1* · **Done when** integration test covers new upload, duplicate, and rejected type.
- [ ] **M1.4** · `pipeline`: JobRunr setup on Postgres, document state machine, stage-run table, reprocess-from-stage · *NFR-5, §5.1* · **Done when** a no-op stage chain advances a document RECEIVED → TEXT_READY and can be re-run.
- [ ] **M1.5** · `text`: PDF text layer with word boxes (PDFBox) and has-text-layer check · *TXT-1, TXT-2* · **Done when** fixture PDF yields expected words + boxes.
- [ ] **M1.6** · `text`: page rendering at 300 DPI, one page at a time; page images to raw bucket · *TXT-2, §10.1* · **Done when** page PNGs stored and referenced in `blob_ref`.
- [ ] **M1.7** · `text`: OpenCV preprocessing (grayscale, deskew, threshold, denoise) · *TXT-3* · **Done when** skewed synthetic scan is deskewed within ±1°.
- [ ] **M1.8** · `text`: Tess4J OCR to the common `TextLayer` format · *TXT-1, TXT-2* · **Done when** rasterised fixture OCRs with ≥ 95% word match.
- [ ] **M1.9** · `text`: rule-based document classifier · *TXT-4, ADR-09* · **Done when** all fixture types classified correctly.
- [ ] **M1.10** · Text stage job wired into the pipeline · *§5.2* · **Done when** uploaded fixture reaches TEXT_READY with a stored text layer.

---

## M2 — PII

- [ ] **M2.1** · Regex detectors: phone, email, PIN, PAN, order/invoice numbers · *PII-2(a)* · **Done when** positive/negative tests pass.
- [ ] **M2.2** · Checksum validators: Verhoeff (Aadhaar), GSTIN mod-36; GSTIN/Aadhaar detectors use them · *PII-2(a), VAL-4* · **Done when** property tests pass.
- [ ] **M2.3** · Seller-block tagging + `AddressBlockDetector` (anchors, buyer name, street, state extraction) · *§6.3, §6.1* · **Done when** fixtures: buyer name/street detected, seller untouched, state kept.
- [ ] **M2.4** · `SpanMerger` (overlaps, word expansion, type priority) · *§6.3* · **Done when** overlap cases tested.
- [ ] **M2.5** · Token generator (HMAC, deterministic) + Tink vault (AEAD) + keys from env · *PII-3, PII-7, ADR-06* · **Done when** same value → same token across docs; vault round-trip tested; no key in config.
- [ ] **M2.6** · Text redactor → `RedactedContent` (package-private constructor) · *PII-1, §6.4* · **Done when** redacted text of fixtures contains tokens and no fixture PII.
- [ ] **M2.7** · Image redactor (masked page PNGs) + redacted PDF rebuilt from images · *PII-4, PII-5, ADR-05* · **Done when** the redaction-proof test passes (re-extract + OCR finds no PII).
- [ ] **M2.8** · `NerDetector` with DJL + ONNX; compare two small NER models on synthetic Indian names · *PII-2(b), A2, R3* · **Done when** chosen model recorded in architecture A2 with recall numbers.
- [ ] **M2.9** · Detector toggles + pseudonymize/redact mode config; optional `LocalLlmDetector` stub, off by default · *PII-2, PII-3* · **Done when** each layer can be switched off in config and tests cover both modes.
- [ ] **M2.10** · PII stage job; failure → `FAILED` with no `RedactedContent` · *PII-1, §5.1* · **Done when** fixture reaches PII_CLEAN; forced failure blocks progress.
- [ ] **M2.11** · ArchUnit PII-gate rules (§6.4) · *PII-1, NFR-1* · **Done when** a deliberate violation fails the build (then removed).

---

## M3 — Extraction

- [ ] **M3.1** · Schema records: `InvoiceExtraction`, `LineItem`, `TaxLine`, `Field<T>` + Bean Validation · *§5, EXT-1* · **Done when** JSON round-trip tested.
- [ ] **M3.2** · Model registry config (provider, model ID, capabilities, prices) · *EXT-3, NFR-8* · **Done when** two providers configurable without code changes.
- [ ] **M3.3** · `LlmGateway` on Spring AI: structured output, one repair retry, rate limiter, `llm_call` recording with tokens/cost/latency · *EXT-1, EXT-4, NFR-4, §6.5* · **Done when** WireMock tests cover success, repair, failure.
- [ ] **M3.4** · Versioned prompt files + loader; `TEXT` strategy with line markers · *EXT-2, EXT-5* · **Done when** prompt name/version stored on each call.
- [ ] **M3.5** · `VISION` and `HYBRID` strategies using redacted page images · *EXT-2* · **Done when** strategy selectable per run and tested with stubs.
- [ ] **M3.6** · Evidence mapping (line markers → word boxes) · *§5.3, REV-2* · **Done when** fields carry page + bbox in tests.
- [ ] **M3.7** · `LeakChecker` after extraction · *PII-6* · **Done when** stubbed leaking response fails the document with `PII_LEAK`.
- [ ] **M3.8** · Extract stage job wired into the pipeline · *§5.2* · **Done when** synthetic fixture reaches EXTRACTED with stubbed LLM (integration test).
- [ ] **M3.9** · [owner] · First manual run on 3 real invoices with a real API key · *§5.2* · **Done when** owner confirms 3 documents reach EXTRACTED (owner shares only status/metrics).

---

## M4 — Validation and decisions

- [ ] **M4.1** · Rule engine + `ValidationRule` interface + `validation_result` table · *§6.6* · **Done when** rules run and results stored.
- [ ] **M4.2** · VAL-1, VAL-2, VAL-3 arithmetic rules (`BigDecimal`, ±₹1) · *VAL-1..3* · **Done when** parameterized tests pass.
- [ ] **M4.3** · VAL-4 (GSTIN + state code) and VAL-5 (CGST/SGST vs IGST) with a state-code table · *VAL-4, VAL-5* · **Done when** all states/UTs covered in tests.
- [ ] **M4.4** · VAL-6 tax-regime date rule · *VAL-6* · **Done when** boundary dates tested.
- [ ] **M4.5** · VAL-7 amount-in-words parser (Indian numbering) · *VAL-7* · **Done when** lakh/crore/paise cases pass.
- [ ] **M4.6** · Confidence scorer + decision (AUTO_APPROVED / NEEDS_REVIEW / FAILED) · *VAL-8, §6.6* · **Done when** decision stored with reasons.
- [ ] **M4.7** · Validate stage job; end-to-end pipeline test RECEIVED → decision with stubbed LLM; narrow `SMOKE_EXPECT_STATUSES` in `ci.yml` to `AUTO_APPROVED,NEEDS_REVIEW` · *§5, ci.md §4* · **Done when** integration test and smoke job green.
- [ ] **M4.8** · Document API: list, detail, reprocess · *§8* · **Done when** endpoints tested; responses contain no raw text.
- [ ] **M4.9** · Micrometer metrics from architecture §12 · *NFR-6* · **Done when** metrics visible at `/actuator/metrics`.
- [ ] **M4.10** · Gatling scenarios: upload/read API, end-to-end time to decision, list endpoints, with thresholds from ci.md §5 · *NFR-3, NFR-13* · **Done when** `performance` job enforces all three scenarios.

---

## M5 — Eval harness and baseline

- [ ] **M5.1** · Label file format (schema + `pii_spans`) and a JSON Schema for it · *EVAL-3, §9.2* · **Done when** format documented in architecture §9.2 (unblocks D5).
- [ ] **M5.2** · `eval` CLI skeleton with picocli: `run`, `compare` · *EVAL-1* · **Done when** `run` executes on synthetic fixtures.
- [ ] **M5.3** · Field accuracy and line-item F1 metrics · *EVAL-2* · **Done when** unit tests on hand-made label/output pairs.
- [ ] **M5.4** · PII recall/precision metric (span overlap ≥ 50%) · *EVAL-2, §6.3* · **Done when** tested on synthetic spans.
- [ ] **M5.5** · Decision metrics (straight-through, false auto-approval), cost, p50/p95 latency; Markdown + CSV + `config.json` reports · *EVAL-2, EVAL-4, NFR-3, NFR-4* · **Done when** report written for a fixture run.
- [ ] **M5.6** · [owner] · **Baseline run (owner):** TEXT strategy, one model, on labelled set · *G5* · **Done when** report committed to `eval-reports/` (metrics only).
- [ ] **M5.7** · [owner] · Comparison runs: VISION vs TEXT, second model · *G5, EXT-2* · **Done when** comparison report committed.
- [ ] **M5.8** · Tune decision threshold and PII settings from results; record changes in specs §9 revision history if targets move · *§9, VAL-8* · **Done when** thresholds updated with eval evidence.
- [ ] **M5.9** · Eval smoke test on synthetic fixtures with stubbed LLM, run as an integration test so it's part of the CI gate · *NFR-9* · **Done when** runs in the `integration-tests` job.

---

## M6 — Reconciliation

- [ ] **M6.1** · Order-history import with configurable column mapping; order numbers tokenized · *REC-1* · **Done when** synthetic CSV imports correctly.
- [ ] **M6.2** · Matcher: multi-invoice orders, credit notes, amount/date tolerance · *REC-2, REC-4* · **Done when** synthetic scenarios tested.
- [ ] **M6.3** · Reconciliation report API + eval metric (match rate) · *REC-3, §9* · **Done when** endpoint and metric tested.
- [ ] **M6.4** · [owner] · Owner runs import + reconciliation on real export · *G4* · **Done when** match rate recorded in eval report.

---

## M7 — Review UI

- [ ] **M7.1** · Thymeleaf + HTMX layout, login page, nav · *G6* · **Done when** pages render behind login.
- [ ] **M7.2** · Review queue page · *REV-1* · **Done when** lists NEEDS_REVIEW with reasons.
- [ ] **M7.3** · Document page: redacted image + SVG evidence overlay + field form · *REV-2* · **Done when** evidence boxes align on fixtures.
- [ ] **M7.4** · Field corrections with audit trail + approve · *REV-3* · **Done when** corrections stored and decision updated.
- [ ] **M7.5** · Label export from reviewed documents · *REV-4* · **Done when** exported files validate against the M5.1 schema.

---

## M8 — Case study and release

- [ ] **M8.1** · [owner] · Publish ≥ 20 irreversibly redacted samples with expected JSON (owner reviews every file) · *UC6, §10* · **Done when** samples committed after owner sign-off.
- [ ] **M8.2** · [owner] · README as a case study: problem, architecture diagram, PII gate, eval results table, trade-offs, what failed · *§10* · **Done when** owner approves README.
- [ ] **M8.3** · [owner] · Demo script/GIF of upload → review → reconciliation on redacted samples · *§10* · **Done when** recorded.
- [ ] **M8.4** · [owner] · Final eval run against spec §9 targets; record results and any target changes · *§9* · **Done when** results table in README and specs revision history.
- [ ] **M8.5** · [owner] · Tag `v1.0.0` (owner pushes) · — · **Done when** tag exists.

Phase 2 (Fly.io) is out of scope for this plan; see `deployment.md`.

---

## Follow-ups

_Work discovered during tasks that is out of scope for the current task. Add entries as
`- [ ] short description · found in <task ID>`._

- [ ] google-java-format is pinned to 1.36.1 because 1.37.0 fails under Spotless 8.10.4 on JDK 25; retry the upgrade when Spotless updates (expect Dependabot PRs for it to fail) · found in M0.2
- [ ] architecture §4.1 lists no module allowed to depend on `ingest`, so the ArchUnit rule forbids all access; M1.3's upload endpoint in `app` (or `pipeline`) will need `ingest` added to §4.1 first · found in M0.4
- [ ] specs.md NFR-11 and §8 Phase 1 still say "MinIO locally"; local S3 is SeaweedFS since ADR-11 (needs owner approval to edit specs) · found in M0.5
- [x] `ci.yml` smoke step name still says "MinIO"; rename when `compose.ci.yaml` lands · found in M0.5
- [ ] Two integration-test contexts (`RANDOM_PORT` web vs. mock) each start a Postgres container; share one context or a singleton container once more ITs exist · found in M0.6
- [ ] `.env.example` must list `APP_ADMIN_USER` and `APP_ADMIN_PASSWORD` (required since M0.7); the runner can't read `.env*` files, so the owner checks it · found in M0.7
- [ ] `PiiLogMasker` (app) holds a stub email/phone pattern list; replace it with the M2.1 detector regex set (PIN, PAN, GSTIN, Aadhaar, order numbers) once those exist, without a dependency from `app` internals on `pii` internals · found in M0.8
- [ ] §4.1 "no module except `pii` may read the vault tables or vault key" has no ArchUnit rule yet; add it with the vault types (M2.5) or the PII-gate rules (M2.11) · found in M0.4
- [ ] The CI `llm-stub` (WireMock) answers every POST with a placeholder body and the app gets no LLM base URL or key yet; add provider-shaped stub mappings and point the app at `http://llm-stub:8080` with a throwaway key once the `llm.*` config keys exist · found in M0.14

---

## Decisions log

| Date | Decision | Task |
|------|----------|------|
| 2026-10-28 | Plan v0.1 created | — |
| 2026-10-28 | Public GitHub repo, trunk-based development, CI merge gates (coverage ≥ 85%) | M0.10 |
| 2026-10-28 | Spring Boot 4.1.2 + Java 25 (no fallback needed); versions in architecture ADR-01 | M0.1 |
| 2026-10-28 | M0.2 ships with M0.3, M0.12 and M0.13 in one PR: CI runs the whole build contract once `settings.gradle.kts` exists (owner decision) | M0.2 |
| 2026-10-28 | M0.9 ships with M0.14 in one PR: a `Dockerfile` turns on the `smoke` job, which needs `compose.ci.yaml` (owner decision) | M0.9 |
| 2026-10-28 | Local S3 store is SeaweedFS, not MinIO (MinIO images no longer public); ADR-11 (owner decision) | M0.5 |
| 2026-10-28 | `/api/**` stateless Basic, CSRF off, no `WWW-Authenticate` challenge; ADR-12 (owner decision) | M0.7 |
