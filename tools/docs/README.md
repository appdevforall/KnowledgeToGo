# tools/docs -- help/manual build

The in-app manual (K2GO-406) is a Help&Manual "webhelp" export. The docs team hands us a
`.zip`; we ship a cleaned static copy.

## Pipeline

1. Drop the new export under `static/k2go-docs/`, named `K2Go-webhelp*.zip` (the exact tail
   may change; the script matches the glob). It is gitignored -- the source zip is never
   committed; the unpacked `content/` is.
2. Run the deterministic cleaner:

   ```
   tools/docs/build-help-docs.sh
   ```

   It auto-locates the newest `K2Go-webhelp*.zip` (fallback `K2Go-help*.zip`) under
   `static/`, unzips it, strips the cruft a static WebView never uses (ASP.NET `*.aspx`
   server pages + `oldfavicon.ico`), and rebuilds `static/k2go-docs/content/`
   (`index.html` at the root of `content/`). Idempotent. The module infra at
   `static/k2go-docs/` root (`README.md`, `k2go-docs-nginx.conf`) is left untouched.
3. Review the diff and commit `static/k2go-docs/content/`.

## Where it goes from here

- **APK (K2GO-406):** a Gradle step copies `static/k2go-docs/content/` into the app's assets,
  and the in-app viewer serves it via `WebViewAssetLoader`.
- **Rootfs (follow-up):** the same `static/k2go-docs/content/` is copied into the box web root
  and served by nginx at `/k2go-docs/` (see `k2go-docs-nginx.conf`).

The cleanup rule lives in `build-help-docs.sh` (the `CRUFT_GLOBS` list) so it is one
committed decision, not a per-drop judgement call.
