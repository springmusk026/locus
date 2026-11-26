#!/usr/bin/env python3
"""Checks a release run must pass before the Release workflow builds anything (docs/RELEASING.md).

A real (not dry) run must:
- have Locus's signing key, when it runs in Locus's own repository. Without it the sign job would
  sign with a throwaway key, and no install could update to that release. A fork has no key and
  still gets a throwaway-signed release, with a warning.
- release a commit that is on main (`git merge-base --is-ancestor <commit> <main>`), so a manual
  run from another branch, or a tag on a commit that never reached main, publishes nothing.

A dry run checks neither: it reads no secrets and may run on any branch.

    python3 tools/release_guard.py --dry-run false --repository owner/name --has-key true \
        --commit <sha> --main origin/main

Exit 0 when the run may go ahead, 1 otherwise; the reasons are printed as workflow commands.
"""

import argparse
import subprocess
import sys

OFFICIAL_REPOSITORY = "springmusk026/locus"


def _flag(value):
    return value.strip().lower() == "true"


def problems(dry_run, repository, has_key, commit, main, cwd=None):
    """(errors, warnings) for this run: any error means it must stop."""
    if dry_run:
        return [], []
    errors, warnings = [], []
    if not has_key:
        if repository.lower() == OFFICIAL_REPOSITORY:
            errors.append(
                "LOCUS_KEYSTORE_B64 is empty, so this release would be signed with a throwaway key that "
                "no install can update to. Check the secret is set (in the release environment or the "
                "repository) under exactly that name, or tick Dry run."
            )
        else:
            warnings.append(f"{repository} has no LOCUS_KEYSTORE_B64: the release will be signed with a throwaway key.")
    ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", commit, main],
        cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    if ancestor.returncode == 1:
        errors.append(
            f"Commit {commit} isn't on {main}. Merge the release commit to main and release from there "
            "(docs/RELEASING.md), or tick Dry run."
        )
    elif ancestor.returncode != 0:
        errors.append(f"Couldn't check that {commit} is on {main}: {ancestor.stderr.strip()}")
    return errors, warnings


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dry-run", required=True, help="true or false")
    parser.add_argument("--repository", required=True, help="owner/name, as in GITHUB_REPOSITORY")
    parser.add_argument("--has-key", required=True, help="true when LOCUS_KEYSTORE_B64 is set")
    parser.add_argument("--commit", required=True, help="the commit being released")
    parser.add_argument("--main", default="origin/main", help="the main branch ref to check against")
    args = parser.parse_args(argv)
    dry_run = _flag(args.dry_run)
    errors, warnings = problems(dry_run, args.repository, _flag(args.has_key), args.commit, args.main)
    if dry_run:
        print("::notice::Dry run: no key or branch checks, and nothing is published.")
    for warning in warnings:
        print(f"::warning::{warning}")
    for error in errors:
        print(f"::error::{error}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
