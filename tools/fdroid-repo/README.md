# fdroid-repo: the self-hosted F-Droid repository (K2GO-421)

The F-Droid repository of Knowledge to Go is static files on Cloudflare R2:

| What | URL |
|---|---|
| Repository (add it in the F-Droid app) | `https://k2go-download.appdevforall.org/fdroid/repo` |
| Landing page (for a person in a browser) | `https://k2go-download.appdevforall.org/fdroid/index.html` |
| Staging copy (manual runs) | `https://k2go-download.appdevforall.org/fdroid-staging/...` |

Design: `controller/docs/ADR-K2GO-421-self-hosted-fdroid-repo.md`.
Workflow: `.github/workflows/app-fdroid-repo.yml`.

## Files

| File | Use |
|---|---|
| `publish.sh` | The publish run: pull from R2, `fdroid update`, verify, page, upload. |
| `fdroid_repo.py` | `config`, `qr`, `verify`, `page` and `plan` steps (Python stdlib; `qr` runs in the container with python3-qrcode). |
| `test_fdroid_repo.py` | Unit tests: `python -m unittest -v test_fdroid_repo` |
| `metadata/org.appdevforall.k2go.yml` | App text for the F-Droid client. The version, permissions and signer come from the APK. |
| `site/` | The landing page: `page.template.html`, and the look of the add-ons site (`styles.css` and `adfa-logo.svg` copied from `appdevforall/plugin-examples` `site/`; `fdroid.css` for this page only). |
| `r2_mock_server.py` | Local test: serves a folder the way R2 does (no listing, no implicit index). |

## Rules that the run keeps

- R2 holds the state. Each run pulls `fdroid/repo/` and `fdroid/archive/`. Without the old index,
  `fdroid update` sets the "added" date of each old version to the date of the run.
- The repo index is signed with the app signing key (the same GitHub secrets as the APK).
  So the repo fingerprint is the fingerprint of the app certificate.
- `verify` stops the run before the upload when the release APK is not in the index, when another
  key signed it, or when the index names a file that is not there.
- Upload order: APKs and images, then the indexes, then `entry.jar`, then the landing page.
  Deletions come last. A published file never names a file that is not in R2 yet.

## Local test

Needs Docker and Python 3. Use a test keystore and an APK signed with it: the release key stays
in CI.

```sh
# A local folder is the bucket. Put the APK at its root, as the release job puts it on R2.
mkdir -p /tmp/bucket && cp K2Go-v1.2.0-fdroid-release.apk /tmp/bucket/
export KEY_ALIAS=... KEYSTORE_PASSWORD=... KEY_PASSWORD=...
TAG=v1.2.0 PREFIX=fdroid BASE_URL=http://localhost:8080 KEYSTORE_FILE=test.jks \
  LOCAL_BUCKET=/tmp/bucket bash tools/fdroid-repo/publish.sh
python tools/fdroid-repo/r2_mock_server.py --root /tmp/bucket --port 8080
# Open http://localhost:8080/fdroid/index.html
```

To test from a phone on the same Wi-Fi: set `BASE_URL=http://<LAN IP of the computer>:8080`
in the publish run (the index, the QR code and the "Add to F-Droid" link carry this address),
then start the server with `--bind 0.0.0.0`. The firewall of the computer must let the port in.

`FDROID_IMAGE` selects the Docker image for `fdroid update` (default `debian:trixie`, which installs
`fdroidserver` from apt on each run).
