---
paths:
  - "**/*.java"
  - "**/src/test/**"
---

# Testing rules

Implements NFR-9 and architecture.md §13.

## Required tests

- Every PII detector, checksum (Verhoeff, GSTIN mod-36), validation rule, token generator and
  the amount-in-words parser gets unit tests with both passing and failing cases.
- Every bug fix starts with a failing test that reproduces it.
- Every new module dependency or LLM-access path is covered by the ArchUnit rules in
  architecture.md §4.1 and §6.4. Don't weaken or `@Disable` those rules to make a build pass.
- Redaction changes must keep the "redaction proof" test green: re-extract and OCR the
  redacted PDF and assert no known PII string is present (PII-4).

## Fixtures

- Use **synthetic** invoices only: generated from templates with fake names, fake addresses
  and valid-format fake GSTINs/PANs. Never real invoices or text copied from them.
- Fixtures live in `src/test/resources/fixtures/` of the module that owns them.
- Keep a small generator for synthetic invoices rather than hand-editing many files.

## External systems

- Postgres and MinIO via Testcontainers; no shared local databases in tests.
- **No real LLM calls in tests.** Stub providers with WireMock; record stub responses by
  hand from the schema, not from real invoice runs.
- Tests must pass offline (apart from pulling container images).

## Style

- JUnit 5 + AssertJ. One behaviour per test; name tests `method_condition_expectedResult`
  or with `@DisplayName` in plain language.
- Prefer parameterized tests for rule tables (tax rates, state codes, checksums).
- No sleeps; use Awaitility for async pipeline assertions.

## Before ticking a task

`./gradlew build` passes with no skipped tests that weren't skipped before.
