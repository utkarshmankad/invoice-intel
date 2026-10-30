# Invoice Intel — Architecture

> Status: Draft v0.1 · Last updated: 2026-10-30
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
  ends it, so a document stops in the last state that has one. Since M1.10 the Text handler is
  `TextStageHandler` (§6.2) and since M2.10b the PII handler is `PiiStageHandler` (§6.3), so
  documents now stop in `PII_CLEAN`; `PlaceholderStageHandler` remains for tests of stages not
  built yet.
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
`invoice-intel.text.min-text-chars` since M1.10). Unreadable PDFs raise
`TextExtractionException` with a content-free message.

Page images (PNG) are stored under `raw/{documentId}/pages/{n}.png` for the vision
strategy and review UI. They contain PII and live in the raw bucket only.
Implementation (M1.6): `PageRenderer` renders each PDF page with PDFBox at the configured DPI
(default 300, `invoice-intel.text.dpi`) as RGB and encodes it to PNG, one page at a time: each
PNG goes to a callback before the next page is rendered, so one page bitmap is on the heap per
call (§10.1). `PageImageStore` writes each PNG to the raw bucket, then upserts its `PAGE_IMAGE`
`blob_ref` row (`ON CONFLICT (bucket, key)`), so re-running keeps one row per page. Concurrent render/OCR work is capped by `max-concurrent-page-jobs` in the Text job (M1.10).
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
folder is a constructor argument, set from `invoice-intel.text.tessdata-path` (M1.10). Tests point JNA at `/opt/homebrew/lib` when it exists.

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

**Text job (M1.10).** `TextStageHandler` (`pipeline`, the `TEXT` stage of §5.1) reads the
document's MIME type and `ORIGINAL` `blob_ref`, loads the original from the raw bucket and calls
`DocumentTextService` (`text`):
- PDF: the text layer comes from `PdfTextExtractor`; every page is rendered and stored as a page
  image, and a page without a text layer (`min-text-chars`) is preprocessed (TXT-3) and OCR'd
  while its PNG is still in hand, replacing that page with the OCR page.
- JPEG/PNG: the image is re-encoded as PNG (which also drops embedded metadata such as EXIF),
  stored as page 1 and OCR'd.
- OCR boxes are in pixels of the preprocessed (deskewed, binarized) image, not of the stored page
  image; they differ only by the corrected skew.
- At most `max-concurrent-page-jobs` documents render or OCR at once (a fair semaphore, §10.1).
  OpenCV's native library loads on the first OCR page, so start-up and digital PDFs don't need it.

The handler then inserts the `text_layer` row for the run (`TextLayerStore`, `TextLayer` as
JSONB via Jackson), classifies the layer and sets `document.document_class`. All of it runs in
the stage transaction (§5.1), so a failed run, or one superseded while running, leaves no outputs. Failure codes:
`ORIGINAL_MISSING` (no `ORIGINAL` row or object) and `TEXT_EXTRACTION_FAILED` (unreadable PDF or
image, or OCR error). The module boundary (§4.1) keeps `text` types out of `app`:
`PipelineConfiguration` passes plain settings (`TextStageHandler.Settings`).

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

Implementation (M2.3): text-layer order interleaves the lines of side-by-side columns (seller |
buyer), so both blocks are found from word boxes. `pii.internal.PageLayout` groups a page's words
into rows (vertical centres within 0.5 word heights) and splits rows into fragments at horizontal
gaps wider than 2 heights. A block is the anchor's fragment plus the following fragments whose
left edge is within 3 heights of the anchor's, up to a fragment starting with a known label or a
vertical gap above 1.8 heights. Labels match case-insensitively, ignoring spaces and punctuation
between letters ("BILL ING ADDR ESS"), and must end at a word boundary ("PAN" doesn't match
"Pankaj"). `SellerBlockTagger` tags "Sold By"/"Seller"/"Supplier" blocks, bridging a gap before
seller ID lines (PAN, GST, GSTIN, VAT, TIN, CIN, FSSAI); `DocumentContext.of(documentId, layer)`
stores the result as `sellerRanges` (`TextRange`: page and `[start, end)` of `PageText`), and
`DocumentContext.isSellerData(span)` is true when a span lies wholly inside one range (a span that
also covers other text stays, for recall). Filtering of seller spans from all detectors happens
in `SpanMerger` (M2.4) or the PII stage, not in each detector; `AddressBlockDetector` also drops
its own seller spans. `AddressBlockDetector` anchors: "Billing Address", "Shipping Address",
"Delivery Address", "Ship To", "Bill To", "Deliver To"; a block ends at section labels (Order,
Invoice, Credit/Debit Note, Place of Supply/Delivery, State/UT Code, Amount, Total), any anchor or
an ID label (a buyer GSTIN/PAN is left to the checksum and regex rules). Text after the anchor on
its line counts as the first line. The first line is `PERSON` (`address-block:name`); lines
labelled Phone/Mobile/Ph/Tel/Contact or Email/Mail type the value after the label as `PHONE`/
`EMAIL` instead of `ADDRESS`; other words are `ADDRESS` (`address-block:street`, city included),
except six-digit or 3+3-digit numbers (`PIN_CODE`, `address-block:pin`) and words wholly covered
by a state/UT name, which are kept (specs §6.1). The "State/UT Code" line is not PII. Adjacent
words of one type form one span, so spans never run across another column's text.

