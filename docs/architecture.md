# Invoice Intel — Architecture

> Status: Draft v0.1 · Last updated: 2026-10-28
>
> This document defines **how** the system is built. Requirements and their IDs
> (`PII-1`, `VAL-5`, …) come from `specs.md`; every design element below points back to them.
> Cloud deployment details live in `deployment.md`.

---

## 1. Design principles

1. **PII safety is enforced by types, not by discipline.** The code that calls remote LLMs
   only accepts objects that the PII module alone can create (§6.4). (PII-1)
2. **Every stage is a durable, idempotent job.** A document can be re-run from any stage
   without side effects. (NFR-5)
3. **Deterministic first, LLM second.** Rules handle what rules can (classification hints,
   PII patterns, arithmetic, GST logic); the LLM handles layout variety.
4. **Everything is measured.** Each LLM call, stage run and validation result is stored
   so the eval harness and dashboards read from the same data. (EXT-4, EVAL-4)
5. **Local and cloud differ only in configuration.** (NFR-10 to NFR-12)
6. **Modular monolith.** One deployable, clear module boundaries checked by tests.
   Microservices would add cost and no value at this scale.

---

## 2. System context

```
                       ┌──────────────────────────── Invoice Intel (one Spring Boot app) ───────────────────────────┐
 Owner (browser) ────► │  REST API + Review UI ──► Pipeline (JobRunr jobs) ──► Postgres                              │
                       │                               │                        ▲                                   │
 Eval CLI ───────────► │                               ├──► Object storage (SeaweedFS / R2)                         │
                       │                               │                                                            │
                       │                               └──► Remote LLM APIs  (redacted content only)  ───────────────┼──► LLM providers
                       └─────────────────────────────────────────────────────────────────────────────────────────────┘
 Amazon.in order-history CSV ──► imported once per export (REC-1)
```

External dependencies: Postgres, an S3-compatible store, one or more LLM providers.
Nothing else.

---

## 3. Technology stack

| Concern | Choice | Notes |
|---------|--------|-------|
| Language / runtime | Java 25 (LTS) | Virtual threads for I/O-bound stages (LLM, storage). |
| Framework | Spring Boot 4.x (4.1.2 chosen in M0.1) | Boot 4 + Java 25 confirmed compatible with the key libraries on 2026-10-28; versions in ADR-01. |
| Build | Gradle, Kotlin DSL, multi-module, version catalog (`gradle/libs.versions.toml`) | Exact versions pinned there, not in this doc. |
| LLM client | Spring AI (`ChatClient`, structured output, multimodal messages) | Providers configured per profile (EXT-3, NFR-8). |
| File type detection | Apache Tika (core only) | ING-3 |
| PDF text, rendering, rebuilding | Apache PDFBox 3.x | TXT-2, PII-4 |
| OCR | Tess4J (Tesseract + Leptonica native libs) | TXT-1, TXT-2 |
| Image preprocessing | OpenCV Java bindings (`org.openpnp:opencv`) | TXT-3 |
| PII NER | DJL + ONNX Runtime engine + HuggingFace tokenizers, small NER model (distilled BERT class) | PII-2(b) |
| Encryption | Google Tink (AEAD for vault, deterministic MAC for tokens) | PII-3, PII-7 |
| Jobs | JobRunr, Postgres storage | NFR-5 |
| Database | PostgreSQL 16+, Flyway, Spring Data JPA (+ JSONB via Hibernate JSON mapping) | |
| Object storage | AWS SDK v2 S3 client | SeaweedFS S3 gateway locally (ADR-11), R2 in Phase 2 (NFR-11) |
| Web / API | Spring Web MVC, springdoc-openapi, Bean Validation | G6 |
| Review UI | Thymeleaf + HTMX | REV-1 to REV-4 |
| Security | Spring Security, form login, single admin user (v1) | NFR-2 |
| Observability | Micrometer, Actuator, structured JSON logs (Logback), OpenTelemetry optional | NFR-6 |
| CLI | picocli (eval harness) | EVAL-1 |
| Tests | JUnit 5, AssertJ, Testcontainers (Postgres, SeaweedFS), WireMock (LLM stubs), ArchUnit | NFR-9 |
| Quality gates | JaCoCo (≥ 85% merged line coverage), Spotless, SpotBugs + FindSecBugs, CodeQL, Trivy, gitleaks, PIT (nightly) | NFR-13, `ci.md` |
| Performance tests | Gatling (Java DSL) in a `perf` module | NFR-3, NFR-13 |
| Container | Multi-stage `Dockerfile` on Eclipse Temurin + `tesseract-ocr` packages | ADR-07 |
| Local stack | Docker Compose | NFR-7 |

---

## 4. Module structure

```
invoice-intel/
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── buildSrc/   convention plugins: java-conventions (toolchain, tests, JaCoCo, Spotless, SpotBugs, locking), mutation-testing, spring-boot-app
├── common/      domain model, IDs, events, money/date utils, status enums
├── storage/     BlobStore interface + S3 implementation
├── ingest/      upload handling, hashing, dedupe, Tika type detection
├── text/        PDF text layer, rendering, OpenCV preprocessing, Tess4J OCR, rule-based classifier
├── pii/         detectors, span merger, tokenizer, vault, text/PDF/image redactors, leak checker
├── extract/     LLM gateway, prompts, extraction strategies, cost accounting
├── validate/    rule engine, GST/arithmetic rules, confidence scorer
├── reconcile/   order-history import, matcher, reconciliation report
├── pipeline/    JobRunr job definitions, stage orchestration, state machine
├── app/         Spring Boot main, REST controllers, review UI, security, config profiles
├── eval/        picocli harness, datasets, metrics, report writers
├── perf/        Gatling simulations and thresholds (CI performance gate)
└── docs/
```

Base package: `dev.invoiceintel.<module>`.

### 4.1 Dependency rules (enforced with ArchUnit)

```
common  ◄── everyone
storage ◄── ingest, text, pii, pipeline, app
ingest  ◄── app
text    ◄── pii, pipeline
pii     ◄── extract, pipeline, app (read-only views)
extract ◄── pipeline, eval
validate, reconcile ◄── pipeline, app, eval
pipeline ◄── app, eval
```

