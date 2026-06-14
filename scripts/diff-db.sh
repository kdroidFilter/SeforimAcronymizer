#!/usr/bin/env bash
# Produce a human-readable Markdown diff (additions / removals) between two acronymizer DBs.
# Usage: scripts/diff-db.sh <old.db> <new.db> > diff.md
set -euo pipefail

OLD="${1:?usage: diff-db.sh <old.db> <new.db>}"
NEW="${2:?usage: diff-db.sh <old.db> <new.db>}"
CAP=200   # max items listed per section (avoid oversized PR comments)

q() { sqlite3 -noheader -batch -separator '|' :memory: "ATTACH '$OLD' AS old; ATTACH '$NEW' AS new; $1"; }

# Pair views (title, acronym) for the link diff.
LINK_OLD="SELECT b.title, a.acronym FROM old.BookAcronyms ba JOIN old.Books b ON b.id=ba.book_id JOIN old.Acronyms a ON a.id=ba.acronym_id"
LINK_NEW="SELECT b.title, a.acronym FROM new.BookAcronyms ba JOIN new.Books b ON b.id=ba.book_id JOIN new.Acronyms a ON a.id=ba.acronym_id"

count() { q "$1" | head -1; }

books_added=$(count "SELECT COUNT(*) FROM (SELECT title FROM new.Books EXCEPT SELECT title FROM old.Books)")
books_removed=$(count "SELECT COUNT(*) FROM (SELECT title FROM old.Books EXCEPT SELECT title FROM new.Books)")
acr_added=$(count "SELECT COUNT(*) FROM (SELECT acronym FROM new.Acronyms EXCEPT SELECT acronym FROM old.Acronyms)")
acr_removed=$(count "SELECT COUNT(*) FROM (SELECT acronym FROM old.Acronyms EXCEPT SELECT acronym FROM new.Acronyms)")
links_added=$(count "SELECT COUNT(*) FROM ($LINK_NEW EXCEPT $LINK_OLD)")
links_removed=$(count "SELECT COUNT(*) FROM ($LINK_OLD EXCEPT $LINK_NEW)")

books_old=$(count "SELECT COUNT(*) FROM old.Books"); books_new=$(count "SELECT COUNT(*) FROM new.Books")
acr_old=$(count "SELECT COUNT(*) FROM old.Acronyms"); acr_new=$(count "SELECT COUNT(*) FROM new.Acronyms")
link_old=$(count "SELECT COUNT(*) FROM old.BookAcronyms"); link_new=$(count "SELECT COUNT(*) FROM new.BookAcronyms")

section() { # title, query
  local title="$1" query="$2" rows
  rows=$(q "$query LIMIT $((CAP+1))")
  local n; n=$(printf '%s\n' "$rows" | grep -c . || true)
  echo "<details><summary>$title</summary>"
  echo
  if [ -z "$rows" ]; then
    echo "_none_"
  else
    printf '%s\n' "$rows" | head -n "$CAP" | sed 's/^/- /'
    [ "$n" -gt "$CAP" ] && echo "- … (+$((n-CAP)) more, truncated)"
  fi
  echo
  echo "</details>"
}

echo "## 📊 Acronymizer database diff"
echo
echo "| | Previous | Proposed | Δ |"
echo "|---|---:|---:|---:|"
echo "| Books | $books_old | $books_new | $((books_new-books_old)) |"
echo "| Acronyms | $acr_old | $acr_new | $((acr_new-acr_old)) |"
echo "| Links | $link_old | $link_new | $((link_new-link_old)) |"
echo
echo "**Added:** ${books_added} books · ${acr_added} acronyms · ${links_added} links — **Removed:** ${books_removed} books · ${acr_removed} acronyms · ${links_removed} links"
echo
section "➕ Books added ($books_added)" "SELECT title FROM new.Books EXCEPT SELECT title FROM old.Books ORDER BY title"
section "➖ Books removed ($books_removed)" "SELECT title FROM old.Books EXCEPT SELECT title FROM new.Books ORDER BY title"
section "➕ Acronyms added ($acr_added)" "SELECT acronym FROM new.Acronyms EXCEPT SELECT acronym FROM old.Acronyms ORDER BY acronym"
section "➖ Acronyms removed ($acr_removed)" "SELECT acronym FROM old.Acronyms EXCEPT SELECT acronym FROM new.Acronyms ORDER BY acronym"
section "➕ Links added ($links_added)" "SELECT title || ' → ' || acronym FROM ($LINK_NEW EXCEPT $LINK_OLD) ORDER BY 1"
section "➖ Links removed ($links_removed)" "SELECT title || ' → ' || acronym FROM ($LINK_OLD EXCEPT $LINK_NEW) ORDER BY 1"
