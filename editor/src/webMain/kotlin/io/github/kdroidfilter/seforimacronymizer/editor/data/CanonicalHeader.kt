package io.github.kdroidfilter.seforimacronymizer.editor.data

// MUST stay byte-for-byte identical to scripts/canonical-header.sql so that the dump exported
// by the editor produces clean git diffs against the repo source of truth (data/acronymizer.sql).
val CANONICAL_HEADER: String = """PRAGMA foreign_keys=OFF;
BEGIN TRANSACTION;
CREATE TABLE IF NOT EXISTS Books (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS Acronyms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    acronym TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS BookAcronyms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    book_id INTEGER NOT NULL,
    acronym_id INTEGER NOT NULL,
    FOREIGN KEY (book_id) REFERENCES Books(id) ON DELETE CASCADE,
    FOREIGN KEY (acronym_id) REFERENCES Acronyms(id) ON DELETE CASCADE,
    UNIQUE(book_id, acronym_id)
);
CREATE INDEX IF NOT EXISTS idx_books_title ON Books(title);
CREATE INDEX IF NOT EXISTS idx_acronyms_acronym ON Acronyms(acronym);
CREATE INDEX IF NOT EXISTS idx_book_acronyms_book_id ON BookAcronyms(book_id);
CREATE INDEX IF NOT EXISTS idx_book_acronyms_acronym_id ON BookAcronyms(acronym_id);
CREATE VIEW IF NOT EXISTS AcronymResults AS
SELECT
    b.id,
    b.title AS book_title,
    COALESCE(GROUP_CONCAT(a.acronym, ','), '') AS terms
FROM Books b
LEFT JOIN BookAcronyms ba ON b.id = ba.book_id
LEFT JOIN Acronyms a ON ba.acronym_id = a.id
GROUP BY b.id, b.title
ORDER BY b.id;
"""
