from .discounts import apply_discount
from .money import format_amount


class Cart:
    def __init__(self):
        self.lines = []  # (sku, name, unit_cents, qty)

    def add(self, sku, name, unit_cents, qty=1):
        self.lines.append((sku, name, unit_cents, qty))

    def subtotal(self):
        return sum(unit * qty for _, _, unit, qty in self.lines)

    def total(self, code=None):
        return apply_discount(self.subtotal(), code)

    def summary(self, code=None):
        rows = [f"{qty} x {name}: {format_amount(unit * qty)}" for _, name, unit, qty in self.lines]
        rows.append(f"Total: {format_amount(self.total(code))}")
        return "\n".join(rows)
