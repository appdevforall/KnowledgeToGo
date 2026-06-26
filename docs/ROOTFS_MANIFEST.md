# IIAB-oA rootfs manifest — contract (`schema: 1`)

Single source of truth for the two small members embedded in every IIAB-oA rootfs
tarball. **Two producers, one schema:** the build pipeline
(`tools/rootfs-builder/build-iiab-rootfs.sh`) and the in-app backup writer both emit these
members; the in-app validator reads them on **import/restore** (the untrusted
gates). Both sides reference THIS file instead of re-describing the recipe, so
they cannot drift.

It exists because downloads are already covered by the sidecar `.meta4`
(`aria2 --check-integrity`), but a **manual import** from external storage
(USB/SD) has no sidecar — so the tarball must be able to validate **itself**.

Scope: **integrity** (corruption / "did this arrive intact, and is it the rootfs
I think it is"). It is **not** anti-tamper — an attacker who edits the tree can
recompute the manifest. Authenticity (signing) is a possible future layer, not
this one.

---

## The two members

Both live inside the rootfs tree (the archive's top-level dir is
`installed-rootfs/iiab/`):

| Member | Path (in-archive) | Position in tar | In treehash? |
|--------|-------------------|-----------------|--------------|
| Identity  | `installed-rootfs/iiab/.iiab-rootfs.json`           | **FIRST** | **yes** |
| Integrity | `installed-rootfs/iiab/.iiab-rootfs.integrity.json` | **LAST**  | **no (excluded)** |

- **Identity first** so the validator can read it after decompressing only a few
  KB — a fast arch/structure gate with no full scan.
- **Integrity last** because its `treehash` cannot be known until the rest of the
  tree is packed (chicken-and-egg); it is the only member excluded from the hash.

### Identity — `.iiab-rootfs.json`
```json
{ "schema": 1, "kind": "iiab-rootfs",
  "arch": "armeabi-v7a", "deb_arch": "arm",
  "tier": "standard", "iiab_commit": "ab88e5d",
  "built": "2026.158", "base": "debian-trixie",
  "builder": "build-iiab-rootfs.sh" }
```
- `arch` uses the **Android ABI id** (`arm64-v8a` | `armeabi-v7a`); `deb_arch`
  (`aarch64` | `arm`) is included for reference.
- The validator uses `kind` + `arch`; the rest is metadata it may surface in the
  UI. Unknown extra keys must be ignored (forward-compatible).

### Integrity — `.iiab-rootfs.integrity.json`
```json
{ "schema": 1, "algo": "iiab-tree-sha256-v1", "treehash": "<lowercase hex>" }
```

---

## `iiab-tree-sha256-v1` — the digest (FROZEN)

Computed over the archive's **logical tar members** — never over an on-disk
filesystem walk. (Reason: hardlinks. On disk a hardlink is a second name for one
inode; in the tar, GNU tar emits the first occurrence as a regular file and the
rest as hardlink entries. Hashing the members of the actual artifact makes the
representation identical for whoever reads that artifact.)

### Per-member digest
For every logical member **except** the integrity member
(`installed-rootfs/iiab/.iiab-rootfs.integrity.json`), and **including** the
identity member:

```
m_i = SHA256( normalized_path + 0x00 + type + 0x00 + payload )
```

- **`normalized_path`** (UTF-8 bytes): replace `\` -> `/`; remove one leading
  `./`; remove all leading `/`; remove all trailing `/`. Keep the full
  `installed-rootfs/...` prefix. **Do NOT trim whitespace** (a path may
  legitimately contain leading/trailing spaces). (D11 already guarantees no `..`.)
- **`type`** (one ASCII byte) and **`payload`**, by tar typeflag:

  | tar typeflag | `type` | `payload` |
  |---|---|---|
  | REGTYPE `0`/`\0`, CONTTYPE | `f` | file content bytes |
  | DIRTYPE `5` | `d` | (empty) |
  | SYMTYPE `2` | `l` | symlink target **as stored (raw)** + `0x00` |
  | LNKTYPE `1` (hardlink) | `h` | **`normalized_path` of the target** + `0x00` |
  | anything else (char/block/fifo/socket) | — | **build ABORTS; verifier -> `CORRUPT`/unverifiable** |

  (The build already `--exclude '*/dev/*'` and tar drops sockets, so no other
  types should appear in a valid artifact.)
- **pax / GNU long-name / extended-header entries are not members.** Both sides
  resolve them to the logical member (real name + content) and never feed the
  auxiliary pseudo-entry into the digest. (Python `tarfile` and a ustar/pax reader
  both do this transparently.)

### Combine (order-independent + domain-separated)
```
treehash = lowercase_hex( SHA256( ascii("iiab-tree-sha256-v1") + 0x00
                                  + concat( m_i sorted ascending by raw 32-byte value ) ) )
```
The combine is **order-independent**: a byte-sort over fixed 32-byte digests, with
no locale/collation/tar-version dependence. Cost is N x 32 bytes held in memory
plus a sort (tens of MB even for a huge tree); member content is streamed, never
buffered whole.

### Per-artifact, not cross-artifact
The treehash is defined over the logical members of **the artifact as packed**,
and is always verified against **that same artifact's** embedded value. Because
the hardlink `f`/`h` assignment follows the archive's member order, two
independent packings of the "same tree" may yield different treehashes — that is
expected and is not a parity problem: a producer hashes the members it writes, and
the verifier reads those same members. Producers therefore do **not** need a
canonical cross-producer member order. (The build still packs the non-identity
members with `tar --sort=name` for reproducible artifacts, but the recipe does not
require it.)

### Lock points (anything below changes the hash -> bump `algo`)
1. Domain prefix bytes `iiab-tree-sha256-v1` and the `0x00` after it.
2. Per-member field order and the two `0x00` separators.
3. The typeflag -> `type` byte mapping in the table above.
4. Symlink payload is the **raw stored** target; hardlink payload is the
   **normalized** target; both followed by a trailing `0x00`.
5. Path normalization rules exactly as stated, including **no whitespace trim**.
6. Combine = SHA256 over the **ascending byte-sorted** 32-byte digests, prefixed
   by the domain string + `0x00`.
7. The integrity member is the **only** exclusion; the identity member **is**
   hashed.

---

## Producing it (build pipeline & app backup)

1. Pack the tree with the **identity member first** and **without** the integrity
   member.
2. Compute `treehash` over that archive's logical members (excluding the integrity
   member by name — it is absent at this point).
3. Append the **integrity member last**, carrying `treehash`.
4. **Self-verify:** re-read the finished artifact, recompute over all members
   except the integrity member, and assert it equals the stored `treehash`. Never
   ship an artifact your own verifier would reject.

The reference implementation is a small streaming pass (Python `tarfile` on the
build side; a ustar/pax reader in the app). It streams member content (no
whole-file buffering), so it scales to multi-GB / 80 GB rootfs images.

## Verifying it (import / restore)
1. Read the **identity** member (fast, first) -> structural + `arch` gate.
2. Stream the gzip->tar once, recompute the treehash over all members except the
   integrity member, and compare to the integrity member's `treehash`.
3. On mismatch or an unverifiable member type: `Result.CORRUPT`.

## Rollout — soft -> strict
- **Soft (now):** validate the manifest when present; fall back to the legacy
  ELF/structure heuristic when absent. Nothing breaks for older artifacts.
- **Strict (later):** require the manifest, once both producers reliably emit it.

## Implementations
- Build pipeline: `tools/rootfs-builder/build-iiab-rootfs.sh` (generation + build self-verify).
- App: `RootfsArchiveValidator` (verifier, `Result.CORRUPT`) and the backup writer.

Bump `schema` on any breaking change to the members; bump the `algo` string
(`iiab-tree-sha256-v2`, ...) on any change to the digest recipe.

---
*Copyright (c) 2026 AppDevForAll.*
