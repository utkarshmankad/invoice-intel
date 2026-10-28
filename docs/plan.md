# Invoice Intel — Plan

> Status: v0.2 · Last updated: 2026-10-28
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

| Milestone | Goal | Depends on | Parallel |
|-----------|------|------------|----------|
| D | Owner data preparation (runs in parallel) | — | — |
| M0 | Buildable skeleton, local stack, CI build contract | — | — |
| M1 | Ingest and text extraction | M0 | — |
| M2 | PII detection, tokens, redaction, PII gate | M1 | {M2.2, M2.3, M2.4, M2.5, M2.8} |
| M3 | LLM extraction and pipeline wiring | M2 | {M3.5, M3.6, M3.7} |
| M4 | Validation rules and decisions | M3 | {M4.2, M4.3, M4.4, M4.5} · {M4.8, M4.9} |
| M5 | Eval harness and first baseline | M4, D3–D5 | {M5.2, M5.3, M5.4} |
| M6 | Order-history import and reconciliation | M4, D2 | — |
| M7 | Review UI | M4 | {M7.2, M7.3} |
| M8 | Case study, redacted samples, release | M5, M6, M7 | — |

**Parallel** lists groups of tasks the milestone runner may have in flight at the same time,
each on its own branch and PR (`runner.md` §3.3, §4). A group starts once every task listed
before it in the milestone is done. Tasks in no group run alone. Groups hold tasks that
shouldn't touch the same files; `[owner]` tasks and tasks that wire a stage job into the
pipeline are never in a group. Changing a group needs a PR.

Branches are per task, not per milestone: `<type>/<task-id>-<short-desc>` (e.g.
`feat/m1.3-upload-endpoint`). Every PR must pass the `CI gate` check before squash merge.

**Critical path:** M0 → M1 → M2 → M3 → M4 → M5. M6 and M7 can run in either order after M4.

---

## D — Owner data preparation (manual, owner only)

Claude does not touch real data (`.claude/rules/pii.md`). These tasks run alongside M0–M4.

- [x] **D1** · [owner] · Request the Amazon.in data export ("Request Your Data") · *R4, R5* · **Done when** request submitted (export can take days).
- [x] **D2** · [owner] · Save the order-history CSV to `data/order-history/`; write down its column names (not values) in `config/order-history-mapping.yml` · *REC-1, R4* · **Done when** mapping file lists every column. Done 2026-10-28 with `scripts/data/select-invoices.sh`: 2 CSVs, mapped per file: RETAIL (940 rows, 29 columns), which invoices reconcile against, and DIGITAL (183 rows, 77 columns). Review the suggested `target`s before M5 imports the order history.
- [x] **D3** · [owner] · Download ~20 invoices spread over 2013–2026, Amazon Retail + third-party, at least 2 credit notes, at least 3 pre-GST · *§4.3, R1* · **Done when** files are in `data/raw/`. Done 2026-10-28 (owner decision) with `scripts/data/select-invoices.sh`: 20 invoices, 3 credit notes, 5 third-party sellers, 7 CGST/SGST + 12 IGST, 4 multi-page, years 2016–2026. Gaps accepted for now: only 1 pre-GST invoice (needs 3) and none from 2013–2015, because the export has nothing older than 2016.
- [x] **D4** · [owner] · Add 3–5 phone photos or printed-and-scanned invoices · *R2* · **Done when** files are in `data/raw/`. Ticked 2026-10-28 (owner decision): the export had 1 scan/photo; the owner adds the remaining 2–4 photos by hand.
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
- [x] **M0.15** · `perf` module: Gatling Java DSL, `thresholds.conf`, health/auth simulation first (pipeline scenarios added in M4.10) · *NFR-3, NFR-13, ci.md §5* · **Done when** `performance` job runs and its assertions gate the PR.
- [x] **M0.16** · First fully green `CI gate` on a PR with all jobs running (none skipped except PR-only ones on `main`) · *NFR-13* · **Done when** green run linked in the Decisions log.

---

## M1 — Ingest and text