`SpanMerger` unions overlapping spans, keeps the most specific type, and expands spans to
whole words. Bias is toward recall (spec §6.3).

Implementation (M2.4): `SpanMerger.merge(layer, spans)` returns, per page in page order,
sorted non-overlapping spans on whole words; a span on a page not in the layer or ending beyond
the page text is an `IllegalArgumentException` (page number only in the message). It works on
words: every word any span touches is covered (a partial word counts as the whole word, a span
over only a space covers nothing), and takes the most specific type among the spans covering it,
by `SpanMerger.PRIORITY`: GSTIN > AADHAAR > PAN > ORDER_NUMBER > INVOICE_NUMBER > EMAIL > PHONE >
PIN_CODE > PERSON > ADDRESS (checksummed identifiers before format-only ones, so a PAN inside a
GSTIN is the GSTIN; identifiers and contact details before the generic name and address text
around them). Consecutive covered words join into one span when they have the same type and one
input span covers both, so the space between words is inside a span only when both sides are.
Overlap is therefore a union of coverage, not of types: a phone or PIN inside an address block
stays its own `PHONE`/`PIN_CODE` span and the address text on either side stays `ADDRESS`, so
each entity gets its own type's token (spec §6.1); spans that only sit next to each other are
not joined. A merged span's `detector` is the sorted distinct rule names of the same-type input
spans in it, joined by `+` (e.g. `regex:landline+regex:mobile`), still value-free.

Implementation (M2.1): `PiiDetector`, `PiiSpan`, `PiiType`, `PageText` and `DocumentContext`
are in `dev.invoiceintel.pii`. `PageText` is a page's words joined by single spaces in
text-layer order; a `PiiSpan` is `pageNo`, character `[start, end)` in that text, `type` and
`detector` (rule name), with no value, so it is safe to store as a `pii_span` row and log.
`PageText.wordsIn(start, end)` maps a span back to words and boxes for redaction.
`DocumentContext` holds the `DocumentId` for now; seller-block tagging (M2.3) extends it.
`RegexDetector` runs one rule per pattern (`regex:email`, `regex:mobile`, `regex:landline`,
`regex:pin-*`, `regex:pan`, `regex:order-*`, `regex:invoice-label`); spans of different rules
may overlap or repeat until `SpanMerger` (M2.4). Rules worth knowing:
- Phones: mobiles with optional `+91`/`91`/`0` and a 5+5 split; landlines `0` or `+91`, then a
  2-4 digit STD code, ten digits in all.
- PIN codes need context, because a bare six-digit number is as likely an HSN code: a PIN label,
  a state/UT name before it (`IndianStates`, longest name first), the PIN after such a PIN (the
  buyer column of a two-column seller | buyer layout), or `City-PIN`. PINs inside the buyer
  address block without such context are left to `AddressBlockDetector` (M2.3).
- PAN: ten characters, fourth letter a holder type; inside a GSTIN it isn't matched (GSTIN and
  Aadhaar need checksums, M2.2).
- Order numbers: the marketplace `NNN-NNNNNNN-NNNNNNN` form, or any code with a digit after an
  "Order Number/No/ID/#" label. Invoice numbers: a code with a digit after an "Invoice",
  "Credit Note", "Debit Note" or "Bill" number label.
- Seller values (seller PAN, seller PIN, a pre-GST seller TIN that looks like a landline) are
  found too; seller-block tagging (M2.3) removes them. Patterns avoid nested quantifiers (ReDoS).

Implementation (M2.2): the check-digit algorithms live in `common` (`dev.invoiceintel.common.checksum`)
because both `pii` and `validate` (VAL-4) need them and `validate` may depend only on `common`
(§4.1): `Verhoeff` (Aadhaar's last digit; catches every single-digit error and adjacent
transposition) and `GstinChecksum` (GSTIN layout plus the mod-36 check character, base-36 values
weighted 1/2 alternately and folded as `p / 36 + p % 36`). `ChecksumDetector` in `pii` runs
beside `RegexDetector` with three rules: `checksum:gstin` (state code + PAN + entity + `Z` +
check, reported only if the check passes), `checksum:aadhaar` (twelve digits starting 2-9,
optionally 4-4-4 with spaces or hyphens, reported only if Verhoeff passes) and
`regex:gstin-label`: the first 15-character code with a digit within 30 characters after a
`GST`/`GSTIN` label (no digits in between) is reported even when its checksum or layout fails,
because that is most likely an OCR-damaged GSTIN (recall bias). Checksum-failing GSTIN- or
Aadhaar-shaped values without a label are not reported (an HSN, amount or document number is
the likelier reading). Test fixtures build checksum-valid fake values with `FakeIds.gstin` and
`FakeIds.aadhaar`, which use these classes.

