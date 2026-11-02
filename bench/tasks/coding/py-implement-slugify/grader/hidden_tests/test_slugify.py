import unittest

from textutil import slugify


class SlugifyTest(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(slugify("Hello, World!"), "hello-world")

    def test_accents(self):
        self.assertEqual(slugify("Crème Brûlée"), "creme-brulee")

    def test_runs_and_edges(self):
        self.assertEqual(slugify("  --Rust & Go:  2024 edition--  "), "rust-go-2024-edition")

    def test_non_ascii_dropped(self):
        self.assertEqual(slugify("日本 Tokyo"), "tokyo")

    def test_empty(self):
        self.assertEqual(slugify("!!!"), "n-a")
        self.assertEqual(slugify(""), "n-a")

    def test_cut_at_word(self):
        self.assertEqual(slugify("alpha beta gamma delta", max_length=12), "alpha-beta")

    def test_cut_without_hyphen(self):
        self.assertEqual(slugify("abcdefghijklmnop", max_length=5), "abcde")

    def test_exact_length(self):
        self.assertEqual(slugify("alpha beta", max_length=10), "alpha-beta")


if __name__ == "__main__":
    unittest.main()
