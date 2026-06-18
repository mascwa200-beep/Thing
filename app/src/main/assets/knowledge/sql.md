# SQL reference

## Query basics
```sql
SELECT id, name FROM users WHERE active = 1 ORDER BY name ASC LIMIT 10;
SELECT COUNT(*), country FROM users GROUP BY country HAVING COUNT(*) > 5;
SELECT DISTINCT country FROM users;
SELECT * FROM users WHERE name LIKE 'A%' AND age BETWEEN 18 AND 30;
```

## Joins
```sql
SELECT u.name, o.total
FROM users u
JOIN orders o ON o.user_id = u.id;        -- INNER: matching rows only
-- LEFT JOIN keeps all users, NULLs where no order.
```

## Mutations
```sql
INSERT INTO users (name, age) VALUES ('A', 30);
UPDATE users SET age = 31 WHERE id = 1;
DELETE FROM users WHERE id = 1;           -- always include WHERE!
```

## Schema
```sql
CREATE TABLE users (
  id    INTEGER PRIMARY KEY AUTOINCREMENT,
  name  TEXT NOT NULL,
  age   INTEGER DEFAULT 0,
  email TEXT UNIQUE
);
CREATE INDEX idx_users_name ON users(name);   -- speeds WHERE/ORDER on name
```

## Idioms
- Aggregates: COUNT, SUM, AVG, MIN, MAX (with GROUP BY). WHERE filters rows, HAVING filters groups.
- Use parameterized queries (`?` placeholders) to prevent SQL injection — never string-concat input.
- Index columns used in WHERE/JOIN/ORDER. Transactions (BEGIN/COMMIT/ROLLBACK) keep multi-step writes atomic.
- NULL is "unknown": use `IS NULL` / `IS NOT NULL`, not `= NULL`.