- `extract` depends on `pii` **only** for the `RedactedContent` type (§6.4).
- No module except `pii` may read the vault tables or vault key.
- No module except `extract` may create Spring AI clients.
- `app` and `eval` are the only modules with a `main`.
- `perf` has no project dependencies; it talks to the running app over HTTP only.

---

## 5. Pipeline

### 5.1 Document state machine

```
RECEIVED ─► TEXT_READY ─► PII_CLEAN ─► EXTRACTED ─► VALIDATED ─┬─► AUTO_APPROVED ─┐
    │            │             │            │                  ├─► NEEDS_REVIEW ──┼─► (REVIEWED) ─► RECONCILED
    └────────────┴─────────────┴────────────┴──────────────────┴─► FAILED         ┘
```

- Each arrow is one JobRunr job. A job reads its inputs from Postgres/storage, writes its
  outputs, then advances the status in the same transaction as its outputs.
- Re-running a stage replaces that stage's outputs (keyed by `document_id` + `stage` +
  `run_no`) and invalidates downstream stages.
- `FAILED` always records `stage`, `reason_code` and a PII-free message.
- Reconciliation also runs as a batch job over all documents after an order-history import.

Implementation (M1.4), in `pipeline`:

- `DocumentState` is the status enum of the diagram (declared in pipeline order). `Stage` is the
  per-document chain `TEXT` (RECEIVED → TEXT_READY), `PII` (→ PII_CLEAN), `EXTRACT`
  (→ EXTRACTED), `VALIDATE` (→ VALIDATED); the decision branch and reconciliation are added by
  their tasks. The leak check runs inside `EXTRACT`.
- A stage is a `StageHandler` bean. Every handler bean joins the chain; a stage with no handler
  ends it, so a document stops in the last state that has one. Until M1.10 the Text handler is a
  `PlaceholderStageHandler` that writes nothing.
- `PipelineService.start` enqueues a `StageJob` (JobRunr `JobRequest` holding the document ID and
  stage only, since JobRunr stores it as JSON and shows it on its dashboard; PII-8).
  `StageJobHandler` runs it in three transactions, each first locking the document row
  (`FOR NO KEY UPDATE`, so stage outputs' foreign keys don't wait on it): (1) if the document is
  in the stage's input state and no run of the stage is `RUNNING`, insert the next `stage_run`
  as `RUNNING`, otherwise skip; (2) call the handler, then mark the run `SUCCEEDED` and move the
  document to the output state, and enqueue the next stage after commit; (3) on an exception,
  mark the run `FAILED` and the document `FAILED` with `failed_stage` and `failure_code`
  (`StageFailedException.code()`, or `UNEXPECTED_ERROR`; only the exception type is logged).
  Stage failures aren't retried by JobRunr; JobRunr's retries only cover exceptions outside the
  handler, such as a lost database connection.
- `PipelineService.reprocess(id, fromStage)` (NFR-5): allowed once the document has reached the
  stage's input state (for a `FAILED` document, the input state of the stage that failed) and the
  stage has a handler. In one transaction it marks the runs of that stage and every later stage
  `SUPERSEDED`, moves the document to the stage's input state and clears any failure; then it
  enqueues the stage. A run that finishes after being superseded rolls back its outputs. The
  HTTP endpoint (§8) comes with M4.8.
- The upload controller starts the pipeline for new (non-duplicate) documents. Ingest can't call
  the pipeline itself (§4.1). If enqueueing fails after the upload, the document stays in
  `RECEIVED` until it is reprocessed from `TEXT`.

### 5.2 Stage summary

| Stage | Module | Input | Output | Spec |
|-------|--------|-------|--------|------|
| Ingest | ingest | uploaded bytes | `document`, raw blob | ING-1..4 |
| Text | text | raw blob | `text_layer` (words + boxes per page), page images, `document_class` hint | TXT-1..4 |
| PII | pii | text layer, page images | `pii_span` rows, vault entries, `RedactedContent` (text + images), redacted PDF | PII-1..5, PII-7 |
| Extract | extract | `RedactedContent` | `extraction` + `llm_call` rows | EXT-1..5 |
| Leak check | pii | extraction output | pass/fail | PII-6 |
| Validate | validate | extraction | `validation_result` rows, decision | VAL-1..8 |
| Reconcile | reconcile | extraction + order history | `reconciliation_match` | REC-1..4 |

Concurrency: one JobRunr worker pool; LLM-bound jobs run on virtual threads with a
per-provider rate limiter (semaphore sized from config).

---

## 6. Component design

### 6.1 Ingest (`ingest`)

- `POST /api/documents` accepts multipart files (one or many).
- SHA-256 of the bytes is the dedupe key (ING-2); a unique index on `document.sha256`
  makes concurrent duplicate uploads safe.
- Tika detects the MIME type from content (ING-3). Allowed: `application/pdf`,
  `image/jpeg`, `image/png`. Others are rejected with `415`.
- Raw bytes go to the **raw** bucket under `raw/{documentId}/original.{ext}` (ING-4).
- Creates the `document` row in `RECEIVED` and enqueues the Text job.
- Wiring (M1.3, owner decision 2026-10-28): `DocumentController` in `app` takes the multipart
  parts named `files` and calls `dev.invoiceintel.ingest.IngestService`, which has no web code.
  Types are checked for every file before anything is stored, so one unsupported file rejects
  the whole batch (`415` problem detail with `fileIndex` and `detectedType`). The response is
  a JSON array of `{id, duplicate}` in request order; `duplicate: true` means the bytes were
  uploaded before (ING-2). The original is stored first, then `document` and its ORIGINAL
  `blob_ref` row are inserted in one statement (`ON CONFLICT (sha256) DO NOTHING`); losing a
  concurrent race deletes the new blob and returns the winner's ID. Multipart limits: 20 MB
  per file, 100 MB per request (`413` above). M1.3 stops at `RECEIVED`; M1.4 adds the enqueue
  hook (no-op chain, done: `DocumentController` calls `PipelineService.start` for each new
  document, §5.1) and M1.10 wires the real Text job.
