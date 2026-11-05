"""Grades totals.csv against totals recomputed from the ledger's data (grader/ledger.json).

Accepts every reasonable rendering: header in any case, rows in any order, an optional currency mark
(EUR or the euro sign), and any number of decimals as long as the value is within half a cent.
"""
import csv, json, re, sys

data = json.load(open("ledger_expected.json"))
want = {}
for t in data:
    if t["date"].startswith("2026-10") and t["status"] == "settled":
        want[t["category"]] = want.get(t["category"], 0) + t["amount"]

try:
    rows = list(csv.reader(open("totals.csv", newline="")))
except FileNotFoundError:
    sys.exit("totals.csv is missing")
rows = [r for r in rows if any(c.strip() for c in r)]
if not rows or [c.strip().lower() for c in rows[0]] != ["category", "total"]:
    sys.exit(f"the header must be category,total; got {rows[:1]}")
got = {}
for r in rows[1:]:
    if len(r) != 2:
        sys.exit(f"bad row {r}")
    cat = r[0].strip().lower()
    num = re.sub(r"(?i)eur|€|\s", "", r[1])
    try:
        got[cat] = float(num)
    except ValueError:
        sys.exit(f"bad total {r[1]!r} for {cat}")
if set(got) != set(want):
    sys.exit(f"categories differ: got {sorted(got)}, want {sorted(want)}")
wrong = {c: (got[c], round(want[c], 2)) for c in want if abs(got[c] - want[c]) > 0.005}
if wrong:
    sys.exit(f"wrong totals (got, want): {wrong}")
print("totals ok")
