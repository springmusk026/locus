from .money import format_amount as fmt


class Invoice:
    def __init__(self, number, customer, currency='EUR'):
        self.number = number
        self.customer = customer
        self.currency = currency
        self.items = []  # (description, cents)

    def add(self, description, cents):
        self.items.append((description, cents))

    def total(self):
        return sum(c for _, c in self.items)

    def render(self):
        lines = [f"Invoice {self.number} for {self.customer}"]
        lines += [f"  {d}: {fmt(c)}" for d, c in self.items]
        lines.append(f"Total due: {fmt(self.total())}")
        return "\n".join(lines)
