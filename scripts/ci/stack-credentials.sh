#!/usr/bin/env bash
# Generates throwaway credentials for the CI stack (compose.yaml + compose.ci.yaml): database,
# S3, admin password and the PII token and vault keysets, new on every run (docs/ci.md §4). In GitHub Actions they're masked and
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
    # Single quotes: the keysets are JSON. No value contains a single quote.
    echo "export $1='$2'"
  fi
}

# A Tink JSON keyset with one random 32-byte key (PII-7, architecture.md §6.3), built from the
# key proto's fixed layout so no Tink tool is needed: $1 = key type, $2 = proto bytes before the
# key bytes (printf escapes), $3 = output prefix type.
tink_keyset() {
  local id value
  id=$(( $(od -An -N4 -tu4 /dev/urandom | tr -d ' ') % 2147483647 + 1 ))
  value=$({ printf '%b' "$2"; openssl rand 32; } | openssl base64 -A)
  printf '{"primaryKeyId":%d,"key":[{"keyData":{"typeUrl":"type.googleapis.com/google.crypto.tink.%s","value":"%s","keyMaterialType":"SYMMETRIC"},"status":"ENABLED","keyId":%d,"outputPrefixType":"%s"}]}' \
    "$id" "$1" "$value" "$id" "$3"
}

emit DB_USER invoice_intel public
emit DB_PASSWORD "$(openssl rand -hex 16)"
emit S3_ACCESS_KEY "$(openssl rand -hex 10)"
emit S3_SECRET_KEY "$(openssl rand -hex 20)"
emit APP_ADMIN_PASSWORD "$(openssl rand -hex 16)"
# HmacKey{params{hash=SHA256, tag_size=32}, key_value}: HMAC_SHA256_256BITTAG_RAW.
emit TOKEN_KEYSET "$(tink_keyset HmacKey '\x12\x04\x08\x03\x10\x20\x1a\x20' RAW)"
# AesGcmKey{key_value}: AES256_GCM.
emit VAULT_KEYSET "$(tink_keyset AesGcmKey '\x1a\x20' TINK)"
