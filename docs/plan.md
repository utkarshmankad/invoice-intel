# Invoice Intel — Plan

> Status: v0.1 · Last updated: 2026-10-28
>
> Execution order for `specs.md` and `architecture.md`. One task = one commit.
> Tick `[x]` in the same commit that finishes the task. New work found along the way goes
> under **Follow-ups**, not into the current task.
>
> Task format: `ID` · what to do · *refs* · **Done when**.

---

## Milestone overview

| Milestone | Goal | Branch | Depends on |
|-----------|------|--------|------------|
| D | Owner data preparation (runs in parallel) | — | — |
| M0 | Buildable skeleton, local stack, CI | `m0-skeleton` | — |
| M1 | Ingest and text extraction | `m1-ingest-text` | M0 |
| M2 | PII detection, tokens, redaction, PII gate | `m2-pii` | M1 |
| M3 | LLM extraction and pipeline wiring | `m3-extract` | M2 |
| M4 | Validation rules and decisions | `m4-validate` | M3 |
| M5 | Eval harness and first baseline | `m5-eval` | M4, D3–D5 |
| M6 | Order-history import and reconciliation | `m6-reconcile` | M4, D2 |
| M7 | Review UI | `m7-review` | M4 |
| M8 | Case study, redacted samples, release | `m8-release` | M5, M6, M7 |

**Critical path:** M0 → M1 → M2 → M3 → M4 → M5. M6 and M7 can run in either order after M4.

---

## D — Owner data preparation (manual, owner only)

Claude does not touch real data (`.claude/rules/pii.md`). These tasks run alongside M0–M4.

- [ ] **D1** · Request the Amazon.in data export ("Request Your Data") · *R4, R5* · **Done when** request submitted (export can take days).
- [ ] **D2** · Save the order-history CSV to `data/order-history/`; write down its column names (not values) in `config/order-history-mapping.yml` · *REC-1, R4* · **Done when** mapping file lists every column.
- [ ] **D3** · Download ~20 invoices spread over 2013–2026, Amazon Retail + third-party, at least 2 credit notes, at least 3 pre-GST · *§4.3, R1* · **Done when** files are in `data/raw/`.
- [ ] **D4** · Add 3–5 phone photos or printed-and-scanned invoices · *R2* · **Done when** files are in `data/raw/`.
- [ ] **D5** · Hand-label the first 20 invoices (schema fields + PII spans) using the label format from M5.1 · *EVAL-3* · **Done when** 20 label files in `data/labels/`.
- [ ] **D6** · Grow the corpus to 150–300 invoices · *§4.3* · **Done when** before M5.6.

---

## M0 — Skeleton

- [ ] **M0.1** · Verify Spring Boot 4 compatibility of Spring AI, JobRunr, springdoc, DJL, Tess4J; pick Boot 4 + Java 25 or Boot 3.5 + Java 21; record versions in `architecture.md` ADR-01 · *A1, ADR-01* · **Done when** ADR-01 states the chosen versions with the date checked.
- [ ] **M0.2** · Gradle multi-module skeleton: all modules from architecture §4, version catalog, Java toolchain, empty `app` Spring Boot main · *§4* · **Done when** `./gradlew build` passes.
- [ ] **M0.3** · Spotless + google-java-format; `.editorconfig` · *java-style.md* · **Done when** `./gradlew spotlessCheck` runs in `build`.
- [ ] **M0.4** · ArchUnit test module with the dependency rules from §4.1 (placeholder rules for empty modules) · *§4.1, NFR-9* · **Done when** a deliberate bad dependency fails the build (then removed).
- [ ] **M0.5** · `compose.yaml` (Postgres, MinIO + bucket init), all bound to `127.0.0.1`; `.env.example` · *NFR-2, NFR-7, §14* · **Done when** `docker compose up -d` gives a reachable DB and two buckets.
- [ ] **M0.6** · Profiles `local` / `test`; typed `@ConfigurationProperties` skeleton under `invoice-intel.*`; Flyway V1 with `document` table; Actuator health · *§10, NFR-12* · **Done when** app starts locally and `/actuator/health` is UP.
- [ ] **M0.7** · Spring Security form login with single admin user from env · *NFR-2, §11* · **Done when** everything except health requires login (test).
- [ ] **M0.8** · Structured JSON logging with `documentId`/`stage` MDC; PII-mask backstop filter (pattern list stub) · *NFR-6, PII-8* · **Done when** log test shows masked phone/email.
- [ ] **M0.9** · Multi-stage `Dockerfile` (Temurin + `tesseract-ocr`) · *ADR-07, NFR-10* · **Done when** image builds and starts with `local` profile.
- [ ] **M0.10** · GitHub Actions: build + tests on push · *NFR-9* · **Done when** workflow file runs green locally with `act` or on first push (owner pushes).
- [ ] **M0.11** · `Makefile` (or script) for `up`, `run`, `test`, `down` · *NFR-7* · **Done when** `make up run` starts the stack.

---

## M1 — Ingest and text

- [ ] **M1.1** · Synthetic invoice generator (test fixtures): GST tax invoice, credit note, pre-GST invoice; fake names/addresses, valid-format fake GSTIN/PAN; outputs digital PDF · *testing.md* · **Done when** generator produces 3 fixture types used by tests.
- [ ] **M1.2** · `storage`: `BlobStore` interface + S3 implementation; Testcontainers MinIO test · *NFR-11, §9.1* · **Done when** put/get/delete tested.
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
- [ ] **M3.8** · Extract stage job; first manual run on 3 real invoices by the owner with a real API key · *§5.2* · **Done when** owner confirms 3 documents reach EXTRACTED (owner shares only status/metrics).

