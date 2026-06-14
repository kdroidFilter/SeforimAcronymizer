#!/usr/bin/env bash
# Export a SQLite acronymizer DB to the canonical, deterministic SQL dump.
# This format MUST match the editor's Kotlin exporter byte-for-byte for clean git diffs.
# Usage: scripts/export-canonical.sh <input.db> > data/acronymizer.sql
set -euo pipefail

DB="${1:?usage: export-canonical.sh <input.db>}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cat "$DIR/canonical-header.sql"

sqlite3 -noheader -batch "$DB" "
SELECT 'INSERT INTO Books(id,title) VALUES(' || id || ',''' || replace(title,'''','''''') || ''');'
FROM Books ORDER BY id;
SELECT 'INSERT INTO Acronyms(id,acronym) VALUES(' || id || ',''' || replace(acronym,'''','''''') || ''');'
FROM Acronyms ORDER BY id;
SELECT 'INSERT INTO BookAcronyms(id,book_id,acronym_id) VALUES(' || id || ',' || book_id || ',' || acronym_id || ');'
FROM BookAcronyms ORDER BY id;
"

echo "COMMIT;"
