# Rootfs-Size Pilot — Analysis & Change Map

> Companion to the refactor context document. This file is the result of the
> "analyze the requested changes" pass and is meant to run **in parallel** with
> the phased refactor (phases 0, 1, 2, 3, K…) without blocking feature work.
>
> Goal of the pilot slice: replace the **hardcoded** rootfs OS sizes with the
> **live** sizes published on the Deploy server, with a safe **fallback** to
> hardcoded values when there is no network access — built through the three
> Clean Architecture layers as the reference implementation.

---

## 1. Live sizes on the Deploy server (ground truth)

Source: the stable `latest_<tier>_<arch>.meta4` Metalink pointers under
`https://iiab.switnet.org/android/rootfs/`. The `.meta4` files carry the exact
size in **bytes** (`<size>` element) and the canonical download `<url>`, and
they do **not** embed the version/hash in the name — so they are the right
target for both the size lookup and the download.

Verified on 2026-06-17:

| Tier | ABI `arm64-v8a` | ABI `armeabi-v7a` |
|------|-----------------|-------------------|
| `basic`    | 1,219,422,532 B — 1.14 GiB / 1.22 GB | 1,220,401,364 B — 1.14 GiB / 1.22 GB |
| `standard` | 1,428,970,336 B — 1.33 GiB / 1.43 GB | 1,429,892,132 B — 1.33 GiB / 1.43 GB |
| `full`     | 2,926,676,923 B — 2.73 GiB / 2.93 GB | 2,917,715,443 B — 2.72 GiB / 2.92 GB |

> Latest builds at verification time: `iiab-oa_2026.158_*_arm64-v8a` and
> `iiab-oa_2026.159_*_armeabi-v7a`. Sizes drift between builds — that is exactly
> why they must be fetched live rather than hardcoded.

### Naming note: "medium" → `standard`

The reference document uses the label **medium**, but the server (and the
existing app code) uses **`standard`**. The pilot keeps the enum
`Tier.{BASIC, STANDARD, FULL}` and maps the user-facing "medium" wording to
`standard` so it matches the real Deploy artifacts.

---

## 2. Current state: where the hardcoded sizes live

The values are hardcoded in **`InstallationPlanner.java`** (lines 25–27):

```java
private static final double OS_BASIC_GB    = 1.0;
private static final double OS_STANDARD_GB  = 1.2;
private static final double OS_FULL_GB      = 2.7;
```

They flow through `calculateProjectedSize(...)` (a `switch (tier)`,
lines 152–162) into `StorageProjection.osSize`, and are rendered in
**`DeployFragment.java`** `recalculateProjection()`:

```java
double pOs = (selectedTier == null) ? 0.0 : projection.osSize;          // line 716
txtLegendIiab.setText(String.format(Locale.US, "%.1fG", pOs));          // line 729
// also folded into the gauge total at line 811
```

### Accuracy gap (why this matters)

The displayed values are stale and round-tripped through a single decimal:

| Tier | Shown today | Live (GiB) | Off by |
|------|-------------|------------|--------|
| basic    | `1.0G` | 1.14 | ~0.14 |
| standard | `1.2G` | 1.33 | ~0.13 |
| full     | `2.7G` | 2.73 | ~0.03 |

`basic` and `standard` are understated by ~12–14%.

### The server URL is already built in the app

`DeployFragment.java` (lines 983–988) already constructs exactly the URL the
data source needs, for the download flow:

```java
String arch        = getTermuxArch();                                   // line 983
String archSuffix  = (arch.contains("arm") && !arch.contains("64"))
                       ? "armeabi-v7a" : "arm64-v8a";                    // line 984
Tier   safeTier    = (selectedTier != null) ? selectedTier : Tier.BASIC;
String tierString  = safeTier.name().toLowerCase(Locale.US);
String directUrl   = "https://iiab.switnet.org/android/rootfs/latest_"
                       + tierString + "_" + archSuffix + ".meta4";       // line 987
```

Arch detection lives in `getTermuxArch()` (lines 2645–2662; falls back to
`Build.SUPPORTED_ABIS[0]`). The RemoteDataSource should reuse this exact
URL convention and arch mapping for **both** ABIs.

