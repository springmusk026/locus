from dataclasses import dataclass

from . import money


@dataclass
class Product:
    sku: str
    name: str
    price_cents: int

    def price_label(self):
        return f"{self.name} ({money.format_amount(self.price_cents)})"


CATALOG = [
    Product("TEA-EG", "Earl Grey", 650),
    Product("MUG-10", "Enamel mug", 1200),
    Product("KT-200", "Stoneware kettle", 4900),
]


def find(sku):
    return next((p for p in CATALOG if p.sku == sku), None)
