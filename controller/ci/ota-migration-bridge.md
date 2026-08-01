# OTA migration bridge (one-time, for the 0.6.0 cutover) — ADFA-4984

Installs from before this change have the **old** OTA host baked into the APK
(`iiab.switnet.org`). They check the old manifest and download from the old base
URL, so they cannot reach `k2go-download.appdevforall.org` on their own. To move
them over, seed one bridge release at the **old** location. After a device takes
it once, the new APK carries the new URLs and every later update comes from
`k2go-download`.

## Why it works
- The new APK is signed with the **same production keystore**, so it installs as
  an update over the old one (and passes the app's same-certificate check).
- Old installs build the download URL as `OLD_APK_BASE_URL + <apkName from manifest>`,
  so the APK files must sit next to the bridge manifest at the old location.

## One-time steps (do this for the 0.6.0 release)
1. Publish 0.6.0 normally (push the `v0.6.0-beta` tag). CI puts the signed APKs and
   `update.json` in R2 → `k2go-download.appdevforall.org`.
2. Download the three published APKs from `k2go-download` (arm64-v8a, armeabi-v7a,
   universal) — the exact signed artifacts, do not rebuild.
3. Upload those same APK files to the **old** location:
   `https://iiab.switnet.org/android/apk/`
4. Write a **bridge** `update.json` at `https://iiab.switnet.org/android/apk/update.json`
   with the new version and the same APK filenames, e.g.:

   ```json
   {
     "versionCodeBase": 60,
     "versionName": "v0.6.0-beta",
     "changelog": "A complete redesign of the app. Tap to update to the latest version.",
     "apk_arm64_v8a": "org.iiab.controller-v0.6.0-beta-arm64-v8a-release.apk",
     "apk_armeabi_v7a": "org.iiab.controller-v0.6.0-beta-armeabi-v7a-release.apk",
     "apk_universal": "org.iiab.controller-v0.6.0-beta-universal-release.apk"
   }
   ```

5. Verify on a device still running an old build (versionCode ≤ 52): it should be
   offered the update, download from the old host, install, and afterward check
   `k2go-download` for future updates.

## After the cutover
- Once enough old installs have migrated, the old `iiab.switnet.org` files can be
  retired (or left in place for stragglers — the bridge manifest is harmless).
- This bridge is only needed **once**. Every 0.6.0+ install already points at
  `k2go-download`, so future releases need no bridge.
