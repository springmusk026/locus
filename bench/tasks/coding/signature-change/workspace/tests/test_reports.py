import unittest

from shopkit.reports.monthly import monthly_revenue


class ReportsTest(unittest.TestCase):
    def test_monthly(self):
        orders = [("2026-10-01", 1000), ("2026-10-31", 250), ("2026-11-01", 999)]
        self.assertEqual(monthly_revenue(orders, "2026-10"), "Revenue for 2026-10: €12.50")