- Persistence uses Spring's `JdbcClient` until a module needs JPA entities.
- `GET /api/documents?status=` returns the newest 100 documents as `{id, status}`, optionally
  filtered by status (UC1; paging comes with the review UI). The Gatling simulation calls it.
- `GET /api/documents/{id}` returns `{id, status}` from `IngestService.find` (M1.3, so the smoke
  test can poll it); unknown IDs get a `404` problem detail. UC2's decision and extraction
  fields are added when those stages exist.
- `SPRING_ENDPOINT` (FindSecBugs inventory marker, not a defect) is suppressed per controller
  with `@SuppressFBWarnings` and a justification (owner decision 2026-10-28).

### 6.2 Text (`text`)

**Digital PDFs:** PDFBox `PDFTextStripper` subclass collects `TextPosition`s and groups them
into words with bounding boxes. A page counts as "has text layer" if it yields more than a
minimum number of non-whitespace characters (configurable) (TXT-1).

**Scanned PDFs and images:**
1. Render each PDF page at 300 DPI with PDFBox (images used as-is).
2. OpenCV: grayscale → deskew (min-area-rect angle) → adaptive threshold → light denoise (TXT-3).
3. Tess4J with `eng` (+ `hin` optional) returns words with boxes and confidences (TXT-2).

**Common output** (`TextLayer`, stored as JSONB):
```
pages[]: { pageNo, width, height, source: PDF_TEXT|OCR,
           words[]: { text, x, y, w, h, confidence } }
```
Implementation (M1.5): `TextLayer`, `TextPage`, `Word` and `TextSource` are records/enums in
`dev.invoiceintel.text`. `pageNo` is 1-based; boxes are top-left `x`, `y` plus `w`, `h` in the
page's unit, which for `PDF_TEXT` is PDF points (1/72 in) from the top left of the crop box
(OCR pages use rendered-image pixels, M1.8). PDF words have `confidence` 1. `PdfTextExtractor`
groups PDFBox `TextPosition`s (sorted by position) into words at whitespace and text-run ends;
`hasTextLayer(page)` is true from `minTextChars` non-whitespace characters on (default 20;
moves to `invoice-intel.text.*` when the Text job is wired, M1.10). Unreadable PDFs raise
`TextExtractionException` with a content-free message.

Page images (PNG) are stored under `raw/{documentId}/pages/{n}.png` for the vision
strategy and review UI. They contain PII and live in the raw bucket only.
Implementation (M1.6): `PageRenderer` renders each PDF page with PDFBox at the configured DPI
(default 300, `invoice-intel.text.dpi`) as RGB and encodes it to PNG, one page at a time: each
PNG goes to a callback before the next page is rendered, so one page bitmap is on the heap per
call (§10.1). `PageImageStore` writes each PNG to the raw bucket, then upserts its `PAGE_IMAGE`
`blob_ref` row (`ON CONFLICT (bucket, key)`), so re-running keeps one row per page. Capping
concurrent render/OCR jobs with `max-concurrent-page-jobs` comes with the Text job (M1.10).
Implementation (M1.7): `ImagePreprocessor.preprocess` (OpenCV via `org.openpnp:opencv` 4.9.0-0,
native library loaded once by `OpenCvLoader`) decodes the image as grayscale, deskews it, applies a
Gaussian adaptive threshold (block 31, offset 15) and a 3×3 median denoise, and returns a
`PreprocessedImage` (binarized PNG + corrected skew). The skew is the median long-side angle of the
minimum-area rectangles around text lines (Otsu ink mask, words merged into lines by a horizontal
dilation 1/40 of the page wide; blobs count from 1/10 of the page width and a 5:1 aspect). One
rectangle around all ink was off by about 1° on ragged invoice layouts. Skews over 15° are treated
as layout and left alone. Undecodable images raise `TextExtractionException`.
Implementation (M1.8): `TesseractOcr.ocr(pageNo, image)` runs Tess4J 5.20.0 (system Tesseract
through JNA) at word level and returns a `TextPage` with `source: OCR`, the image size and pixel
boxes, and confidences scaled to 0–1. It creates a `Tesseract` instance per call because instances
aren't thread-safe. Languages join with `+` (`invoice-intel.text.ocr-languages`). The tessdata
folder is a constructor argument; a config key for it comes with the Text job (M1.10). Tests point JNA at `/opt/homebrew/lib` when it exists.

**Classification (TXT-4)** is rule-based at this stage, because no LLM may see the
document before PII removal: keyword and pattern scoring ("Tax Invoice", "Credit Note",
"Bill of Supply", GSTIN present, invoice date vs 2017-07-01). The extraction LLM later
returns its own `documentClass`; a mismatch lowers confidence.
Implementation (M1.9): `DocumentClass` (specs §4.1) is an enum in `common`, because `text`,
`extract` and `validate` all need it and only `common` is visible to all of them (§4.1).
`DocumentClassifier.classify(TextLayer)` joins all words, upper-cases them and scores each class:
`TAX_INVOICE` from "Tax Invoice" (3), a GSTIN-shaped token (2), CGST/SGST/UTGST/IGST (1) and a
document date on or after 2017-07-01 (1), minus 4 for a date before it; `CREDIT_NOTE` from
"Credit Note" (4) and "Original Invoice" (1); `BILL_OF_SUPPLY` from "Bill of Supply" (4);
`PRE_GST_INVOICE`, only when "Invoice" appears, from a date before 2017-07-01 (3), VAT/CST/TIN
without GST markers (2) and "Retail Invoice" (1). The document date is the one labelled
"Invoice Date" (`yyyy-MM-dd`, `dd.MM.yyyy`, `dd/MM/yyyy`, `dd-MM-yyyy`, `dd Mon yyyy`), else the
latest date in the text. The highest score of at least 3 wins (ties: credit note, bill of supply,
pre-GST, tax invoice); otherwise `OTHER`. Phrases match with any spacing so OCR'd text classifies
the same; tests cover every fixture type through both the PDF text layer and OCR.

### 6.3 PII (`pii`)

#### Detection (PII-2)

```java
public interface PiiDetector {
    List<PiiSpan> detect(TextLayer layer, DocumentContext ctx);
}
```