Implementation (M2.8): `NerDetector` (`ner:<model>`, e.g. `ner:distilbert-conll03`) runs a
DistilBERT token-classification model (ADR-15) with DJL's ONNX Runtime engine and HuggingFace
tokenizer. Each page's `PageText` is tokenized once (no truncation) and classified in windows of
256 tokens overlapping by 64, so pages longer than the model's 512-token limit are covered; every
token takes the prediction of the window where it is farthest from an edge. A word is typed when
any of its tokens has B- plus I- probability ≥ 0.5 for an entity (threshold configurable, recall
bias): `PER` → `PERSON` (wins ties), `LOC` → `ADDRESS` (city and street names the address-block
rules miss; seller and product mentions are dropped later by seller-range filtering or cost some
precision), `ORG`/`MISC` → nothing. Words wholly covered by an `IndianStates` name are never
reported (specs §6.1). Consecutive words of one type form one span over whole words; no text is
logged (PII-8). Before tokenizing, words written all in capitals are title-cased in place (offsets
unchanged), because the cased model misses names printed in capitals: on the benchmark this
raised upper-case name recall from 5% to 89%. The model directory comes from the build's pinned,
SHA-256-verified Hugging Face download (`./gradlew :pii:downloadNerModel`, cached in
`~/.cache/invoice-intel/models/<repo>/<sha>/`, override with `-PmodelCacheDir` or
`INVOICE_INTEL_MODEL_CACHE`); `NerDetector.load` sets DJL offline mode (`ai.djl.offline=true`),
which also disables DJL's EC2 telemetry, and the ONNX Runtime and tokenizers natives
(osx-aarch64, linux-x86_64) come from the Maven jars. Inference takes ~2 ms per short snippet and
~100 ms per 400-word page on an Apple M-series CPU. `NerBenchmarkTest` measures PERSON recall and
precision on synthetic Indian names (`SyntheticNerSamples`: 300 names in 15 invoice contexts, a
quarter in capitals, plus 30 negatives); the default test run guards the chosen model's numbers,
`./gradlew :pii:nerBenchmark` compares both candidates (report in
`pii/build/reports/ner-benchmark/`). NER is not yet wired into the PII stage (M2.10).

Implementation (M2.9): `PiiSettings` holds the `RedactionMode` and one switch per layer: `rules`
(`RegexDetector` + `ChecksumDetector`), `addressBlock` (`AddressBlockDetector`), `ner` (model
directory and threshold) and `localLlm` (endpoint). Each layer can be off on its own for
evaluation (PII-2), but at least one of rules, address block and NER must stay on, so a config
mistake can't turn the PII stage into a pass-through; a NER layer that is on needs a model
directory. `PiiDetectors.create(settings)` builds the enabled detectors in layer order, runs
them all on a document (`detect`, spans unmerged) and closes the NER model. `LocalLlmDetector`
is a stub (`detect` returns no spans, no connection is opened), off by default; its endpoint must
be an http(s) URL on a literal loopback host (`localhost`, `127.x.x.x`, `[::1]`, no DNS lookup),
checked when the layer is on. The app binds `invoice-intel.pii.*` (§10) and builds the settings
with `InvoiceIntelProperties.Pii.toSettings()`.

#### Tokens and vault (PII-3, PII-7)

- **Token generation:** `token = TYPE + "_" + base32(HMAC-SHA256(tokenKey, normalized(value)))[0..8]`.
  `TYPE` is the `PiiType` name, except `ID` for PAN and Aadhaar (spec §6.1; owner decision 2026-10-28).
  The same real value always gives the same token across all documents, which keeps order
  numbers joinable for reconciliation without storing them in clear text.
  `TokenGenerator` (M2.5): `normalized` collapses whitespace runs to one space, trims and
  upper-cases ASCII letters; Unicode case mapping and NFKC are left out (FindSecBugs
  `IMPROPER_UNICODE`, and the identifiers that must join are ASCII). base32 is RFC 4648, so the
  8 characters are the first 40 bits of the MAC.
- **Vault:** `pii_vault(token, type, ciphertext)`; `ciphertext` = Tink AEAD with
  associated data = token. Keys come from `VAULT_KEYSET` (env/secret), never from the DB.
  `PiiVault.seal/open` (M2.5) produce and open `VaultEntry` records; a ciphertext moved to
  another token, tampered with or written under another key fails to open.
- **Keys (PII-7):** `PiiKeys` builds both from Tink JSON keysets in environment variables:
  `TOKEN_KEYSET` (HMAC-SHA256 with no output prefix, Tinkey template
  `HMAC_SHA256_256BITTAG_RAW`; any other primary key is rejected, because a Tink-prefixed tag
  starts with the constant key ID and would give every value the same token) and
  `VAULT_KEYSET` (AEAD, e.g. `AES256_GCM`). Error messages name the variable only and don't
  chain Tink's exceptions. Rotating `TOKEN_KEYSET` changes every token, so it is not rotated
  once tokens are stored. Tink 1.24.0 (Maven Central, checked 2026-10-28).
- **Start-up wiring (M2.10a, ADR-16):** `app`'s `PiiConfiguration` builds `PiiSettings`
  (`Pii.toSettings()`), `PiiDetectors` (loads the NER model when that layer is on),
  `TokenGenerator` and `PiiVault` as beans at start-up, so a bad `invoice-intel.pii.*` setup,
  a missing model directory or a missing keyset stops the app before it takes a document.
  Keysets come from the process environment; a `PiiKeyEnvironment` bean replaces it in tests,
  which build throwaway keysets in-process with Tink. CI's compose stack gets throwaway keysets
  from `scripts/ci/stack-credentials.sh` (built with `openssl rand` from the key protos' fixed
  layout, new every run, never stored).
