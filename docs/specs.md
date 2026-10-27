# Invoice Intel — Product Specification

> Status: Draft v0.3 · Owner: Utkarsh Mankad · Last updated: 2026-10-28
>
> This document defines **what** the system does and **why**. It deliberately avoids
> implementation detail — that lives in `architecture.md`. Execution order lives in `plan.md`.
> The future cloud deployment plan lives in `deployment.md`.

---

## 1. Problem statement

Freelancers and small businesses in India receive purchase invoices in dozens of
seller-specific layouts. Getting them into a ledger means manual data entry, which is slow
and error-prone, and mistakes in GST fields (seller GSTIN, tax split, place of supply)
cost real money through missed or rejected Input Tax Credit (ITC).

**Invoice Intel** ingests purchase invoices (digital PDF, scanned PDF, photo), removes
personal data, extracts structured invoice data with an LLM, validates it against
arithmetic and GST rules, reconciles it against order records, and routes only
low-confidence documents to a human reviewer.

### 1.1 Why this dataset

The v1 corpus is the owner's personal **Amazon.in** purchase history (2013 onward):

- Heterogeneous layouts: Amazon Retail vs. hundreds of third-party sellers.
- Two tax regimes: pre-GST (VAT/CST, before 1 July 2017) and GST era.
- Multiple document types: tax invoices, credit notes (returns), multi-seller orders
  producing multiple invoices per order.
- Free ground truth: Amazon's "Request Your Data" export provides an order-history CSV
  that serves as labels for automated evaluation.

Amazon is the **benchmark corpus**, not the product. The system must not depend on
Amazon-specific layouts beyond what is configurable.

---

## 2. Goals and non-goals

### 2.1 Goals (v1)

| ID | Goal |
|----|------|
| G1 | Extract a defined invoice schema (§5) from Amazon.in invoices with measured field-level accuracy. |
| G2 | Guarantee no raw PII is sent to any third-party (cloud) LLM or committed to the public repository. |
| G3 | Validate every extraction with deterministic rules and produce a confidence decision (auto-approve vs. review). |
| G4 | Reconcile extracted invoices against the Amazon order-history export. |
| G5 | Provide a reproducible evaluation harness comparing extraction strategies on accuracy, cost and latency. |
| G6 | Expose everything through a documented REST API and a minimal review UI. |

### 2.2 Non-goals (v1)

- Multi-tenant SaaS, billing, or user sign-up.
- Filing GST returns or integrating with the GST portal (GSTR-2B reconciliation is a v2 candidate).
- Accounting-software export (Tally, Zoho Books) — v2 candidate.
- Mobile app.
- Bulk scraping of Amazon. Invoices are downloaded by the owner from their own account.
- Non-Indian tax regimes.

---

## 3. Users and use cases

| Persona | Description |
|---------|-------------|
| **Owner / Operator** | Single user (the developer). Uploads invoices, reviews flagged items, runs evaluations. |
| **Reviewer** | Same person in v1, acting through the review UI to correct low-confidence fields. |
| **Portfolio viewer** | Recruiter or engineer reading the repo, README and eval reports. Sees only redacted data. |

### 3.1 Core use cases

- **UC1 — Ingest:** Upload one or many invoice files; system deduplicates and queues them.
- **UC2 — Process:** System classifies, extracts text (OCR if needed), removes PII, extracts fields, validates and scores.
- **UC3 — Review:** Reviewer sees flagged invoices side-by-side with the (redacted) document, corrects fields, approves.
- **UC4 — Reconcile:** System matches invoices to order-history rows and reports matches, mismatches and missing invoices.
- **UC5 — Evaluate:** Operator runs the eval harness against a labelled set and gets an accuracy/cost/latency report.
- **UC6 — Publish sample:** Operator exports an irreversibly redacted sample set safe for the public repo.

---

## 4. Input data

### 4.1 Invoice documents

| Type | Description | Expected share |
|------|-------------|----------------|
| Digital PDF | Text layer present (most Amazon.in invoices) | High |
| Scanned / image PDF | No text layer | Low (also simulated by rasterising + degrading digital PDFs; must be labelled synthetic) |
| Photo (JPG/PNG) | Phone photo of a printed invoice | Low |

Document classes: `TAX_INVOICE`, `CREDIT_NOTE`, `BILL_OF_SUPPLY`, `PRE_GST_INVOICE`, `OTHER`.

### 4.2 Order-history export (ground truth)

Obtained through Amazon.in "Request Your Data". Expected to contain per-item rows with
order ID, order date, product name, ASIN, quantity, unit price, tax, shipping and total.
Exact column names must be verified on receipt and mapped in configuration, not hard-coded.