| Layer | Detector | Finds |
|-------|----------|-------|
| a. Rules | `RegexDetector` set + checksum validators | phone, email, PAN, Aadhaar (Verhoeff), GSTIN (mod-36 checksum), PIN code, order/invoice numbers |
| a. Layout | `AddressBlockDetector` | Lines following anchors such as "Billing Address", "Shipping Address", "Ship To", until the next known label or a vertical gap. Name = first line; state = matched against a list of Indian states/UTs. |
| b. NER | `NerDetector` (DJL + ONNX) | person names and locations missed by rules |
| c. Local LLM | `LocalLlmDetector` | optional, **off by default**; only allowed with a local endpoint |

Seller-side values (seller name, seller GSTIN/PAN/address) are excluded by position: the
"Sold By" block and its GSTIN/PAN lines are tagged as seller data before detection runs.

`SpanMerger` unions overlapping spans, keeps the most specific type, and expands spans to
whole words. Bias is toward recall (spec §6.3).

#### Tokens and vault (PII-3, PII-7)

- **Token generation:** `token = TYPE + "_" + base32(HMAC-SHA256(tokenKey, normalized(value)))[0..8]`.
  The same real value always gives the same token across all documents, which keeps order
  numbers joinable for reconciliation without storing them in clear text.
- **Vault:** `pii_vault(token, type, ciphertext)`; `ciphertext` = Tink AEAD with
  associated data = token. Keys come from `VAULT_KEYSET` (env/secret), never from the DB.
- **Order-history import** passes order numbers through the same tokenizer, so
  reconciliation joins on tokens.
- Redact mode: same spans, but replaced by fixed `[REDACTED:TYPE]` text and no vault entry.

#### Redaction outputs

| Output | Method | Spec |
|--------|--------|------|
| Redacted text | Rebuild page text from words, substituting tokens for spans. Word boxes are kept so evidence still maps to positions. | PII-1 |
| Redacted page images | Fill span boxes (padded) with solid rectangles on the page PNGs. | PII-5 |
| Redacted PDF | Build a **new** PDF from the redacted page images. The original content stream is not reused, so no hidden text survives. Text layer is dropped by design. | PII-4 |

The rebuilt image-only PDF is the simplest way to *guarantee* PII-4. Selective removal of
text operators from the original content stream is a possible later improvement (ADR-05).

#### Leak check (PII-6)

After extraction, `LeakChecker` decrypts the vault values for that document in memory and
searches the LLM's raw response (normalized: case, whitespace, digits-only variants). Any hit
→ document `FAILED` with `reason_code = PII_LEAK`, response discarded, alert metric incremented.

### 6.4 The PII gate (type-level enforcement of PII-1)

```java
// in module pii
public final class RedactedContent {
    private final DocumentId documentId;
    private final String text;
    private final List<PageImage> pageImages;
    RedactedContent(...) { ... }          // package-private: only pii can construct
}

// in module extract
public interface LlmGateway {
    <T> LlmResult<T> extract(RedactedContent content, ExtractionRequest<T> request);
}
```

- `LlmGateway` is the only path to a remote model. It has no overload that takes a
  `String`, `byte[]` or `TextLayer`.
- An ArchUnit test fails the build if any class outside `extract` uses Spring AI types,
  or if `extract` references `TextLayer` or raw blob keys.
- `RedactedContent` is only created after the PII stage finished successfully, so a
  document that failed PII processing has no `RedactedContent` and cannot reach a model.

### 6.5 Extract (`extract`)

**Strategies (EXT-2)**, chosen per run by config or eval flag:

| Strategy | Model input | When useful |
|----------|-------------|-------------|
| `TEXT` | Redacted text with page/line markers | Digital PDFs; cheapest |
| `VISION` | Redacted page images | Scans, photos, table-heavy layouts |
| `HYBRID` | Both | Hard documents; eval comparison |

**Structured output (EXT-1):** Java records mirror the spec §5 schema
(`InvoiceExtraction`, `LineItem`, `TaxLine`, `Field<T>{value, confidence, evidence}`).
Spring AI structured output generates the JSON schema from the records; responses are
parsed and then checked with Bean Validation. A parse failure gets one repair retry with the
error message, then the stage fails.

**Prompts (EXT-5):** `extract/src/main/resources/prompts/{name}/v{n}.st` (StringTemplate).
The prompt name + version is stored on every `llm_call`. Changing a prompt means adding a
new version file, never editing an old one.

**Model registry (EXT-3, NFR-8):** `invoice-intel.llm.models` in config lists each model's
provider, model ID, capabilities (text/vision) and price per million input/output tokens.
Cost per call = tokens × configured price (EXT-4). Prices are config, so they can be
updated without code changes.

**Evidence:** the prompt asks the model to cite the line markers it used; the extractor maps
them back to word boxes from the text layer for review highlighting.

### 6.6 Validate (`validate`)

```java
public interface ValidationRule {
    String id();                                  // e.g. "VAL-2"
    RuleResult check(InvoiceExtraction x, ValidationContext ctx);   // PASS | WARN | FAIL + message
}
```

| Rule | Logic |
|------|-------|
| VAL-1 | Σ `netAmount` vs `subtotal`, tolerance ±₹1 |
| VAL-2 | per tax line: `rate × base` vs `amount`, ±₹1 |
| VAL-3 | `subtotal + totalTax + charges` vs `grandTotal`, ±₹1 |
| VAL-4 | GSTIN regex + mod-36 checksum; first two digits vs seller state code |
| VAL-5 | seller state == place of supply ⇒ CGST+SGST; else IGST |
| VAL-6 | date < 2017-07-01 ⇒ no GST tax types; else no VAT/CST |
| VAL-7 | parse `amountInWords` (Indian numbering: lakh, crore) vs `grandTotal` |

All amounts use `BigDecimal` with `HALF_UP` at 2 decimals.

