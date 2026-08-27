# Task 4 Report: Documentation

## What was inserted

Added the `### pb binary resolution` subsection from the task brief, documenting:

- `pb` CLI requirement and resolution order (`pbBinary` > `PATH` > auto-install)
- Cache location: `$GRADLE_USER_HOME/caches/pebblehost-deploy/pb/<version>/`
- `pbVersion` DSL with `latest` default and pinning example
- One GitHub API call per deploy run for `latest`; optional `GITHUB_TOKEN` auth
- Pinned versions offline once cached

## Where

Inserted in `README.md` immediately after the main `pebblehost { … }` usage Kotlin block (after line 100) and before the "Manual:" deploy command line.

## Verification

```
$ grep -n "pb binary resolution" README.md
102:### pb binary resolution
```

Exactly one match (expected).

## Commit

`7ef77e6` — docs: describe pb binary resolution and pbVersion
