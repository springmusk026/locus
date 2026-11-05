import unittest

from shopkit.money import format_amount, parse_amount


class MoneyTest(unittest.TestCase):
    def test_format(self):
        self.assertEqual(format_amount(1250), "€12.50")
        self.assertEqual(format_amount(123456), "€1,234.56")

    def test_parse(self):
        self.assertEqual(parse_amount("€12.50"), 1250)
        self.assertEqual(parse_amount("1,234.56"), 123456)
