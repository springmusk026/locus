# Unreleased changelog entries

Each pull request adds its own file here instead of editing `CHANGELOG.md`, so pull requests never
change the same lines and merging one doesn't put the others in conflict (each conflict meant
another merge and another CI run).

- **Name:** anything unique, usually the branch or the issue: `security-s3.md`, `342-chat-state.md`.
- **Content:** one or more sections in Keep a Changelog form, exactly as they'd appear under
  `## [Unreleased]`:

  ```markdown
  ### Fixed
  - **Connector keys stay on their host.** … (#365)
  ```

  Allowed headings: `### Added`, `### Changed`, `### Deprecated`, `### Removed`, `### Fixed`,
  `### Security`. Say if it hasn't been tried on a phone, as before.

When a release is cut, `python3 tools/changelog.py` moves every entry into `CHANGELOG.md` under
`## [Unreleased]` (each under its heading, in file-name order) and deletes the files; then the
release steps in `docs/RELEASING.md` rename `[Unreleased]` as usual.
