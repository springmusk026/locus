#!/usr/bin/env python3
"""Check Locus string translations against the English strings.xml files.

For every module with `src/main/res/values/strings.xml`, and every translation
folder it has (values-es, values-pt, values-b+zh+Hans), verify:

  * the same keys, strings and plurals, as English (translatable="false" skipped);
  * the same placeholders per string (%1$s, %2$d, %%, \\n ...);
  * plurals use the CLDR categories of the language (es, pt: one/many/other;
    zh: other), and every item has the placeholders English has;
  * no unescaped apostrophe or quote (an Android resource string needs \\' and \\",
    unless the whole string is in double quotes);
  * no leading @ or ? that Android would read as a resource reference;
  * well-formed XML, no empty text, no "..." where English has none.

Modules with no translation folder yet are listed as pending and do not fail the
run (use --strict to fail on them). Standard library only.

Usage: python3 tools/i18n_check.py [--strict] [module ...]
"""
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANGS = {
    "es": ("values-es", {"one", "many", "other"}),
    "pt": ("values-pt", {"one", "many", "other"}),
    "zh": ("values-b+zh+Hans", {"other"}),
}
EN_CATEGORIES = {"one", "other"}
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]|\\n|\\t")
UNESCAPED_APOS = re.compile(r"(?<!\\)'")
UNESCAPED_QUOTE = re.compile(r'(?<!\\)"')


def load(path):
    """Return ({name: text}, {name: {quantity: text}}) or raise on bad XML."""
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for e in root:
        if callable(e.tag):  # comment
            continue
        if e.get("translatable") == "false":
            continue
        if e.tag == "string":
            if e.get("name") in strings or e.get("name") in plurals:
                raise ValueError(f"duplicate key {e.get('name')}")
            strings[e.get("name")] = e.text or ""
        elif e.tag == "plurals":
            if e.get("name") in strings or e.get("name") in plurals:
                raise ValueError(f"duplicate key {e.get('name')}")
            items = {}
            for i in e:
                if callable(i.tag):
                    continue
                if i.tag != "item" or i.get("quantity") in items:
                    raise ValueError(f"bad plural item in {e.get('name')}")
                items[i.get("quantity")] = i.text or ""
            plurals[e.get("name")] = items
        else:
            raise ValueError(f"unsupported element <{e.tag}>")
    return strings, plurals


def placeholders(text):
    return Counter(PLACEHOLDER.findall(text))


def text_problems(key, text, english):
    out = []
    if not text.strip():
        out.append(f"{key}: empty text")
    wrapped = len(text) >= 2 and text.startswith('"') and text.endswith('"')
    if not wrapped:
        if UNESCAPED_APOS.search(text):
            out.append(f"{key}: unescaped apostrophe (use \\')")
        if UNESCAPED_QUOTE.search(text):
            out.append(f"{key}: unescaped double quote (use \\\")")
    if text[:1] in ("@", "?"):
        out.append(f"{key}: starts with {text[:1]!r}, escape it (\\{text[:1]})")
    if "..." in text and "..." not in english:
        out.append(f"{key}: three dots, use the single … character")
    return out


def check_module(mod_dir, strict):
    problems = []
    en_path = mod_dir / "src/main/res/values/strings.xml"
    try:
        en_s, en_p = load(en_path)
    except Exception as ex:  # noqa: BLE001
        return [f"{en_path}: {ex}"], 0
    for name, items in en_p.items():
        if set(items) != EN_CATEGORIES:
            problems.append(f"{en_path.name}:{name}: English plural needs exactly one/other, has {sorted(items)}")
    checked = 0
    for lang, (folder, cats) in LANGS.items():
        path = mod_dir / "src/main/res" / folder / "strings.xml"
        label = f"{mod_dir.relative_to(ROOT)}/{folder}"
        if not path.exists():
            if strict:
                problems.append(f"{label}: missing strings.xml")
            continue
        try:
            tr_s, tr_p = load(path)
        except Exception as ex:  # noqa: BLE001
            problems.append(f"{label}: {ex}")
            continue
        checked += 1
        add = lambda m: problems.append(f"{label}: {m}")  # noqa: E731
        for k in en_s:
            if k not in tr_s:
                add(f"missing string {k}")
        for k in en_p:
            if k not in tr_p:
                add(f"missing plurals {k}")
        for k in tr_s:
            if k not in en_s:
                add(f"extra string {k}")
        for k in tr_p:
            if k not in en_p:
                add(f"extra plurals {k}")
        # app's locale_name is the language's own name, not a translation
        for k, en_text in en_s.items():
            if k not in tr_s or k == "locale_name":
                continue
            t = tr_s[k]
            if placeholders(t) != placeholders(en_text):
                add(f"{k}: placeholders {dict(placeholders(t))} differ from English {dict(placeholders(en_text))}")
            for m in text_problems(k, t, en_text):
                add(m)
        for k, en_items in en_p.items():
            if k not in tr_p:
                continue
            items = tr_p[k]
            if set(items) != cats:
                add(f"{k}: plural categories {sorted(items)}, need {sorted(cats)}")
            # every item needs the placeholders of English "other" (the count is
            # allowed to be written out in `one`, as in "One action").
            want = placeholders(en_items["other"])
            for q, t in items.items():
                got = placeholders(t)
                if q == "one" and not got and got != want:
                    pass  # "1 action" may become "one action" in some languages
                elif got != want:
                    add(f"{k}[{q}]: placeholders {dict(got)} differ from English {dict(want)}")
                for m in text_problems(f"{k}[{q}]", t, en_items["other"]):
                    add(m)
    return problems, checked


def main(argv):
    strict = "--strict" in argv
    names = [a for a in argv if not a.startswith("--")]
    modules = sorted(
        p.parent.parent.parent.parent.parent
        for p in ROOT.glob("**/src/main/res/values/strings.xml")
        # Skip build output and hidden folders (.git, .gradle, agent worktrees under .claude).
        if not any(part == "build" or part.startswith(".") for part in p.relative_to(ROOT).parts)
    )
    if names:
        modules = [m for m in modules if str(m.relative_to(ROOT)) in names]
    failed = False
    for m in modules:
        problems, checked = check_module(m, strict)
        rel = m.relative_to(ROOT)
        if problems:
            failed = True
            print(f"FAIL {rel}")
            for p in problems:
                print(f"  {p}")
        elif checked == 0:
            print(f"pending {rel} (no translations yet)")
        else:
            print(f"ok   {rel} ({checked} languages)")
    print("FAILED" if failed else "PASSED")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
