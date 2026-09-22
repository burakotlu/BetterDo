CREATE TABLE generated_lessons (
    id TEXT PRIMARY KEY,
    language TEXT NOT NULL,
    word_key TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at REAL NOT NULL,
    UNIQUE(language, word_key)
);
CREATE TABLE lesson_jobs (
    id TEXT PRIMARY KEY,
    language TEXT NOT NULL,
    category TEXT NOT NULL,
    level TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('pending','processing','completed','failed')),
    lesson_id TEXT REFERENCES generated_lessons(id),
    error TEXT,
    created_at REAL NOT NULL
);
CREATE INDEX lesson_jobs_created ON lesson_jobs(created_at);
PRAGMA user_version = 2;
