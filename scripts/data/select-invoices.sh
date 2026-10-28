#!/usr/bin/env bash
# Owner-only data preparation for plan tasks D2–D4 (docs/plan.md). Picks a varied invoice set
# from an Amazon.in export and copies it into the git-ignored data/raw/ and data/order-history/.
# Prints counts, years and CSV column names only, never file names or content (pii.md).
#
# Usage (from the repository root):
#   scripts/data/select-invoices.sh "<export-dir>"            # dry run: report only
#   scripts/data/select-invoices.sh "<export-dir>" --apply    # copy files, write mapping
# Options: --count N (D3 size, default 20), --scans N (D4, default 5), --force (overwrite)
#
# Claude never runs this on real data (CLAUDE.md non-negotiables).
set -euo pipefail

cd "$(dirname "$0")/../.."

# PDFBox from the Gradle cache (the version in gradle/libs.versions.toml); fetched by any build.
pdfbox_version="$(sed -n 's/^pdfbox = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
cache="$HOME/.gradle/caches/modules-2/files-2.1"
jar() { find "$cache/$1" -name "$2" ! -name '*-sources.jar' 2>/dev/null | sort -V | tail -1; }
cp=""
for spec in \
  "org.apache.pdfbox/pdfbox pdfbox-$pdfbox_version.jar" \
  "org.apache.pdfbox/pdfbox-io pdfbox-io-$pdfbox_version.jar" \
  "org.apache.pdfbox/fontbox fontbox-$pdfbox_version.jar" \
  "commons-logging/commons-logging commons-logging-*.jar"; do
  read -r dir pattern <<<"$spec"
  path="$(jar "$dir" "$pattern")"
  if [[ -z "$path" ]]; then
    echo "select-invoices: $pattern not in the Gradle cache; run ./gradlew build first" >&2
    exit 2
  fi
  cp="$cp:$path"
done

exec java -cp "${cp#:}" scripts/data/SelectInvoices.java "$@"
