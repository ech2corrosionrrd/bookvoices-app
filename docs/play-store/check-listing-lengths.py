"""Перевіряє ліміти Play для listing-1.16-all-languages.md.

Короткий опис <= 80, повний <= 4000, «Що нового» <= 500 символів.
Запуск з кореня репозиторію: python docs/play-store/check-listing-lengths.py
"""
import pathlib
import re
import sys

PATH = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).with_name("listing-1.18-all-languages.md")
text = PATH.read_text(encoding="utf-8")

ok = True
for section in re.split(r"\n## ", text)[1:]:
    lang = section.split("\n", 1)[0].strip()
    short = re.search(r"^\*\*Short:\*\* (.+)$", section, re.M)
    blocks = re.findall(r"```text\n(.*?)\n```", section, re.S)
    if not short or len(blocks) != 2:
        continue
    checks = [("short", short.group(1).strip(), 80),
              ("full", blocks[0], 4000),
              ("whats-new", blocks[1], 500)]
    report = []
    for name, value, limit in checks:
        n = len(value)
        flag = "" if n <= limit else "  <-- ПЕРЕВИЩЕНО"
        ok &= n <= limit
        report.append(f"{name}={n}/{limit}{flag}")
    print(f"{lang:6} " + "  ".join(report))

sys.exit(0 if ok else 1)
