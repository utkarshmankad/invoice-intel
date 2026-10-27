#!/usr/bin/env bash
# Adds Gatling results to the job summary. Informational only: the Gatling assertions in the
# perf module are the actual gate (docs/ci.md, "Performance").
# Usage: perf-summary.sh <gatling-reports-dir>
set -euo pipefail

dir="${1:?reports dir required}"
summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
stats="$(find "$dir" -name stats.json -path '*/js/*' 2>/dev/null | sort | tail -1 || true)"

{
  echo "### Performance test"
  echo ""
  if [[ -z "$stats" ]]; then
    echo "No Gatling stats found in \`$dir\`; see the performance-report artifact."
  else
    jq -r '
      .stats as $s |
      "| Metric | Value |",
      "|--------|-------|",
      "| Requests (total / ok / ko) | \($s.numberOfRequests.total) / \($s.numberOfRequests.ok) / \($s.numberOfRequests.ko) |",
      "| Mean response time (ms) | \($s.meanResponseTime.total) |",
      "| p95 response time (ms) | \($s.percentiles3.total) |",
      "| p99 response time (ms) | \($s.percentiles4.total) |",
      "| Throughput (req/s) | \($s.meanNumberOfRequestsPerSecond.total) |"
    ' "$stats" 2>/dev/null || echo "Could not parse $stats; see the performance-report artifact."
  fi
  echo ""
} >> "$summary"
