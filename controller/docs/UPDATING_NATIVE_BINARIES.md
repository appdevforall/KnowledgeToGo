# Updating native binaries (aria2, proot, tar, …)

The pinned native binaries are defined by **three** files that must always stay in sync.
Update them **together, in the same commit** whenever you bump to a new binaries release.

| File | Role |
|------|------|
| `controller/binary_version.txt` | The pinned release tag (e.g. `binaries-2026-07-05_05-38`). |
| `controller/app/src/main/assets/ninja_manifest.json` | Expected SHA256 of every binary for that tag. The build audits against this. |
| `controller/app/src/main/assets/cacert.pem` | CA bundle shipped with the release (update only if it changed). |

## How to bump

1. Set the new tag in `controller/binary_version.txt`.
2. Run the sync so the manifest (and cacert) come from that exact release:
   ```bash
   cd controller && ./gradlew :app:syncNativeArtifacts
   ```
3. Commit the three files together:
   ```bash
   git add controller/binary_version.txt \
           controller/app/src/main/assets/ninja_manifest.json \
           controller/app/src/main/assets/cacert.pem
   git commit -m "chore: bump native binaries to <tag>"
   ```

## Why (background)

`ninja_manifest.json` is the integrity source of truth and is **version-controlled on purpose**
(supply-chain traceability). If `binary_version.txt` moves without the manifest, the on-disk
binaries (gitignored) no longer match the committed manifest, and the SHA256 audit in
`syncNativeArtifacts` fails on the next build (first offender is usually `libaria2c.so`),
forcing a re-download. PR #150 bumped only `binary_version.txt`; this is the miss this doc
prevents. The CI check `binary-bump-consistency` enforces it.

## Retention and archive (K2GO-506)

- **GitHub** keeps the 6 newest `binaries-*` releases, plus every release that
  `binary_version.txt` pins on any branch of this repository (`main`, `development`, PR
  branches; not PRs from forks) or `v*` app tag. A branch keeps its release while the branch
  exists, so delete branches you no longer need. GitHub deletes a release only after that
  release is archived in R2. The build downloads from GitHub (`syncNativeArtifacts`), so
  pin a tag that GitHub still has.
- **R2** archives every release and never deletes it:
  `https://k2go-download.appdevforall.org/binaries/<tag>/termux-binaries-latest.zip`
  (and `.sha256`). Use it for debugging, or to restore a release that GitHub rotated out.
- The workflow **Binaries lifecycle** (`binaries-lifecycle.yml`) does the archive and the
  prune. `build-static-binaries.yml` calls it after each publish. Run it by hand to backfill
  the archive, or with `dry_run` to see what a prune would delete.
- **Provenance:** builds from K2GO-506 on carry a signed build attestation. Verify a copy from
  any source (GitHub, R2, a box) against the workflow run that built it:
  ```bash
  gh attestation verify termux-binaries-latest.zip --repo appdevforall/KnowledgeToGo
  ```

The R2 bucket layout: [R2_LAYOUT.md](R2_LAYOUT.md).
