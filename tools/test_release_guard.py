"""Tests for tools/release_guard.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""

import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import release_guard  # noqa: E402

OFFICIAL = "springmusk026/locus"


def git(cwd, *args):
    env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.invalid",
               GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.invalid")
    return subprocess.run(["git", *args], cwd=cwd, env=env, check=True, stdout=subprocess.PIPE, text=True).stdout.strip()


class ReleaseGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        git(self.repo, "init", "-q", "-b", "main")
        git(self.repo, "commit", "-q", "--allow-empty", "-m", "on main")
        self.on_main = git(self.repo, "rev-parse", "HEAD")
        git(self.repo, "checkout", "-q", "-b", "feature")
        git(self.repo, "commit", "-q", "--allow-empty", "-m", "only on a branch")
        self.off_main = git(self.repo, "rev-parse", "HEAD")

    def tearDown(self):
        self.tmp.cleanup()

    def run_guard(self, dry_run, has_key, commit, repository=OFFICIAL):
        cwd = os.getcwd()
        out = io.StringIO()
        try:
            os.chdir(self.repo)
            with contextlib.redirect_stdout(out):
                code = release_guard.main([
                    "--dry-run", str(dry_run).lower(), "--repository", repository,
                    "--has-key", str(has_key).lower(), "--commit", commit, "--main", "main",
                ])
        finally:
            os.chdir(cwd)
        return code, out.getvalue()

    def test_real_release_without_the_key_fails(self):
        code, out = self.run_guard(dry_run=False, has_key=False, commit=self.on_main)
        self.assertEqual(1, code)
        self.assertIn("::error::LOCUS_KEYSTORE_B64 is empty", out)

    def test_dry_run_without_the_key_is_fine(self):
        code, out = self.run_guard(dry_run=True, has_key=False, commit=self.on_main)
        self.assertEqual(0, code)
        self.assertNotIn("::error::", out)

    def test_dry_run_from_a_branch_is_fine(self):
        self.assertEqual(0, self.run_guard(dry_run=True, has_key=False, commit=self.off_main)[0])

    def test_real_release_of_a_commit_not_on_main_fails(self):
        code, out = self.run_guard(dry_run=False, has_key=True, commit=self.off_main)
        self.assertEqual(1, code)
        self.assertIn(f"::error::Commit {self.off_main} isn't on main", out)

    def test_real_release_of_a_main_commit_with_the_key_passes(self):
        code, out = self.run_guard(dry_run=False, has_key=True, commit=self.on_main)
        self.assertEqual(0, code, out)
        self.assertEqual("", out)

    def test_a_fork_without_the_key_only_warns(self):
        code, out = self.run_guard(dry_run=False, has_key=False, commit=self.on_main, repository="someone/locus")
        self.assertEqual(0, code, out)
        self.assertIn("::warning::", out)

    def test_a_fork_still_cannot_release_off_main(self):
        self.assertEqual(1, self.run_guard(dry_run=False, has_key=False, commit=self.off_main, repository="someone/locus")[0])

    def test_an_unknown_main_ref_fails_closed(self):
        errors, _ = release_guard.problems(False, OFFICIAL, True, self.on_main, "origin/main", cwd=self.repo)
        self.assertEqual(1, len(errors))
        self.assertIn("Couldn't check", errors[0])


if __name__ == "__main__":
    unittest.main()
