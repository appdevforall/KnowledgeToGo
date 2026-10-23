import os
import unittest

from cx_core.config import DEFAULT_PORT, Config, _parse_port


class ParsePortTest(unittest.TestCase):
    def test_valid_port(self):
        self.assertEqual(_parse_port("8080"), 8080)

    def test_non_numeric_falls_back(self):
        self.assertEqual(_parse_port("abc"), DEFAULT_PORT)

    def test_empty_falls_back(self):
        self.assertEqual(_parse_port(""), DEFAULT_PORT)

    def test_out_of_range_falls_back(self):
        self.assertEqual(_parse_port("0"), DEFAULT_PORT)
        self.assertEqual(_parse_port("70000"), DEFAULT_PORT)


class ContentDirTest(unittest.TestCase):
    def setUp(self):
        self._saved = os.environ.pop("CX_CONTENT_DIR", None)

    def tearDown(self):
        if self._saved is not None:
            os.environ["CX_CONTENT_DIR"] = self._saved
        else:
            os.environ.pop("CX_CONTENT_DIR", None)

    def test_defaults_to_library(self):
        self.assertEqual(Config.from_env().content_dir, "/library")

    def test_env_override(self):
        os.environ["CX_CONTENT_DIR"] = "/data/content"
        self.assertEqual(Config.from_env().content_dir, "/data/content")


if __name__ == "__main__":
    unittest.main()
