# Invoice Intel — Branching, CI and Merge Gates

> Status: v0.1 · Last updated: 2026-10-28 · Implements `specs.md` NFR-9 and NFR-13.
>
> Workflows: `.github/workflows/ci.yml` (merge gate), `.github/workflows/nightly.yml`.
> Branch rules: `.github/rulesets/main.json`, applied with `scripts/github/apply-rulesets.sh`.

---

## 1. Trunk-based development

- `main` is the trunk and is always releasable.
- All changes go through a **short-lived branch** and a pull request. Branches live hours
  to a day or two, never a whole milestone.
- Branch name: `<type>/<task-id>-<short-desc>`, e.g. `feat/m1.3-upload-endpoint`,
  `docs/spec-v0.3`. Types match Conventional Commits.
- One plan task per PR; one PR per squash commit on `main`.
- Work that spans several PRs stays hidden behind configuration
  (`invoice-intel.features.*` flags) until it's complete, so `main` stays releasable.
- Releases are tags on `main` (`v1.0.0`); no release branches.

### 1.1 Rules on `main` (ruleset `main-trunk`)

| Rule | Setting |
|------|---------|
| Direct pushes | Blocked; changes only via pull request |
| Required approvals | 0 (solo project; the CI gate is the reviewer) |
| Required status check | `CI gate`, and the branch must be up to date with `main` |
| Merge method | Squash only; PR title becomes the commit message |
| History | Linear; force-push and branch deletion blocked |
| Review threads | Must be resolved before merge |
| Bypass | None, including the admin |
| Merged branches | Deleted automatically |

Repository security settings: Dependabot alerts and security updates, secret scanning and
secret-scanning push protection are on.

---

## 2. Merge gates (`ci.yml`)

All jobs report into **CI gate**, the single required check. A job that is skipped because
its prerequisite doesn't exist yet (no Gradle build before M0.2, no Dockerfile before M0.9,
no perf module before M0.15) counts as passed.

| Gate | Job | Fails when | Tooling |
|------|-----|-----------|---------|
| PR hygiene | `pr-hygiene` | PR title isn't Conventional Commits; branch name doesn't match §1 | action-semantic-pull-request |
| PII and secrets | `repo-guards` | Private data folders, `.env`, stray invoice/CSV files or keys are tracked; secrets or Indian PII patterns found | `scripts/ci/pii-guard.sh`, gitleaks + `.gitleaks.toml` |
| Workflow lint | `repo-guards` | Workflow files are invalid | actionlint |
| Format | `lint` | Code isn't formatted | Spotless + google-java-format |
| Unit tests | `unit-tests` | Any unit or ArchUnit test fails | JUnit 5, `./gradlew test` |
| Integration tests | `integration-tests` | Any integration test fails | Testcontainers, WireMock, `./gradlew integrationTest` |
| **Coverage** | `coverage` | Merged unit + integration **line coverage < 85%** | JaCoCo |
| Static analysis | `static-analysis` | SpotBugs or FindSecBugs findings | SpotBugs + FindSecBugs |
| SAST | `codeql` | CodeQL `security-extended` alerts at error level | CodeQL |
| Dependency vulnerabilities | `dependencies` | PR adds a dependency with a high/critical CVE or a GPL/AGPL license | Gradle dependency graph + dependency-review |
| Container | `container` | Dockerfile lint errors; HIGH/CRITICAL fixable CVEs in the image (OS packages and bundled JARs) | hadolint, Trivy |
| **Smoke test** | `smoke` | Stack doesn't start, or any smoke check fails (§4) | Docker Compose, `scripts/ci/smoke-test.sh` |
| **Performance** | `performance` | Any Gatling assertion fails (§5) | Gatling (Java DSL), `perf` module |

Unit, integration and merged coverage are each shown in the job summary; the merged figure
is the gate. Reports, test results, smoke results and Gatling reports are uploaded as run
artifacts for 14 days.

### 2.1 Nightly (`nightly.yml`, not a merge gate)

| Check | What |
|-------|------|
| Mutation tests | PIT on `pii` and `validate`, the modules where a weak test is most costly |
| Image rescan | Trivy scan of a fresh `main` image for newly published CVEs; results go to the Security tab |

### 2.2 Other automation

- **Dependabot**: weekly Gradle, GitHub Actions and Docker updates, grouped. Its PRs go
  through the same gate.
