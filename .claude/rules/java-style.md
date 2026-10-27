---
paths:
  - "**/*.java"
  - "**/*.kts"
  - "**/src/main/resources/**"
---

# Java and Spring conventions

## Language

- Java 25 features are welcome where they make code clearer: records, sealed interfaces,
  pattern matching, switch expressions, text blocks.
- DTOs, value objects and extraction schema types are **records**. No Lombok.
- Money is `BigDecimal` with scale 2 and `RoundingMode.HALF_UP`; compare with `compareTo`,
  never `equals`. Never use `double`/`float` for amounts or tax rates.
- Dates are `LocalDate`; timestamps are `Instant`.
- Return `Optional` instead of `null` from lookups. Don't use `Optional` for fields or
  parameters.
- Prefer small, immutable types and pure functions, especially in `validate` and `pii`.
- Typed IDs (`DocumentId`, `ExtractionId`) instead of raw `UUID`s across module boundaries.

## Spring

- Constructor injection only; no field injection, no `@Autowired` on fields.
- Configuration through `@ConfigurationProperties` records under `invoice-intel.*`, with
  validation annotations. No `@Value` scattered through classes.
- Controllers stay thin: validate input, call a service, map to a response record.
- Errors returned as RFC 9457 problem details via a single `@RestControllerAdvice`.
- Transactions on service methods, not controllers or repositories.
- Use virtual threads for I/O-bound work (LLM, storage); cap CPU-heavy work (rendering,
  OCR) with a bounded executor (architecture.md §10.1).

## Structure

- Package by module and feature: `dev.invoiceintel.<module>.<feature>`.
- Public API of a module lives in its root package; internals in `internal` subpackages.
- Interfaces only where there are (or will soon be) multiple implementations: detectors,
  validation rules, extraction strategies, blob store.
- Validation rules carry the spec ID they implement (`id()` returns `"VAL-2"`).
- Prompts live in `extract/src/main/resources/prompts/{name}/v{n}.st`. Never edit an existing
  version; add a new one.

## Database

- Every schema change is a new Flyway migration `V{n}__{description}.sql`. Never edit an
  applied migration.
- JSONB columns map to records; keep the record the single definition of the shape.

## Logging

- SLF4J with structured key-value fields (`documentId`, `stage`, `runNo`).
- No field values or document text in logs (see `pii.md`).

## Formatting

- Follow the formatter configured in the build (Spotless + google-java-format once the
  skeleton exists). Don't reformat files you aren't otherwise changing.
- Comments explain *why*, not *what*. Reference spec IDs where a rule comes from the spec.
