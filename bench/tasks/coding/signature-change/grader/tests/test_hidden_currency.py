"""Hidden FAIL_TO_PASS tests: the new signature and the currency-aware invoice."""
import unittest

from shopkit.invoice import Invoice
from shopkit.money import format_amount


class CurrencyTest(unittest.TestCase):
    def test_codes(self):
        self.assertEqual(format_amount(1250, 'EUR'), "€12.50")
        self.assertEqual(format_amount(1250, "USD"), "$12.50")
        self.assertEqual(format_amount(123456, "GBP"), "£1,234.56")
        self.assertEqual(format_amount(cents=99, currency="USD"), "$0.99")

    def test_required(self):
        # Required: leaving it out is an error (TypeError for a missing argument, or ValueError if the
        # function checks for None itself). Both are a correct reading of "required".
        with self.assertRaises((TypeError, ValueError)):
            format_amount(1250)

    def test_unknown(self):
        with self.assertRaises(ValueError):
            format_amount(1250, "XYZ")

    def test_invoice_uses_its_currency(self):
        inv = Invoice("INV-2", "Bo", currency="USD")
        inv.add("Hosting", 1999)
        inv.add("Support", 500)
        self.assertEqual(inv.render(), "Invoice INV-2 for Bo\n  Hosting: $19.99\n  Support: $5.00\nTotal due: $24.99")
        gbp = Invoice("INV-3", "Cy", currency="GBP")
        gbp.add("Licence", 100000)
        self.assertIn("£1,000.00", gbp.render())