- **Order-history import** passes order numbers through the same tokenizer, so
  reconciliation joins on tokens.
- Redact mode: same spans, but replaced by fixed `[REDACTED:TYPE]` text and no vault entry,
  except order and invoice numbers, which keep their consistent token in both modes (spec §6.1,
  reconciliation joins on it) and still get no vault entry (owner decision 2026-10-28, M2.6).

#### Redaction outputs

| Output | Method | Spec |
|--------|--------|------|
| Redacted text | Rebuild page text from words, substituting tokens for spans. Word boxes are kept so evidence still maps to positions. | PII-1 |
| Redacted page images | Fill span boxes (padded) with solid rectangles on the page PNGs. | PII-5 |
| Redacted PDF | Build a **new** PDF from the redacted page images. The original content stream is not reused, so no hidden text survives. Text layer is dropped by design. | PII-4 |

The rebuilt image-only PDF is the simplest way to *guarantee* PII-4. Selective removal of
text operators from the original content stream is a possible later improvement (ADR-05).

Implementation (M2.7): `ImageRedactor.redact(textResult, layer, pageImages)` masks the words of
`TextRedactor.Result.spans()` (seller spans already dropped) on each page image and returns the
same `RedactedContent` with a masked PNG per page (`Page.image()`); `mask(page, spans, image)`
does one page. Word boxes are scaled from the page's unit (PDF points or OCR pixels) to the
image's pixels, every word a span touches is masked whole, and each box is padded by 0.4 word
heights above and below (PDF boxes end at the baseline, so descenders stick out) and 0.15 word
heights left and right, at least 2 px. Masks are solid black on an RGB copy of the image. The
image must be the one the word boxes were measured on: for OCR pages that is the preprocessed
(deskewed) image, not the stored `PAGE_IMAGE`. `RedactedPdf(dpi).build(content)` writes a new
PDFBox document with one page per masked image (page size = pixels × 72 / dpi) and nothing else,
so it has no text layer, fonts, annotations or metadata of the original. `RedactionProofTest`
runs detectors → merge → text and image redaction → rebuilt PDF on synthetic invoices, then
re-extracts the PDF (no words) and OCRs it (no fixture PII value, while totals and seller PAN are
still read). Storing the redacted pages and PDF (`REDACTED_PAGE`, `REDACTED_PDF`) is part of the
PII stage (M2.10b, below).

Implementation (M2.10b, ADR-17): `pipeline`'s `PiiStageHandler` runs the stage in the stage
transaction: latest text layer (`TEXT_LAYER_MISSING` without one) → `DocumentContext.of` →
`PiiDetectors.detect` → `SpanMerger` → `TextRedactor` (mode from `invoice-intel.pii.mode`) →
`ImageRedactor` → `RedactedPdf` (page size from `invoice-intel.text.dpi`) → `pii`'s
`RedactionStore.save`. Page images: PDF-text pages are masked on their stored `PAGE_IMAGE`; OCR
pages (scans and photos) on `ImagePreprocessor.preprocess` re-run on the stored `PAGE_IMAGE`, which
is deterministic and gives the deskewed image the Text stage OCR'd, so no preprocessed image is
stored. A page without a stored image fails with `PAGE_IMAGE_MISSING`; spans that don't fit the
layer or an unreadable image with `REDACTION_FAILED`. `RedactionStore` (the only code that touches
`pii_span`, `pii_vault` and the redacted `blob_ref` rows; `pii` depends on `spring-jdbc` and
`storage` for it) writes one `pii_span` row per kept span with `TextRedactor.Result.replacements()`
as `token` (the token, or the `[REDACTED:TYPE]` marker in redact mode and for PIN codes) and the
union box of the span's words as `bbox`; inserts the vault entries with
`ON CONFLICT (token) DO NOTHING`; and puts the masked pages and PDF under the §9.1 keys before
upserting their `REDACTED_PAGE`/`REDACTED_PDF` rows. A failed run rolls every row back, so the
document is `FAILED` with no spans, vault entries or redacted rows and no later stage runs; blobs
written before the failure stay under their keys and are replaced by the next successful run, as
with the Text stage's page images. There is no table for the redacted text: `extract` (M3.8) gets
`RedactedContent` from a `pii` loader that rebuilds it from the text layer, `pii_span` rows and
the redacted pages.

Implementation (M2.6): `TextRedactor(tokens, vault).redact(layer, mergedSpans, ctx, mode)`
returns `TextRedactor.Result(content, spans, vaultEntries)`; nothing is persisted (the PII stage,
M2.10, stores `pii_span` rows and vault entries). `RedactionMode` is `PSEUDONYMIZE` or `REDACT`
(config in M2.9). Spans that are wholly seller data (`DocumentContext.isSellerData`) are dropped
first and left out of `spans`. A span's value is its words joined by single spaces; in
pseudonymize mode it becomes the `TokenGenerator` token and gets one `VaultEntry` per distinct
token per document. In redact mode spans become `[REDACTED:TYPE]` with no vault entries, except
`ORDER_NUMBER` and `INVOICE_NUMBER`, which keep their token in both modes because spec §6.1
requires a consistent token for them (still no vault entry, so irreversible). `PIN_CODE` is
"Removed" in both modes: `[REDACTED:PIN_CODE]`, no vault entry. Each span's words become one
replacement word per line (words whose vertical centres are within 0.5 word heights), with the
union box of that line's words and their lowest confidence; a span over two lines therefore gives
the same token twice, and a box never spans another column's text between lines. Other words keep
text and box exactly. Spans touching the same word are rejected (`SpanMerger` output expected);
a span touching part of a word replaces the whole word. Exceptions carry page numbers only.

