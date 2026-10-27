# Invoice Intel — Deployment Plan

> Status: **Planned (Phase 2)** · Last updated: 2026-10-28
>
> v1 runs on the developer laptop (see `specs.md` §8.2). This document records how the
> system moves to Fly.io later, so Phase 1 decisions stay compatible with it.
> Prices and platform limits below were checked in October 2026 and change often —
> re-check them before deploying.

---

## 1. Phases at a glance

| | Phase 1 — Local (active) | Phase 2 — Fly.io (planned) |
|---|---|---|
| App | Spring Boot container via Docker Compose | Same image on one Fly.io machine |
| Database | Postgres container | Managed free-tier Postgres (Supabase or Neon) |
| File storage | SeaweedFS container (S3 API, architecture ADR-11) | S3-compatible object storage (Cloudflare R2) |
| Background jobs | JobRunr, Postgres-backed | Same |
| LLM | Remote APIs | Same remote APIs |
| Secrets | `.env` file (git-ignored) | `fly secrets` |
| Access | `localhost` only | HTTPS + authentication |

The only differences between phases are **configuration**: a `local` and a `fly` Spring
profile, plus environment variables. No code changes.

---

## 2. Target topology (Phase 2)

```
            HTTPS (auth required)
 Browser ───────────────────────────►  Fly.io machine (shared-cpu-1x, 2 GB, auto-stop)
                                        └─ Spring Boot app + JobRunr workers
                                              │            │              │
                                    JDBC/TLS  │            │ S3 API/TLS   │ HTTPS
                                              ▼            ▼              ▼
                                  Managed Postgres   Object storage    LLM APIs
                                  (Supabase/Neon)    (R2 buckets)      (redacted text only)
                                  - metadata          - raw-private
                                  - extractions       - redacted
                                  - encrypted vault
                                  - job queue
```

Region: Fly.io Mumbai (`bom`) if available on your account; otherwise Singapore (`sin`).
Check with `fly platform regions`. Put the database in the closest region its provider offers.

---

## 3. Components and cost estimate

| Component | Choice | Est. monthly cost |
|-----------|--------|-------------------|
| App machine | Fly.io shared-cpu-1x, 2 GB RAM, auto-stop | ~$3–13 (low end when mostly idle; ~$11–13 if always on) |
| Database | Supabase or Neon free tier | $0 |
| Object storage | Cloudflare R2 free tier (10 GB) | $0 |
| TLS + domain | Fly-provided `*.fly.dev` certificate | $0 |
| **Infra total** | | **~$3–13** (budget cap $15) |
| LLM APIs | Pay per use | Separate; ~₹1 per invoice target (NFR-4) |

Set a spending alert or limit on every paid account before the first deploy.

---

## 4. Prerequisites

- [ ] Phase 1 meets NFR-10 to NFR-12 (≤ 2 GB memory, S3-compatible storage, all config from env).
- [ ] Spring Boot Actuator health endpoint at `/actuator/health`.
- [ ] Authentication enabled for the API and review UI.
- [ ] Flyway migrations run cleanly against an empty database.
- [ ] `flyctl` installed and logged in to your existing Fly.io account.
- [ ] Supabase or Neon project created; connection string with TLS.
- [ ] R2 buckets created: `invoice-intel-raw` (private) and `invoice-intel-redacted` (private); API token scoped to these two buckets only.

---

## 5. Configuration

### 5.1 Environment variables / secrets

| Name | Purpose | Set via |
|------|---------|---------|
| `SPRING_PROFILES_ACTIVE` | `fly` | `fly.toml` `[env]` |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Postgres connection | `fly secrets set` |
| `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | Object storage | `fly secrets set` |
| `S3_BUCKET_RAW`, `S3_BUCKET_REDACTED` | Bucket names | `fly.toml` `[env]` |
| `LLM_*_API_KEY` | LLM provider keys | `fly secrets set` |
| `VAULT_KEYSET` | Encryption key for the PII vault | `fly secrets set` |
| `APP_ADMIN_USER`, `APP_ADMIN_PASSWORD` (or OAuth settings) | Login; user defaults to `admin`, password is required | `fly secrets set` |

Rules: secrets never go in `fly.toml`, the repo, logs, or the image.

`application.yaml` already reads the `DB_*` and `S3_*` variables above (architecture.md §10);
their defaults point at the local compose stack, so on Fly every one of them must be set.

### 5.2 Draft `fly.toml`

```toml
app = "invoice-intel"
primary_region = "bom"          # fall back to "sin" if bom is unavailable
kill_timeout = "300s"           # let in-flight jobs finish on shutdown; check Fly's current maximum

