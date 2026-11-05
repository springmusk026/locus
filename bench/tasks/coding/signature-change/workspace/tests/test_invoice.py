import unittest

from shopkit.invoice import Invoice


class InvoiceTest(unittest.TestCase):
    def test_render_eur(self):
        inv = Invoice("INV-1", "Ana")
        inv.add("Consulting", 50000)
        self.assertEqual(inv.render(), "Invoice INV-1 for Ana\n  Consulting: €500.00\nTotal due: €500.00")