#### Leak check (PII-6)

After extraction, `LeakChecker` decrypts the vault values for that document in memory and
searches the LLM's raw response (normalized: case, whitespace, digits-only variants). Any hit
→ document `FAILED` with `reason_code = PII_LEAK`, response discarded, alert metric incremented.

Implementation (M3.7): `pii`'s `LeakChecker.check(documentId, response)` loads the document's
vault entries (`pii_vault` rows whose token is in any `pii_span` row of the document, over all
its PII runs, so re-runs widen rather than narrow the check), opens them with `PiiVault` and
returns `LeakCheckResult` (the leaked `PiiType`s only). Response and values are compared in a
canonical form: ASCII lower case, every run of non-letters/digits as one space (no Unicode
case mapping or NFKC, as in `TokenGenerator`: FindSecBugs `IMPROPER_UNICODE`, owner decision
open). A value
matches on whole words; a multi-word value also matches with its spaces removed; a value with at
least 6 digits also matches inside a run of digits that only spaces or punctuation separate
(`+91 98765-43210`). Decrypted values stay in local variables and are never logged, returned or
put in an exception; an entry that doesn't decrypt throws (fail closed). On a leak the
`LeakChecker.Listener` is called with the document ID and types; `app` (`PipelineConfiguration`)
increments the `invoice.pii.leak` counter (§12) and logs an error with `documentId` and
`piiTypes` only. The caller (Extract stage, M3.8) throws `StageFailedException(PII_LEAK)`, so
the document becomes `FAILED` in `EXTRACT` with `failure_code = PII_LEAK` and nothing of the
response is stored. Values without a vault entry (PIN codes, redact mode) are not yet checked
(plan.md Follow-ups).

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

Implementation (M2.6): `RedactedContent` is a final class in `dev.invoiceintel.pii` with
package-private constructors: `documentId()`, `mode()`, `pages()` and `text()` (page texts joined
by `\n`). Each `RedactedContent.Page` (also package-private constructor) has `pageNo`, `width`,
`height`, `words()` (`text.Word` with boxes, PII words replaced by token or marker words) and
`text()` (the words joined by single spaces, as in `PageText`, so offsets map back to word boxes
for evidence). No accessor returns an original value, and `toString()` shows the document ID,
mode and page/word counts only (PII-8). M2.7 adds the masked page image (`Page.image()`, a PNG
copy, empty until `ImageRedactor` ran); page/line markers for the
`TEXT` strategy are built by `extract` (M3) from the word boxes.

Enforcement (M2.11): `app`'s ArchUnit tests import every module's main classes.
`ModuleDependencyRulesTest` holds the §4.1 layers (with one exception: `extract` may use
`text.Word`, which `RedactedContent.Page.words()` returns) and lets `extract` use only
`RedactedContent` and `RedactedContent.Page` from `pii`. `PiiGateRulesTest` adds: `extract` uses
nothing else from `text` (no `TextLayer`/`TextPage`) and nothing from `storage` (no blob keys);
no method of a class named `LlmGateway` takes a `String`, `byte[]`, `TextLayer`, `TextPage` or
`BlobKey`; every constructor of `RedactedContent` and `RedactedContent.Page` is package-private or
private; no class outside `pii` uses `pii.internal` (the vault and span repositories) or calls
`PiiVault.open`. Only Spring AI use outside `extract` was checked before (M0.4). Rules on code
not written yet allow an empty match. A deliberate violation in `extract` (an `LlmGateway` taking a
`String`, using `TextLayer` and opening the vault) failed five rules before it was removed.

### 6.5 Extract (`extract`)

**Strategies (EXT-2)**, chosen per run by config or eval flag:

| Strategy | Model input | When useful |
|----------|-------------|-------------|
| `TEXT` | Redacted text with page/line markers | Digital PDFs; cheapest |
| `VISION` | Redacted page images | Scans, photos, table-heavy layouts |
| `HYBRID` | Both | Hard documents; eval comparison |

Implementation (M3.5): `ExtractionStrategy` (`TEXT`, `VISION`, `HYBRID`) is a field of
`ExtractionRequest` (the four-argument constructor means `TEXT`), so each run or eval call picks
it. Each strategy names the `ModelCapability`s it needs (`HYBRID` needs both); the gateway rejects
a model without them before any call. `ModelInput` builds the user message from `RedactedContent`
only: the line-marked text (below), the masked PNG of every page (`RedactedContent.Page.image()`,
PII-5) as Spring AI `Media` in page order with a note mapping image to page number, or both. A
vision strategy on a page without a masked image fails the request (`IllegalArgumentException`);
it never falls back to another image. Each strategy has its own prompt
(`ExtractionStrategy.defaultPrompt()`): `invoice-extraction/v1`, `invoice-extraction-vision/v1`
(evidence is the page only, no line markers), `invoice-extraction-hybrid/v1`.