[build]
  dockerfile = "Dockerfile"     # multi-stage build incl. Tesseract (architecture.md ADR-07)

[env]
  SPRING_PROFILES_ACTIVE = "fly"
  JAVA_TOOL_OPTIONS = "-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
  S3_BUCKET_RAW = "invoice-intel-raw"
  S3_BUCKET_REDACTED = "invoice-intel-redacted"

[http_service]
  internal_port = 8080
  force_https = true
  auto_stop_machines = "stop"
  auto_start_machines = true
  min_machines_running = 0

  [[http_service.checks]]
    method = "GET"
    path = "/actuator/health"
    interval = "15s"
    timeout = "5s"
    grace_period = "60s"        # JVM start + model loading

[[vm]]
  size = "shared-cpu-1x"
  memory = "2gb"
```

Verify keys against the current Fly.io `fly.toml` reference before use.

---

## 6. Background jobs vs auto-stop (risk R8)

Fly.io stops idle machines based on incoming traffic, not on background work. A batch of
extraction jobs can be cut off mid-run.

Mitigations, in order of preference:

1. **Jobs are durable and idempotent** (NFR-5). JobRunr stores jobs in Postgres; an
   interrupted job is retried after the machine starts again. This is required regardless.
2. **Graceful shutdown**: Spring graceful shutdown + `kill_timeout` lets running jobs finish
   or checkpoint before the machine stops.
3. **Batch mode**: for large batch runs, temporarily set `min_machines_running = 1` and
   redeploy, then set it back to `0`.

---

## 7. Deployment steps

1. Check the image builds and starts locally:
   ```bash
   docker build -t invoice-intel:local .
   ```
2. Create the app (first time only):
   ```bash
   fly apps create invoice-intel
   ```
3. Set secrets (first time and on rotation):
   ```bash
   fly secrets set DB_URL=... DB_USER=... DB_PASSWORD=... S3_ENDPOINT=... S3_ACCESS_KEY=... S3_SECRET_KEY=... VAULT_KEYSET=...
   ```
4. Deploy (Fly builds the image from the `Dockerfile` and pushes it to its registry):
   ```bash
   fly deploy --image-label $(git rev-parse --short HEAD)
   ```
5. Verify:
   ```bash
   fly status
   fly logs
   ```
   Then check the health endpoint, log in, upload one **redacted sample** invoice, and confirm it is processed end to end.

Flyway runs migrations on app start. Migrations must be backward compatible with the
previous release so a rollback does not break the database.

---

## 8. Data migration from Phase 1

- Do **not** bulk-copy raw invoices by default. Re-upload through the API so the cloud
  pipeline does dedupe, PII handling and storage itself.
- The PII vault is migrated only if needed, and only by exporting it encrypted and
  importing it with the same `VAULT_KEYSET`. Never move it as plain text.
- Eval reports and redacted samples are already in Git; nothing to migrate.

---

## 9. Security checklist

- [ ] Every endpoint except `/actuator/health` requires authentication.
- [ ] Raw bucket is private; no public URLs. Redacted files are served through the app, not directly.
- [ ] Database and storage connections use TLS.
- [ ] R2 token and database user have the minimum permissions needed.
- [ ] Logs checked for PII before first deploy (PII-8).
- [ ] Spending alerts set on Fly.io and the LLM provider.

---

## 10. Rollback

```bash
fly releases
fly deploy --image registry.fly.io/invoice-intel:<previous-tag>
```

Every deploy is labelled with the Git commit SHA (step 4), so `fly releases` shows which commit each release came from and earlier images can be redeployed.

---

## 11. Fallback

If Fly.io auto-stop or free-tier database limits become a problem, move to a single
always-on VM (~$12/month, e.g. AWS Lightsail Mumbai or DigitalOcean Bangalore, 2 GB)
running the same Docker Compose file, with files kept in R2.

---

## 12. Open items

- Confirm Fly.io Mumbai region availability and current machine prices.
- Choose Supabase vs Neon (region, free-tier pause rules, storage cap).
- Decide on authentication for Phase 2: single admin login vs OAuth.
- Decide whether to add a custom domain.
