#!/usr/bin/env bash
# Creates or updates the trunk-based ruleset on the GitHub repo from .github/rulesets/main.json.
# Run after `main` exists on GitHub (first push), otherwise the rules can block that push.
# Usage: scripts/github/apply-rulesets.sh [owner/repo]
set -euo pipefail

repo="${1:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"
file="$(dirname "$0")/../../.github/rulesets/main.json"
name="$(jq -r .name "$file")"

if ! gh api "repos/$repo/branches/main" --silent 2>/dev/null; then
  echo "main does not exist on $repo yet. Push it first: git push -u origin main" >&2
  exit 1
fi

id="$(gh api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$name\") | .id")"

if [[ -n "$id" ]]; then
  gh api -X PUT "repos/$repo/rulesets/$id" --input "$file" --jq '"updated ruleset \(.name) (#\(.id)), enforcement: \(.enforcement)"'
else
  gh api -X POST "repos/$repo/rulesets" --input "$file" --jq '"created ruleset \(.name) (#\(.id)), enforcement: \(.enforcement)"'
fi
