"""Step 2a: sourced alias proposals for every book, from Sefaria (never from a model).

Usage: python3 scripts/cleanup/propose.py <cleaned.db> <sefaria_cache_dir> <proposals.json>

- the Hebrew titles of the book's own Sefaria index (`ב"ק` for בבא קמא, `של"ה`);
- for a commentary, its commentator's Hebrew names (Sefaria term) joined to its base text's Hebrew
  titles (`רש"י` + `ב"ק` → `רש"י ב"ק`, `רש"י על ב"ק`).
A proposal already present, equal to the title, another book's title or malformed is dropped.

Resumable: every Sefaria answer is cached as one file, written atomically; a 404 is cached as
missing, so nothing is ever asked twice. A network failure is retried with backoff, never cached,
and stops the run after RETRIES: run it again to resume where it stopped.
"""
import json
import os
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clean import drop_reason, rewrite  # noqa: E402

QUOTES = re.compile(r"[\"'`׳״”“’‘]")
SEP = re.compile(r"[\.\-־‐,:;]")
MAX_PROPOSALS = 12
DELAY = 0.3  # seconds between two real requests, to stay polite with Sefaria
RETRIES = 6
USER_AGENT = "SeforimAcronymizer-cleanup (https://github.com/kdroidFilter/SeforimAcronymizer)"


def norm(s: str) -> str:
    # רש"י and רשי are the same word: quotes vanish, punctuation separates
    return re.sub(r"\s+", " ", SEP.sub(" ", QUOTES.sub("", s))).strip()


def write_atomic(path: str, data: bytes) -> None:
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


class NetworkDown(Exception):
    pass


class Sefaria:
    def __init__(self, cache: str):
        self.cache = cache
        self.requests = 0
        for sub in ("raw", "terms"):
            os.makedirs(os.path.join(cache, sub), exist_ok=True)
        index = self._get("index.json", "https://www.sefaria.org/api/index")
        self.books = []
        self._walk(index)
        self.by_he = {norm(b["heTitle"]): b for b in self.books}
        self.by_en = {b["title"]: b for b in self.books}

    def _walk(self, node):
        if isinstance(node, list):
            for child in node:
                self._walk(child)
        elif isinstance(node, dict):
            if "heTitle" in node and "contents" not in node:
                self.books.append(node)
            self._walk(node.get("contents", []))

    def _get(self, rel: str, url: str):
        path = os.path.join(self.cache, rel)
        if not os.path.exists(path):
            write_atomic(path, self._fetch(url))
        with open(path, encoding="utf-8") as f:
            return json.load(f)

    def _fetch(self, url: str) -> bytes:
        for attempt in range(RETRIES):
            time.sleep(DELAY if attempt == 0 else min(60, 2**attempt))
            self.requests += 1
            try:
                req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(req, timeout=30) as r:
                    data = r.read()
                json.loads(data)  # a truncated answer is a network failure, not data
                return data
            except urllib.error.HTTPError as e:
                if e.code in (400, 404):  # Sefaria has no such index or term: cache it as missing
                    return b"{}"
                print(f"  HTTP {e.code} on {url}, retry {attempt + 1}/{RETRIES}", file=sys.stderr)
            except (urllib.error.URLError, TimeoutError, ConnectionError, ValueError, OSError) as e:
                print(f"  {type(e).__name__} on {url}, retry {attempt + 1}/{RETRIES}", file=sys.stderr)
        raise NetworkDown(url)

    def he_titles(self, en_title: str) -> list:
        q = urllib.parse.quote(en_title.replace(" ", "_"))
        raw = self._get(f"raw/{en_title.replace('/', '_')}.json", f"https://www.sefaria.org/api/v2/raw/index/{q}")
        titles = raw.get("schema", {}).get("titles") or raw.get("titles") or []
        return [t["text"] for t in titles if t.get("lang") == "he"]

    def term_he(self, name: str) -> list:
        q = urllib.parse.quote(name)
        term = self._get(f"terms/{name.replace('/', '_')}.json", f"https://www.sefaria.org/api/terms/{q}")
        return [t["text"] for t in term.get("titles", []) if t.get("lang") == "he"]


def proposals(book_title: str, sefaria: Sefaria) -> list:
    entry = sefaria.by_he.get(norm(book_title))
    if entry is None:
        return []
    found = list(sefaria.he_titles(entry["title"]))
    base_titles = entry.get("base_text_titles") or []
    collective = entry.get("collectiveTitle")
    if collective and len(base_titles) == 1:
        # A collective name holding a whole title (`X על Y`) is not a commentator's name
        names = {x for x in sefaria.term_he(collective) + [entry.get("heCollectiveTitle", "")] if " על " not in x}
        base = sefaria.by_en.get(base_titles[0])
        bases = set(sefaria.he_titles(base_titles[0])) | ({base["heTitle"]} if base else set())
        # Short forms first: that is what people type
        for name in sorted(filter(None, names), key=len):
            for b in sorted(bases, key=len):
                found += [f"{name} {b}", f"{name} על {b}"]
    return found


def main(db_path: str, cache: str, out_path: str) -> None:
    db = sqlite3.connect(db_path)
    books = db.execute("SELECT id, title FROM Books ORDER BY id").fetchall()
    titles = {norm(t) for _, t in books}
    have = {}
    for book_id, alias in db.execute(
        "SELECT ba.book_id, a.acronym FROM BookAcronyms ba JOIN Acronyms a ON a.id = ba.acronym_id"
    ):
        have.setdefault(book_id, set()).add(norm(alias))
    sefaria = Sefaria(cache)
    result, start = {}, time.time()
    try:
        for k, (book_id, title) in enumerate(books, 1):
            seen = have.get(book_id, set()) | {norm(title)}
            kept = []
            for p in map(rewrite, proposals(title, sefaria)):
                # Sefaria's terms carry niqqud, numbered duplicates (`ראב"ד2`)...: same bar as the DB
                if re.search(r"\d", p) or drop_reason(title, p):
                    continue
                key = norm(p)
                if key in seen or key in titles or len(key.replace(" ", "")) < 2:
                    continue
                seen.add(key)
                kept.append(p)
            if kept:
                result[str(book_id)] = kept[:MAX_PROPOSALS]
            if k % 250 == 0:
                print(f"{k}/{len(books)} books, {sefaria.requests} requests, {time.time() - start:.0f}s", flush=True)
    except NetworkDown as e:
        print(f"Network down on {e}: stopped at book {k}/{len(books)}. Run again to resume.", file=sys.stderr)
        sys.exit(2)
    write_atomic(out_path, json.dumps(result, ensure_ascii=False, indent=1).encode("utf-8"))
    print(f"Done: {len(result)} books with proposals, {sum(map(len, result.values()))} proposals, "
          f"{sefaria.requests} requests -> {out_path}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