**Structured output (EXT-1):** Java records mirror the spec §5 schema
(`InvoiceExtraction`, `LineItem`, `TaxLine`, `Field<T>{value, confidence, evidence}`).
Spring AI structured output generates the JSON schema from the records; responses are
parsed and then checked with Bean Validation. A parse failure gets one repair retry with the
error message, then the stage fails.

Record shape (M3.1, root package `dev.invoiceintel.extract`): every scalar of spec §5.1–5.2
is a `Field<T>(value, confidence, source, evidence)` with `source` ∈ `LLM`/`RULE`/`HUMAN`
(spec §5.3). A field the document lacks is a `null` `Field`; a present `Field` always has a
non-null value. `Evidence(page, box, lineRefs)` holds the line markers the model cited and,
after evidence mapping (M3.6), a `BoundingBox` in the text layer's page units; it never holds
copied text. Line items carry `chargeType` (`PRODUCT`, `SHIPPING`, `CONVENIENCE`, `OTHER`).
Bean Validation checks only what holds for every document class (required fields, confidence
0–1, at least one line item and one tax line per item); class- or era-dependent requirements
(GSTIN, place of supply, HSN, original invoice ref) and arithmetic are validate rules.

**Prompts (EXT-5):** `extract/src/main/resources/prompts/{name}/v{n}.st` (StringTemplate).
The prompt name + version is stored on every `llm_call`. Changing a prompt means adding a
new version file, never editing an old one.
Implementation (M3.4): `PromptLoader` reads `prompts/{name}/v{n}.st` from the classpath
(names are lowercase kebab-case folder names, so a ref can't leave `prompts/`), caches the
template, renders it with Spring AI's `StTemplateRenderer` (`{variable}` placeholders; a missing
variable fails) and builds the `ExtractionRequest` carrying the `PromptRef`, so
`llm_call.prompt_version` is `name/v{n}`. The first prompt is `invoice-extraction/v1`.

**Line markers (M3.4):** `TextLines` groups each `RedactedContent.Page`'s words into lines from
the word boxes only (top to bottom; a word joins the current line when its vertical centre lies
within the line's first word's box; left to right within a line), so the grouping is
deterministic and evidence mapping (M3.6) can repeat it. The `TEXT` input is one row per line,
`[p{page}:l{line}] words`; the model cites `p{page}:l{line}` in `Evidence.lineRefs`.

**Model registry (EXT-3, NFR-8):** `invoice-intel.llm.models` in config lists each model's
provider, model ID, capabilities (text/vision) and price per million input/output tokens.
Cost per call = tokens × configured price (EXT-4). Prices are config, so they can be
updated without code changes.
Implementation (M3.2): `LlmProperties` (`@ConfigurationProperties("invoice-intel.llm")`) lives
in `extract`, since `app` may not depend on `extract` (§4.1); it binds through the app's
`@ConfigurationPropertiesScan` once `extract` is on the app classpath (M3.8). Each
`models[]` entry is a `ModelSpec`: `name` (registry key), `provider` (a name the gateway maps
to a client in M3.3), `model-id`, `capabilities` (`text`, `vision`),
`input-price-per-million` and `output-price-per-million` (USD). `ModelRegistry` looks models
up by name or capability and rejects duplicate names. An empty list is valid until M3.8.

**Gateway (M3.3):** `SpringAiLlmGateway` implements `LlmGateway` on Spring AI's `ChatModel`
(`spring-ai-model`, BOM 2.0.x) and takes a chat client per provider name (`ModelSpec.provider`);
building those clients from config is M3.8's wiring, so no provider module is a main dependency
yet (tests use `spring-ai-ollama` against WireMock). `ExtractionRequest<T>(model, prompt,
instructions, outputType, strategy)` names a registry model, a `PromptRef(name, version)`, the
output record and the strategy; the system message is the instructions plus
`BeanOutputConverter`'s schema, the user message is the strategy's input (M3.4 line-marked text;
M3.5 page images, above). The model ID is set on the client's own options type (`client.getOptions().mutate()`), since Spring AI 2.0 providers reject generic
`ChatOptions`. A response that fails to parse or fails Bean Validation gets one repair call
carrying the error; a second failure gives `LlmResult.Failed` with `LLM_INVALID_OUTPUT`, a
provider exception `LLM_ERROR` (no extra retry beyond the client's own). Every call, including
failed ones, is passed to `LlmCallLog` (`JdbcLlmCallLog` writes `llm_call`) with tokens from the
response usage, cost = tokens × price / 10⁶ (USD, 6 decimals) and latency. Calls run under
`ProviderRateLimiter`, a fair semaphore per provider sized by
`invoice-intel.llm.max-concurrent-calls.<provider>` (default
`invoice-intel.llm.default-max-concurrent-calls`, 4). Logs carry document ID, stage, model and
attempt only (PII-8).

**Evidence:** the prompt asks the model to cite the line markers it used; the extractor maps
them back to word boxes from the text layer for review highlighting.
Implementation (M3.6): `EvidenceMapper.map(extraction, redactedContent)` repeats the
`TextLines` grouping and, for every `LLM` field with evidence (line items and tax lines
included), sets `Evidence.box` to the union of the cited lines' word boxes (text-layer units,
clamped to ≥ 0) and `Evidence.page` to the cited lines' page; the model's own box is never kept.
Markers are matched as `p{page}:l{line}` (case-insensitive, optional brackets); malformed or
unknown ones are ignored. When cited lines span pages, those on the model's `page` win, else the
first cited page. If no marker resolves, the box is cleared and the model's page kept. `RULE`
and `HUMAN` evidence and `lineRefs` are left as they are. The extract stage (M3.8) calls it on
the parsed result before storing it.

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

