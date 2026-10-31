#!/usr/bin/env python3
"""Per-module line and branch coverage from Kover's JaCoCo-format XML reports.

Run after `./gradlew koverXmlReportUnit` (every module's report plus the merged root one):
    python3 tools/coverage/summary.py

Prints two tables:
- by module, each counting only that module's own tests;
- by module from the merged report, where a module's code also counts as covered when another
  module's tests run it (core:datastore's SecretStore under core:data's tests, for example).
"""
import glob
import os
import xml.etree.ElementTree as ET
from collections import defaultdict

MERGED = "build/reports/kover/reportUnit.xml"


def counters(element):
    return {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in element.findall("counter")}


def pct(pair):
    covered, missed = pair
    total = covered + missed
    return "n/a" if total == 0 else f"{100.0 * covered / total:.1f}%"


def row(name, c):
    line, branch = c.get("LINE", (0, 0)), c.get("BRANCH", (0, 0))
    return f"| {name} | {line[0]}/{sum(line)} | {pct(line)} | {branch[0]}/{sum(branch)} | {pct(branch)} |"


HEADER = "| Module | Lines | Line % | Branches | Branch % |\n|---|---:|---:|---:|---:|"


def module_of(package):
    parts = package.split("/")  # dev/locus/core/data/repository
    return ":" + ":".join(parts[2:4]) if len(parts) >= 4 and parts[2] in ("core", "feature") else ":app"


def main():
    reports = sorted(p for p in glob.glob("**/build/reports/kover/reportUnit.xml", recursive=True) if p != MERGED and "core/testing" not in p)
    if not reports:
        raise SystemExit("No reports: run ./gradlew koverXmlReportUnit first")
    print("Own tests only:\n")
    print(HEADER)
    for path in reports:
        print(row(":" + path.split("/build/reports")[0].replace("/", ":"), counters(ET.parse(path).getroot())))

    if not os.path.exists(MERGED):
        return
    root = ET.parse(MERGED).getroot()
    by_module = defaultdict(lambda: defaultdict(lambda: (0, 0)))
    for package in root.findall("package"):
        totals = by_module[module_of(package.get("name"))]
        for kind, (covered, missed) in counters(package).items():
            c, m = totals[kind]
            totals[kind] = (c + covered, m + missed)
    print("\nAll tests (merged report):\n")
    print(HEADER)
    for module in sorted(by_module):
        print(row(module, by_module[module]))
    print(row("**all**", counters(root)))


if __name__ == "__main__":
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    main()
