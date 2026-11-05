"""Hidden PASS_TO_PASS tests: every caller's output is unchanged (euros), including callers the prompt doesn't name."""
import subprocess
import sys
import unittest

from shopkit.cart import Cart
from shopkit.catalog import find
from shopkit.discounts import describe
from shopkit.emails.receipt import receipt
from shopkit.invoice import Invoice
from shopkit.money import parse_amount
from shopkit.reports.monthly import monthly_revenue
from shopkit.reports.top_products import top_products
from shopkit.shipping import label


class CallersTest(unittest.TestCase):
    def test_cart(self):
        cart = Cart()
        cart.add("KT-200", "Stoneware kettle", 4900)
        cart.add("TEA-EG", "Earl Grey", 650, 3)
        self.assertEqual(cart.summary("WELCOME5"), "1 x Stoneware kettle: €49.00\n3 x Earl Grey: €19.50\nTotal: €63.50")

    def test_discounts(self):
        self.assertEqual(describe("WELCOME5"), "WELCOME5: €5.00 off")
        self.assertEqual(describe("SAVE10"), "SAVE10: 10% off")

    def test_catalog(self):
        self.assertEqual(find("MUG-10").price_label(), "Enamel mug (€12.00)")

    def test_shipping(self):
        self.assertEqual(label(1500), "Shipping: €6.95")

    def test_invoice_default_eur(self):
        inv = Invoice("INV-9", "Di")
        inv.add("Design", 123456)
        self.assertEqual(inv.render(), "Invoice INV-9 for Di\n  Design: €1,234.56\nTotal due: €1,234.56")

    def test_reports(self):
        self.assertEqual(monthly_revenue([("2026-09-30", 5), ("2026-10-02", 1999)], "2026-10"), "Revenue for 2026-10: €19.99")
        self.assertEqual(top_products({"Mug": 2400, "Tea": 9100, "Kettle": 4900, "Spoon": 300}, 2), "Tea €91.00, Kettle €49.00")

    def test_receipt(self):
        self.assertIn("You paid €7.25.", receipt("Ana", "ORD-1", 725))

    def test_parse_unchanged(self):
        self.assertEqual(parse_amount("$19.99"), 1999)

    def test_cli(self):
        out = subprocess.run([sys.executable, "-m", "shopkit.cli", "price", "1250"], capture_output=True, text=True, check=True).stdout
        self.assertEqual(out.strip(), "€12.50")
