# Acronymizer Database Structure

## Overview

The database uses a normalized relational structure with junction tables to efficiently manage acronyms.

## Relational Schema

```
┌─────────────┐         ┌──────────────────┐         ┌──────────────┐
│   Books     │         │  BookAcronyms    │         │  Acronyms    │
├─────────────┤         ├──────────────────┤         ├──────────────┤
│ id (PK)     │◄───────┤ book_id (FK)     │         │ id (PK)      │
│ title       │         │ acronym_id (FK)  ├────────►│ acronym      │
└─────────────┘         └──────────────────┘         └──────────────┘
```

## Tables

### 1. `Books`
Main table containing unique books.

| Column      | Type    | Description                          |
|-------------|---------|--------------------------------------|
| id          | INTEGER | Auto-incremented primary key         |
| title       | TEXT    | Unique book title                    |

**Indexes**:
- `idx_books_title` on `title`

### 2. `Acronyms`
Table containing all unique acronyms (deduplicated).

| Column      | Type    | Description                          |
|-------------|---------|--------------------------------------|
| id          | INTEGER | Auto-incremented primary key         |
| acronym     | TEXT    | Unique acronym                       |

**Indexes**:
- `idx_acronyms_acronym` on `acronym`

**Example acronyms**:
- `רמב"ם`
- `שו"ע או"ח`
- `ב"ק`
- `משנ"ב`

### 3. `BookAcronyms`
Many-to-many junction table between Books and Acronyms.

| Column       | Type    | Description                          |
|--------------|---------|--------------------------------------|
| id           | INTEGER | Auto-incremented primary key         |
| book_id      | INTEGER | Foreign key to Books(id)             |
| acronym_id   | INTEGER | Foreign key to Acronyms(id)          |

**Constraints**:
- `FOREIGN KEY (book_id)` → `Books(id)` ON DELETE CASCADE
- `FOREIGN KEY (acronym_id)` → `Acronyms(id)` ON DELETE CASCADE
- `UNIQUE(book_id, acronym_id)` - Prevents duplicates

**Indexes**:
- `idx_book_acronyms_book_id` on `book_id`
- `idx_book_acronyms_acronym_id` on `acronym_id`

## Compatibility View

### `AcronymResults` (View)
View to ensure backward compatibility with the old CSV format.

```sql
CREATE VIEW AcronymResults AS
SELECT
    b.id,
    b.title AS book_title,
    COALESCE(GROUP_CONCAT(a.acronym, ','), '') AS terms
FROM Books b
LEFT JOIN BookAcronyms ba ON b.id = ba.book_id
LEFT JOIN Acronyms a ON ba.acronym_id = a.id
GROUP BY b.id, b.title
ORDER BY b.id;
```

This view allows using old queries without modification.

## Relational Structure Benefits

### 1. **No Duplication**
Each acronym is stored only once, even if used by multiple books.

**Example**: The acronym `רמב"ם` is stored once, but linked to:
- משנה תורה, הלכות דעות
- משנה תורה, הלכות שבת
- משנה תורה, הלכות ברכות
- ... (88 books total)

### 2. **Efficient Search**
Indexes enable fast searches:
- By book title
- By acronym
- Optimized many-to-many relationships

### 3. **Data Integrity**
- FOREIGN KEY constraints guarantee referential integrity
- UNIQUE constraints prevent duplicates
- CASCADE allows clean deletion

### 4. **Simplified Maintenance**
- Modify an acronym → single row in `Acronyms`
- Add an acronym to a book → single row in `BookAcronyms`
- Delete a book → automatic link deletion (CASCADE)

## Common Queries

### Find all acronyms for a book

```sql
SELECT a.acronym
FROM Books b
JOIN BookAcronyms ba ON b.id = ba.book_id
JOIN Acronyms a ON ba.acronym_id = a.id
WHERE b.title = 'בראשית';
```

### Find all books for an acronym

```sql
SELECT b.title
FROM Acronyms a
JOIN BookAcronyms ba ON a.id = ba.acronym_id
JOIN Books b ON ba.book_id = b.id
WHERE a.acronym = 'רמב"ם';
```

### Count acronyms per book

```sql
SELECT b.title, COUNT(ba.acronym_id) as acronym_count
FROM Books b
LEFT JOIN BookAcronyms ba ON b.id = ba.book_id
GROUP BY b.id, b.title
ORDER BY acronym_count DESC;
```

### Find acronyms shared by multiple books

```sql
SELECT a.acronym, COUNT(ba.book_id) as book_count
FROM Acronyms a
JOIN BookAcronyms ba ON a.id = ba.acronym_id
GROUP BY a.id, a.acronym
HAVING COUNT(ba.book_id) > 1
ORDER BY book_count DESC;
```

## Migration

Migration from the old CSV format to the relational structure was handled by the Kotlin script `MigrateToRelational.kt` (now removed as it was a one-shot operation).

### Migration Process (completed)

1. **Automatic backup**: Created backup with timestamp
2. **Schema creation**: Books, Acronyms, BookAcronyms tables
3. **Book migration**: Copied from AcronymResults to Books
4. **Acronym extraction**: Parsed CSV and deduplicated
5. **Link creation**: Populated BookAcronyms
6. **Renaming**: AcronymResults → AcronymResults_old
7. **Compatibility view**: Created AcronymResults view
8. **Verification**: Record counting

### Rollback

If necessary, restore from backup:

```bash
cp acronymizer_backup_TIMESTAMP.db acronymizer.db
```

## Database Cleanup

After migration, cleanup was performed to optimize the structure.

### Cleanup Operations (completed)

1. **Backup creation**: Automatic backup with timestamp
2. **Table recreation**: New tables without timestamp fields
3. **Data migration**: Copied data (id, title, acronym)
4. **Old table deletion**: Including AcronymResults_old
5. **Renaming**: Activated new tables
6. **Index recreation**: To maintain performance
7. **View recreation**: AcronymResults without created_at
8. **VACUUM**: Disk space recovery (58% reduction)

## Statistics (after migration and cleanup)

- **Books**: 6,878
- **Unique Acronyms**: 27,475
- **Book-Acronym Links**: 28,273
- **Average acronyms per book**: ~4.1
- **Database size**: 5.9 MB (after VACUUM)

### Optimizations Applied

1. **Relational migration**: Transformation from CSV structure to normalized tables
   - Acronym deduplication (unique storage)
   - Many-to-many relationships via junction table

2. **Database cleanup**:
   - Removed all `created_at` and `updated_at` fields
   - Deleted old `AcronymResults_old` table
   - VACUUM to recover disk space
   - **Size reduction**: 14 MB → 5.9 MB (58% savings)

## Maintenance

### Add an acronym to a book

```sql
INSERT INTO BookAcronyms (book_id, acronym_id)
SELECT
    (SELECT id FROM Books WHERE title = 'בראשית'),
    (SELECT id FROM Acronyms WHERE acronym = 'בר''')
WHERE NOT EXISTS (
    SELECT 1 FROM BookAcronyms
    WHERE book_id = (SELECT id FROM Books WHERE title = 'בראשית')
    AND acronym_id = (SELECT id FROM Acronyms WHERE acronym = 'בר''')
);
```

### Remove an acronym from a book

```sql
DELETE FROM BookAcronyms
WHERE book_id = (SELECT id FROM Books WHERE title = 'בראשית')
AND acronym_id = (SELECT id FROM Acronyms WHERE acronym = 'בר''');
```

### Clean up orphaned acronyms

```sql
DELETE FROM Acronyms
WHERE id NOT IN (SELECT DISTINCT acronym_id FROM BookAcronyms);
```
