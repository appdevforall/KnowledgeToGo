"""Unit tests for the pure rules in play_publish. Run: python -m unittest -v"""
import unittest

import play_publish as pp

GRADLE = '''
    defaultConfig {
        versionCode 71
        def ciSuffix = ""
        versionName "v1.2.0${ciSuffix}"
    }
'''


class VersionTest(unittest.TestCase):
    def test_reads_version_and_code_base(self):
        self.assertEqual("v1.2.0", pp.version_name(GRADLE))
        self.assertEqual(71, pp.version_code_base(GRADLE))

    def test_rejects_a_prerelease_in_the_binary(self):
        with self.assertRaises(pp.PlanError):
            pp.version_name(GRADLE.replace("v1.2.0", "v1.2.0-rc.1"))

    def test_reports_a_missing_version(self):
        with self.assertRaises(pp.PlanError):
            pp.version_name("versionCode 71")


class RcTagTest(unittest.TestCase):
    def test_first_candidate_is_rc1(self):
        self.assertEqual("v1.2.0-rc.1", pp.next_rc_tag({"v1.1.0", "v1.0.0"}, "v1.2.0"))

    def test_next_candidate_follows_the_highest_not_the_count(self):
        tags = {"v1.1.0", "v1.2.0-rc.1", "v1.2.0-rc.3", "v1.2.0-rc.10"}
        self.assertEqual("v1.2.0-rc.11", pp.next_rc_tag(tags, "v1.2.0"))

    def test_other_versions_do_not_count(self):
        tags = {"v1.1.0-rc.4", "v1.2.0-rc.1x", "v1.2.0-beta"}
        self.assertEqual("v1.2.0-rc.1", pp.next_rc_tag(tags, "v1.2.0"))

    def test_a_released_version_is_closed(self):
        with self.assertRaises(pp.PlanError):
            pp.next_rc_tag({"v1.2.0", "v1.2.0-rc.2"}, "v1.2.0")


class FinalTagTest(unittest.TestCase):
    TAGS = {"v1.1.0", "v1.2.0-rc.1", "v1.2.0-rc.2"}

    def test_newest_candidate_becomes_the_release(self):
        self.assertEqual("v1.2.0", pp.final_tag(self.TAGS, "v1.2.0-rc.2"))

    def test_an_older_candidate_is_refused(self):
        with self.assertRaises(pp.PlanError):
            pp.final_tag(self.TAGS, "v1.2.0-rc.1")

    def test_a_missing_candidate_is_refused(self):
        with self.assertRaises(pp.PlanError):
            pp.final_tag(self.TAGS, "v1.2.0-rc.3")

    def test_a_released_version_is_closed(self):
        with self.assertRaises(pp.PlanError):
            pp.final_tag(self.TAGS | {"v1.2.0"}, "v1.2.0-rc.2")

    def test_only_candidate_tags_are_accepted(self):
        for bad in ("v1.2.0", "v1.2.0-rc.2; rm -rf /", "1.2.0-rc.2", "v1.2.0-internal.723"):
            with self.assertRaises(pp.PlanError):
                pp.final_tag(self.TAGS | {bad}, bad)


class NotesTest(unittest.TestCase):
    MD = "# OTA update notes\n\nIntro.\n\n## v1.2.0\nLine one.\n\nLine two.\n\n## v1.1.0\nOld.\n"

    def test_takes_the_top_entry_without_blank_lines(self):
        self.assertEqual("Line one.\nLine two.", pp.release_notes(self.MD, "v1.2.0"))

    def test_top_entry_must_match_the_version(self):
        with self.assertRaises(pp.PlanError):
            pp.release_notes(self.MD, "v1.3.0")

    def test_play_limit_is_enforced(self):
        with self.assertRaises(pp.PlanError):
            pp.release_notes("## v1.2.0\n" + "x" * 501 + "\n", "v1.2.0")

    def test_empty_entry_is_rejected(self):
        with self.assertRaises(pp.PlanError):
            pp.release_notes("## v1.2.0\n\n## v1.1.0\nOld.\n", "v1.2.0")


class VersionCodeTest(unittest.TestCase):
    def test_takes_highest_used_plus_one(self):
        self.assertEqual(721, pp.next_version_code({700, 710, 720}, 71))

    def test_floor_is_the_gradle_code(self):
        # build.gradle moved ahead of Play: start at its APK code, never below it.
        self.assertEqual(800, pp.next_version_code({720}, 80))
        self.assertEqual(710, pp.next_version_code(set(), 71))

    def test_counts_tracks_and_uploads_never_released(self):
        tracks = {"tracks": [
            {"track": "beta", "releases": [{"versionCodes": ["700"]}, {"status": "draft"}]},
            {"track": "production", "releases": []},
            {"track": "internal"},
        ]}
        bundles = {"bundles": [{"versionCode": 690}, {"versionCode": 715}]}
        self.assertEqual({690, 700, 715, 640},
                         pp.used_version_codes(tracks, bundles, {"apks": [{"versionCode": 640}]}))
        self.assertEqual(set(), pp.used_version_codes({}, {}, {}))


class TrackReleaseTest(unittest.TestCase):
    def test_release_goes_to_review_with_notes(self):
        body = pp.track_release("beta", "v1.2.0-rc.2 (722)", 722, "Notes.")
        release = body["releases"][0]
        self.assertEqual("beta", body["track"])
        self.assertEqual(["722"], release["versionCodes"])
        self.assertEqual("completed", release["status"])
        self.assertEqual([{"language": "en-US", "text": "Notes."}], release["releaseNotes"])


if __name__ == "__main__":
    unittest.main()
