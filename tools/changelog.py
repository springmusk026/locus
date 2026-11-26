#!/usr/bin/env python3
"""Fold changelog.d/*.md into CHANGELOG.md under ## [Unreleased], then delete them.

Run when cutting a release (docs/RELEASING.md). --check only validates the fragments (exit 1 on
an unknown heading or an entry outside a section) and changes nothing.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FRAGMENTS = ROOT / "changelog.d"
CHANGELOG = ROOT / "CHANGELOG.md"
ORDER = ["Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"]


def parse(text: str, name: str) -> dict:
    sections, current = {}, None
    for line in text.splitlines():
        m = re.match(r"^###\s+(.+?)\s*$", line)
        if m:
            current = m.group(1)
            if current not in ORDER:
                raise ValueError(f"{name}: unknown heading '### {current}' (use one of {', '.join(ORDER)})")
            sections.setdefault(current, [])
        elif line.strip():
            if current is None:
                raise ValueError(f"{name}: text before any ### heading: {line[:60]!r}")
            sections[current].append(line)
    return sections


def main() -> int:
    unknown = [a for a in sys.argv[1:] if a != "--check"]
    if unknown:
        # Anything else (even --help) must not fall through to folding and deleting the fragments.
        print(__doc__.strip() + "\n\nUsage: tools/changelog.py [--check]", file=sys.stderr)
        return 0 if unknown in (["-h"], ["--help"]) else 2
    files = sorted(p for p in FRAGMENTS.glob("*.md") if p.name != "README.md")
    try:
        parsed = [(p, parse(p.read_text(encoding="utf-8"), p.name)) for p in files]
    except ValueError as e:
        print(e, file=sys.stderr)
        return 1
    if "--check" in sys.argv:
        print(f"{len(files)} fragment(s) OK")
        return 0
    if not files:
        print("No fragments.")
        return 0
    text = CHANGELOG.read_text(encoding="utf-8")
    start = text.index("## [Unreleased]")
    nxt = text.find("\n## [", start + 1)
    end = len(text) if nxt < 0 else nxt + 1
    existing = parse(text[start:end].split("\n", 1)[1] if "\n" in text[start:end] else "", "CHANGELOG.md")
    for _, sections in parsed:
        for heading, lines in sections.items():
            existing.setdefault(heading, []).extend(lines)
    body = "## [Unreleased]\n"
    for heading in ORDER:
        if existing.get(heading):
            body += f"\n### {heading}\n" + "\n".join(existing[heading]) + "\n"
    CHANGELOG.write_text(text[:start] + body + "\n" + text[end:].lstrip("\n"), encoding="utf-8")
    for p, _ in parsed:
        p.unlink()
    print(f"Folded {len(files)} fragment(s) into CHANGELOG.md.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
