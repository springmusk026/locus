import unittest

from skycast.format import temperature


class FormatTest(unittest.TestCase):
    def test_metric(self):
        self.assertEqual(temperature(21.4), "21 °C")
