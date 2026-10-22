import unittest

from cx_core.config import DEFAULT_PORT, _parse_port


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


if __name__ == "__main__":
    unittest.main()
