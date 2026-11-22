"""Unit tests for fdroid_repo. Run: python -m unittest -v test_fdroid_repo"""
import tempfile
import unittest
from pathlib import Path

import fdroid_repo as fr

FP = "B2272FF836D8265AA45E82FE97A4E13AF544C01EB3D77304DDCF36442B934CC5"


def index(versions):
    return {
        "repo": {"name": {"en-US": "Repo <name>"}, "description": {"en-US": "Desc"},
                 "address": "https://k2go-download.appdevforall.org/fdroid/repo",
                 "timestamp": 1795240931000},
        "packages": {fr.APP_ID: {
            "metadata": {"name": {"en-US": "Knowledge to Go"},
                         "summary": {"en-US": "Offline library"},
                         "icon": {"en-US": {"name": "/org.appdevforall.k2go/en-US/icon_x.png"}},
                         "license": "GPL-3.0-or-later",
                         "sourceCode": "https://github.com/appdevforall/KnowledgeToGo"},
            "versions": {str(code): {
                "added": 1795240928000,
                "file": {"name": f"/K2Go-{name}-fdroid-release.apk", "sha256": "ab" * 32,
                         "size": 37675656},
                "manifest": {"versionName": name, "versionCode": code,
                             "usesSdk": {"minSdkVersion": 24, "targetSdkVersion": 36},
                             "nativecode": ["arm64-v8a", "armeabi-v7a"],
                             "signer": {"sha256": ["3a" * 32]}},
            } for name, code in versions},
        }},
    }


class ConfigTest(unittest.TestCase):
    def test_no_secret_in_the_file(self):
        text = fr.config_yml("https://h/fdroid/repo/", "../keystore.jks")
        self.assertIn('repo_url: "https://h/fdroid/repo"', text)
        self.assertIn('archive_url: "https://h/fdroid/archive"', text)
        self.assertIn("keystorepass: {env: KEYSTORE_PASSWORD}", text)
        self.assertIn("keypass: {env: KEY_PASSWORD}", text)
        self.assertIn("repo_keyalias: {env: KEY_ALIAS}", text)

    def test_url_must_end_with_repo(self):
        with self.assertRaises(ValueError):
            fr.config_yml("https://h/fdroid", "k.jks")


class PageTest(unittest.TestCase):
    TEMPLATE = "{{repo_name}}|{{add_link}}|{{version}}|{{android}}|{{fingerprint}}|{{rows}}"

    def test_newest_version_and_escaping(self):
        out = fr.render(index([("v1.2.0", 10200990), ("v1.3.0", 10300990)]), FP, self.TEMPLATE)
        name, link, version, android, fp, rows = out.split("|", 5)
        self.assertEqual("Repo &lt;name&gt;", name)
        self.assertEqual("https://fdroid.link/#https://k2go-download.appdevforall.org/fdroid/repo"
                         "?fingerprint=" + FP, link)
        self.assertEqual("v1.3.0", version)
        self.assertEqual("7.0", android)
        self.assertTrue(fp.startswith("B2 27 2F"))
        self.assertLess(rows.index("v1.3.0"), rows.index("v1.2.0"))
        self.assertIn('href="repo/K2Go-v1.3.0-fdroid-release.apk"', rows)

    def test_real_template_has_no_unfilled_placeholder(self):
        with tempfile.TemporaryDirectory() as tmp:
            assets = fr.write_assets(Path(tmp))
            template = (fr.SITE / "page.template.html").read_text(encoding="utf-8")
            out = fr.render(index([("v1.2.0", 10200990)]), FP, template, assets)
        self.assertNotIn("{{", out)
        for name in fr.SITE_ASSETS + (fr.ICON_ASSET,):
            self.assertRegex(assets[name], r"^assets/[a-z0-9-]+\.[0-9a-f]{8}\.(css|svg)$")
            self.assertIn(f'"{assets[name]}"', out)
        self.assertIn('src="add-repo-qr.png"', out)

    def test_hashed_name_changes_with_the_bytes(self):
        self.assertRegex(fr.hashed_name("styles.css", b"a"), r"^styles\.[0-9a-f]{8}\.css$")
        self.assertNotEqual(fr.hashed_name("styles.css", b"a"), fr.hashed_name("styles.css", b"b"))

    def test_http_address_uses_the_same_link(self):
        self.assertEqual("https://fdroid.link/#http://192.168.1.66:8081/fdroid/repo?fingerprint=B227",
                         fr.add_repo_link("http://192.168.1.66:8081/fdroid/repo/", "b2:27"))


