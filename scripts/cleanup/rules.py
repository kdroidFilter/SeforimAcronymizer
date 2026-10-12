"""Step 3 without agents: decide every alias and proposal by rules.

Usage: python3 scripts/cleanup/rules.py <cleaned.db> <proposals.json> <batches_dir> <out_dir> [batch ...]

Writes, per batch, the same decisions an agent would (`remove`, `accept`), so validate.py,
compare.py and apply.py work the same. An alias is kept when:
1. every word of it is explained by the title: the word itself (prefix letters and כתיב מלא/חסר
   aside), a known abbreviation of title words (`ב"ק`, `רמב"ם`, `הל'`), a quoted initialism of
   consecutive title words (`או"ש`, `אדר"נ`), a shortening with geresh (`ירוש'`, `תוס'`), or a
   filler (`על`, `ספר`, `מסכת`);
2. it reaches the end of the title: its last content word is covered (`בבא` does not name בבא בתרא,
   `או"ש` alone does not name one part of אור שמח), and for `X על Y` it names X too;
3. it is not another book's title, unless that book is only another edition of this one;
4. it belongs to this book: valid for several books, it goes to the shortest title only (see owners).
"""
import json
import os
import re
import sqlite3
import sys

QUOTES = re.compile(r"[\"'`׳״”“’‘]")
SPLIT = re.compile(r"[\s\-־‐,:;.()]+")
PREFIX_LETTERS = "והבלמשכד"
ORDINALS = {"ראשון": "א", "שני": "ב", "שלישי": "ג", "רביעי": "ד", "חמישי": "ה", "שישי": "ו", "שביעי": "ז"}
FILLERS = {"על", "ספר", "מסכת", "מסכתא", "הלכות", "הל", "חלק", "פירוש", "ביאור", "חידושי", "חידושים", "משנה", "תלמוד", "פרקי", "ה", "שות",
           # nobody types these to name a book: שולחן ערוך before its section, גאון after a name
           "שולחן", "שלחן", "ערוך", "גאון", "השגות", "הגהות", "חדושי", "באורי", "ס", "מס", "מסכ"}
SYNONYMS = [{"רבי", "רבינו", "רבנו", "ר"}]

# Abbreviation (without quotes) -> title words it stands for (all must be in the title)
ABBR = {
    "רמבם": ["משנה", "תורה"], "הרמבם": ["משנה", "תורה"], "רמב": ["משנה", "תורה"], "מיימוני": ["משנה", "תורה"],
    "הל": ["הלכות"], "הלכ": ["הלכות"], "תוס": ["תוספתא|תוספות"], "ירוש": ["ירושלמי"], "ירו": ["ירושלמי"],
    "בבלי": [], "שות": ["שות"], "תשובות": ["שות"], "שאלות": ["שות"], "ותשובות": ["שות"],
    "שוע": ["שולחן", "ערוך"], "אוח": ["אורח", "חיים"], "יוד": ["יורה", "דעה"], "אהע": ["אבן", "העזר"],
    "אבהעז": ["אבן", "העזר"], "אהעז": ["אבן", "העזר"], "חומ": ["חושן", "משפט"],
    "רשי": ["רשי"], "רש": ["רשי"], "תויט": ["תוספות", "יום", "טוב"], "תוייט": ["תוספות", "יום", "טוב"],
    "מצוד": ["מצודת", "דוד"], "מצוצ": ["מצודת", "ציון"], "דהא": ["דברי", "הימים", "א"], "דהב": ["דברי", "הימים", "ב"],
    "דהי": ["דברי", "הימים"], "בר": ["בראשית"], "פרקי": ["אבות"],
}


FINALS = str.maketrans("ךםןףץ", "כמנפצ")


def bare(s: str) -> str:
    # מהרא"ף and מהראפ are the same word: no quotes, no final letter forms
    return QUOTES.sub("", s).translate(FINALS)


def norm(s: str) -> str:
    return " ".join(w for w in SPLIT.split(bare(s)) if w)


def plene(w: str) -> str:
    # כתיב מלא/חסר: ביכורים/בכורים, מידות/מדות, עדיות/עדויות
    return w[0] + re.sub("[וי]", "", w[1:]) if len(w) > 2 else w


