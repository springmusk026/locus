import unittest

from app.api import user_json
from app.users import fetch_user


class UsersTest(unittest.TestCase):
    def test_fetch(self):
        self.assertEqual(fetch_user(1)["name"], "Ada")

    def test_missing(self):
        self.assertIsNone(fetch_user(99))

    def test_api(self):
        self.assertEqual(user_json(2), {"user": {"id": 2, "name": "Linus"}})
