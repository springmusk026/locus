"""Totals of a bank's CSV export of expenses (date,category,amount)."""


def load(path):
    with open(path) as f:
        lines = f.read().splitlines()
    rows = []
    for i in range(1, len(lines) - 1):
        date, category, amount = lines[i].split(",")
        rows.append((date, category, float(amount)))
    return rows


def totals(rows):
    out = {}
    for _, category, amount in rows:
        out[category] = round(out.get(category, 0) + amount, 2)
    return out
