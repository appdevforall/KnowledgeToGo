#!/usr/bin/env python3
# K2GO-476: checks for build_kolibri_catalog.py. Run: python3 -m unittest test_build_kolibri_catalog
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_kolibri_catalog as g  # noqa: E402


class DistinctBytesTest(unittest.TestCase):

    def test_a_file_shared_by_two_topics_counts_once_per_folder(self):
        # root -> A -> v1, root -> B -> v2; v1 and v2 use the same 100-byte video.
        parent_of = {"root": None, "A": "root", "B": "root", "v1": "A", "v2": "B"}
        uses = {"video": (100, ["v1", "v2"]), "pdf": (7, ["v2"])}
        sub, direct = g.distinct_bytes(uses, parent_of, {"v1", "v2"})
        self.assertEqual(sub["A"], 100)
        self.assertEqual(sub["B"], 107)
        self.assertEqual(sub["root"], 107)   # not 207
        self.assertEqual(direct["A"], 100)
        self.assertEqual(direct["B"], 107)


if __name__ == "__main__":
    unittest.main()
