#!/usr/bin/env python3
"""Per-module line and branch coverage from Kover's JaCoCo-format XML reports.

Run after `./gradlew koverXmlReportUnit` (every module's report plus the merged root one):
    python3 tools/coverage/summary.py
"""
import glob
import os
import xml.etree.ElementTree as ET


def counters(path):
    root = ET.parse(path).getroot()
    out = {}
    for c in root.findall("counter"):  # report-level totals only
        out[c.get("type")] = (int(c.get("covered")), int(c.get("missed")))
    return out


def pct(pair):
    covered, missed = pair
    total = covered + missed
    return "n/a" if total == 0 else f"{100.0 * covered / total:.1f}%"


def main():
    reports = sorted(glob.glob("**/build/reports/kover/reportUnit.xml", recursive=True))
    if not reports:
        raise SystemExit("No reports: run ./gradlew koverXmlReportUnit first")
    print("| Module | Lines | Line % | Branches | Branch % |")
    print("|---|---:|---:|---:|---:|")
    merged = None
    for path in reports:
        c = counters(path)
        line, branch = c.get("LINE", (0, 0)), c.get("BRANCH", (0, 0))
        if path.startswith("build/"):
            merged = (line, branch)
            continue
        module = ":" + path.split("/build/reports")[0].replace("/", ":")
        print(f"| {module} | {line[0]}/{sum(line)} | {pct(line)} | {branch[0]}/{sum(branch)} | {pct(branch)} |")
    if merged:
        line, branch = merged
        print(f"| **all (merged)** | {line[0]}/{sum(line)} | {pct(line)} | {branch[0]}/{sum(branch)} | {pct(branch)} |")

if __name__ == "__main__":
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    main()
