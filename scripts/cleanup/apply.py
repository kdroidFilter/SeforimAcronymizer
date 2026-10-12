"""Step 5: apply the decisions to the cleaned DB.

Usage: python3 scripts/cleanup/apply.py <cleaned.db> <batches_dir> <decisions_dir> <out.db>

Removed aliases lose their link, accepted proposals and additions are linked, acronyms left
without book are dropped. Untouched rows keep their ids, so the canonical SQL diff stays readable.
"""
import json
import os
import shutil
import sqlite3
import sys


def main(src: str, batches_dir: str, decisions_dir: str, dst: str) -> None:
    shutil.copyfile(src, dst)
    db = sqlite3.connect(dst)
    acronym_ids = {a: i for i, a in db.execute("SELECT id, acronym FROM Acronyms")}
    removed = added = 0
    for name in sorted(f for f in os.listdir(batches_dir) if f.startswith("batch_")):
        path = os.path.join(decisions_dir, name)
        if not os.path.exists(path):
            sys.exit(f"missing decisions for {name}")
        books = {b["id"]: b for b in json.load(open(os.path.join(batches_dir, name), encoding="utf-8"))}
        for decision in json.load(open(path, encoding="utf-8")):
            book_id = decision["id"]
            for r in decision.get("remove", []):
                aid = acronym_ids.get(r["acronym"])
                removed += db.execute("DELETE FROM BookAcronyms WHERE book_id = ? AND acronym_id = ?", (book_id, aid)).rowcount
            for alias in decision.get("accept", []) + decision.get("add", []):
                if alias not in acronym_ids:
                    acronym_ids[alias] = db.execute("INSERT INTO Acronyms(acronym) VALUES(?)", (alias,)).lastrowid
                added += db.execute(
                    "INSERT OR IGNORE INTO BookAcronyms(book_id, acronym_id) VALUES(?, ?)", (book_id, acronym_ids[alias])
                ).rowcount
            assert book_id in books, f"{name}: unknown book {book_id}"
    db.execute("DELETE FROM Acronyms WHERE id NOT IN (SELECT acronym_id FROM BookAcronyms)")
    db.commit()
    db.execute("VACUUM")
    counts = db.execute(
        "SELECT (SELECT COUNT(*) FROM Books), (SELECT COUNT(*) FROM Acronyms), (SELECT COUNT(*) FROM BookAcronyms),"
        " (SELECT COUNT(*) FROM Books WHERE id NOT IN (SELECT book_id FROM BookAcronyms))"
    ).fetchone()
    print(f"removed {removed} links, added {added}")
    print("Books=%d Acronyms=%d Links=%d BooksWithoutAlias=%d" % counts)


if __name__ == "__main__":
    main(*sys.argv[1:5])