def same_word(t: str, w: str) -> bool:
    """Whether the typed word [t] is the title word [w]: spelling, ordinal or prefix letters aside."""
    forms = {t}
    if len(t) > 2 and t[0] in PREFIX_LETTERS:  # לרמבם, ורדבז, דזבים
        forms.add(t[1:])
        if len(t) > 3 and t[1] in PREFIX_LETTERS:  # והרדבז
            forms.add(t[2:])
    titles = {w}
    if len(w) > 3 and w[0] in "והל":  # הרמבם, לרשי typed רמבם, רשי
        titles.add(w[1:])
        if len(w) > 4 and w[1] in "הל":  # והרדבז typed רדבז
            titles.add(w[2:])
    for group in SYNONYMS:
        if w in group:
            titles |= group
    if w in ORDINALS:  # חלק ראשון typed חלק א
        titles.add(ORDINALS[w])
    return any(f == x or plene(f) == plene(x) for f in forms for x in titles)


def initialism(token: str, words: list) -> list:
    """Indexes of the 2+ consecutive title words whose starts spell [token] (או"ש, אדר"נ), else []."""
    def spell(rest, i):
        if not rest:
            yield []
            return
        if i >= len(words):
            return
        for k in range(min(len(words[i]), len(rest)), 0, -1):  # a word gives 1+ letters (או"ש: או+ש)
            if words[i][:k] == rest[:k]:
                for tail in spell(rest[k:], i + 1):
                    yield [i] + tail

    for start in range(len(words)):
        for found in spell(token, start):
            if len(found) >= 2:  # one word is not an initialism
                return found
    if any(w in ORDINALS for w in words):  # חלק שני spelled ח"ב
        return initialism(token, [ORDINALS.get(w, w) for w in words])
    return []


ABBR = {k.translate(FINALS): [x.translate(FINALS) for x in v] for k, v in ABBR.items()}
FILLERS = {w.translate(FINALS) for w in FILLERS}
SYNONYMS = [{w.translate(FINALS) for w in g} for g in SYNONYMS]
ORDINALS = {k.translate(FINALS): v for k, v in ORDINALS.items()}


class Book:
    def __init__(self, title: str):
        self.title = title
        main = re.sub(r"\([^)]*\)", " ", title)
        self.words = [w for w in SPLIT.split(bare(main)) if w]
        self.extra = len(self.words)  # words from (ליברמן) come after, never required
        self.words += [w for p in re.findall(r"\(([^)]*)\)", title) for w in SPLIT.split(bare(p)) if w]
        head, _, tail = norm(main).partition(" על ")
        self.head = set(range(len(head.split()))) if tail else set()
        self.content = [i for i, w in enumerate(self.words[: self.extra]) if w not in FILLERS]
        self.last = self.content[-1] if self.content else 0
        # A long hilchot name (מלכים ומלחמות, ביכורים ושאר...) is cited by its first word
        words = self.words[: self.extra]
        hilchot = "הלכות" in words
        if hilchot:
            section = [i for i in range(words.index("הלכות") + 1, self.extra) if words[i] not in FILLERS]
            if len(section) >= 2:
                self.last = section[0]
            elif section:
                self.last = section[-1]
        # The first word of the name must be there too (`שלמה ב"מ`, `יעקב יוסף` cut the name), and for
        # `X על Y` the first word of Y (`רשי על קמא`)
        n_head = len(head.split()) if tail else 0
        first = [i for i in self.content if i >= 0]
        self.required = {self.last}
        if first:
            self.required.add(first[0])
        if tail and not hilchot:
            tail_content = [i for i in self.content if i >= n_head]
            if tail_content:
                self.required.add(tail_content[0])

    def explain(self, alias: str, quoted_forms: set):
        """(covered title word indexes, unexplained alias word or None, words matched as written)."""
        covered, direct = set(), 0
        raw_tokens = [t for t in SPLIT.split(alias) if t]
        for raw in raw_tokens:
            t = bare(raw)
            if not t:
                continue
            hits = [i for i, w in enumerate(self.words) if same_word(t, w)]
            if hits:
                covered.update(hits)
                continue
            key = next((k for k in (t, t[1:], t[2:]) if k in ABBR), None)
            if key is not None and all(any(same_word(x, w) for x in alt.split("|") for w in self.words) for alt in ABBR[key]):
                covered.update(i for i, w in enumerate(self.words) for alt in ABBR[key] for x in alt.split("|") if same_word(x, w))
                continue
            stem = raw.rstrip("'׳’")
            if stem != raw and len(stem) >= 2:  # ירוש', תוס', הל': a shortening, not a cut word
                hits = [i for i, w in enumerate(self.words)
                        if (w.startswith(stem) or plene(w).startswith(plene(stem))) and len(stem) <= len(w) - 2]
                if hits:
                    covered.update(hits)
                    continue
            if t in FILLERS:
                continue
            quoted = bool(re.search(r'["״”]', raw)) or t in quoted_forms
            spelled = initialism(t, self.words) if quoted and len(t) >= 2 else []
            if spelled:
                covered.update(spelled)
                continue
            return covered, raw, direct
        return covered, None, direct


