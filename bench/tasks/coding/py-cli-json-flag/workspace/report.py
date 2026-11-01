import csv
import sys


def load(path="data.csv"):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def main(argv):
    rows = load()
    print(f"{'City':<12}{'Population':>12}")
    for row in rows:
        print(f"{row['city']:<12}{int(row['population']):>12,}")


if __name__ == "__main__":
    main(sys.argv[1:])
