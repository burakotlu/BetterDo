CREATE TABLE lesson_videos (
    id TEXT PRIMARY KEY,
    lesson_id TEXT NOT NULL UNIQUE,
    provider TEXT NOT NULL,
    provider_job_id TEXT,
    status TEXT NOT NULL CHECK(status IN ('pending', 'processing', 'completed', 'failed')),
    request TEXT NOT NULL,
    script TEXT NOT NULL,
    pronunciation TEXT NOT NULL,
    video_url TEXT,
    source_url TEXT,
    duration REAL,
    error_message TEXT,
    failure_kind TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    polls INTEGER NOT NULL DEFAULT 0,
    next_check REAL NOT NULL,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL
);
CREATE TABLE generation_requests (id TEXT PRIMARY KEY, created_at REAL NOT NULL);
CREATE INDEX generation_requests_created ON generation_requests(created_at);
PRAGMA user_version = 1;