### 4.3 Data handling constraints

- Raw invoices live **outside** version control (`data/raw/` is git-ignored).
- Only irreversibly redacted documents may be committed (`data/samples-redacted/`).
- Target corpus for v1: **150–300 invoices** spanning 2013–2026, Amazon Retail and third-party sellers, pre- and post-GST.

---

## 5. Extraction schema (v1)

### 5.1 Header fields

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `documentClass` | enum | yes | See §4.1 |
| `invoiceNumber` | string | yes | Pseudonymized consistently (§6) |
| `invoiceDate` | date | yes | |
| `orderNumber` | string | yes | Amazon format `NNN-NNNNNNN-NNNNNNN`; join key for reconciliation |
| `orderDate` | date | no | |
| `sellerName` | string | yes | Business data — not redacted |
| `sellerGstin` | string | GST era | 15-char GSTIN, checksum-validated |
| `sellerPan` | string | no | Seller PAN is business data |
| `sellerAddress` | string | no | |
| `placeOfSupply` | string (state) | GST era | Needed for IGST vs CGST+SGST rule |
| `placeOfDelivery` | string (state) | no | |
| `buyerState` | string (state) | yes | Retained after PII removal |
| `reverseCharge` | boolean | no | |
| `currency` | enum | yes | Always INR in v1 |
| `subtotal` | decimal | yes | Sum of net amounts |
| `totalTax` | decimal | yes | |
| `grandTotal` | decimal | yes | |
| `amountInWords` | string | no | Used as a cross-check |
| `originalInvoiceRef` | string | credit notes | Link from credit note to original invoice |

### 5.2 Line items

| Field | Type | Required |
|-------|------|----------|
| `lineNo` | int | yes |
| `description` | string | yes |
| `hsnSac` | string | GST era |
| `unitPrice` | decimal | yes |
| `quantity` | decimal | yes |
| `discount` | decimal | no |
| `netAmount` | decimal | yes |
| `taxLines[]` | list of `{type: CGST\|SGST\|IGST\|UTGST\|CESS\|VAT\|CST, rate, amount}` | yes |
| `lineTotal` | decimal | yes |

Shipping/convenience charges are represented as line items with a `chargeType` flag.

### 5.3 Per-field metadata

Every extracted field carries: `value`, `confidence` (0–1), `source` (`LLM`, `RULE`,
`HUMAN`), and optional `evidence` (page + bounding box or text span).

---

## 6. PII protection requirements

### 6.1 Entities

| Entity | Action (pseudonymize mode) | Action (redact mode) |
|--------|----------------------------|----------------------|
| Buyer name (billing / shipping) | `PERSON_n` token | Removed |
| Street address lines | `ADDRESS_n` token | Removed |
| PIN code | Removed | Removed |
| Buyer **state** | **Kept** | **Kept** |
| Phone number | `PHONE_n` | Removed |
| Email | `EMAIL_n` | Removed |
| Buyer PAN / Aadhaar | `ID_n` | Removed |
| Buyer GSTIN (if present) | `GSTIN_n` | Removed |
| Order number, invoice number | Consistent token (same real value → same token across all documents) | Consistent token |

Seller name, seller GSTIN, seller PAN, seller address, HSN codes, amounts, dates and
product descriptions are **not** PII for this system and are preserved.

### 6.2 Functional requirements

| ID | Requirement |
|----|-------------|
| PII-1 | PII removal runs **before** any text or image is sent to a remote LLM API. In v1 every extraction LLM is a remote API, so no extraction call ever sees raw PII. A document with failed PII processing must never reach a remote model. |
| PII-2 | Detection is layered: (a) rules and checksums, (b) local NER model, (c) optional local LLM. Each layer can be toggled and evaluated independently. |
| PII-3 | Two modes: **pseudonymize** (reversible, mapping stored encrypted) and **redact** (irreversible). |
| PII-4 | PDF redaction removes the underlying text from the content stream; drawing a box over text that is still present is not acceptable. |
| PII-5 | Image redaction uses OCR word coordinates to mask regions. |
| PII-6 | A post-extraction leak check scans LLM output for known real PII values of that document; any hit fails the document. |
| PII-7 | The pseudonym vault is encrypted at rest and is never exported, logged or committed. |
| PII-8 | Logs and metrics must never contain raw PII. |

### 6.3 PII acceptance criteria

- PII **recall ≥ 99%** on a hand-labelled set of ≥ 50 invoices (missed PII is the costly error).
- PII precision ≥ 90% (over-redaction must not destroy extraction-relevant fields).
- **Zero** leaks detected by PII-6 across the evaluation corpus.

---

