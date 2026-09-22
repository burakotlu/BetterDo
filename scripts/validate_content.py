"""Validate the lesson format shared by the Android app and content agent."""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "content/lessons.json"
LEVELS = ("A1", "A2", "B1", "B2", "C1", "C2")


def text(value, field, maximum=1200):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ValueError("{} must be nonempty text (max {} characters)".format(field, maximum))
    if any(ord(char) < 32 and char not in "\n\t" for char in value):
        raise ValueError("{} contains control characters".format(field))


def validate_lesson(lesson):
    if not isinstance(lesson, dict):
        raise ValueError("A lesson must be an object")
    fields = ("id", "language", "word", "pronunciation", "meaning", "partOfSpeech", "level", "category", "context", "dialogueContext")
    for field in fields:
        text(lesson.get(field), field)
    if lesson["language"] not in ("en", "de"):
        raise ValueError("language must be en or de")
    if not re.fullmatch(lesson["language"] + r"-[a-z0-9]+(?:-[a-z0-9]+)*", lesson["id"]):
        raise ValueError("id must be a language-prefixed slug")
    if lesson["level"] not in LEVELS:
        raise ValueError("level must be A1, A2, B1, B2, C1, or C2")
    for field, low, high in (("examples", 3, 3), ("dialogue", 3, 5), ("quiz", 2, 2)):
        items = lesson.get(field)
        if not isinstance(items, list) or not low <= len(items) <= high:
            raise ValueError("{} needs {}–{} items".format(field, low, high))
        if not all(isinstance(item, dict) for item in items):
            raise ValueError("{} items must be objects".format(field))
    for line in lesson["examples"] + lesson["dialogue"]:
        text(line.get("text"), "text")
        text(line.get("translation"), "translation")
    for line in lesson["dialogue"]:
        if line.get("speaker") not in ("A", "B"):
            raise ValueError("dialogue speaker must be A or B")
    for question in lesson["quiz"]:
        text(question.get("question"), "question")
        text(question.get("explanation"), "explanation")
        options = question.get("options")
        if not isinstance(options, list) or len(options) != 3:
            raise ValueError("Each quiz question needs three options")
        for option in options:
            text(option, "option")
        if len(set(option.casefold().strip() for option in options)) != 3:
            raise ValueError("Quiz options must be distinct")
        answer = question.get("answer")
        if type(answer) is not int or not 0 <= answer < 3:
            raise ValueError("answer must be an integer option index (0–2)")
    return lesson


def validate_catalog(data):
    if not isinstance(data, dict) or type(data.get("version")) is not int or data.get("version") != 1:
        raise ValueError("Catalog version must be 1")
    lessons = data.get("lessons")
    if not isinstance(lessons, list):
        raise ValueError("Catalog must contain lessons")
    ids, words, languages = set(), set(), set()
    for lesson in lessons:
        validate_lesson(lesson)
        key = (lesson["language"], lesson["word"].casefold().strip())
        if lesson["id"] in ids or key in words:
            raise ValueError("Duplicate lesson: " + lesson["id"])
        ids.add(lesson["id"])
        words.add(key)
        languages.add(lesson["language"])
    return data


def load_catalog(path=CATALOG):
    return validate_catalog(json.loads(Path(path).read_text(encoding="utf-8")))


if __name__ == "__main__":
    try:
        data = load_catalog()
        print("Valid catalog: {} lessons.".format(len(data["lessons"])))
    except (OSError, ValueError) as error:
        print("Content validation failed: {}".format(error), file=sys.stderr)
        sys.exit(1)
