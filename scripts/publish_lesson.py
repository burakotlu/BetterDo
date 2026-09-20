"""Add one reviewed draft to the remote catalog; no APK build is needed."""
import argparse
import json
import os
import sys
import tempfile
from pathlib import Path

from validate_content import CATALOG, load_catalog, validate_catalog, validate_lesson


def publish(draft, catalog_path=CATALOG):
    lesson = validate_lesson(json.loads(Path(draft).read_text(encoding="utf-8")))
    catalog_path = Path(catalog_path)
    catalog = load_catalog(catalog_path)
    catalog["lessons"].append(lesson)
    validate_catalog(catalog)
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=str(catalog_path.parent), delete=False) as output:
            temp_path = output.name
            json.dump(catalog, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.replace(temp_path, str(catalog_path))
    finally:
        if temp_path and os.path.exists(temp_path):
            os.unlink(temp_path)
    return lesson["id"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("draft", type=Path)
    parser.add_argument("--reviewed", action="store_true", required=True, help="Confirm translations, IPA, and quiz answers have been reviewed")
    args = parser.parse_args()
    try:
        print("Added lesson: " + publish(args.draft))
    except (OSError, ValueError) as error:
        print("Publish failed: {}".format(error), file=sys.stderr)
        sys.exit(1)
