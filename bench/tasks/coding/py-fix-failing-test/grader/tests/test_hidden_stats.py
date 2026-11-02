import unittest

from stats import mean, median


class HiddenStatsTest(unittest.TestCase):
    def test_median_even_unsorted(self):
        self.assertEqual(median([10, 2, 8, 4]), 6)

    def test_median_single(self):
        self.assertEqual(median([7]), 7)

    def test_median_empty(self):
        with self.assertRaises(ValueError):
            median([])

    def test_mean_unchanged(self):
        self.assertEqual(mean([2, 4]), 3)


if __name__ == "__main__":
    unittest.main()