**Confidence scoring (VAL-8):**
```
docScore = min(field confidences of required fields)
         × (any FAIL ? 0 : 1)
         × (0.9 ^ number of WARNs)
         × (classifier and LLM documentClass agree ? 1 : 0.8)

AUTO_APPROVED  if docScore ≥ approveThreshold (config, start at 0.85) and no FAIL
FAILED         if extraction missing or PII_LEAK
NEEDS_REVIEW   otherwise
```
Thresholds are tuned from eval results to meet the false auto-approval target (≤ 2%, spec §9).

### 6.7 Reconcile (`reconcile`)

- **Import (REC-1):** `POST /api/order-history` takes the Amazon CSV. Column mapping lives
  in `config/order-history-mapping.yml`. Rows are stored with order numbers tokenized and
  product names kept.
- **Matching (REC-2, REC-4):**
  1. Group invoices by `orderNumber` token; group order rows by the same token.
  2. For each order: Σ invoice `grandTotal` (minus credit notes) vs Σ order totals (±₹1),
     and invoice date within N days of order date.
  3. Unmatched sides become `INVOICE_WITHOUT_ORDER` or `ORDER_WITHOUT_INVOICE`.
- **Report (REC-3):** `GET /api/reconciliation` returns counts and per-order rows; the eval
  harness reads the same table.

### 6.8 Review UI (`app`)

- Server-rendered with Thymeleaf; HTMX for partial updates (field save, approve).
- **Queue page** (REV-1): documents in `NEEDS_REVIEW` with reason codes, sortable.
- **Document page** (REV-2): redacted page image on the left with evidence boxes as an
  SVG overlay; field form on the right. The UI never shows unredacted images.
- **Corrections** (REV-3): each edit writes a `field_correction` row (old, new, user, time);
  the current value is computed as latest correction over extraction.
- **Export** (REV-4): `GET /api/labels/export` writes reviewed documents in the eval label
  format (§9.2).

### 6.9 Eval (`eval`)

Command examples:
```bash
./gradlew :eval:run --args="run --dataset data/labels --strategy TEXT --model claude-text-default"
./gradlew :eval:run --args="compare runs/2026-11-02-a runs/2026-11-02-b"
```

- Runs the pipeline in-process against a dataset (Testcontainers or the local Compose stack).
- Metrics (EVAL-2): per-field exact match (normalized), line-item F1 (match on description
  similarity + amount), straight-through rate, false auto-approval rate, PII recall/precision
  (span overlap ≥ 50%), cost per document, p50/p95 latency.
- Writes `eval-reports/{runId}/report.md`, `fields.csv`, `config.json` (model IDs,
  prompt versions, git SHA) (EVAL-4). Reports contain no PII.

---

## 7. Data model

All tables have `id UUID`, `created_at`, `updated_at`. JSONB where the shape is owned by code.

| Table | Key columns | Notes |
|-------|-------------|-------|
| `document` | `sha256` (unique), `mime_type`, `status`, `document_class`, `failed_stage`, `failure_code` | One per uploaded file |
| `blob_ref` | `document_id`, `kind` (ORIGINAL, PAGE_IMAGE, REDACTED_PAGE, REDACTED_PDF), `bucket`, `key`, `page_no` | Pointers into object storage |
| `stage_run` | `document_id`, `stage`, `run_no`, `status`, `started_at`, `ended_at`, `error_code` | Timing for metrics |
| `text_layer` | `document_id`, `run_no`, `layer JSONB` | Contains PII; never exposed by API |
| `pii_span` | `document_id`, `page_no`, `start`, `end`, `bbox JSONB`, `type`, `detector`, `token` | No raw values |
| `pii_vault` | `token` (PK), `type`, `ciphertext BYTEA` | Only `pii` module accesses |
| `extraction` | `document_id`, `run_no`, `strategy`, `model_id`, `prompt_version`, `result JSONB` | Pseudonymized values only |
| `llm_call` | `document_id`, `stage`, `provider`, `model_id`, `prompt_version`, `input_tokens`, `output_tokens`, `cost_usd`, `latency_ms`, `outcome` | EXT-4 |
| `validation_result` | `extraction_id`, `rule_id`, `outcome`, `message` | |
| `decision` | `document_id`, `extraction_id`, `score`, `outcome`, `reasons JSONB` | VAL-8 |
| `field_correction` | `document_id`, `field_path`, `old_value`, `new_value`, `corrected_by` | REV-3 |
| `order_history_item` | `import_id`, `order_token`, `order_date`, `product_name`, `asin`, `quantity`, `unit_price`, `item_total` | REC-1 |
| `reconciliation_match` | `order_token`, `status`, `invoice_ids JSONB`, `amount_diff` | REC-3 |
| `eval_run`, `eval_metric` | run config, metric name/value | EVAL-4 |

JobRunr creates and owns its own tables.

`stage_run` (V3, M1.4): `stage` is one of `TEXT`, `PII`, `EXTRACT`, `VALIDATE`; `status` is
`RUNNING`, `SUCCEEDED`, `FAILED` or `SUPERSEDED`; `ended_at` is null exactly while `RUNNING`;
`error_code` is set for `FAILED` and kept when a failed run is superseded. `(document_id, stage,
run_no)` is unique, and a partial unique index allows one `RUNNING` run per document and stage.
The current output of a stage is that of its latest `SUCCEEDED` run. V3 also limits
`document.failed_stage` to the same stage names.

`blob_ref` (V2, M1.3): `kind` has a `CHECK` on the four kinds; `page_no` is set exactly for
`PAGE_IMAGE` and `REDACTED_PAGE`; `(bucket, key)` is unique; rows cascade-delete with their
document.

Flyway migrations live in `app/src/main/resources/db/migration` (`V{n}__{description}.sql`)
and run on app start. `document.status` has a `CHECK` listing the states of §5.1, so adding a
state needs a migration; `failed_stage`/`failure_code` are set exactly when status is `FAILED`.

---

## 8. API