def checker(book_id: int, title: str, candidates: list, titles: dict):
    """verdict(alias) -> None when the alias names this book, else (rule, why). Ownership aside."""
    b = Book(title)
    edition = norm(re.sub(r"\([^)]*\)", " ", title))
    quoted_forms = {bare(t) for a in candidates for t in SPLIT.split(a) if re.search(r'["״”]', t)}

    def verdict(alias):
        others = [t for t in titles.get(norm(alias), []) if t != title]
        if others and not all(norm(re.sub(r"\([^)]*\)", " ", t)) == edition for t in others):
            return 2, f"title of another book: {others[0]}"
        if re.search(r"[א-ת]\.[א-ת]", alias):
            return 4, "dotted letters"
        covered, unexplained, _ = b.explain(alias, quoted_forms)
        if unexplained:
            return 1, f"'{unexplained}' is not in the title"
        missing = sorted(b.required - covered)
        if missing:
            return 3, f"leaves out '{b.words[missing[0]]}'"
        if len(covered & set(b.content)) < min(2, len(b.content)):
            return 3, "one word of a longer title"
        if b.head and not (covered & b.head):
            return 3, "names the base text, not this commentary"
        return None

    return verdict, len(b.words[: b.extra]), edition


def owners(books: dict, titles: dict) -> dict:
    """alias key -> ids of the books it belongs to.

    An alias valid for several books belongs to the one with the shortest title, the work it names
    most directly (`ב"ב` is בבא בתרא, not משנה בבא בתרא). Several different works tied at the
    shortest: the alias is ambiguous (`תוס"ב`: ביצה, בכורות, ביכורים...) and belongs to none.
    """
    valid = {}
    for book_id, (title, candidates) in books.items():
        verdict, length, edition = checker(book_id, title, candidates, titles)
        for alias in candidates:
            if verdict(alias) is None:
                valid.setdefault(norm(alias), []).append((length, edition, book_id))
    result = {}
    for key, holders in valid.items():
        shortest = min(h[0] for h in holders)
        best = [h for h in holders if h[0] == shortest]
        if len({h[1] for h in best}) == 1:  # one work (its editions count as one)
            result[key] = {h[2] for h in best}
        else:
            result[key] = set()
    return result


def decide(book: dict, titles: dict, owned: dict) -> dict:
    candidates = book["acronyms"] + book.get("proposed", [])
    verdict, _, _ = checker(book["id"], book["title"], candidates, titles)
    out = {"id": book["id"], "remove": [], "accept": []}

    def full(alias):
        v = verdict(alias)
        if v is None and book["id"] not in owned.get(norm(alias), {book["id"]}):
            return 3, "names another book better" if owned.get(norm(alias)) else "ambiguous between several books"
        return v

    for alias in book["acronyms"]:
        v = full(alias)
        if v:
            out["remove"].append({"acronym": alias, "rule": v[0], "why": v[1]})
    for alias in book.get("proposed", []):
        if full(alias) is None:
            out["accept"].append(alias)
    return out


def main(db_path, proposals_path, batches_dir, out_dir, *names):
    db = sqlite3.connect(db_path)
    titles, books = {}, {}
    for book_id, t in db.execute("SELECT id, title FROM Books"):
        titles.setdefault(norm(t), []).append(t)
        books[book_id] = (t, [])
    for book_id, alias in db.execute(
        "SELECT ba.book_id, a.acronym FROM BookAcronyms ba JOIN Acronyms a ON a.id = ba.acronym_id"
    ):
        books[book_id][1].append(alias)
    with open(proposals_path, encoding="utf-8") as f:
        for book_id, proposed in json.load(f).items():
            books[int(book_id)][1].extend(proposed)
    owned = owners(books, titles)
    os.makedirs(out_dir, exist_ok=True)
    names = names or sorted(f[:-5] for f in os.listdir(batches_dir) if f.startswith("batch_"))
    for name in names:
        batch = json.load(open(os.path.join(batches_dir, name + ".json"), encoding="utf-8"))
        result = [decide(book, titles, owned) for book in batch]
        with open(os.path.join(out_dir, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=1)
    print(f"{len(names)} batches decided -> {out_dir}")


if __name__ == "__main__":
    main(*sys.argv[1:])
