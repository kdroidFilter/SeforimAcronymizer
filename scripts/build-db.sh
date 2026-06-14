#!/usr/bin/env bash
# Build the binary acronymizer.db from the canonical SQL dump.
# Usage: scripts/build-db.sh [data/acronymizer.sql] [out.db]
set -euo pipefail

SRC="${1:-data/acronymizer.sql}"
OUT="${2:-acronymizer.db}"

rm -f "$OUT"
sqlite3 "$OUT" < "$SRC"
echo "Built $OUT from $SRC"
sqlite3 -batch "$OUT" "SELECT 'Books=' || (SELECT COUNT(*) FROM Books), 'Acronyms=' || (SELECT COUNT(*) FROM Acronyms), 'Links=' || (SELECT COUNT(*) FROM BookAcronyms);"
