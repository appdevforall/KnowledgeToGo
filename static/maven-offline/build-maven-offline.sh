#!/usr/bin/env bash
#
# maven-offline: the download machine (K2GO-437).
#
# Builds one offline Maven2 repository under out/repo that holds every dependency
# the builds of Knowledge to Go, Code on the Go, and the add-ons need. A device or
# a laptop on the K2Go network then builds those projects with the network off.
#
# Pipeline: resolve (per project, isolated Gradle home) -> reshape (Gradle cache to
# Maven2 layout) -> extras (R8/D8, aapt2, brotli4j) -> checksums -> report -> smoke.
#
# Run on an online host. Linux is the canonical producer host. See README.md.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/out/repo"
HOMES="$HERE/.gradle-homes"
WORK="$HERE/.work"
INIT="$HERE/gradle/resolve-all.init.gradle"
PROJECTS_TSV="$HERE/config/projects.tsv"
EXTRAS_TSV="$HERE/config/extra-artifacts.tsv"
LOCAL_SRC="$HERE/config/sources.local.tsv"

MAVEN_CENTRAL="https://repo1.maven.org/maven2"
GOOGLE_MAVEN="https://dl.google.com/dl/android/maven2"

DO_SMOKE=0
ONLY=""

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --smoke) DO_SMOKE=1 ;;
        --only) ONLY="${2:-}"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
    shift
done

log() { printf '>> %s\n' "$*"; }

# gradlew wrapper for a project dir, picking the Windows launcher under Git Bash.
gradlew_for() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) printf '%s/gradlew.bat' "$1" ;;
        *) printf '%s/gradlew' "$1" ;;
    esac
}

# Resolve a local path override for a project name (config/sources.local.tsv), else "".
local_override() {
    [ -f "$LOCAL_SRC" ] || { printf ''; return; }
    awk -F'\t' -v n="$1" '!/^#/ && $1==n {print $2; exit}' "$LOCAL_SRC"
}

# Clone (shallow) or point at a local checkout; echoes the source dir.
source_dir() {
    local name="$1" source="$2" ref="$3" ovr
    ovr="$(local_override "$name")"
    if [ -n "$ovr" ]; then printf '%s' "$ovr"; return; fi
    if [ "$source" = "self" ]; then printf '%s' "$REPO_ROOT"; return; fi
    local dst="$WORK/$name"
    if [ ! -d "$dst/.git" ]; then
        mkdir -p "$WORK"
        git clone --depth 1 --branch "$ref" "$source" "$dst" >&2
    fi
    printf '%s' "$dst"
}

# Force-download one Gradle build's dependencies into an isolated home.
resolve_build() {
    local gradle_root="$1" home="$2" gw
    gw="$(gradlew_for "$gradle_root")"
    log "resolve: $gradle_root (home ${home##*/})"
    ( cd "$gradle_root" && "$gw" --gradle-user-home "$home" \
        --init-script "$INIT" --no-daemon --console=plain -q \
        resolveAllDeps ) || log "resolve returned nonzero (lenient; continuing)"
}

# Copy one isolated home's module cache into out/repo as Maven2 layout.
# Gradle cache: files-2.1/<group>/<artifact>/<version>/<hash>/<file>
# Maven2:       <group-with-slashes>/<artifact>/<version>/<file>
reshape_home() {
    local home="$1" cache="$1/caches/modules-2/files-2.1" n=0
    [ -d "$cache" ] || { log "no cache in ${home##*/}"; return; }
    while IFS= read -r f; do
        local rel="${f#"$cache"/}"
        local group="${rel%%/*}"; rel="${rel#*/}"
        local artifact="${rel%%/*}"; rel="${rel#*/}"
        local version="${rel%%/*}"; rel="${rel#*/}"
        local file="${rel##*/}"
        local dest="$OUT/${group//.//}/$artifact/$version"
        mkdir -p "$dest"
        [ -f "$dest/$file" ] || cp "$f" "$dest/$file"
        n=$((n+1))
    done < <(find "$cache" -type f)
    log "reshaped ${home##*/}: $n files"
}