---

## M4 — Validation and decisions

- [ ] **M4.1** · Rule engine + `ValidationRule` interface + `validation_result` table · *§6.6* · **Done when** rules run and results stored.
- [ ] **M4.2** · VAL-1, VAL-2, VAL-3 arithmetic rules (`BigDecimal`, ±₹1) · *VAL-1..3* · **Done when** parameterized tests pass.
- [ ] **M4.3** · VAL-4 (GSTIN + state code) and VAL-5 (CGST/SGST vs IGST) with a state-code table · *VAL-4, VAL-5* · **Done when** all states/UTs covered in tests.
- [ ] **M4.4** · VAL-6 tax-regime date rule · *VAL-6* · **Done when** boundary dates tested.
- [ ] **M4.5** · VAL-7 amount-in-words parser (Indian numbering) · *VAL-7* · **Done when** lakh/crore/paise cases pass.
- [ ] **M4.6** · Confidence scorer + decision (AUTO_APPROVED / NEEDS_REVIEW / FAILED) · *VAL-8, §6.6* · **Done when** decision stored with reasons.
- [ ] **M4.7** · Validate stage job; end-to-end pipeline test RECEIVED → decision with stubbed LLM · *§5* · **Done when** integration test green.
- [ ] **M4.8** · Document API: list, detail, reprocess · *§8* · **Done when** endpoints tested; responses contain no raw text.
- [ ] **M4.9** · Micrometer metrics from architecture §12 · *NFR-6* · **Done when** metrics visible at `/actuator/metrics`.

---

## M5 — Eval harness and baseline

- [ ] **M5.1** · Label file format (schema + `pii_spans`) and a JSON Schema for it · *EVAL-3, §9.2* · **Done when** format documented in architecture §9.2 (unblocks D5).
- [ ] **M5.2** · `eval` CLI skeleton with picocli: `run`, `compare` · *EVAL-1* · **Done when** `run` executes on synthetic fixtures.
- [ ] **M5.3** · Field accuracy and line-item F1 metrics · *EVAL-2* · **Done when** unit tests on hand-made label/output pairs.
- [ ] **M5.4** · PII recall/precision metric (span overlap ≥ 50%) · *EVAL-2, §6.3* · **Done when** tested on synthetic spans.
- [ ] **M5.5** · Decision metrics (straight-through, false auto-approval), cost, p50/p95 latency; Markdown + CSV + `config.json` reports · *EVAL-2, EVAL-4, NFR-3, NFR-4* · **Done when** report written for a fixture run.
- [ ] **M5.6** · **Baseline run (owner):** TEXT strategy, one model, on labelled set · *G5* · **Done when** report committed to `eval-reports/` (metrics only).
- [ ] **M5.7** · Comparison runs: VISION vs TEXT, second model · *G5, EXT-2* · **Done when** comparison report committed.
- [ ] **M5.8** · Tune decision threshold and PII settings from results; record changes in specs §9 revision history if targets move · *§9, VAL-8* · **Done when** thresholds updated with eval evidence.
- [ ] **M5.9** · CI eval smoke test on redacted samples with stubbed LLM · *NFR-9* · **Done when** runs in CI.

---

## M6 — Reconciliation

- [ ] **M6.1** · Order-history import with configurable column mapping; order numbers tokenized · *REC-1* · **Done when** synthetic CSV imports correctly.
- [ ] **M6.2** · Matcher: multi-invoice orders, credit notes, amount/date tolerance · *REC-2, REC-4* · **Done when** synthetic scenarios tested.
- [ ] **M6.3** · Reconciliation report API + eval metric (match rate) · *REC-3, §9* · **Done when** endpoint and metric tested.
- [ ] **M6.4** · Owner runs import + reconciliation on real export · *G4* · **Done when** match rate recorded in eval report.

---

## M7 — Review UI

- [ ] **M7.1** · Thymeleaf + HTMX layout, login page, nav · *G6* · **Done when** pages render behind login.
- [ ] **M7.2** · Review queue page · *REV-1* · **Done when** lists NEEDS_REVIEW with reasons.
- [ ] **M7.3** · Document page: redacted image + SVG evidence overlay + field form · *REV-2* · **Done when** evidence boxes align on fixtures.
- [ ] **M7.4** · Field corrections with audit trail + approve · *REV-3* · **Done when** corrections stored and decision updated.
- [ ] **M7.5** · Label export from reviewed documents · *REV-4* · **Done when** exported files validate against the M5.1 schema.

---

## M8 — Case study and release

- [ ] **M8.1** · Publish ≥ 20 irreversibly redacted samples with expected JSON (owner reviews every file) · *UC6, §10* · **Done when** samples committed after owner sign-off.
- [ ] **M8.2** · README as a case study: problem, architecture diagram, PII gate, eval results table, trade-offs, what failed · *§10* · **Done when** owner approves README.
- [ ] **M8.3** · Demo script/GIF of upload → review → reconciliation on redacted samples · *§10* · **Done when** recorded.
- [ ] **M8.4** · Final eval run against spec §9 targets; record results and any target changes · *§9* · **Done when** results table in README and specs revision history.
- [ ] **M8.5** · Tag `v1.0.0` (owner pushes) · — · **Done when** tag exists.

Phase 2 (Fly.io) is out of scope for this plan; see `deployment.md`.

---

## Follow-ups

_Work discovered during tasks that is out of scope for the current task. Add entries as
`- [ ] short description · found in <task ID>`._

---

## Decisions log

| Date | Decision | Task |
|------|----------|------|
| 2026-10-28 | Plan v0.1 created | — |
