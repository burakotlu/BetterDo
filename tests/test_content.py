import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from validate_content import load_catalog, validate_catalog, validate_lesson
from publish_lesson import publish
from generate_lesson import generate


class ContentTests(unittest.TestCase):
    def setUp(self):
        self.catalog = load_catalog()
        self.lesson = copy.deepcopy(self.catalog["lessons"][0])

    def test_seed_has_week_in_each_language(self):
        for language in ("en", "de"):
            self.assertGreaterEqual(sum(item["language"] == language for item in self.catalog["lessons"]), 7)

    def test_boolean_answer_is_not_an_index(self):
        self.lesson["quiz"][0]["answer"] = True
        with self.assertRaises(ValueError):
            validate_lesson(self.lesson)

    def test_duplicate_options_rejected(self):
        self.lesson["quiz"][0]["options"] = ["Yes", " yes ", "No"]
        with self.assertRaises(ValueError):
            validate_lesson(self.lesson)

    def test_traversal_slug_rejected(self):
        self.lesson["id"] = "en-../../escape"
        with self.assertRaises(ValueError):
            validate_lesson(self.lesson)

    def test_duplicate_word_with_new_id_rejected(self):
        self.lesson["id"] = "en-other"
        self.catalog["lessons"].append(self.lesson)
        with self.assertRaises(ValueError):
            validate_catalog(self.catalog)

    def test_missing_translation_rejected(self):
        del self.lesson["examples"][0]["translation"]
        with self.assertRaises(ValueError):
            validate_lesson(self.lesson)

    def test_publish_preserves_catalog_on_invalid_draft(self):
        with tempfile.TemporaryDirectory() as directory:
            catalog = Path(directory) / "catalog.json"
            draft = Path(directory) / "draft.json"
            original = json.dumps(self.catalog, ensure_ascii=False)
            catalog.write_text(original, encoding="utf-8")
            draft.write_text(json.dumps(self.lesson), encoding="utf-8")
            with self.assertRaises(ValueError):
                publish(draft, catalog)
            self.assertEqual(original, catalog.read_text(encoding="utf-8"))

    def test_publish_new_draft(self):
        with tempfile.TemporaryDirectory() as directory:
            catalog = Path(directory) / "catalog.json"
            draft = Path(directory) / "draft.json"
            catalog.write_text(json.dumps(self.catalog), encoding="utf-8")
            self.lesson.update(id="en-test-fixture", word="test fixture")
            draft.write_text(json.dumps(self.lesson), encoding="utf-8")
            self.assertEqual(publish(draft, catalog), "en-test-fixture")
            self.assertEqual(len(load_catalog(catalog)["lessons"]), len(self.catalog["lessons"]) + 1)

    @patch("generate_lesson.urlopen")
    def test_agent_repairs_bad_response(self, mock_urlopen):
        self.lesson.update(id="en-test-fixture", word="test fixture")
        mock_urlopen.return_value.__enter__.return_value.read.side_effect = [
            json.dumps({"message": {"content": "not json"}}).encode(),
            json.dumps({"message": {"content": json.dumps(self.lesson)}}).encode()
        ]
        self.assertEqual(generate("en", "daily life", "test-model", "http://localhost:11434")["id"], "en-test-fixture")
        self.assertEqual(mock_urlopen.call_count, 2)

    @patch("generate_lesson.urlopen")
    def test_agent_stops_after_three_invalid_responses(self, mock_urlopen):
        mock_urlopen.return_value.__enter__.return_value.read.return_value = b'{"message":{"content":"{}"}}'
        with self.assertRaises(ValueError):
            generate("en", "daily life", "test-model", "http://localhost:11434")
        self.assertEqual(mock_urlopen.call_count, 3)


if __name__ == "__main__":
    unittest.main()
