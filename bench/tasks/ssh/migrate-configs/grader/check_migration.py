"""Grades the migration on the SSH server's home folder (argv[1]) against the expected configs.

Any JSON formatting and key order passes; values must have the right JSON types.
"""
import json, os, sys

home = sys.argv[1]
want = json.load(open("expected_configs.json"))
problems = []
for name, cfg in want.items():
    d = os.path.join(home, "services", name)
    if os.path.exists(os.path.join(d, "service.ini")):
        problems.append(f"{name}: service.ini not deleted")
    try:
        got = json.load(open(os.path.join(d, "service.json")))
    except Exception as e:
        problems.append(f"{name}: {e}"); continue
    if got != cfg:
        for sec in sorted(set(cfg) | set(got)):
            a, b = got.get(sec), cfg.get(sec)
            if a != b:
                problems.append(f"{name} [{sec}]: got {a!r}, want {b!r}")
if open(os.path.join(home, "services/legacy/service.ini.disabled")).read() != "[server]\nport = 9000\n":
    problems.append("legacy/service.ini.disabled was changed")
if problems:
    sys.exit("\n".join(problems))
print("migration ok")
