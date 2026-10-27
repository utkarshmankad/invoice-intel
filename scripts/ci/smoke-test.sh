#!/usr/bin/env bash
# Smoke test against a running stack (compose.yaml + compose.ci.yaml, stubbed LLM).
# Uses synthetic fixtures only. Checks grow with the plan:
#   always       health is UP, API rejects anonymous requests, admin credentials work
#   from M1.3    upload works, duplicate upload is deduplicated
#   from M4.7    the document reaches a final decision (SMOKE_EXPECT_STATUSES)
#
# Usage: smoke-test.sh <base-url>
# Env: APP_ADMIN_USER (default admin), APP_ADMIN_PASSWORD (required),
#      SMOKE_FIXTURE, SMOKE_EXPECT_STATUSES, SMOKE_TIMEOUT_SECONDS
set -euo pipefail

base="${1:?base url required}"
user="${APP_ADMIN_USER:-admin}"
pass="${APP_ADMIN_PASSWORD:?APP_ADMIN_PASSWORD required}"
fixture="${SMOKE_FIXTURE:-app/src/integrationTest/resources/fixtures/smoke/gst-tax-invoice.pdf}"
expect="${SMOKE_EXPECT_STATUSES:-AUTO_APPROVED,NEEDS_REVIEW}"
timeout="${SMOKE_TIMEOUT_SECONDS:-120}"
out="build/smoke"
summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
mkdir -p "$out"

results=()
failed=0
record() { results+=("| $1 | $2 | $3 |"); [[ "$2" == "FAIL" ]] && failed=1; return 0; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# 1. Health
status=""
for _ in $(seq 1 60); do
  status="$(curl -s "$base/actuator/health" | jq -r '.status' 2>/dev/null || true)"
  [[ "$status" == "UP" ]] && break
  sleep 2
done
[[ "$status" == "UP" ]] && record "Health" PASS "UP" || record "Health" FAIL "status=${status:-none}"

# 2. Authentication enforced
c="$(code "$base/api/documents")"
[[ "$c" == "401" ]] && record "Anonymous API request rejected" PASS "$c" || record "Anonymous API request rejected" FAIL "got $c, want 401"

c="$(code -u "$user:$pass" "$base/api/documents")"
[[ "$c" != "401" && "$c" != "403" ]] && record "Admin credentials accepted" PASS "$c" || record "Admin credentials accepted" FAIL "got $c"

# 3. Ingest and pipeline (only once the fixture exists)
if [[ -f "$fixture" ]]; then
  first="$(curl -s -u "$user:$pass" -F "files=@$fixture" "$base/api/documents")"
  id="$(jq -r '.[0].id // .id // empty' <<<"$first" 2>/dev/null || true)"
  [[ -n "$id" ]] && record "Upload synthetic invoice" PASS "id=$id" || record "Upload synthetic invoice" FAIL "no id in response"

  second="$(curl -s -u "$user:$pass" -F "files=@$fixture" "$base/api/documents")"
  id2="$(jq -r '.[0].id // .id // empty' <<<"$second" 2>/dev/null || true)"
  [[ -n "$id" && "$id" == "$id2" ]] && record "Duplicate upload deduplicated" PASS "same id" || record "Duplicate upload deduplicated" FAIL "ids differ"

  if [[ -n "$id" ]]; then
    final=""
    deadline=$((SECONDS + timeout))
    while (( SECONDS < deadline )); do
      final="$(curl -s -u "$user:$pass" "$base/api/documents/$id" | jq -r '.status' 2>/dev/null || true)"
      [[ ",$expect,FAILED," == *",$final,"* ]] && break
      sleep 2
    done
    if [[ ",$expect," == *",$final,"* ]]; then
      record "Pipeline reaches expected status" PASS "$final"
    else
      record "Pipeline reaches expected status" FAIL "status=${final:-none}, expected one of $expect within ${timeout}s"
    fi
  fi
else
  record "Ingest and pipeline" SKIP "fixture not present yet ($fixture)"
fi

{
  echo "### Smoke test"
  echo ""
  echo "| Check | Result | Detail |"
  echo "|-------|--------|--------|"
  printf '%s\n' "${results[@]}"
  echo ""
} | tee "$out/smoke-results.md" >> "$summary"

exit $failed