- [x] **M1.1** · Synthetic invoice generator (test fixtures): GST tax invoice, credit note, pre-GST invoice; fake names/addresses, valid-format fake GSTIN/PAN; outputs digital PDF · *testing.md, §13, ADR-14* · **Done when** generator produces 3 fixture types used by tests. The smoke fixture is committed in M1.3 with the upload endpoint (owner decision 2026-10-28: committing it earlier turns on the smoke upload checks before the endpoint exists).
- [x] **M1.2** · `storage`: `BlobStore` interface + S3 implementation; Testcontainers SeaweedFS test · *NFR-11, §9.1* · **Done when** put/get/delete tested.
- [x] **M1.3** · `ingest`: upload endpoint, SHA-256 dedupe, Tika MIME check, raw blob write, `document` row · *ING-1..4, §6.1* · **Done when** integration test covers new upload, duplicate, and rejected type, and the smoke fixture `app/src/integrationTest/resources/fixtures/smoke/gst-tax-invoice.pdf` (written with `SyntheticInvoiceGenerator.writePdf`) is committed so the smoke upload checks run. The document stays in `RECEIVED`; M1.4 adds the enqueue hook and M1.10 the Text job (owner decision 2026-10-28).
- [x] **M1.4** · `pipeline`: JobRunr setup on Postgres, document state machine, stage-run table, reprocess-from-stage · *NFR-5, §5.1* · **Done when** a no-op stage chain advances a document RECEIVED → TEXT_READY and can be re-run. Done 2026-10-28: `PipelineService`, `StageJob`, V3 `stage_run`; uploads start the chain with a placeholder Text stage, so CI smoke documents now reach `TEXT_READY` (§5.1).
- [x] **M1.5** · `text`: PDF text layer with word boxes (PDFBox) and has-text-layer check · *TXT-1, TXT-2* · **Done when** fixture PDF yields expected words + boxes. Done 2026-10-28: `PdfTextExtractor`, `TextLayer` records (architecture §6.2).
- [x] **M1.6** · `text`: page rendering at 300 DPI, one page at a time; page images to raw bucket · *TXT-2, §10.1* · **Done when** page PNGs stored and referenced in `blob_ref`. Done 2026-10-28: `PageRenderer`, `PageImageStore` (architecture §6.2).
- [x] **M1.7** · `text`: OpenCV preprocessing (grayscale, deskew, threshold, denoise) · *TXT-3* · **Done when** skewed synthetic scan is deskewed within ±1°. Done 2026-10-28: `ImagePreprocessor` (architecture §6.2).
- [x] **M1.8** · `text`: Tess4J OCR to the common `TextLayer` format · *TXT-1, TXT-2* · **Done when** rasterised fixture OCRs with ≥ 95% word match. Done 2026-10-28: `TesseractOcr` (architecture §6.2).
- [x] **M1.9** · `text`: rule-based document classifier · *TXT-4, ADR-09* · **Done when** all fixture types classified correctly. Done 2026-10-28: `DocumentClassifier`, `DocumentClass` in `common` (architecture §6.2).
- [x] **M1.10** · Text stage job wired into the pipeline · *§5.2* · **Done when** uploaded fixture reaches TEXT_READY with a stored text layer. Also remove `RECEIVED` from `SMOKE_EXPECT_STATUSES` in `ci.yml` (accepted since M1.3, owner decision 2026-10-28). Done 2026-10-28: `TextStageHandler`, `DocumentTextService`, V4 `text_layer` (architecture §6.2, §7).

---

## M2 — PII

