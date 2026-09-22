"""On-demand lessons: validated model output, persistent jobs, and reusable catalog."""
import json
import threading
import time
import uuid
from scripts.generate_lesson import generate
from scripts.validate_content import validate_lesson

CATEGORIES = [
    {"id": "daily-life", "title": "Daily life", "description": "Useful words for everyday conversations"},
    {"id": "travel", "title": "Travel", "description": "Airports, directions, hotels, and exploring"},
    {"id": "work", "title": "Work", "description": "Meetings, colleagues, and getting things done"},
    {"id": "food", "title": "Food and drinks", "description": "Ordering food, cooking, and shopping"},
    {"id": "social", "title": "Friends and feelings", "description": "Meeting people and expressing yourself"},
    {"id": "shopping", "title": "Shopping", "description": "Prices, sizes, purchases, and returns"},
]


class LessonService:
    def __init__(self, database, model, base_url, limit=30, generator=generate):
        self.database, self.model, self.base_url = database, model, base_url
        self.limit, self.generator = limit, generator
        self.lock = threading.Lock()

    def categories(self):
        return {"categories": CATEGORIES, "generationEnabled": bool(self.model), "levels": ["A1", "A2", "B1"]}

    def get(self, identity):
        with self.database.connect() as db:
            row = db.execute("SELECT * FROM lesson_jobs WHERE id=?", (identity,)).fetchone()
            if row is None:
                return None
            lesson = db.execute("SELECT body FROM generated_lessons WHERE id=?", (row["lesson_id"],)).fetchone()
            return {"id": row["id"], "status": row["status"], "error": row["error"],
                    "lesson": json.loads(lesson[0]) if lesson else None}

    def catalog(self, language=None):
        with self.database.connect() as db:
            rows = db.execute("SELECT body FROM generated_lessons WHERE (? IS NULL OR language=?) ORDER BY created_at DESC LIMIT 500", (language, language)).fetchall()
            return {"version": 1, "lessons": [json.loads(row[0]) for row in rows]}

    def create(self, identity, language, category, level):
        if not isinstance(identity, str) or len(identity) != 32 or any(c not in "0123456789abcdef" for c in identity):
            raise ValueError("Invalid lesson request ID")
        if language not in ("en", "de") or category not in [item["id"] for item in CATEGORIES] or level not in ("A1", "A2", "B1"):
            raise ValueError("Choose a supported language, category, and level")
        if not self.model:
            raise ValueError("The server needs AI_LESSON_MODEL and a running Ollama service")
        with self.database.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            old = db.execute("SELECT * FROM lesson_jobs WHERE id=?", (identity,)).fetchone()
            if old:
                if (old["language"], old["category"], old["level"]) != (language, category, level):
                    raise ValueError("Request ID already belongs to another lesson request")
            else:
                count = db.execute("SELECT COUNT(*) FROM lesson_jobs WHERE created_at>?", (time.time() - 86400,)).fetchone()[0]
                if count >= self.limit:
                    raise ValueError("The server's daily lesson generation allowance is used up")
                active = db.execute("SELECT COUNT(*) FROM lesson_jobs WHERE status IN ('pending','processing')").fetchone()[0]
                if active >= 5:
                    raise ValueError("The lesson queue is full. Try again later")
                db.execute("INSERT INTO lesson_jobs (id,language,category,level,status,created_at) VALUES (?,?,?,?,'pending',?)",
                           (identity, language, category, level, time.time()))
        return self.get(identity)

    def recover(self):
        with self.database.connect() as db:
            db.execute("UPDATE lesson_jobs SET status='failed',error='Lesson generation was interrupted. Please try again.' WHERE status='processing'")

    def tick(self):
        with self.lock:
            with self.database.connect() as db:
                db.execute("BEGIN IMMEDIATE")
                row = db.execute("SELECT * FROM lesson_jobs WHERE status='pending' ORDER BY created_at LIMIT 1").fetchone()
                if row is None:
                    return
                db.execute("UPDATE lesson_jobs SET status='processing' WHERE id=?", (row["id"],))
            try:
                lesson = self.generator(row["language"], row["category"], self.model, self.base_url,
                                        catalog=self.catalog(row["language"]), level=row["level"])
                validate_lesson(lesson)
                if lesson["language"] != row["language"] or lesson["level"] != row["level"]:
                    raise ValueError("Wrong language or level")
                # IDs belong to the server, never to the model. No collision with published IDs.
                lesson["id"] = row["language"] + "-generated-" + uuid.uuid4().hex
                lesson["category"] = next(item["title"] for item in CATEGORIES if item["id"] == row["category"])
                lesson["context"] += "\nAI-generated lesson. Check unfamiliar usage with a trusted dictionary."
                validate_lesson(lesson)
                with self.database.connect() as db:
                    db.execute("INSERT INTO generated_lessons VALUES (?,?,?,?,?)", (lesson["id"], lesson["language"], lesson["word"].strip().casefold(), json.dumps(lesson), time.time()))
                    db.execute("UPDATE lesson_jobs SET status='completed',lesson_id=? WHERE id=?", (lesson["id"], row["id"]))
            except Exception:
                with self.database.connect() as db:
                    db.execute("UPDATE lesson_jobs SET status='failed',error=? WHERE id=?",
                               ("The model could not produce a valid new lesson. Please try again.", row["id"]))
