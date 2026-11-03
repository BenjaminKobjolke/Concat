# Version
3

# Coding Rules (Pointer)

This project's rules live in two files in the project root:

- `CODING_RULES.md` — code quality and conventions. BINDING for all code work in
  this repository, always.
- `IMPLEMENTATION_FLOW.md` — the end-to-end flow to follow when planning and
  implementing a change (checks, gates, Definition of Done).

MANDATORY: Before writing or editing ANY code, you MUST Read BOTH files in full
**in the current session**. Do not rely on memory of a previous session, a
summary, or partial reads.

If you are about to make a code change and have not read both files in this
session: STOP, read them, then continue.

An external tool may orchestrate the implementation itself. When a skill or run
states that the implementation is orchestrated, ignore `IMPLEMENTATION_FLOW.md`
entirely — the orchestrator runs those steps as its own phases. `CODING_RULES.md`
stays binding either way.

Next to `CODING_RULES.md`, apply also writes `CODING_RULES_PLAN.md`,
`CODING_RULES_REVIEW.md` and `CODING_RULES_CODE.md`. When an orchestrator's run
instructions name one of these step files, that file replaces `CODING_RULES.md`
for that run; without such an instruction, `CODING_RULES.md` stays binding in full.

Do not inline rules back into this file and do not use `@import` for either file —
they are intentionally referenced, not imported.

# Project Notes

- This is a fork of jub0t/Concat that merges from upstream. Apply `CODING_RULES.md` to new and changed code only. Do not refactor, reformat, move or rename upstream code a task does not need. Prefer new files for features.
- The Cargo workspace root is `src/`. Run `cargo` commands from `src/`, not the repo root.
- graphify: this project's code directory is `.`. The scan scope is set by the root `.graphifyignore` (todo #14335). Never build the graph from a different root.
- No Rust rule set ships yet (requested in Coding Rules draft #14342). Re-run `/coding-rules:apply` once it ships.

## Post-Implementation Code Analysis

After any feature, finished plan, or bug fix, run the changed-files analyzer and fix reported
issues before considering the work complete:

```bash
powershell -Command "cd 'D:\GIT\BenjaminKobjolke\Concat'; cmd /c '.\tools\analyze_changed_and_new_files.bat'"
```

This reports only changed Kotlin files. The CLI code analyzer does not support Rust, so Rust
source remains covered by the Cargo checks in `tools/tickets-watcher.json`.

For a whole-project Kotlin audit, run `tools\analyze_code.bat` instead. Use it for explicit
audits, before a release or merge, and after refactors that touch shared Kotlin code. Results go
to `code_analysis_results/` as per-rule CSV files; a missing CSV means that rule found nothing.
