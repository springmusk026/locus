import io
import unittest
from contextlib import redirect_stdout

from app import cli
from app.api import user_json
from app.users import get_user


class HiddenRenameTest(unittest.TestCase):
    def test_get_user(self):
        self.assertEqual(get_user(2)["name"], "Linus")

    def test_api_still_works(self):
        self.assertEqual(user_json(99), {"error": "not found"})

    def test_cli(self):
        out = io.StringIO()
        with redirect_stdout(out):
            cli.main(["cli", "1"])
        self.assertEqual(out.getvalue().strip(), "Ada")