# Download <url> to <dest> (skip if present and non-empty).
fetch() {
    local url="$1" dest="$2"
    [ -s "$dest" ] && return 0
    mkdir -p "$(dirname "$dest")"
    curl -fsSL "$url" -o "$dest" && return 0
    rm -f "$dest"; log "miss: $url"; return 1
}

# Place one GAV artifact (jar + pom) from a base repo into out/repo.
place_artifact() {
    local base="$1" group="$2" artifact="$3" version="$4" classifier="$5" ext="$6"
    local gpath="${group//.//}/$artifact/$version"
    local name="$artifact-$version"; [ "$classifier" != "-" ] && name="$name-$classifier"
    fetch "$base/$gpath/$name.$ext" "$OUT/$gpath/$name.$ext" || true
    fetch "$base/$gpath/$artifact-$version.pom" "$OUT/$gpath/$artifact-$version.pom" || true
}

# Extras a plain resolve misses: brotli4j natives (config) + aapt2/R8 (derived).
fetch_extras() {
    log "extras: brotli4j natives"
    while IFS=$'\t' read -r group artifact version classifier ext hosts; do
        [ -z "${group:-}" ] && continue
        case "$group" in \#*) continue ;; esac
        place_artifact "$MAVEN_CENTRAL" "$group" "$artifact" "$version" "$classifier" "$ext"
    done < "$EXTRAS_TSV"
    derive_aapt2_r8
}

