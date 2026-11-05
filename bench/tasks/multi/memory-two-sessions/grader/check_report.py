"""Grades the second chat's report: saved where and how the first chat asked, with the right facts."""
import datetime, os, re, sys

files = [f for f in os.listdir("reports")] if os.path.isdir("reports") else []
named = [f for f in files if re.fullmatch(r"(\d{4}-\d{2}-\d{2})-[\w.-]+\.md", f)]
if len(named) != 1:
    sys.exit(f"want exactly one reports/YYYY-MM-DD-<topic>.md, found {files}")
day = datetime.date.fromisoformat(named[0][:10])
if abs((day - datetime.date.today()).days) > 1:
    sys.exit(f"{named[0]}: not today's date")
text = open(os.path.join("reports", named[0])).read()
lines = [l for l in text.splitlines() if l.strip()]
if not lines or "tl;dr" not in " ".join(lines[:2]).lower():
    sys.exit(f"the report must start with a TL;DR line; starts: {lines[:2]}")
body = re.sub(r"\d{4}-\d{2}-\d{2}", "", text)
if not re.search(r"\b13\b", body):
    sys.exit("the report doesn't give the total of 13 TODOs")
if "sync.py" not in body:
    sys.exit("the report doesn't name sync.py as the file with the most")
print("report ok")