- **Action pinning**: every action is pinned to a commit SHA with the version in a comment;
  Dependabot updates both.
- **CODEOWNERS** and a **PR template** with the PII checklist.

---

## 3. Build contract (implemented in plan M0)

CI calls these Gradle tasks and paths. Changing a name means changing `ci.yml` in the same PR.

| Task / path | Purpose |
|-------------|---------|
| `spotlessCheck` | Formatting |
| `test` | Unit tests (`src/test`), including ArchUnit |
| `integrationTest` | Integration tests in a separate `src/integrationTest` source set |
| `jacocoUnitReport` → `build/reports/jacoco/jacocoUnitReport/jacocoUnitReport.csv` | Aggregated unit coverage |
| `jacocoIntegrationReport` → `build/reports/jacoco/jacocoIntegrationReport/jacocoIntegrationReport.csv` | Aggregated integration coverage |
| `jacocoMergedReport` → `build/reports/jacoco/jacocoMergedReport/jacocoMergedReport.csv` | Merged coverage from all `*.exec` files |
| `jacocoMergedCoverageVerification -PminCoverage=85` | Fails the build below the line-coverage minimum |
| `spotbugsMain` | SpotBugs + FindSecBugs |
| `:pii:pitest`, `:validate:pitest` | Mutation tests (nightly) |
| `:perf:gatlingRun -Dperf.baseUrl=… -Dperf.profile=ci` | Performance simulations |
| `compose.ci.yaml` | Override that adds the app container (from `invoice-intel:ci`) and a WireMock LLM stub, with throwaway keys generated per run by `scripts/ci/stack-credentials.sh` |
| `Dockerfile` | Image `invoice-intel:ci` built by the `container` job |

Coverage exclusions (kept small and listed in the build): generated code, `*Application`
main classes, configuration-properties records. Excluding anything else needs an entry here
with a reason.

---

## 4. Smoke test

Runs `scripts/ci/smoke-test.sh` against the full stack (app, Postgres, SeaweedFS, WireMock LLM)
using synthetic fixtures. API calls use HTTP Basic with a password generated per run.

| Check | Active from |
|-------|-------------|
| `/actuator/health` is `UP` | M0.6 |
| Anonymous `/api/documents` gets `401` | M0.7 |
| Admin credentials accepted | M0.7 |
| Upload synthetic invoice returns an ID | M1.3 (when the smoke fixture exists) |
| Duplicate upload returns the same ID | M1.3 |
| Document reaches an accepted status within 120 s, never `FAILED` | M1.10 onward; until M4.7 any status from `TEXT_READY` onward is accepted, then `SMOKE_EXPECT_STATUSES` in `ci.yml` narrows to `AUTO_APPROVED,NEEDS_REVIEW` |

---

## 5. Performance engineering

**In CI (gate):** Gatling simulations in the `perf` module run against the CI stack with the
WireMock LLM stub (fixed latency 800 ms to imitate a real model). Assertions:

| Scenario | Load | Assertion |
|----------|------|-----------|
| Upload + read API | 10 users, 60 s | p95 < 500 ms, error rate < 1% |
| End-to-end pipeline (digital PDF fixture) | 20 documents | p95 time to decision < 20 s (NFR-3), 0 failures |
| Review queue/list endpoints | 10 users, 60 s | p95 < 300 ms |

Thresholds live in `perf/src/gatling/resources/thresholds.conf`, so they're tuned in one
place. Tightening is free; loosening needs a note in the PR description.

**Outside CI:**
- Memory: the CI stack runs the app with a 2 GB container limit, so an out-of-memory
  regression fails smoke or performance (NFR-10).
- Real-model latency and cost are measured by the eval harness (EVAL-2, NFR-4), not in CI,
  because CI never calls a real LLM.

---

## 6. Local equivalents

```bash
./gradlew build                      # format, unit, integration, coverage verification, SpotBugs
scripts/ci/pii-guard.sh              # PII guard
docker build -t invoice-intel:ci .
eval "$(scripts/ci/stack-credentials.sh)"   # throwaway DB, S3 and admin credentials
docker compose -f compose.yaml -f compose.ci.yaml up -d --wait
scripts/ci/smoke-test.sh http://127.0.0.1:8080
./gradlew :perf:gatlingRun -Dperf.baseUrl=http://127.0.0.1:8080 -Dperf.profile=ci
```
