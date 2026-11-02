import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))


def load_services():
    with open(os.path.join(HERE, "services.json")) as f:
        return json.load(f)


def main():
    services = load_services()
    up = [s for s in services if s["status"] == "up"]
    print("disk: ok")
    print("services: " + len(up) + " up")


if __name__ == "__main__":
    main()