## 7. Functional requirements

### 7.1 Ingestion
| ID | Requirement |
|----|-------------|
| ING-1 | Accept PDF, JPG, PNG via REST upload (single and batch). |
| ING-2 | Deduplicate by content hash; re-upload returns the existing document ID. |
| ING-3 | Detect file type by content, not extension. |
| ING-4 | Persist original files in a private store, separate from redacted artifacts. |

### 7.2 Classification and text extraction
| ID | Requirement |
|----|-------------|
| TXT-1 | Detect whether a usable text layer exists; route to OCR otherwise. |
| TXT-2 | Produce text with positional information (page, coordinates) for both paths. |
| TXT-3 | Apply image preprocessing (deskew, binarize, denoise) before OCR. |
| TXT-4 | Classify the document into a `documentClass` (§4.1). |

### 7.3 Extraction
| ID | Requirement |
|----|-------------|
| EXT-1 | Extract the §5 schema as typed structured output, not free text. |
| EXT-2 | Support at least two strategies: (a) text → LLM, (b) page image → vision LLM. Strategy is selectable per run. |
| EXT-3 | Model provider is configurable (cloud and local) without code changes. |
| EXT-4 | Record per-call model, prompt version, tokens, cost and latency. |
| EXT-5 | Prompts are versioned files, not inline strings. |

### 7.4 Validation and confidence
| ID | Requirement |
|----|-------------|
| VAL-1 | Σ line `netAmount` = `subtotal` (tolerance ±₹1). |
| VAL-2 | For each tax line, `rate × base ≈ amount` (tolerance ±₹1). |
| VAL-3 | `subtotal + totalTax (+ charges) ≈ grandTotal`. |
| VAL-4 | GSTIN format and checksum valid; state code in GSTIN consistent with seller state. |
| VAL-5 | Intra-state supply ⇒ CGST + SGST; inter-state ⇒ IGST (based on seller state vs place of supply). |
| VAL-6 | Invoices dated before 2017-07-01 must not carry GST fields; after, must not carry VAT/CST. |
| VAL-7 | `amountInWords`, when present, matches `grandTotal`. |
| VAL-8 | Each document receives a decision: `AUTO_APPROVED`, `NEEDS_REVIEW`, or `FAILED`, with reasons. |

### 7.5 Reconciliation
| ID | Requirement |
|----|-------------|
| REC-1 | Import the order-history export via a configurable column mapping. |
| REC-2 | Match invoices to orders by order number, then verify amount and date. |
| REC-3 | Report: matched, amount mismatch, invoice without order, order without invoice. |
| REC-4 | Handle one order → many invoices (multi-seller) and credit notes against orders. |

### 7.6 Review
| ID | Requirement |
|----|-------------|
| REV-1 | List documents in `NEEDS_REVIEW` with reasons. |
| REV-2 | Show redacted document alongside extracted fields with evidence highlighting. |
| REV-3 | Allow field corrections; corrections are stored with `source = HUMAN` and an audit trail. |
| REV-4 | Corrected documents can be exported as new labelled examples for evaluation. |

### 7.7 Evaluation
| ID | Requirement |
|----|-------------|
| EVAL-1 | CLI runs a pipeline configuration over a labelled set and emits Markdown + CSV reports. |
| EVAL-2 | Metrics: per-field exact-match accuracy, line-item F1, document-level straight-through rate, PII recall/precision, cost per document, p50/p95 latency. |
| EVAL-3 | Ground truth sources: order-history CSV (automatic) + hand-labelled set (GSTIN, HSN, tax split, PII spans). |
| EVAL-4 | Runs are reproducible: config, prompt versions and model IDs are recorded with results. |

---

## 8. Non-functional requirements

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-1 | Privacy | All PII rules in §6 are hard requirements, not best-effort. |
| NFR-2 | Security | API and UI require authentication; raw files and vault are not reachable publicly. Locally, services bind to `localhost` only. |
| NFR-3 | Performance | Digital-PDF invoice processed end-to-end in p95 ≤ 20 s (excluding queue wait). |
| NFR-4 | Cost | Average LLM cost ≤ ₹1 per invoice for the default strategy; reported per run. |
| NFR-5 | Reliability | Pipeline stages are idempotent and retryable; a failed stage does not lose the document. |
| NFR-6 | Observability | Per-stage metrics (latency, errors, tokens, cost) and structured logs with document IDs only. |
| NFR-7 | Reproducibility | Whole stack starts locally with one command. |
| NFR-8 | Portability | No hard dependency on a single LLM vendor. |
| NFR-9 | Quality | Automated tests for every validation and PII rule; CI runs tests and an eval smoke set. |
| NFR-10 | Deployability | The app runs as a single container whose memory use stays ≤ 2 GB, so it can move to a small cloud machine unchanged. |
| NFR-11 | Deployability | File storage goes through an S3-compatible interface (MinIO locally; S3-compatible object storage in the cloud). |
| NFR-12 | Deployability | All environment-specific settings (DB URL, storage endpoint, LLM keys) come from environment variables or a secrets store; no secrets in the repository. |
| NFR-13 | Quality gates | Changes reach `main` only through a pull request that passes CI: unit tests, integration tests, merged line coverage ≥ 85%, security checks (SAST, dependency and container vulnerabilities, secret and PII scanning), smoke test and performance test. Gates and thresholds are defined in `ci.md`. |

