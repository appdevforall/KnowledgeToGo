import io
import os
import tarfile
import tempfile
import unittest

from cx_core.export import file_stream, resolve_within, tar_stream


class FileStreamTest(unittest.TestCase):
    def test_streams_whole_file(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "f.bin")
            data = os.urandom(3 * 1024 * 1024 + 7)  # spans several chunks
            with open(p, "wb") as f:
                f.write(data)
            self.assertEqual(b"".join(file_stream(p, chunk=1024 * 1024)), data)


class ResolveWithinTest(unittest.TestCase):
    def test_inside_is_resolved(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "a", "b"))
            self.assertEqual(resolve_within(d, "a/b"), os.path.realpath(os.path.join(d, "a", "b")))

    def test_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertIsNone(resolve_within(d, "../etc"))
            self.assertIsNone(resolve_within(d, "a/../../x"))
            self.assertIsNone(resolve_within(d, ""))


class TarStreamTest(unittest.TestCase):
    def test_round_trips_a_tree(self):
        with tempfile.TemporaryDirectory() as src:
            os.makedirs(os.path.join(src, "sub"))
            with open(os.path.join(src, "a.txt"), "wb") as f:
                f.write(b"hello")
            with open(os.path.join(src, "sub", "b.bin"), "wb") as f:
                f.write(b"\x00\x01\x02world")

            blob = b"".join(tar_stream(src, arcname="item"))
            with tarfile.open(fileobj=io.BytesIO(blob), mode="r") as tar:
                names = sorted(m.name for m in tar.getmembers() if m.isfile())
                self.assertEqual(names, ["item/a.txt", "item/sub/b.bin"])
                self.assertEqual(tar.extractfile("item/a.txt").read(), b"hello")
                self.assertEqual(tar.extractfile("item/sub/b.bin").read(), b"\x00\x01\x02world")


if __name__ == "__main__":
    unittest.main()