- [x] **M2.1** · Regex detectors: phone, email, PIN, PAN, order/invoice numbers · *PII-2(a)* · **Done when** positive/negative tests pass. Done 2026-10-28: `RegexDetector`, `PiiSpan`, `PageText` (architecture §6.3).
- [x] **M2.2** · Checksum validators: Verhoeff (Aadhaar), GSTIN mod-36; GSTIN/Aadhaar detectors use them · *PII-2(a), VAL-4* · **Done when** property tests pass. Done 2026-10-28: `Verhoeff`, `GstinChecksum` (common), `ChecksumDetector` (pii) (architecture §6.3).
- [x] **M2.3** · Seller-block tagging + `AddressBlockDetector` (anchors, buyer name, street, state extraction) · *§6.3, §6.1* · **Done when** fixtures: buyer name/street detected, seller untouched, state kept. Done 2026-10-28: `SellerBlockTagger`, `AddressBlockDetector`, `DocumentContext.sellerRanges`, `TextRange` (architecture §6.3).
- [x] **M2.4** · `SpanMerger` (overlaps, word expansion, type priority) · *§6.3* · **Done when** overlap cases tested. Done 2026-10-28: `SpanMerger` (architecture §6.3).
- [x] **M2.5** · Token generator (HMAC, deterministic) + Tink vault (AEAD) + keys from env · *PII-3, PII-7, ADR-06* · **Done when** same value → same token across docs; vault round-trip tested; no key in config. Done 2026-10-28: `TokenGenerator`, `PiiVault`, `VaultEntry`, `PiiKeys` (`TOKEN_KEYSET`, `VAULT_KEYSET`) (architecture §6.3).
- [x] **M2.6** · Text redactor → `RedactedContent` (package-private constructor) · *PII-1, §6.4* · **Done when** redacted text of fixtures contains tokens and no fixture PII. Done 2026-10-28: `TextRedactor`, `RedactedContent` (architecture §6.3, §6.4).
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
- [x] architecture §4.1 lists no module allowed to depend on `ingest`, so the ArchUnit rule forbids all access; M1.3's upload endpoint in `app` (or `pipeline`) will need `ingest` added to §4.1 first · found in M0.4 · resolved in M1.3: `ingest ◄── app` (owner decision 2026-10-28)
- [ ] specs.md NFR-11 and §8 Phase 1 still say "MinIO locally"; local S3 is SeaweedFS since ADR-11 (needs owner approval to edit specs) · found in M0.5
- [x] `ci.yml` smoke step name still says "MinIO"; rename when `compose.ci.yaml` lands · found in M0.5
- [ ] Two integration-test contexts (`RANDOM_PORT` web vs. mock) each start a Postgres container; share one context or a singleton container once more ITs exist · found in M0.6
- [ ] `.env.example` must list `APP_ADMIN_USER` and `APP_ADMIN_PASSWORD` (required since M0.7); the runner can't read `.env*` files, so the owner checks it · found in M0.7
- [ ] `PiiLogMasker` (app) holds a stub email/phone pattern list; replace it with the M2.1 detector regex set (PIN, PAN, GSTIN, Aadhaar, order numbers) once those exist, without a dependency from `app` internals on `pii` internals · found in M0.8
- [ ] §4.1 "no module except `pii` may read the vault tables or vault key" has no ArchUnit rule yet; add it with the vault types (M2.5) or the PII-gate rules (M2.11) · found in M0.4
- [ ] The CI `llm-stub` (WireMock) answers every POST with a placeholder body and the app gets no LLM base URL or key yet; add provider-shaped stub mappings and point the app at `http://llm-stub:8080` with a throwaway key once the `llm.*` config keys exist · found in M0.14
- [ ] Until M1.10 the placeholder Text stage moves uploads to `TEXT_READY` without a text layer; after M1.10, reprocess those documents from `TEXT` (or clear the local database) · found in M1.4
- [ ] The JobRunr dashboard is off: it serves on its own port (8000) without the app's login. Decide whether to enable it for `local` only, bound to `127.0.0.1` with its own credentials from `.env` · found in M1.4
- [ ] Stage job concurrency uses JobRunr's default worker count; size it from `invoice-intel.pipeline.*` (architecture §5.2, §10.1) when render/OCR stages arrive (M1.6–M1.8) · found in M1.4
- [ ] `ChecksumDetector` reports Aadhaar numbers only when Verhoeff passes; an OCR-damaged Aadhaar after an "Aadhaar"/"UID" label, masked Aadhaar (`XXXX XXXX 1234`), the 16-digit VID and GSTINs/Aadhaars split across OCR words are not found. Check on the eval set (M5) whether a label rule like `regex:gstin-label` is needed · found in M2.2
- [x] Image uploads (JPEG/PNG) have no page image yet: `PageImageStore` renders PDFs only. The Text job (M1.10) should store the image itself as page 1 (normalised to PNG, "images used as-is", §6.2) · found in M1.6 · resolved in M1.10
- [ ] OpenCV loads its bundled native library (`OpenCvLoader`) only in tests so far. M1.10 added `--enable-native-access=ALL-UNNAMED` to the image's `java` command and loads OpenCV only on the first OCR page, but no CI check OCRs inside the container (the smoke fixture is a digital PDF). Add a scanned smoke fixture or a container check that the temp dir is writable and the native library loads · found in M1.7, narrowed in M1.10
- [ ] `LogContext.close()` removes its keys even when an outer context set them, so `PageImageStore`'s nested `LogContext.forDocument` drops `documentId` from the Text stage's later log lines. Make `LogContext` restore the previous MDC values (common) · found in M1.10
- [x] `TesseractOcr` takes the tessdata folder as a constructor argument. The Text job (M1.10) needs a config key for it (e.g. `invoice-intel.text.tessdata-path`, default `/usr/share/tesseract-ocr/5/tessdata` in the container, Homebrew path for `local`) in architecture §10 and deployment.md · found in M1.8 · resolved in M1.10: `invoice-intel.text.tessdata-path` (`TESSDATA_PATH`)
- [x] The Text job (M1.10) should store `DocumentClassifier`'s result in `document.document_class` (column from V1, architecture §5.2, §7) · found in M1.9 · resolved in M1.10
- [ ] `scripts/ci/perf-summary.sh` looks for `js/stats.json`, which Gatling 3.16 reports no longer contain, so the job summary always says "No Gatling stats found"; read `simulation.log` or the HTML report instead (the assertions still gate) · found in M0.15
- [ ] A merged `PiiSpan.detector` joins every contributing rule name with `+` (e.g. `regex:landline+regex:mobile`), so it can be longer than one rule name; give `pii_span.detector` a `TEXT` (not a short `VARCHAR`) column when its migration is written · found in M2.4
- [x] `SpanMerger` (M2.4) or the PII stage (M2.10) must drop spans with `DocumentContext.isSellerData(span)`; `RegexDetector` still reports seller PAN/PIN/TIN, and only `AddressBlockDetector` filters its own spans · found in M2.3 · resolved in M2.6: `TextRedactor` drops them before replacing words and returns only the kept spans
- [ ] The signature line "For <seller name>:" at the bottom of invoices is not tagged as seller data; tag it (or treat the seller name as known seller text) before NER (M2.8) flags it as a PERSON · found in M2.3
- [ ] Layout thresholds (`PageLayout` row/column/gap factors) are tuned on synthetic PDFs and hand-made layouts only; check them on OCR pages and on the owner's redacted eval runs (M4) · found in M2.3
- [ ] `pii_vault` has no Flyway migration or store yet; M2.5 only seals and opens `VaultEntry`s in memory. Add the migration (§7 shape) and a `pii`-only store when the PII stage (M2.10) first writes entries · found in M2.5
- [ ] Token normalization folds ASCII case and spacing only. Decide (owner, FindSecBugs `IMPROPER_UNICODE`) whether NFKC/full-width folding is wanted for OCR output, and whether digit-only types (phone, PIN, Aadhaar) should drop separators before hashing · found in M2.5
- [ ] Local and CI runs of the PII stage need `TOKEN_KEYSET`/`VAULT_KEYSET`; document generating throwaway keysets for `.env` (Tinkey) when M2.10 wires `PiiKeys` into Spring · found in M2.5
- [ ] Spec §6.1 names buyer PAN/Aadhaar tokens `ID_n`, but `TokenGenerator` (architecture §6.3) prefixes the `PiiType` name, so they come out as `PAN_…`/`AADHAAR_…`. Owner to decide which is right; aligning either side is a one-line change · found in M2.6
- [ ] Architecture §6.3 says redact mode replaces every span with `[REDACTED:TYPE]`, while spec §6.1 requires a consistent token for order/invoice numbers in redact mode too. `TextRedactor` follows the spec (token, no vault entry); update the §6.3 "Redact mode" bullet once the owner confirms · found in M2.6
- [ ] The leak check (PII-6) reads known values from the vault, but PIN codes (removed in both modes) and every value in redact mode get no vault entry. Decide how the leak check gets those values for a document (e.g. an in-memory list from the PII stage run, never persisted in clear) before it is built · found in M2.6
- [ ] `RedactedContent` has page text and word boxes but no page images yet (architecture §6.4 shape); the image redactor (M2.7) adds them. The `TEXT` strategy's page/line markers (§6.5) are not built here: page text is words joined by single spaces in text-layer order, so M3 builds line markers from the word boxes (e.g. the `PageLayout` row rule) · found in M2.6
- [ ] `RedactedContent.Page.words()` exposes `dev.invoiceintel.text.Word`; the M2.11 ArchUnit rules must allow `extract` to use `Word` while still forbidding `TextLayer` and raw blob keys (§6.4) · found in M2.6

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
| 2026-10-28 | First fully green `CI gate` with all 14 jobs running, none skipped: [run 40635998717](https://github.com/utkarshmankad/invoice-intel/actions/runs/40635998717) on [PR #13](https://github.com/utkarshmankad/invoice-intel/pull/13). Smoke fixture checks stay dormant until M1.1/M1.3 by design | M0.16 |
| 2026-10-28 | Plan v0.2: Parallel column; the runner may run grouped tasks in parallel (`runner.md` v0.2) | — |
