# Docs workflow

The project is spec-driven. Docs are the source of truth; code implements them.

## Order of authority

`specs.md` (what/why) → `architecture.md` (how) → `plan.md` (next steps) → code.

## Rules

- **specs.md** changes only with the owner's explicit approval. When approved: bump the
  version in the header and add a row to the revision history. Never renumber existing
  requirement IDs; add new ones or mark old ones `(removed)`.
- **architecture.md** is updated in the same commit as any code change that alters a design
  decision, module boundary, table, endpoint or config key. New decisions get an ADR row.
- **plan.md**: tick tasks when done; add discovered work under "Follow-ups" instead of
  doing it unplanned. Every task references the spec IDs or architecture sections it covers.
- **deployment.md** is updated when config keys, profiles, ports or the Docker build change.
- If implementing a task shows the spec or architecture is wrong or incomplete, stop and
  propose the doc change before writing more code.
- Facts that may go stale (prices, library versions, platform limits) are marked with the
  date they were checked.
