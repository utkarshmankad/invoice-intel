#!/usr/bin/env bash
# Prints line and branch coverage from a JaCoCo CSV report to the job summary.
# With a minimum (percent), fails when line coverage is below it.
# Usage: coverage-summary.sh <label> <jacoco.csv> [min-line-percent]
set -euo pipefail

label="$1"
csv="$2"
min="${3:-}"
summary="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

if [[ ! -f "$csv" ]]; then
  echo "::warning::$label: coverage report not found at $csv"
  [[ -n "$min" ]] && exit 1
  exit 0
fi

# JaCoCo CSV columns: 6 BRANCH_MISSED, 7 BRANCH_COVERED, 8 LINE_MISSED, 9 LINE_COVERED
read -r line_pct branch_pct < <(awk -F, 'NR > 1 { bm += $6; bc += $7; lm += $8; lc += $9 }
  END {
    lt = lm + lc; bt = bm + bc
    printf "%.2f %.2f\n", (lt ? 100 * lc / lt : 0), (bt ? 100 * bc / bt : 0)
  }' "$csv")

{
  echo "### Coverage: $label"
  echo ""
  echo "| Metric | Value |"
  echo "|--------|-------|"
  echo "| Line coverage | ${line_pct}% |"
  echo "| Branch coverage | ${branch_pct}% |"
  [[ -n "$min" ]] && echo "| Required line coverage | ${min}% |"
  echo ""
} >> "$summary"

if [[ -n "$min" ]] && awk -v a="$line_pct" -v b="$min" 'BEGIN { exit !(a < b) }'; then
  echo "::error::$label line coverage ${line_pct}% is below the required ${min}%"
  exit 1
fi
echo "$label: line ${line_pct}%, branch ${branch_pct}%"