# aapt2 (Linux + Windows) and R8 follow each project's AGP version. Resolve the exact
# aapt2 version from Google Maven metadata for each AGP in use, then fetch the host jars.
derive_aapt2_r8() {
    # AGP in use: 8.4.1 (K2Go), 8.8.2 (CoGo), 9.3.1 (add-ons). For AGP 9.x the R8/D8
    # tool ships inside com.android.tools.build:builder, so a resolve already captures it;
    # only aapt2 (per build-host OS) must be added here.
    local agp
    for agp in 8.4.1 8.8.2 9.3.1; do
        local meta="$WORK/aapt2-metadata.xml"
        fetch "$GOOGLE_MAVEN/com/android/tools/build/aapt2/maven-metadata.xml" "$meta" || continue
        local ver
        ver="$(grep -oE "<version>${agp//./\\.}-[0-9]+</version>" "$meta" | sed -E 's:</?version>::g' | tail -1)"
        [ -z "$ver" ] && { log "aapt2: no version for AGP $agp"; continue; }
        log "aapt2 for AGP $agp -> $ver (linux, windows)"
        place_artifact "$GOOGLE_MAVEN" com.android.tools.build aapt2 "$ver" linux jar
        place_artifact "$GOOGLE_MAVEN" com.android.tools.build aapt2 "$ver" windows jar
    done
    # R8 is architecture-neutral. If the resolve already captured it, nothing to do; the
    # smoke test confirms whether a specific r8 version is missing. TODO: pin r8 per AGP
    # once the smoke test names the exact version a build requests.
}

# Gradle Module Metadata (.module) can declare a file whose served `url` differs from the
# cache `name` (KMP androidx -android AARs: cache name lifecycle-runtime-release.aar, url
# lifecycle-runtime-android-<v>.aar). A Maven2 consumer reading the .module fetches by url,
# so a reshape that keeps only the `name` 404s offline. Materialize a copy under each url.
materialize_module_urls() {
    log "materialize GMM urls"
    python3 - "$OUT" <<'PY'
import json, os, sys, shutil
root = sys.argv[1]; made = 0
for dp, _, files in os.walk(root):
    for fn in files:
        if not fn.endswith('.module'): continue
        try:
            with open(os.path.join(dp, fn), encoding='utf-8') as f: mod = json.load(f)
        except Exception: continue
        for var in mod.get('variants', []):
            for fe in var.get('files', []):
                name, url = fe.get('name'), fe.get('url')
                if not name or not url or name == url: continue
                src, dst = os.path.join(dp, name), os.path.join(dp, url)
                if os.path.exists(src) and not os.path.exists(dst):
                    shutil.copyfile(src, dst); made += 1
print(f"materialized {made} url-named copies")
PY
}

# sha1 + md5 beside every artifact (Gradle validates .sha1 on download).
write_checksums() {
    log "checksums"
    find "$OUT" -type f ! -name '*.sha1' ! -name '*.md5' | while IFS= read -r f; do
        [ -f "$f.sha1" ] || sha1sum "$f" | cut -d' ' -f1 > "$f.sha1"
        [ -f "$f.md5" ]  || md5sum  "$f" | cut -d' ' -f1 > "$f.md5"
    done
}

report() {
    log "repository: $OUT"
    log "size: $(du -sh "$OUT" | cut -f1)   files: $(find "$OUT" -type f | wc -l)"
}

process_project() {
    local name="$1" source="$2" ref="$3" gradle_root="$4" mode="$5"
    [ -n "$ONLY" ] && [ "$ONLY" != "$name" ] && return
    local src; src="$(source_dir "$name" "$source" "$ref")"
    local root="$src/$gradle_root"
    if [ "$mode" = "multi" ]; then
        local sub
        for sub in "$root"/*/; do
            [ -e "$sub/settings.gradle" ] || [ -e "$sub/settings.gradle.kts" ] || continue
            resolve_build "${sub%/}" "$HOMES/$name-$(basename "$sub")"
            reshape_home "$HOMES/$name-$(basename "$sub")"
        done
    else
        resolve_build "$root" "$HOMES/$name"
        reshape_home "$HOMES/$name"
    fi
}

main() {
    mkdir -p "$OUT" "$HOMES"
    while IFS=$'\t' read -r name source ref gradle_root mode; do
        [ -z "${name:-}" ] && continue
        case "$name" in \#*) continue ;; esac
        process_project "$name" "$source" "$ref" "$gradle_root" "$mode"
    done < "$PROJECTS_TSV"
    fetch_extras
    materialize_module_urls
    write_checksums
    report
    [ "$DO_SMOKE" = 1 ] && smoke_test
    log "done"
}

# Prove each root project resolves from out/repo with the network off. This is the
# acceptance proof that the repository is complete. It reuses each project's already
# downloaded Gradle distribution so --offline needs no network for the wrapper itself.
smoke_test() {
    local offline_init="$HERE/gradle/offline-repo.init.gradle"
    local repo; repo="$(cd "$OUT" && pwd)"
    while IFS=$'\t' read -r name source ref gradle_root mode; do
        [ -z "${name:-}" ] && continue
        case "$name" in \#*) continue ;; esac
        [ -n "$ONLY" ] && [ "$ONLY" != "$name" ] && continue
        [ "$mode" = "multi" ] && continue   # the add-ons share the trunk; smoke the roots
        local src root warm smoke gw
        src="$(source_dir "$name" "$source" "$ref")"
        root="$src/$gradle_root"
        warm="$HOMES/$name"; smoke="$HOMES/$name-offline"
        rm -rf "$smoke"; mkdir -p "$smoke"
        [ -d "$warm/wrapper" ] && cp -r "$warm/wrapper" "$smoke/wrapper"
        gw="$(gradlew_for "$root")"
        log "smoke (offline): $name"
        if ( cd "$root" && "$gw" --gradle-user-home "$smoke" --offline \
                "-Dmavenoffline.repo=$repo" \
                --init-script "$offline_init" --init-script "$INIT" \
                --no-daemon --console=plain -q resolveAllDeps ); then
            log "smoke OK: $name resolves with the network off"
        else
            log "smoke FAIL: $name has missing artifacts in out/repo"; return 1
        fi
    done < "$PROJECTS_TSV"
}

main
