# PII rules

These rules apply to every task. They implement `docs/specs.md` §6 and NFR-1. When a rule
here conflicts with convenience, the rule wins. If a task seems to need breaking one, stop
and ask the owner.

## What counts as PII here

Buyer name, street address, PIN code, phone, email, buyer PAN/Aadhaar/GSTIN, and real order
and invoice numbers. Buyer **state**, seller details, HSN codes, amounts, dates and product
descriptions are not PII (spec §6.1).

## Claude's own access

- Never read, list contents of, `cat`, `grep`, `head`, open or summarize files under
  `data/raw/`, `data/order-history/`, `data/labels/` or `data/vault/`. These hold real personal
  data, and reading them sends it to a remote model.
- Never query `text_layer`, `pii_vault`, `order_history_item` or raw-bucket objects and print
  the results. Counts and IDs are fine.
- To debug extraction or PII on a real document, ask the owner to run the command and share
  only redacted output, or reproduce the problem with a synthetic fixture.
- Never copy a real invoice, or text from one, into tests, docs, prompts, commit messages or
  chat.

## Code rules

- Remote LLM calls go only through `LlmGateway`, which accepts only `RedactedContent`
  (architecture.md §6.4). Don't add overloads taking `String`, `byte[]`, `TextLayer` or blob
  keys, and don't create Spring AI clients outside the `extract` module.
- `RedactedContent` constructors stay package-private in the `pii` module.
- Nothing classifies, summarizes or "pre-checks" a document with an LLM before the PII stage
  succeeds. Pre-PII classification is rule-based only (ADR-09).
- Logs, metrics, exceptions and API errors contain document IDs, stages and codes only, never
  field values or text snippets (PII-8). Don't `toString()` domain objects that hold text
  into logs.
- No API endpoint returns raw text, original files, unredacted page images or vault values.
- Vault and token keys come only from environment/secrets, never from code, config files or
  the database (PII-7).
- Redacted PDFs are rebuilt from masked images; never "redact" by drawing over live text
  (PII-4, ADR-05).
- Bias detectors toward recall. A change that lowers PII recall on the eval set needs the
  owner's approval.

## Data and Git

- Only `data/samples-redacted/` and `eval-reports/` may contain anything derived from real
  invoices, and only irreversibly redacted output or aggregate metrics.
- Before every commit, check `git status` for files under ignored data folders or anything
  that looks like an invoice, export or key file. If unsure, don't commit; ask.
