import sqlite3
from contextlib import contextmanager
from pathlib import Path


class VideoDatabase:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version == 0:
                db.executescript(Path(__file__).with_name("migrations").joinpath("001_videos.sql").read_text())
            elif version != 1:
                raise ValueError("Unsupported video database version")

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def get(self, lesson_id):
        with self.connect() as db:
            row = db.execute("SELECT * FROM lesson_videos WHERE lesson_id=?", (lesson_id,)).fetchone()
            return dict(row) if row else None
