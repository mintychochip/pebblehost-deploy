
**Files:**
- Modify: `README.md` (installation/usage area)

- [ ] **Step 1: Add the section**

Insert after the plugin-application usage block (after the `plugins { id("dev.mintychochip.pebblehost.deploy") … }` snippet near line 83):

````markdown
### pb binary resolution

The deploy task needs the [`pb` CLI](https://github.com/mintychochip/pebblehost-cli).
Resolution order: an explicit `pbBinary` path (must exist), `pb` on your
`PATH`, otherwise a release binary is downloaded automatically, verified
against the published sha256 digest, cached under
`$GRADLE_USER_HOME/caches/pebblehost-deploy/pb/<version>/`, and reused from
there.

```kotlin
pebblehost {
    pbVersion = "latest"   // default; or pin, e.g. "2026.8.21.16"
}
```

`latest` makes one GitHub API call per deploy run; set `GITHUB_TOKEN` in the
environment to avoid unauthenticated rate limits. Pinned versions are fully
offline once cached.
````

- [ ] **Step 2: Verify docs render sensibly**

Run: `grep -n "pb binary resolution" README.md`
Expected: one match.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: describe pb binary resolution and pbVersion"
```

---

