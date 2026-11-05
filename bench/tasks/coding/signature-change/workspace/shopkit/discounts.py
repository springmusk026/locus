"""Discount codes: a percentage off, or a fixed amount off in cents."""
from .money import format_amount

CODES = {
    "SAVE10": ("percent", 10),
    "WELCOME5": ("fixed", 500),
}


def apply_discount(cents, code=None):
    if not code:
        return cents
    kind, value = CODES[code]
    if kind == "percent":
        return cents - cents * value // 100
    return max(0, cents - value)


def describe(code):
    kind, value = CODES[code]
    if kind == "percent":
        return f"{code}: {value}% off"
    return f"{code}: {format_amount(cents=value)} off"
