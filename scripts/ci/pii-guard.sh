#!/usr/bin/env bash
# Fails if Git tracks anything that may hold real personal data (.claude/rules/pii.md, specs.md §4.3).
# Checks file paths and types only; it never prints file contents.
set -euo pipefail

fail=0
tracked="$(git ls-files)"

# 1. Private data folders must never be tracked.
private="$(grep -E '^data/(raw|order-history|labels|vault)/' <<<"$tracked" || true)"
if [[ -n "$private" ]]; then
  echo "::error::Files from private data folders are tracked:"
  echo "$private"
  fail=1
fi

# 2. Env files with real values must never be tracked (.env.example is allowed).
envfiles="$(grep -E '(^|/)\.env(\..+)?$' <<<"$tracked" | grep -vE '(^|/)\.env\.example$' || true)"
if [[ -n "$envfiles" ]]; then
  echo "::error::Env files are tracked:"
  echo "$envfiles"
  fail=1
fi

# 3. Documents and exports only in folders meant for synthetic or redacted files.
doc_ext='\.(pdf|jpe?g|png|tiff?|heic|webp|csv|xlsx?)$'
allowed='(^|/)src/(test|integrationTest)/resources/fixtures/|^data/samples-redacted/|^docs/images/|^perf/src/gatling/resources/'
stray="$(grep -iE "$doc_ext" <<<"$tracked" | grep -vE "$allowed" || true)"
if [[ -n "$stray" ]]; then
  echo "::error::Document or export files outside allowed folders (fixtures, samples-redacted, docs/images, perf resources):"
  echo "$stray"
  fail=1
fi

# 4. Key material.
keys="$(grep -iE '\.(pem|p12|jks|keystore)$|(^|/)(vault|tink)[-_]?keyset' <<<"$tracked" || true)"
if [[ -n "$keys" ]]; then
  echo "::error::Key files are tracked:"
  echo "$keys"
  fail=1
fi

if [[ $fail -eq 0 ]]; then
  echo "PII guard passed: no private data, env, stray document or key files tracked."
fi
exit $fail
