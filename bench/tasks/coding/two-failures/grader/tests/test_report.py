import unittest

from report import load, totals


class ReportTest(unittest.TestCase):
    def test_october(self):
        self.assertEqual(
            totals(load("data/expenses.csv")),
            {"food": 21.25, "transport": 3.2, "utilities": 64.1, "rent": 1200.0},
        )