### Reusable networking entry points (no new HTTP library needed)

- `InstallationPlanner.getOrFetchCatalog` (lines 74–147): the canonical
  `HttpURLConnection` + 8 s timeout + disk cache + `Handler(mainLooper)` pattern.
- `DeployFragment.pingUrl` / `checkInternetAccess` (lines 2619 / 2674) and the
  existing `HEAD` requests at lines 2627 / 2683 — already do
  `conn.setRequestMethod("HEAD")`; ideal template for `Content-Length`.
- `Aria2Manager` (`--follow-metalink=mem`) already consumes the `.meta4` URL for
  the actual download.

---

## 3. Project / architecture readiness

| Item | Value |
|------|-------|
| Language | **Pure Java** — no Kotlin plugin, no `.kt` files in our modules |
| AGP / Gradle | 8.4.1 / 8.8 |
| Java | 17 |
| compileSdk / minSdk / targetSdk | 34 / 24 / **28** (intentional, required for proot W^X) |
| Layering | **None** — flat package `org.iiab.controller`, no ViewModel / Repository / UseCase / DI |
| HTTP client | None — hand-rolled `HttpURLConnection` (no OkHttp/Retrofit) |
| Lifecycle (ViewModel/LiveData) | **Not present** |
| Byte-formatting helper | **Missing** — formatting is inlined ad hoc |
| Tests | JUnit 4 + `unitTests.returnDefaultValues = true`; two JVM-only tests of pure static methods; `androidTest` empty; no Mockito |

**Implication:** this is a *greenfield* slice — it establishes the layering
pattern rather than refactoring an existing one. That is precisely the intended
role of the pilot.

### Recommendation: write the pilot in **Java**

Match the existing codebase. Introducing Kotlin would add the Kotlin plugin +
stdlib (+ coroutines for idiomatic value) and conflate a *language migration*
with an *architecture pilot*. Clean Architecture is fully expressible in Java 17,
keeps reviewers focused on the layer boundaries, and lets the all-Java team adopt
the pattern with no language ramp. A Kotlin migration, if desired, should be its
own explicit ADR later (it interops cleanly with this slice).

---

## 4. Proposed pilot slice (layer mapping → real code)

New sub-package under `controller/app/src/main/java/org/iiab/controller/rootfs/`:

```
org.iiab.controller.rootfs
├── domain                         (pure JVM — NO Android, NO HTTP imports)
│   ├── Rootfs.java                // entity: tier, abi, url, sizeBytes
│   ├── RootfsRepository.java      // port (interface) the domain owns
│   └── GetRootfsSizeUseCase.java  // business rules: reject 0 / negative / absurd
├── data                           (implementation details)
│   ├── RootfsCatalog.java         // tier+abi -> latest_*.meta4 URL + fallback bytes
│   ├── RootfsRemoteDataSource.java// HttpURLConnection: HEAD Content-Length OR meta4 <size>
│   └── RootfsRepositoryImpl.java  // implements RootfsRepository; live-then-fallback; DTO->entity
└── presentation
    └── RootfsViewModel.java       // exposes Loading / Success / Error state

org.iiab.controller.util
└── ByteFormatter.java             // long bytes -> "1.3 GiB" (pure, unit-testable)
```

Dependency direction (enforced): `presentation → domain ← data`. `domain` imports
nothing from Android or `java.net`, so `GetRootfsSizeUseCase` and `Rootfs` are
unit-testable on the JVM with the existing JUnit setup (mirrors
`SystemStatsUtilTest`).

### Layer responsibilities

**Data**
- `RootfsRemoteDataSource`: build `latest_<tier>_<abi>.meta4`; preferred path is
  a `HEAD` on the resolved `.tar.gz` reading `getContentLengthLong()`, or read the
  `.meta4` and parse `<size>(\d+)</size>` (exact bytes — avoids hardcoding the
  version in the URL). 8 s timeouts, background thread.
- `RootfsCatalog`: single source for the URL convention **and** the hardcoded
  fallback byte values (see §5). Detects ABI via `Build.SUPPORTED_ABIS` /
  `getTermuxArch()` and selects `arm64-v8a` vs `armeabi-v7a`.