### 8.1 Technical constraints

- Backend: Java (LTS) + Spring Boot. No Python services in v1.
- Details of the stack are specified in `architecture.md`.

### 8.2 Deployment phases

| Phase | Where it runs | LLM | Status |
|-------|---------------|-----|--------|
| **Phase 1 (v1)** | Developer laptop (macOS). App, Postgres and MinIO via Docker Compose. | Remote LLM APIs, called from the laptop. | **Active** |
| **Phase 2** | Fly.io machine for the app; managed free-tier Postgres; S3-compatible object storage. Target infra cost ≤ $15/month, excluding LLM spend. | Same remote LLM APIs. | **Planned** — see `deployment.md` |

Phase 1 must not make choices that block Phase 2 (NFR-10 to NFR-12).

---

## 9. Success metrics (v1 definition of done)

| Metric | Target |
|--------|--------|
| Header field accuracy (digital PDFs) | ≥ 95% |
| Line-item F1 (digital PDFs) | ≥ 90% |
| Header field accuracy (scanned/photo) | ≥ 85% |
| Straight-through (auto-approved and correct) rate | ≥ 70% |
| False auto-approval rate (auto-approved but wrong) | ≤ 2% |
| Reconciliation match rate | ≥ 95% of invoices matched to an order |
| PII recall | ≥ 99%, zero leaks |
| Avg cost per invoice | ≤ ₹1 |

Targets are initial hypotheses; they are revised after the first baseline eval and the
revision is recorded in this document.

---

## 10. Deliverables

1. Running system (API, pipeline, review UI) via one-command local start.
2. Evaluation report comparing at least two extraction strategies and two models.
3. Public redacted sample set (≥ 20 invoices) with expected outputs.
4. README written as a case study: problem, approach, results, trade-offs, what failed.

---

## 11. Risks and open questions

| # | Item | Type |
|---|------|------|
| R1 | Old (2013–2016) invoices may be unavailable or in very different formats. | Risk |
| R2 | Real scanned invoices are scarce; synthetic degradation may overstate OCR quality. | Risk |
| R3 | NER models trained on Western names may under-detect Indian names; rules on address-block anchors must compensate. | Risk |
| R4 | Order-history export columns and granularity unknown until received. | Open |
| R5 | Does the export include per-seller invoice splits, or only per-order totals? | Open |
| R6 | Which cloud LLM(s) to benchmark, and budget for eval runs. | Open |
| R7 | Is a local vision model good enough to make an all-local (zero cloud) mode viable? | Open |
| R8 | Phase 2: Fly.io auto-stop may stop a machine while background extraction jobs are running. | Risk |
| R9 | Phase 2: free-tier database and storage limits (pausing when idle, size caps) may change or be exceeded. | Risk |

---

## 12. Glossary

- **GSTIN** — 15-character GST Identification Number; first two digits are the state code.
- **HSN/SAC** — Harmonized System / Services Accounting Codes classifying goods and services.
- **CGST/SGST/IGST** — Central/State/Integrated GST. Intra-state supply → CGST+SGST; inter-state → IGST.
- **ITC** — Input Tax Credit, GST paid on purchases that a registered business can offset.
- **Straight-through rate** — share of documents processed with no human touch.
- **Pseudonymization** — replacing PII with consistent reversible tokens; **redaction** — irreversible removal.

---

## 13. Revision history

| Version | Date | Change |
|---------|------|--------|
| 0.1 | 2026-10-28 | Initial draft. |
| 0.2 | 2026-10-28 | Local-first execution with remote LLM APIs (§8.2). Fly.io deployment planned as Phase 2. PII-1 tightened to cover all remote LLM APIs. Added NFR-10 to NFR-12 and risks R8, R9. |
| 0.3 | 2026-10-28 | Added NFR-13: trunk-based development with CI merge gates (coverage ≥ 85%, unit, integration, security, smoke, performance), requested by the owner. |
