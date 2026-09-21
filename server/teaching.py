"""Deterministic scripts reuse reviewed lessons; no second language model is needed."""
import re
from scripts.validate_content import text


def generate_teaching_script(word, definition, examples, level):
    for value in (word, definition):
        text(value, "Teaching script text")
    if level not in ("A1", "A2", "B1") or len(examples) < 2:
        raise ValueError("A valid level and two examples are required")
    for example in examples[:2]:
        text(example, "Example")
    script = ('Today\'s word is "{0}".\n"{0}" means {1}.\nFor example:\n{2}\n{3}\n'
              'Try saying it yourself: "{0}".').format(word.strip(), definition.strip().rstrip("."),
                                                       examples[0].strip(), examples[1].strip())
    if len(script.split()) < 38:
        script = "Let's practice a word you can use in everyday conversations.\n" + script
    # Never truncate a sentence or alter reviewed teaching content to fit the budget.
    if len(re.findall(r"\S+", script)) > 70 or len(script) > 900:
        raise ValueError("This lesson needs a shorter definition or examples for a 15-30 second video")
    return script
