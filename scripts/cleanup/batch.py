"""Step 2: split the (cleaned) acronymizer DB into review batches for the agents.

Usage: python3 scripts/cleanup/batch.py <cleaned.db> <out_dir> [proposals.json]

Books of one family (same commentator, same base work) go to the same batch so they are judged
alike; a batch holds at most MAX_BOOKS books and MAX_LINKS aliases. Each book carries the aliases
that are another book's exact title (`collisions`), those shared by many books, and its sourced
`proposed` aliases (see propose.py); a book without alias is reviewed when it has proposals.
"""
import collections
import json
import os
import re
import sqlite3
import sys

MAX_BOOKS = 40
MAX_LINKS = 450
SHARED_MIN = 4
# Heads naming a corpus rather than a commentator: the family is the corpus
CORPORA = ("משנה תורה", "תלמוד ירושלמי", "תוספתא", "משנה", "שולחן ערוך", "טור", "מדרש", "שות", "סידור", "סליחות", "מחזור")
SEP = re.compile(r"[\"'`׳״\.\-־‐,:;]")


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", SEP.sub(" ", s)).strip()


def family(title: str) -> str:
    if " על " in title:
        return title.split(" על ")[0].strip()
    for corpus in CORPORA:
        if title == corpus or title.startswith(corpus + " ") or title.startswith(corpus + ","):
            return corpus
    return title.split(" ")[0]


def main(db_path: str, out_dir: str, proposals_path: str = None) -> None:
    db = sqlite3.connect(db_path)
    proposed = {}
    if proposals_path:
        with open(proposals_path, encoding="utf-8") as f:
            proposed = {int(k): v for k, v in json.load(f).items()}
    titles = dict(db.execute("SELECT id, title FROM Books ORDER BY id"))
    links = collections.defaultdict(list)
    for book_id, alias in db.execute(
        "SELECT ba.book_id, a.acronym FROM BookAcronyms ba JOIN Acronyms a ON a.id = ba.acronym_id ORDER BY ba.id"
    ):
        links[book_id].append(alias)
    by_title = collections.defaultdict(list)
    for title in titles.values():
        by_title[norm(title)].append(title)
    users = collections.defaultdict(set)
    for book_id, aliases in links.items():
        for alias in aliases:
            users[norm(alias)].add(book_id)

    def item(book_id: int) -> dict:
        title, aliases = titles[book_id], links[book_id]
        return {
            "id": book_id,
            "title": title,
            "acronyms": aliases,
            "collisions": {a: by_title[norm(a)] for a in aliases if any(t != title for t in by_title.get(norm(a), []))},
            "shared_by_n_books": {a: len(users[norm(a)]) for a in aliases if len(users[norm(a)]) >= SHARED_MIN},
            "proposed": proposed.get(book_id, []),
        }

    families = collections.defaultdict(list)
    for book_id in titles:
        if links[book_id] or proposed.get(book_id):
            families[family(titles[book_id])].append(book_id)

    # Big families are cut in chunks; small ones are packed together, a family never split
    batches, current, size = [], [], 0
    for name in sorted(families, key=lambda f: (-len(families[f]), f)):
        ids = sorted(families[name], key=lambda i: titles[i])
        chunks = [ids]
        if len(ids) > MAX_BOOKS or sum(len(links[i]) + len(proposed.get(i, [])) for i in ids) > MAX_LINKS:
            chunks, chunk, n = [], [], 0
            for i in ids:
                weight = len(links[i]) + len(proposed.get(i, []))
                if chunk and (len(chunk) == MAX_BOOKS or n + weight > MAX_LINKS):
                    chunks.append(chunk)
                    chunk, n = [], 0
                chunk.append(i)
                n += weight
            chunks.append(chunk)
        for chunk in chunks:
            n = sum(len(links[i]) + len(proposed.get(i, [])) for i in chunk)
            if current and (len(current) + len(chunk) > MAX_BOOKS or size + n > MAX_LINKS):
                batches.append(current)
                current, size = [], 0
            current += chunk
            size += n
    if current:
        batches.append(current)

    os.makedirs(out_dir, exist_ok=True)
    manifest = []
    for k, ids in enumerate(batches, 1):
        name = f"batch_{k:03d}"
        with open(os.path.join(out_dir, name + ".json"), "w", encoding="utf-8") as f:
            json.dump([item(i) for i in ids], f, ensure_ascii=False, indent=1)
        fams = sorted({family(titles[i]) for i in ids})
        manifest.append({"batch": name, "books": len(ids), "links": sum(len(links[i]) for i in ids), "families": fams[:5]})
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print(f"{len(batches)} batches, {sum(m['books'] for m in manifest)} books, {sum(m['links'] for m in manifest)} links")


if __name__ == "__main__":
    main(*sys.argv[1:4])
