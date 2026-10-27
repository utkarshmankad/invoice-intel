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
 Eval CLI ───────────► │                               ├──► Object storage (MinIO / R2)                             │
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
| Object storage | AWS SDK v2 S3 client | MinIO locally, R2 in Phase 2 (NFR-11) |
| Web / API | Spring Web MVC, springdoc-openapi, Bean Validation | G6 |
| Review UI | Thymeleaf + HTMX | REV-1 to REV-4 |
| Security | Spring Security, form login, single admin user (v1) | NFR-2 |
| Observability | Micrometer, Actuator, structured JSON logs (Logback), OpenTelemetry optional | NFR-6 |
| CLI | picocli (eval harness) | EVAL-1 |
| Tests | JUnit 5, AssertJ, Testcontainers (Postgres, MinIO), WireMock (LLM stubs), ArchUnit | NFR-9 |
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
Page images (PNG) are stored under `raw/{documentId}/pages/{n}.png` for the vision
strategy and review UI. They contain PII and live in the raw bucket only.

**Classification (TXT-4)** is rule-based at this stage, because no LLM may see the
document before PII removal: keyword and pattern scoring ("Tax Invoice", "Credit Note",
"Bill of Supply", GSTIN present, invoice date vs 2017-07-01). The extraction LLM later
returns its own `documentClass`; a mismatch lowers confidence.

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

Both buckets are private. Locally they are two MinIO buckets.

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
| `local` | Compose Postgres | Compose MinIO | Remote APIs via keys in `.env` | Binds to `127.0.0.1` |
| `test` | Testcontainers | Testcontainers MinIO | WireMock stubs | No network LLM calls |
| `fly` | Managed Postgres | R2 | Remote APIs via Fly secrets | See `deployment.md` |

Typed config with `@ConfigurationProperties` under the `invoice-intel.*` prefix:
`pipeline.*` (thresholds, concurrency), `pii.*` (detector toggles, mode), `llm.models[]`,
`storage.*`, `text.*` (DPI, OCR languages).

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
- All services bind to `127.0.0.1` locally (NFR-2).
- Secrets only from env / secret store (NFR-12). `.env` is git-ignored; `.env.example` is committed.
- Logging: a Logback filter masks anything matching the PII regex set as a backstop; code
  logs only IDs, stages and codes (PII-8).
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
| Integration | Full pipeline with Postgres + MinIO containers and WireMock LLM | Testcontainers, WireMock |
| Architecture | Module dependency rules, PII gate rules (§6.4) | ArchUnit |
| Eval smoke | 5 redacted sample docs through the pipeline in CI with stubbed LLM; checks wiring, not accuracy | eval module |
| Smoke | Full container stack; health, auth, upload, dedupe, pipeline status | `scripts/ci/smoke-test.sh` |
| Performance | API latency and end-to-end time to decision under load, stubbed LLM | Gatling, `perf` module |

Unit tests live in `src/test`, integration tests in a separate `src/integrationTest` source
set with its own Gradle task. Merge gates, thresholds and the Gradle task names CI depends on
are defined in `docs/ci.md`.

Test fixtures use **synthetic** invoices generated from a template (fake names, valid-format
fake GSTINs). Real invoices never enter the test tree.

---

## 14. Local development (NFR-7)

`compose.yaml` services, all bound to `127.0.0.1`:

| Service | Image | Port |
|---------|-------|------|
| postgres | `postgres:16` | 5432 |
| minio | `minio/minio` | 9000 (API), 9001 (console) |
| app | built from `Dockerfile` (optional; usually run from IDE / `bootRun`) | 8080 |

`compose.ci.yaml` overrides this for CI: adds the app container (2 GB memory limit) and a
WireMock container standing in for the LLM provider, with throwaway keys.

One command: `docker compose up -d && ./gradlew :app:bootRun --args='--spring.profiles.active=local'`
(wrapped in a `Makefile` or script). Tesseract is installed on the Mac via Homebrew for
`bootRun`, and inside the image via `apt`.

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

---

## 17. Open questions

| # | Question |
|---|----------|
| A1 | ~~Confirm Boot 4 compatibility of Spring AI, JobRunr, springdoc and DJL at scaffold time.~~ Resolved 2026-10-28 (M0.1): all compatible; see ADR-01. |
| A2 | Which NER model (size vs Indian-name recall)? Benchmark two candidates on the PII label set. |
| A3 | Which LLMs to compare in the first eval (spec R6)? |
| A4 | Does Hindi text appear often enough to need the `hin` OCR language? |
| A5 | Is the vision strategy accurate enough on redacted images, given masked regions may confuse layout understanding? |