- `RootfsRepositoryImpl`: try live, on any failure/offline return fallback;
  map raw bytes (DTO) → `Rootfs` entity.

**Domain**
- `GetRootfsSizeUseCase`: validation rules — reject `0`, negative, or absurd
  sizes (e.g. < 100 MB or > 10 GB), in which case the fallback is authoritative.
- `RootfsRepository`: `Rootfs getSize(Tier tier, Abi abi)` contract; no leakage
  of HTTP/Android types.

**Presentation**
- `RootfsViewModel`: calls the use case off the main thread; emits
  `Loading → Success(Rootfs) | Error(fallback)`; formats via `ByteFormatter`.

---

## 5. Fallback constants (ready to use)

Replace the three `double … _GB` constants with per-ABI **byte** fallbacks
seeded from today's live values. Bytes (not rounded GB) keep the fallback exact
and let the UI choose GiB vs GB formatting consistently.

```java
// Fallback sizes in BYTES, captured from latest_*.meta4 on 2026-06-17.
// Used only when the live HEAD/meta4 lookup fails (offline or server error).
// arm64-v8a
static final long FALLBACK_BASIC_ARM64    = 1_219_422_532L; // 1.14 GiB
static final long FALLBACK_STANDARD_ARM64 = 1_428_970_336L; // 1.33 GiB
static final long FALLBACK_FULL_ARM64     = 2_926_676_923L; // 2.73 GiB
// armeabi-v7a
static final long FALLBACK_BASIC_ARMV7    = 1_220_401_364L; // 1.14 GiB
static final long FALLBACK_STANDARD_ARMV7 = 1_429_892_132L; // 1.33 GiB
static final long FALLBACK_FULL_ARMV7     = 2_917_715_443L; // 2.72 GiB
```

> Display note: the rest of the app formats storage with binary units
> (`G` = GiB, dividing by 1024³). Convert bytes → GiB for the legend
> (`txtLegendIiab`) and gauge so the new value is consistent with free-space math.

---

## 6. Ordered change map (when implementing)

1. **`build.gradle` (app):** add `androidx.lifecycle:lifecycle-viewmodel:2.8.7`
   and `:lifecycle-livedata:2.8.7` (Java artifacts, no Kotlin). Optional:
   `testImplementation 'org.mockito:mockito-core:5.12.0'`. No HTTP lib needed.
2. **Create the `rootfs/` package** with the classes in §4. Domain first
   (pure, test it), then data, then presentation.
3. **`ByteFormatter`** util + JVM unit test (matches `SystemStatsUtilTest` style).
4. **Wire into `InstallationPlanner`:** demote `OS_*_GB` to fallback; have
   `calculateProjectedSize(...)` (already on its own `Thread`, line 150) obtain
   the OS size from the repository (live-then-fallback) using the tier + ABI.
   Pass `archSuffix` from the two call sites (`DeployFragment` lines 706 / 1032),
   which already compute it.
5. **Display:** no markup change required — once `projection.osSize` is live,
   `DeployFragment` line 729 (`txtLegendIiab`) and the gauge total (line 811)
   update automatically. Optional `(live)` / `(offline)` indicator only if wanted.
6. **Gate offline:** consult `checkInternetAccess()` (line 2674) to skip the live
   call and go straight to fallback without a timeout wait.
7. **Validate against the real Deploy server** for both ABIs and offline mode.

### Tests
- Domain: `GetRootfsSizeUseCaseTest` with a fake `RootfsRepository` — accepts
  valid sizes, rejects 0 / negative / absurd, returns fallback on repo failure.
- Util: `ByteFormatterTest` — boundaries (B/KiB/MiB/GiB), rounding.
- Data: `RootfsRepositoryImplTest` — live success maps DTO→entity; failure →
  fallback bytes per ABI.

---

## 7. Parallelism with the phased refactor

This slice is self-contained inside the new `rootfs/` package and touches the
legacy God classes only at two seams (`InstallationPlanner` OS-size source and
the two `DeployFragment` call sites). It can proceed alongside phases 0/1/2/3/K
without freezing other features — consistent with the strangler-fig strategy and
the boy-scout rule. It also doubles as the reference implementation future slices
copy.