| Method | Path | Purpose | Spec |
|--------|------|---------|------|
| POST | `/api/documents` | Upload one or more files | ING-1 |
| GET | `/api/documents?status=` | List documents | UC1 |
| GET | `/api/documents/{id}` | Status, decision, extraction (pseudonymized) | UC2 |
| POST | `/api/documents/{id}/reprocess?fromStage=` | Re-run from a stage | NFR-5 |
| GET | `/api/documents/{id}/pages/{n}/redacted` | Redacted page image | REV-2 |
| PATCH | `/api/documents/{id}/fields` | Field corrections | REV-3 |
| POST | `/api/documents/{id}/approve` | Approve after review | REV-3 |
| POST | `/api/order-history` | Import Amazon CSV | REC-1 |
| POST | `/api/reconciliation/run` | Run reconciliation | REC-2 |
| GET | `/api/reconciliation` | Reconciliation report | REC-3 |
| GET | `/api/labels/export` | Export reviewed labels | REV-4 |
| POST | `/api/samples/publish` | Export irreversibly redacted sample set | UC6 |
| GET | `/actuator/health` | Health (public) | |

OpenAPI docs at `/swagger-ui.html` (authenticated). Errors use RFC 9457 problem details.
No endpoint returns raw text, original files or vault values.

---

## 9. Storage layout

### 9.1 Object storage

| Bucket | Keys | Contents |
|--------|------|----------|
| `raw` | `raw/{documentId}/original.{ext}`, `raw/{documentId}/pages/{n}.png` | Originals and page renders (PII) |
| `redacted` | `redacted/{documentId}/pages/{n}.png`, `redacted/{documentId}/document.pdf` | Safe to show in UI |

Both buckets are private. Locally they are two SeaweedFS buckets, created by the
`seaweedfs-init` service in `compose.yaml`; names come from `S3_BUCKET_RAW` and
`S3_BUCKET_REDACTED` (defaults `raw`, `redacted`).

Access goes through `dev.invoiceintel.storage.BlobStore` (`put`, `get` → `Optional<byte[]>`,
`delete`; deleting a missing object is a no-op) addressed by `BlobKey(bucket, key)`, the same
pair `blob_ref` stores. Keys are validated: relative, no empty or `.`/`..` segments.
`S3BlobStore` implements it on an injected AWS SDK v2 `S3Client` (sync, Apache HTTP client;
the Netty async transport is excluded) and wraps SDK errors in `BlobStoreException`, whose
message names only the operation and bucket (PII-8). The integration test runs against the
same `chrislusf/seaweedfs:4.51` image as compose (M1.2). `app.config.StorageConfiguration`
builds the `S3Client` from `invoice-intel.storage.*` (M1.3): endpoint override, path-style
addressing, region `us-east-1` (ignored by SeaweedFS, an alias of `auto` on R2), static
credentials from `access-key`/`secret-key`.

### 9.2 Files in the repo

```
data/raw/                 git-ignored; drop zone for downloaded invoices
data/order-history/       git-ignored; Amazon export
data/labels/              git-ignored; hand labels (contain pseudonymized values only, still kept private)
data/samples-redacted/    committed; irreversibly redacted PDFs + expected JSON
eval-reports/             committed; metrics only
```

Label format: one JSON file per document matching the extraction schema, plus `pii_spans`
for PII evaluation.

---

## 10. Configuration and profiles

| Profile | Database | Storage | LLM | Notes |
|---------|----------|---------|-----|-------|
| `local` | Compose Postgres | Compose SeaweedFS | Remote APIs via keys in `.env` | Binds to `127.0.0.1` |
| `test` | Testcontainers | Testcontainers SeaweedFS | WireMock stubs | No network LLM calls |
| `fly` | Managed Postgres | R2 | Remote APIs via Fly secrets | See `deployment.md` |

Typed config with `@ConfigurationProperties` under the `invoice-intel.*` prefix:
`pipeline.*` (thresholds, concurrency), `pii.*` (detector toggles, mode), `llm.models[]`,
`storage.*`, `text.*` (DPI, OCR languages).

- Shared settings are in `app/src/main/resources/application.yaml`; each value that differs
  per environment reads an environment variable (`DB_URL`, `DB_USER`, `DB_PASSWORD`,
  `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET_RAW`, `S3_BUCKET_REDACTED`;
  NFR-12) with a default that points at the local compose stack. Defaults are never secrets;
  credentials default to empty, and blank S3 keys fail start-up like the admin password.
- `application-local.yaml` binds the server to `127.0.0.1` and imports the git-ignored `.env`
  from the repository root (`spring.config.import: optional:file:.env[.properties]`; `bootRun`
  runs in the root). `.env` is read as a properties file, so values are unquoted `KEY=value`.
- `application-test.yaml`: Postgres comes from Testcontainers via `@ServiceConnection`
  (`TestcontainersConfiguration` in `app/src/integrationTest`).
- The `fly` profile is added in Phase 2 (`deployment.md`).
- The typed skeleton is one record, `dev.invoiceintel.app.config.InvoiceIntelProperties`
  (`storage`, `text` so far), validated at start-up. A group moves into its own module when
  that module gets Spring wiring; `pipeline`, `pii` and `llm` are added by the tasks that use
  them.
