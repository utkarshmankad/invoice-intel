#!/usr/bin/env bash
# Generates throwaway credentials for the CI stack (compose.yaml + compose.ci.yaml): database,
# S3 and admin password, new on every run (docs/ci.md §4). In GitHub Actions they're masked and
# appended to $GITHUB_ENV for the later steps; elsewhere they're printed as export lines:
#
#   eval "$(scripts/ci/stack-credentials.sh)"
set -euo pipefail

# Plain function instead of an associative array: macOS still ships bash 3.2.
emit() {
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    [[ "${3:-}" == public ]] || echo "::add-mask::$2"
    echo "$1=$2" >> "$GITHUB_ENV"
  else
    echo "export $1=$2"
  fi
}

emit DB_USER invoice_intel public
emit DB_PASSWORD "$(openssl rand -hex 16)"
emit S3_ACCESS_KEY "$(openssl rand -hex 10)"
emit S3_SECRET_KEY "$(openssl rand -hex 20)"
emit APP_ADMIN_PASSWORD "$(openssl rand -hex 16)"