FG = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="1600" android:viewportHeight="1600">
  <group android:scaleX="1.9" android:scaleY="1.9" android:translateX="571.19" android:translateY="407.46">
    <path android:fillColor="#80233490" android:pathData="M0,0 H10 V10 Z"/>
  </group>
</vector>"""
BG = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
  <path android:fillColor="#FFFFFF" android:pathData="M0,0h108v108h-108z"/>
</vector>"""


class IconTest(unittest.TestCase):
    def layers(self, tmp, fg=FG):
        b, f = Path(tmp) / "bg.xml", Path(tmp) / "fg.xml"
        b.write_text(BG)
        f.write_text(fg)
        return b, f

    def test_converts_the_adaptive_icon(self):
        with tempfile.TemporaryDirectory() as tmp:
            svg = fr.launcher_svg(*self.layers(tmp))
        # The central 66 dp of 108 dp: 1600 * 66 / 108 = 977.778, centered.
        self.assertIn('viewBox="311.111 311.111 977.778 977.778"', svg)
        self.assertIn('<g transform="scale(14.8148 14.8148)"><path d="M0,0h108v108h-108z" fill="#FFFFFF"/></g>', svg)
        self.assertIn('translate(571.19 407.46) rotate(0) scale(1.9 1.9) translate(0 0)', svg)
        self.assertIn('fill="#233490" fill-opacity="0.501961"', svg)

    def test_path_without_fill_color_draws_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            svg = fr.launcher_svg(*self.layers(tmp, FG.replace('android:fillColor="#80233490" ', '')))
        self.assertIn('<path d="M0,0 H10 V10 Z" fill="none"/>', svg)

    def test_unknown_attribute_stops_the_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                fr.launcher_svg(*self.layers(tmp, FG.replace('android:fillColor="#80233490"',
                                                             'android:fillColor="@color/brand"')))
            with self.assertRaises(ValueError):
                fr.launcher_svg(*self.layers(tmp, FG.replace("<path ", '<path android:strokeWidth="2" ')))

    def test_the_real_app_icon_converts(self):
        svg = fr.launcher_svg(*fr.ICON_LAYERS)
        self.assertEqual(11, svg.count("<path "))
        self.assertTrue(svg.isascii())


class VerifyTest(unittest.TestCase):
    def test_release_signed_by_the_repo_key(self):
        fr.verify(index([("v1.2.0", 10200990)]), "v1.2.0", ":".join(["3A"] * 32))

    def test_missing_release(self):
        with self.assertRaises(ValueError):
            fr.verify(index([("v1.2.0", 10200990)]), "v1.3.0", "3a" * 32)

    def test_other_signer(self):
        with self.assertRaises(ValueError):
            fr.verify(index([("v1.2.0", 10200990)]), "v1.2.0", FP)

    def test_missing_named_file(self):
        idx = index([("v1.2.0", 10200990)])
        idx["packages"][fr.APP_ID]["metadata"]["icon"]["en-US"]["sha256"] = "cd" * 32
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            (repo / "K2Go-v1.2.0-fdroid-release.apk").write_text("x")
            self.assertEqual(["/org.appdevforall.k2go/en-US/icon_x.png"], fr.missing_files(idx, repo))
            (repo / "org.appdevforall.k2go/en-US").mkdir(parents=True)
            (repo / "org.appdevforall.k2go/en-US/icon_x.png").write_text("x")
            self.assertEqual([], fr.missing_files(idx, repo))


