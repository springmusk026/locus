"""Runs the server's fixed build-report.sh on new data (so a hand-written summary.json can't pass) and checks the result."""
import json, os, shutil, subprocess, sys

app = os.path.join(sys.argv[1], "app")
script = os.path.join(app, "build-report.sh")
if not os.path.isfile(script):
    sys.exit("app/build-report.sh is missing")
NEW = "region,units,unit_price\nwest,2,10.00\nwest,3,10.00\nnorth,1,0.99\ncentral,4,1.25\n"
with open(os.path.join(app, "data/sales.csv"), "w") as f:
    f.write(NEW)
shutil.rmtree(os.path.join(app, "out"), ignore_errors=True)
r = subprocess.run(["sh", script], capture_output=True, text=True, timeout=60)
if r.returncode != 0:
    sys.exit(f"build-report.sh failed ({r.returncode}): {r.stdout} {r.stderr}")
try:
    got = json.load(open(os.path.join(app, "out/summary.json")))
except Exception as e:
    sys.exit(f"out/summary.json: {e}")
want = {"west": (5, 50.0), "north": (1, 0.99), "central": (4, 5.0)}
regions = got.get("regions", {})
if set(regions) != set(want):
    sys.exit(f"regions: got {sorted(regions)}")
for name, (units, revenue) in want.items():
    g = regions[name]
    if g.get("units") != units or abs(float(g.get("revenue", -1)) - revenue) > 0.005:
        sys.exit(f"{name}: got {g}, want units {units} revenue {revenue}")
if abs(float(got.get("total_revenue", -1)) - 55.99) > 0.005:
    sys.exit(f"total_revenue: got {got.get('total_revenue')}")
print("report ok")
