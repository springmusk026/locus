"""Hidden tests: another export from the same bank, with quoted fields and a last row that matters."""
import os
import tempfile
import unittest

from report import load, totals

EXPORT = 'date,category,amount\n2026-11-02,"books, comics",15.00\n2026-11-04,food,"2,345.60"\n2026-11-09,food,4.40\n2026-11-28,travel,99.99\n'


class HiddenReportTest(unittest.TestCase):
    def test_other_export(self):
        fd, path = tempfile.mkstemp(suffix=".csv")
        with os.fdopen(fd, "w") as f:
            f.write(EXPORT)
        try:
            self.assertEqual(totals(load(path)), {"books, comics": 15.0, "food": 2350.0, "travel": 99.99})
        finally:
            os.remove(path)

    def test_header_only(self):
        fd, path = tempfile.mkstemp(suffix=".csv")
        with os.fdopen(fd, "w") as f:
            f.write("date,category,amount\n")
        try:
            self.assertEqual(totals(load(path)), {})
        finally:
            os.remove(path)