class PlanTest(unittest.TestCase):
    FILES = ["index.html", "add-repo-qr.png", "repo/entry.jar", "repo/entry.json", "repo/index-v2.json",
             "repo/index-v1.jar", "repo/diff/1795240931000.json", "repo/K2Go-v1.3.0-fdroid-release.apk",
             "repo/icons/k2go.png", "archive/K2Go-v1.0.0-fdroid-release.apk", "archive/entry.jar"]

    def keys(self, lines, op):
        return [line[1] for line in lines if line[0] == op]

    def test_files_go_up_before_the_index_that_names_them(self):
        puts = self.keys(fr.plan(self.FILES, "fdroid", []), "put")
        pos = {k: i for i, k in enumerate(puts)}
        apk = pos["fdroid/repo/K2Go-v1.3.0-fdroid-release.apk"]
        self.assertLess(apk, pos["fdroid/repo/index-v2.json"])
        self.assertLess(pos["fdroid/repo/icons/k2go.png"], pos["fdroid/repo/index-v1.jar"])
        self.assertLess(pos["fdroid/repo/index-v2.json"], pos["fdroid/repo/entry.jar"])
        self.assertLess(pos["fdroid/repo/diff/1795240931000.json"], pos["fdroid/repo/entry.json"])
        self.assertEqual(["fdroid/index.html", "fdroid/repo"], puts[-2:])
        self.assertLess(pos["fdroid/add-repo-qr.png"], pos["fdroid/index.html"])

    def test_repo_address_serves_the_landing_page(self):
        line = [x for x in fr.plan(self.FILES, "fdroid", []) if x[1] == "fdroid/repo"][0]
        self.assertEqual(("put", "fdroid/repo", "index.html", "text/html; charset=utf-8", fr.SHORT), line)
        self.assertNotIn("fdroid/repo", [x[1] for x in fr.plan(["repo/entry.jar"], "fdroid", [])])

    def test_headers(self):
        lines = {line[1]: line for line in fr.plan(self.FILES + ["assets/styles.0123abcd.css"], "fdroid", [])}
        self.assertEqual(("text/css", fr.LONG), lines["fdroid/assets/styles.0123abcd.css"][3:])
        apk = lines["fdroid/repo/K2Go-v1.3.0-fdroid-release.apk"]
        self.assertEqual(("application/vnd.android.package-archive", fr.LONG), apk[3:])
        self.assertEqual(("application/java-archive", fr.SHORT), lines["fdroid/repo/entry.jar"][3:])
        self.assertEqual(fr.SHORT, lines["fdroid/index.html"][4])

    def test_known_apk_is_not_sent_again(self):
        remote = ["fdroid/repo/K2Go-v1.3.0-fdroid-release.apk"]
        puts = self.keys(fr.plan(self.FILES, "fdroid", remote), "put")
        self.assertNotIn("fdroid/repo/K2Go-v1.3.0-fdroid-release.apk", puts)
        self.assertIn("fdroid/repo/entry.jar", puts)

    def test_deletes_last_and_only_stale_repo_keys(self):
        remote = ["fdroid/repo/K2Go-v1.0.0-fdroid-release.apk",   # moved to archive/
                  "fdroid/repo/entry.jar",                          # still published
                  "fdroid/index.html",                              # not under repo/ or archive/
                  "fdroid-staging/repo/x.apk"]                      # another prefix
        lines = fr.plan(self.FILES, "fdroid/", remote)
        self.assertEqual([("rm", "fdroid/repo/K2Go-v1.0.0-fdroid-release.apk")],
                         [line for line in lines if line[0] == "rm"])
        self.assertEqual("rm", lines[-1][0])

    def test_local_files_skip_the_run_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            for rel in ("repo/entry.jar", "repo/status/update.json", "archive/index-v2.json",
                        "config.yml", "keystore.jks", "index.html", "add-repo-qr.png",
                        "assets/styles.0123abcd.css",
                        "fingerprint.txt", "metadata/x.yml"):
                (work / rel).parent.mkdir(parents=True, exist_ok=True)
                (work / rel).write_text("x")
            self.assertEqual(["add-repo-qr.png", "archive/index-v2.json", "assets/styles.0123abcd.css",
                              "index.html", "repo/entry.jar"],
                             sorted(fr.local_files(work)))


if __name__ == "__main__":
    unittest.main()
