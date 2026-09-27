CREATE TABLE accounts (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    password_salt BLOB NOT NULL,
    password_hash BLOB NOT NULL,
    verified INTEGER NOT NULL DEFAULT 0 CHECK(verified IN (0,1)),
    code_hash BLOB,
    code_expires_at REAL,
    code_attempts INTEGER NOT NULL DEFAULT 0,
    created_at REAL NOT NULL
);
CREATE TABLE account_sessions (
    token_hash BLOB PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    expires_at REAL NOT NULL
);
CREATE INDEX account_sessions_user ON account_sessions(account_id);
CREATE TABLE auth_attempts (
    email TEXT NOT NULL,
    action TEXT NOT NULL,
    attempted_at REAL NOT NULL
);
CREATE INDEX auth_attempts_recent ON auth_attempts(email,action,attempted_at);
ALTER TABLE lesson_jobs ADD COLUMN owner_id TEXT REFERENCES accounts(id);
CREATE INDEX lesson_jobs_owner_recent ON lesson_jobs(owner_id,created_at);
PRAGMA user_version = 3;