- Actuator exposes only `/actuator/health` over HTTP, without details.
- JobRunr (`jobrunr.*`, M1.4) uses the app's datasource and creates its own tables. The
  background job server is on in `application.yaml` and off in `application-test.yaml`, so only
  tests that enable it (`PipelineIT`) run stage jobs. The JobRunr dashboard is off (it would
  serve on its own port without the app's login), and anonymous usage reporting
  (`jobrunr.miscellaneous.allow-anonymous-data-usage`) is off.

### 10.1 Memory budget (NFR-10, 2 GB container)

| Item | Budget |
|------|--------|
| JVM heap (`-XX:MaxRAMPercentage=60`) | ~1.2 GB |
| NER model + ONNX Runtime (off-heap) | ~300 MB |
| Tesseract / OpenCV native | ~200 MB |
| Metaspace, threads, buffers | ~250 MB |

Page rendering is the main heap risk: render one page at a time and cap concurrent
render/OCR jobs (config, default 2).

---

## 11. Security

- Spring Security, one admin user (password from env): form login for the UI (CSRF on),
  HTTP Basic for `/api/**` so the eval CLI, smoke and performance tests can call the API
  without a browser session.
- Admin credentials: `APP_ADMIN_USER` (default `admin`) and `APP_ADMIN_PASSWORD` (required, no
  default) bind to `invoice-intel.security.*`; only a bcrypt hash is kept in memory.
- `/api/**` is stateless with CSRF off. Its 401 carries no `WWW-Authenticate` challenge, so a
  browser never shows a Basic prompt or caches the credentials, which is the remaining CSRF
  route for Basic auth (ADR-12). API clients send `Authorization` on the first request.
- Public without login: `/actuator/health` and `/error` (the status page of a rejected
  request; guarding it would turn every 401/403 into a login redirect).
- All services bind to `127.0.0.1` locally (NFR-2).
- Secrets only from env / secret store (NFR-12). `.env` is git-ignored; `.env.example` is committed.
- Logging: code logs only IDs, stages and codes (PII-8). Console logs are JSON lines in
  Spring Boot's ECS format (`logging.structured.format.console: ecs`), with MDC fields
  `documentId`, `stage` and `runNo` set through `dev.invoiceintel.common.logging.LogContext`
  (NFR-6). As a backstop, `PiiMaskingJsonMembersCustomizer` (`app`, registered with
  `logging.structured.json.customizer`) runs `PiiLogMasker` over every string value of a log
  line (message, MDC, stack trace) and replaces PII-shaped substrings with `[EMAIL]` /
  `[PHONE]` (ADR-13). The pattern list is a stub (email, Indian mobile) until M2.1's
  detector regexes exist.
- Vault key and token key are separate keys; rotating the token key requires re-tokenizing
  (documented runbook, not automated in v1).

---

## 12. Observability (NFR-6)

| Metric | Type | Tags |
|--------|------|------|
| `invoice.stage.duration` | timer | `stage`, `outcome` |
| `invoice.decision` | counter | `outcome` |
| `invoice.llm.tokens` | counter | `provider`, `model`, `direction` |
| `invoice.llm.cost` | counter (USD) | `provider`, `model` |
| `invoice.pii.spans` | counter | `type`, `detector` |
| `invoice.pii.leak` | counter | — (must stay 0) |

Logs are JSON with `documentId`, `stage`, `runNo`. Locally, `/actuator/metrics` and the
JobRunr dashboard are enough; Prometheus/Grafana are optional.

---

## 13. Testing strategy (NFR-9)

| Level | What | Tools |
|-------|------|-------|
| Unit | Every regex/checksum, every validation rule, token generation, amount-in-words parser, span merger | JUnit, AssertJ, property tests for checksums |
| Component | Text extraction and redaction on fixture PDFs (synthetic invoices, no real PII) | PDFBox, Tess4J |
| Redaction proof | After redaction, re-extract text and OCR the redacted PDF; assert no known PII string is present | PII-4 test |
| Integration | Full pipeline with Postgres + SeaweedFS containers and WireMock LLM | Testcontainers, WireMock |
| Architecture | Module dependency rules, PII gate rules (§6.4) | ArchUnit, in `app/src/test` (`dev.invoiceintel.app.architecture`); `app` adds every module as a test-only dependency so the rules see all main classes |
| Eval smoke | 5 redacted sample docs through the pipeline in CI with stubbed LLM; checks wiring, not accuracy | eval module |
| Smoke | Full container stack; health, auth, upload, dedupe, pipeline status | `scripts/ci/smoke-test.sh` |
| Performance | API latency and end-to-end time to decision under load, stubbed LLM | Gatling, `perf` module |

Unit tests live in `src/test`, integration tests in a separate `src/integrationTest` source
set with its own Gradle task. Merge gates, thresholds and the Gradle task names CI depends on
are defined in `docs/ci.md`.

Test fixtures use **synthetic** invoices generated from a template (fake names, valid-format
fake GSTINs). Real invoices never enter the test tree.

The generator lives in `common`'s test fixtures (`java-test-fixtures` plugin, ADR-14):
`common/src/testFixtures/java/dev/invoiceintel/common/fixtures/SyntheticInvoiceGenerator`.
`generate(InvoiceType, seed)` returns the ground truth (`SyntheticInvoice`: numbers, dates,
seller, buyer, line items, totals, amount in words, `piiValues()`) and `toPdf` renders it as a
one-page digital PDF with PDFBox; the same type and seed always give the same bytes. Types:
GST tax invoice, credit note, pre-GST (VAT/CST) invoice. Other modules use it with
`testImplementation(testFixtures(project(":common")))`; it never reaches main code, so the
§4.1 rules are unaffected. Identifier-shaped values (GSTIN, PAN, mobile) are computed at run
time, never written as literals, so the gitleaks PII rules stay quiet on source files.

---

## 14. Local development (NFR-7)

`compose.yaml` services, all bound to `127.0.0.1`:

| Service | Image | Port |
|---------|-------|------|
| postgres | `postgres:16.15-alpine` | 5432 |
| seaweedfs | `chrislusf/seaweedfs:4.51` (`weed server -s3`, ADR-11) | 9000 (S3 API; container port 8333) |
| seaweedfs-init | same image, one-shot | creates the two buckets of §9.1 if missing |
| app | built from `Dockerfile` (optional; usually run from IDE / `bootRun`) | 8080 |

`compose.ci.yaml` overrides this for CI: adds the app container (2 GB memory limit) and a
WireMock container (`llm-stub`, 800 ms latency) standing in for the LLM provider, with
throwaway keys generated per run by `scripts/ci/stack-credentials.sh`. The app's compose
healthcheck probes `/actuator/health` with bash `/dev/tcp`, since the runtime image has no curl.

One command: `make up run` (`docker compose up -d --wait`, then
`./gradlew :app:bootRun --args='--spring.profiles.active=local'`). The `Makefile` also has
`test` (`./gradlew build`) and `down`, and sets the colima Testcontainers variables below when
colima is in use. Tesseract is installed on the Mac via Homebrew for
`bootRun`, and inside the image via `apt`.

Testcontainers needs to find the Docker socket. With colima, set
`DOCKER_HOST=unix://$HOME/.colima/default/docker.sock` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` before `./gradlew build`.

---

## 15. Requirement traceability

| Spec IDs | Module(s) | Section |
|----------|-----------|---------|
| ING-1..4 | ingest, storage | §6.1 |
| TXT-1..4 | text | §6.2 |
| PII-1 | pii, extract (gate) | §6.4 |
| PII-2, PII-3, PII-7 | pii | §6.3 |
| PII-4, PII-5 | pii | §6.3 |
| PII-6 | pii | §6.3 |
| PII-8 | app (logging), all | §11 |
| EXT-1..5 | extract | §6.5 |
| VAL-1..8 | validate | §6.6 |
| REC-1..4 | reconcile | §6.7 |
| REV-1..4 | app | §6.8 |
| EVAL-1..4 | eval | §6.9 |
| NFR-1 | pii, ArchUnit tests | §6.4, §13 |
| NFR-2, NFR-12 | app | §11 |
| NFR-3, NFR-6 | pipeline, app | §12 |
| NFR-4 | extract (cost accounting) | §6.5 |
| NFR-5 | pipeline | §5 |
| NFR-7 | compose, scripts | §14 |
| NFR-8, EXT-3 | extract (model registry) | §6.5 |
| NFR-9 | all | §13 |
| NFR-10 | app, text | §10.1 |
| NFR-11 | storage | §9.1 |
| NFR-13 | CI workflows, perf | §13, `ci.md` |

---

## 16. Architecture decision records (summary)

| ID | Decision | Alternatives | Reason |
|----|----------|--------------|--------|
| ADR-01 | Spring Boot 4 + Java 25 (chosen 2026-10-28, M0.1). Versions checked on Maven Central 2026-10-28: Boot 4.1.2, Spring AI 2.0.2 (built against Boot 4.1.2), JobRunr 9.0.2 (`jobrunr-spring-boot-4-starter`), springdoc-openapi 3.2.0 (Boot 4 line, built against 4.1.2), DJL 0.39.0 + ONNX Runtime engine 0.39.0 + HF tokenizers 0.39.0, Tess4J 5.20.0, Gradle 9.8.2. DJL and Tess4J don't depend on Spring; on Java 25 they need `--enable-native-access=ALL-UNNAMED` (JNA/JNI). | Boot 3.5 + Java 21 | Use current LTS, but never block on library compatibility. Every key library has a GA Boot 4 release, so the fallback isn't needed. |
| ADR-02 | Modular monolith with Gradle modules | Microservices; single module | Clear boundaries and ArchUnit checks without distributed-system cost. |
| ADR-03 | Spring AI for LLM access | LangChain4j; raw HTTP SDKs | Native Spring config, structured output, multimodal, many providers. |
| ADR-04 | JobRunr on Postgres for the pipeline | Spring Batch; Kafka/RabbitMQ | Durable retries and a dashboard with no extra infrastructure. |
| ADR-05 | Redacted PDF = rebuilt from masked page images | Remove text operators from the original content stream | Guarantees no hidden text survives (PII-4); simplicity over file size. |
| ADR-06 | Deterministic HMAC tokens for pseudonyms | Random tokens + lookup | Same value → same token across documents, so reconciliation works on tokens. |
| ADR-07 | Multi-stage Dockerfile instead of Jib | Jib with a custom base image | Tesseract and Leptonica native packages are needed in the image; a Dockerfile is the simplest way to add them. |
| ADR-08 | Thymeleaf + HTMX review UI | React/Vue SPA | Keeps the project in one language and one deployable. |
| ADR-09 | Rule-based classification before PII removal | LLM classification | No LLM may see a document before PII removal (PII-1). |
| ADR-10 | Tess4J for OCR, vision LLM as the alternative path | PaddleOCR/docTR (Python) | Spec forbids Python services in v1; the vision strategy covers hard scans. |
| ADR-11 | SeaweedFS (`chrislusf/seaweedfs`, S3 gateway) as the local S3-compatible store (owner decision 2026-10-28, M0.5) | MinIO; Garage; RustFS; Chainguard MinIO; MinIO built from source | MinIO stopped publishing public images in late 2025 (`minio/minio` not pullable, checked 2026-10-28). SeaweedFS is Apache-2.0, has pinned tags, and the app only uses the AWS SDK S3 client (NFR-11), so no code depends on the choice. |
| ADR-12 | `/api/**`: stateless HTTP Basic with CSRF off, 401 without a `WWW-Authenticate` challenge; the FindSecBugs `SPRING_CSRF_PROTECTION_DISABLED` finding is suppressed on that one method with this ADR as justification (owner decision 2026-10-28, M0.7) | CSRF tokens on `/api/**` too; CSRF off with the default Basic challenge | No session cookie means no CSRF via cookies. Without the challenge, browsers never cache Basic credentials, which closes the other route. CSRF tokens would force the CLI, smoke script and Gatling to fetch a token and a session first. |
| ADR-13 | Structured logs use Spring Boot's built-in ECS JSON formatter; the PII backstop is a `StructuredLoggingJsonMembersCustomizer` value processor, not a Logback filter (M0.8) | Logback `Filter` (can only accept or drop events, not rewrite them); `logstash-logback-encoder` with a masking decorator | Built into Boot, no new dependency, and the value processor reaches every string field including MDC and stack traces. Masking applies only to the JSON output, so plain-text console logging must not be re-enabled. |
| ADR-14 | Synthetic invoice generator in `common` test fixtures (`java-test-fixtures`), PDFBox as a `testFixturesImplementation` dependency (owner decision 2026-10-28, M1.1) | A separate test-only `fixtures` module | Shared by every module's tests without a new module or any main-code dependency, so ArchUnit rules and the §4 module list stay unchanged. |

---

## 17. Open questions

| # | Question |
|---|----------|
| A1 | ~~Confirm Boot 4 compatibility of Spring AI, JobRunr, springdoc and DJL at scaffold time.~~ Resolved 2026-10-28 (M0.1): all compatible; see ADR-01. |
| A2 | Which NER model (size vs Indian-name recall)? Benchmark two candidates on the PII label set. |
| A3 | Which LLMs to compare in the first eval (spec R6)? |
| A4 | Does Hindi text appear often enough to need the `hin` OCR language? |
| A5 | Is the vision strategy accurate enough on redacted images, given masked regions may confuse layout understanding? |