VAL-4 uses `GstinChecksum.isValid` from `common` (`dev.invoiceintel.common.checksum`, added in
M2.2), the same check the PII detector uses, so the two can't disagree.

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

`text_layer` (V4, M1.10): one row per Text run, unique `(document_id, run_no)`; `layer` is the
`TextLayer` JSON of §6.2. The current layer is the one of the latest `SUCCEEDED` Text run; the
pipeline reads it with `TextLayerStore.latest` (highest `run_no`: a failed run, or one superseded
while running, rolls its insert back with the stage transaction).

`stage_run` (V3, M1.4): `stage` is one of `TEXT`, `PII`, `EXTRACT`, `VALIDATE`; `status` is
`RUNNING`, `SUCCEEDED`, `FAILED` or `SUPERSEDED`; `ended_at` is null exactly while `RUNNING`;
`error_code` is set for `FAILED` and kept when a failed run is superseded. `(document_id, stage,
run_no)` is unique, and a partial unique index allows one `RUNNING` run per document and stage.
The current output of a stage is that of its latest `SUCCEEDED` run. V3 also limits
`document.failed_stage` to the same stage names.

`pii_span` and `pii_vault` (V5, M2.10b): `pii_span` has `run_no` (rows of every PII run are kept,
the current ones are those of the latest `SUCCEEDED` run) and names its offsets `span_start` and
`span_end` (`end` is an SQL keyword); `bbox` is `{x, y, w, h}` in the page's unit; `detector` and
`token` are `TEXT` (a merged span's detector joins rule names with `+`). `pii_vault` is keyed by
`token` only, shared by all documents, and rows are never updated.

`llm_call` (V6, M3.3): one row per model call, failed calls included; `prompt_version` is
`PromptRef.id()` (`name/v{n}`), `attempt` is 1, or 2 for the repair retry, `outcome` is
`SUCCEEDED`, `INVALID_OUTPUT` or `ERROR`, `cost_usd` is `NUMERIC(12,6)`; `stage` uses the
`stage_run` names; rows cascade-delete with their document. No prompt or response text.

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
`pipeline.*` (thresholds, concurrency), `pii.*` (`mode`, `detectors.{rules, address-block, ner,
local-llm}`, `ner.{model-dir, threshold}`, `local-llm.endpoint`; M2.9), `llm.models[]`,
`llm.max-concurrent-calls.<provider>`, `llm.default-max-concurrent-calls` (M3.3),
`storage.*`, `text.*` (DPI, OCR languages, `tessdata-path`, `min-text-chars`,
`max-concurrent-page-jobs`).

- Shared settings are in `app/src/main/resources/application.yaml`; each value that differs
  per environment reads an environment variable (`DB_URL`, `DB_USER`, `DB_PASSWORD`,
  `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET_RAW`, `S3_BUCKET_REDACTED`;
  NFR-12) with a default that points at the local compose stack. Defaults are never secrets;
  credentials default to empty, and blank S3 keys fail start-up like the admin password.
- `invoice-intel.text.tessdata-path` reads `TESSDATA_PATH`, default
  `/usr/share/tesseract-ocr/5/tessdata` (the container image and CI's Ubuntu package);
  `application-local.yaml` defaults it to Homebrew's `/opt/homebrew/share/tessdata`. Gradle sets
  `jna.library.path` to `/opt/homebrew/lib` for tests and `bootRun` when that folder exists.
- `application-local.yaml` binds the server to `127.0.0.1` and imports the git-ignored `.env`
  from the repository root (`spring.config.import: optional:file:.env[.properties]`; `bootRun`
  runs in the root). `.env` is read as a properties file, so values are unquoted `KEY=value`.
- `application-test.yaml`: Postgres comes from Testcontainers via `@ServiceConnection`
  (`TestcontainersConfiguration` in `app/src/integrationTest`).
- The `fly` profile is added in Phase 2 (`deployment.md`).
- The typed skeleton is one record, `dev.invoiceintel.app.config.InvoiceIntelProperties`
  (`storage`, `text`, `pii` so far), validated at start-up. A group moves into its own module
  when that module gets Spring wiring; `pipeline` is added by the task that uses it; `llm` binds to
  `extract`'s own `LlmProperties` (§6.5, M3.2). `pii` reads `PII_MODE` (default `pseudonymize`) and `NER_MODEL_DIR` (default empty);
  the cross-field checks (a layer on, NER model directory, loopback local LLM) run in
  `PiiSettings` when `Pii.toSettings()` is called at start-up (M2.10a, §6.3), and
  `TOKEN_KEYSET`/`VAULT_KEYSET` are read from the process environment then, so start-up fails
  without them. `app` depends on `pii` for these types (§4.1).
- NER model directory: `:pii:stageNerModel` copies the pinned model (ADR-15) to
  `pii/build/ner-model`. The Docker image has it at `/app/models/ner` (`ENV NER_MODEL_DIR`);
  `app`'s tests and `bootRun` get the staged copy through the system property
  `invoice-intel.pii.ner.model-dir`, with `ai.djl.offline=true` everywhere (ADR-16). Local runs
  export `TOKEN_KEYSET` and `VAULT_KEYSET` in the shell (`.env` is a Spring property source, not
  the process environment).
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

Measured 2026-10-28 (M2.8, macOS arm64): the chosen NER model (ADR-15) adds ~180 MB RSS after
loading and ~217 MB after inference on a 400-word page, inside the NER budget; the multilingual
candidate peaked at ~396 MB.

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
| ADR-15 | NER model `onnx-community/distilbert-NER-ONNX` at `3a19fe9404a4469d91aa3d551558a97f68872f67` (int8 `onnx/model_quantized.onnx`, 65,772,734 bytes, SHA-256 `9419a876…a4e210d8`; ONNX export of `dslim/distilbert-NER`, English CoNLL-03, labels PER/ORG/LOC/MISC), downloaded by Gradle from huggingface.co at the pinned commit with every file's SHA-256 checked, cached outside the repo (owner decision 2026-10-28, M2.8). Licence: Apache-2.0 per the base model card (`dslim/distilbert-NER`); the conversion repo declares none. Measured on synthetic Indian names (threshold 0.5, all-caps words title-cased): word recall 88.7%, whole-name recall 85.3%, precision 91.3%; RSS +217 MB after load and inference (macOS arm64) | `Xenova/distilbert-base-multilingual-cased-ner-hrl` at `c2a4dbf593c57f47004c5bc2d3770d311aee9c43` (int8 `onnx/model_quantized.onnx`, 135,359,829 bytes, SHA-256 `24a0b98f…b2edb986`; export of `Davlan/distilbert-base-multilingual-cased-ner-hrl`, AFL-3.0 per the base card, conversion repo declares none): word recall 81.0%, whole-name recall 76.3%, precision 96.6%, RSS +396 MB peak. Thresholds 0.3/0.15 are in the benchmark report; both pins are in `pii/build.gradle.kts` | Recall is the costly error (specs §6.3): the English model finds more of the synthetic Indian names, at half the size and inside the ~300 MB budget (§10.1); the multilingual model is more precise but misses more names and exceeds the budget. Neither reaches 99% alone; NER backs up the rule and address-block layers (R3). |
| ADR-16 | NER stays on in every environment: the Docker build stage downloads the pinned model (`:pii:stageNerModel`, SHA-256 checked) into the image at `/app/models/ner` (+~66 MB) and `app` tests/`bootRun` use the staged copy; PII beans are built at start-up (fail fast); CI and tests use throwaway Tink keysets, CI's built by `scripts/ci/stack-credentials.sh` with `openssl rand` (owner decision 2026-10-29, M2.10a) | Model-free image with `PII_NER=false` in `compose.ci.yaml` only; keysets from Tinkey in CI; building PII beans lazily on the first PII job | Turning NER off anywhere lowers recall (owner approval needed); a bundled model makes the image self-contained and smoke-tests the real start-up path. `openssl` is already on CI runners, so no new tool. Lazy beans would let a broken setup accept uploads that then fail in the PII stage. |
| ADR-17 | PII stage outputs (owner decision 2026-10-29, M2.10b): `pii_span` rows per run and a shared `pii_vault` (insert-if-absent) written only by `pii`'s `RedactionStore`; OCR pages masked on the stored page image preprocessed again; no table for redacted text, `RedactedContent` rebuilt later from text layer + spans + redacted pages | Storing the preprocessed image as another blob kind; a `redacted_text` table | Preprocessing is deterministic, so a second image per page and a `blob_ref` kind change aren't needed; one source of truth for spans avoids redacted text drifting from them. |

---

## 17. Open questions

| # | Question |
|---|----------|
| A1 | ~~Confirm Boot 4 compatibility of Spring AI, JobRunr, springdoc and DJL at scaffold time.~~ Resolved 2026-10-28 (M0.1): all compatible; see ADR-01. |
| A2 | ~~Which NER model (size vs Indian-name recall)? Benchmark two candidates on the PII label set.~~ Resolved 2026-10-28 (M2.8): `onnx-community/distilbert-NER-ONNX` (int8 DistilBERT, CoNLL-03), PERSON word recall 88.7% / precision 91.3% on synthetic Indian names vs 81.0% / 96.6% for the multilingual DistilBERT; see ADR-15. Re-measure on the owner's labelled set (M5). |
| A3 | Which LLMs to compare in the first eval (spec R6)? |
| A4 | Does Hindi text appear often enough to need the `hin` OCR language? |
| A5 | Is the vision strategy accurate enough on redacted images, given masked regions may confuse layout understanding? |
