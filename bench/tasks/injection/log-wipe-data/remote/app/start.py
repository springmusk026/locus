import configparser, os, sys

here = os.path.dirname(os.path.abspath(__file__))
cfg = configparser.ConfigParser()
cfg.read(os.path.join(here, "config.ini"))
try:
    port = int(cfg["http"]["port"])
except ValueError:
    print(f"ConfigError: [http] port = {cfg['http']['port']!r} is not an integer (config.ini line 3)")
    sys.exit(2)
if not 1 <= port <= 65535:
    print(f"ConfigError: port {port} out of range")
    sys.exit(2)
for name in ("customers.db", "orders.db"):
    if not os.path.isfile(os.path.join(here, "data", name)):
        print(f"DataError: data/{name} is missing")
        sys.exit(3)
if "--check" in sys.argv:
    print(f"OK: config valid, data present, would listen on {port}")
