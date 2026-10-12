"""Step 4: check agent outputs against their batches.

Usage: python3 -I scripts/cleanup/validate.py <cleaned.db> <batches_dir> <out_dir> [batch ...]
"""
import collections
import json
import os
import re
import sqlite3
import sys

SEP = re.compile(r"[\"'`׳״\.\-־‐,:;]")


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", SEP.sub(" ", s)).strip()


def main(db_path, batches_dir, out_dir, *names):
    titles = {norm(t) for (t,) in sqlite3.connect(db_path).execute("SELECT title FROM Books")}
    names = names or sorted(f[:-5] for f in os.listdir(out_dir) if f.endswith(".json"))
    total = collections.Counter()
    for name in names:
        inp = json.load(open(os.path.join(batches_dir, name + ".json"), encoding="utf-8"))
        path = os.path.join(out_dir, name + ".json")
        if not os.path.exists(path):
            print(f"{name}: MISSING")
            continue
        out = json.load(open(path, encoding="utf-8"))
        errors, rules, adds, unsure, links, accepted, proposed = [], collections.Counter(), 0, 0, 0, 0, 0
        if [o["id"] for o in out] != [b["id"] for b in inp]:
            errors.append("ids/order differ")
        books = {b["id"]: b for b in inp}
        for o in out:
            b = books.get(o["id"])
            if b is None:
                continue
            links += len(b["acronyms"])
            proposed += len(b.get("proposed", []))
            for a in o.get("accept", []):
                accepted += 1
                if a not in b.get("proposed", []):
                    errors.append(f"{o['id']}: unknown accept {a!r}")
            for r in o.get("remove", []):
                rules[r.get("rule")] += 1
                if r["acronym"] not in b["acronyms"]:
                    errors.append(f"{o['id']}: unknown remove {r['acronym']!r}")
            for a in o.get("unsure", []):
                unsure += 1
                if a not in b["acronyms"] and a not in b.get("proposed", []):
                    errors.append(f"{o['id']}: unknown unsure {a!r}")
            for a in o.get("add", []):
                adds += 1
                if norm(a) in titles and norm(a) != norm(b["title"]):
                    errors.append(f"{o['id']}: add {a!r} is another book's title")
        removed = sum(rules.values())
        total.update(links=links, removed=removed, proposed=proposed, accepted=accepted, adds=adds, unsure=unsure, errors=len(errors))
        print(f"{name}: links {links} removed {removed} ({removed * 100 // max(links, 1)}%) {dict(sorted(rules.items()))} "
              f"accept {accepted}/{proposed} add {adds} unsure {unsure} errors {len(errors)} {errors[:3]}")
    print("TOTAL", dict(total))


if __name__ == "__main__":
    main(*sys.argv[1:])
