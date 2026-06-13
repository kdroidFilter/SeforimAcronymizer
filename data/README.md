# Acronymizer Dataset

This directory is the **source of truth** for the acronymizer database.
`acronymizer.db` is a build artifact generated from these files — do not edit it directly.

## Files

- `acronyms.txt` — all books and their acronyms, sorted by title.
- `enriched_books.txt` — titles already processed by the AI enrichment pass (skipped by `runEnrich`).

## Format

Each book is a block: a `# title` header line followed by one acronym per line.
Blocks are separated by a blank line. No quoting or escaping — lines are taken verbatim.

```
# שולחן ערוך, אורח חיים
שו"ע או"ח
שולחן ערוך אורח חיים

# משנה ברורה
משנ"ב
```

## Contributing / תרומת נתונים

1. Find the book's `# title` block in `acronyms.txt` and add acronyms under it, or add a new block for a new book.
2. Titles must be unique; the build fails on duplicates.
3. Open a pull request — the diff shows exactly what was added.

הוסיפו ראשי תיבות מתחת לכותרת `# שם הספר` הקיימת ב-`acronyms.txt`, או צרו בלוק חדש לספר חדש, ושלחו Pull Request.

## Building the database

```bash
./gradlew :acronymizer:buildDb        # data/ -> acronymizer.db
./gradlew :acronymizer:exportDataset  # acronymizer.db -> data/ (after pipeline runs)
```

Both accept the `acronymizer_db` and `acronymizer_data` env vars to override paths.
