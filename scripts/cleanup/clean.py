"""Step 1: mechanical cleanup of the acronymizer DB, no judgment involved.

Usage: python3 -I scripts/cleanup/clean.py <in.db> <out.db> <changes.tsv>

Rewrites an alias (stripped of niqqud, typographic quotes made ASCII, edge periods dropped) and
drops it when malformed or identical to its book's title. Every change is logged in changes.tsv.
Ids of untouched rows are kept, so the canonical SQL diff stays small.
"""
import re
import shutil
import sqlite3
import sys

NIQQUD = re.compile(r"[֑-ֽֿ-ׇ]")
LATIN = re.compile(r"[A-Za-z]")
NOTES = re.compile(r"בלי גרש|ללא גרש|בגרשיים|גרשיים רגילים|�")
# A gershayim opening a word or closing it (רשי", "סידור): a real one sits before the last letter
BAD_GERSHAYIM = re.compile(r'(^|[\s\-־])["״]|["״]($|[\s\-־.,])')
SEP = re.compile(r"[\"'`׳״\.\-־‐,:;]")


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", SEP.sub(" ", s)).strip()


def rewrite(alias: str) -> str:
    s = NIQQUD.sub("", alias)
    # ״ and ׳ stay: Zayit strips them but not ", so both spellings are needed
    s = s.replace("“", '"').replace("”", '"').replace("‘", "'").replace("’", "'")
    s = re.sub(r"\s+", " ", s).strip()
    # Dotted abbreviations (ב.ב., מצ.צ.) are left to the agents
    if "." not in s.strip("."):
        s = s.strip(".").strip()
    return s


def drop_reason(title: str, alias: str):
    if LATIN.search(alias):
        return "latin"
    if NOTES.search(alias):
        return "note"
    parens = re.findall(r"\(([^)]*)\)", alias)
    if any(p.strip() not in title for p in parens) or alias.count("(") != alias.count(")"):
        return "parenthesis"
    if len(norm(alias).replace(" ", "")) <= 1:
        return "one_letter"
    if BAD_GERSHAYIM.search(alias):
        return "misplaced_gershayim"
    if norm(alias) == norm(title):
        return "same_as_title"
    return None


def linked(db, book_id, acronym_id) -> bool:
    return acronym_id is not None and db.execute(
        "SELECT 1 FROM BookAcronyms WHERE book_id = ? AND acronym_id = ?", (book_id, acronym_id)
    ).fetchone() is not None


def main(src: str, dst: str, log_path: str) -> None:
    shutil.copyfile(src, dst)
    db = sqlite3.connect(dst)
    titles = dict(db.execute("SELECT id, title FROM Books"))
    acronym_ids = {a: i for i, a in db.execute("SELECT id, acronym FROM Acronyms")}
    links = db.execute(
        "SELECT ba.id, ba.book_id, a.acronym FROM BookAcronyms ba JOIN Acronyms a ON a.id = ba.acronym_id ORDER BY ba.id"
    ).fetchall()
    seen = set()  # (book_id, alias) already linked
    with open(log_path, "w", encoding="utf-8") as log:
        log.write("action\treason\tbook\talias\tnew_alias\n")
        for link_id, book_id, alias in links:
            title = titles[book_id]
            new = rewrite(alias)
            reason = drop_reason(title, new)
            # Already linked, earlier or as a later row this rewrite would collide with
            if reason is None and ((book_id, new) in seen or (new != alias and linked(db, book_id, acronym_ids.get(new)))):
                reason = "duplicate"
            if reason:
                db.execute("DELETE FROM BookAcronyms WHERE id = ?", (link_id,))
                log.write(f"drop\t{reason}\t{title}\t{alias}\t\n")
                continue
            seen.add((book_id, new))
            if new != alias:
                if new not in acronym_ids:
                    acronym_ids[new] = db.execute("INSERT INTO Acronyms(acronym) VALUES(?)", (new,)).lastrowid
                db.execute("UPDATE BookAcronyms SET acronym_id = ? WHERE id = ?", (acronym_ids[new], link_id))
                log.write(f"rewrite\tnormalize\t{title}\t{alias}\t{new}\n")
    db.execute("DELETE FROM Acronyms WHERE id NOT IN (SELECT acronym_id FROM BookAcronyms)")
    db.commit()
    db.execute("VACUUM")
    counts = db.execute(
        "SELECT (SELECT COUNT(*) FROM Books), (SELECT COUNT(*) FROM Acronyms), (SELECT COUNT(*) FROM BookAcronyms)"
    ).fetchone()
    print("Books=%d Acronyms=%d Links=%d" % counts)


if __name__ == "__main__":
    main(*sys.argv[1:4])
