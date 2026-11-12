# PR #755: a project opens on Windows and Android from one synced folder

- Upstream PR: https://github.com/jub0t/Concat/pull/755 (opened 2026-11-12 as a draft)
- Head: `BenjaminKobjolke:feat/sync-windows-android`, base: `jub0t/Concat` `main`
- Feature description for users: [`docs/SYNC.md`](../SYNC.md)

## What the branch is

One signed-off commit on top of `upstream/main` at `504a814e`. It is **not** a branch of
the fork's `main`: `main` carries fork-only files (coding rules, `tools/`, `CLAUDE.md`,
`scripts/android-wsl/`) and other fork changes (keep-awake, encoder and test fixes) that
must not reach the PR.

The commit was built from these fork commits, cherry-picked in this order and squashed:

| Fork commit | Content |
|---|---|
| `25f2748f` | the feature: relative media paths, collect media, Android folder picker, relink matching |
| `bb67b214` | `docs/SYNC.md` |
| `03d05e57` | model paths stored in plain local spelling |
| `c413d2e1` | rename inside the Android picker installer |
| part of a later commit | the `Command::UpdateFontPath { .. } => "Relink font"` arm in `src/crates/concat/src/studio.rs`; without it the branch does not compile |

Conflicts resolved while cherry-picking:

- `src/crates/concat/locales/az.json`, `bn.json`, `vi.json`: upstream's translations kept,
  only the five new keys added (`relink.collectMedia`, `studio.collectMedia*`).
- `src/crates/concat/src/studio/project_files.rs`: upstream's proxy sweep kept as it is
  (including its `log::debug!`), wrapped in `if session.project().missing_media().is_empty()`.

## Changing the PR later

Work on the PR branch, never on `main`:

```sh
git fetch --no-tags upstream
git switch feat/sync-windows-android
# edit, then
git commit -s -m "fix(sync): ..."
git push origin feat/sync-windows-android
git switch main
```

Rules that upstream enforces:

- Every commit is signed off (`git commit -s`). The `Commits signed off` check fails otherwise.
- Commit messages in upstream's style (`feat(sync): ...`, `fix(sync): ...`). No references
  to our internal ticket numbers (`Refs #15550`): GitHub would link them to unrelated
  upstream issues.
- New source files start with upstream's two SPDX header lines (see `CONTRIBUTING.md`).
- The contributing terms (CLA.md, MIT-0) are confirmed for this PR. A new PR by the same
  account does not need the comment again.

A fix that the fork needs too: make it on `main` first, then `git cherry-pick -s <commit>`
onto the PR branch (or the other way round). Keep both sides the same.

When upstream moves and the PR shows conflicts, merge `upstream/main` into the PR branch
and resolve; do not force-push over review comments unless the maintainer asks for a rebase.

When the PR is merged upstream, the next `/git:sync-upstream` brings the same change back
into `main`. Expect conflicts in the files listed above; both sides hold the same feature,
so resolve to one copy.

## State of the checks on 2026-11-12

Run from `src/` on Windows:

| Check | Result |
|---|---|
| `cargo fmt --check` | pass |
| `cargo clippy --all-targets -- -D warnings` | pass |
| `cargo test --no-fail-fast` | 8 failures, none in code the PR touches |
| `python scripts/locales.py` | not committed |

The test failures:

- `concat-host --test export`: `one_clip_exports_whole_at_every_rate`,
  `the_edges_export_too`, `every_edit_still_exports` (`the sound runs 5.931s, not 6s`).
  The same three fail on plain `upstream/main` on this machine.
- `concat-media --lib`: four encoder and HDR-tag tests. `concat-text --lib`:
  `emoji_sequences_draw_as_one_picture`. The PR changes no file in either crate; these
  were not re-run on plain upstream. The fork's `main` carries its own fixes for them.

`scripts/locales.py` on Windows drops 20 existing upstream keys (`lib.fonts`,
`speedRamp.bullet`, ...) and `--check` fails the same way on plain `upstream/main`, so its
output was not committed. The five new keys are present in `en.json` and all 26 locales,
but not at the position the script would sort them to. If upstream CI complains, run the
script on Linux or in WSL and commit only the result there.

Not verified on the PR branch: the Android build. The WSL build scripts exist only on
`main`; the Android side was built and run from `main`.

## Open points a reviewer may raise

- `MANAGE_EXTERNAL_STORAGE` in `src/crates/concat-android/Cargo.toml` (all-files access)
  has Play Store policy implications.
- Existing projects keep absolute paths until **Collect media into project** is run.
- The project lock is local to each device; two devices editing at once produce a sync
  conflict file.
- The five new strings are machine-translated.
- `CONTRIBUTING.md` asks to open an issue or talk on Discord before a larger change. That
  did not happen for this PR.
