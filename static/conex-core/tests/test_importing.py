import io
import os
import shutil
import tarfile
import tempfile
import unittest

from conex_core.importing import escapes_root, is_within_dir, safe_extract_tar, single_child_dir


def _tar(path, entries):
    """entries: list of (name, type) where type in {'file','../','abs','sym'} or a plain name."""
    with tarfile.open(path, "w") as t:
        for name, kind in entries:
            if kind == "sym":
                ti = tarfile.TarInfo(name)
                ti.type = tarfile.SYMTYPE
                ti.linkname = "/etc/passwd"
                t.addfile(ti)
            else:
                data = b"data"
                ti = tarfile.TarInfo(name)
                ti.size = len(data)
                t.addfile(ti, io.BytesIO(data))


class EscapesRootTest(unittest.TestCase):
    def test_safe_names(self):
        for n in ["a.txt", "./a.txt", "sub/b.bin", "a/../b.txt", "chan/content/databases/x.sqlite3"]:
            self.assertFalse(escapes_root(n), n)

    def test_unsafe_names(self):
        for n in ["../x", "a/../../x", "/etc/passwd", "..", "sub/../../out"]:
            self.assertTrue(escapes_root(n), n)


class IsWithinDirTest(unittest.TestCase):
    def test_inside_and_outside(self):
        with tempfile.TemporaryDirectory() as d:
            inside = os.path.join(d, "a", "b")
            self.assertTrue(is_within_dir(d, inside))
            self.assertFalse(is_within_dir(d, os.path.dirname(d)))


class SafeExtractTarTest(unittest.TestCase):
    def test_extracts_a_safe_tar(self):
        with tempfile.TemporaryDirectory() as d:
            tar_path = os.path.join(d, "ok.tar")
            _tar(tar_path, [("item/a.txt", "file"), ("item/sub/b.txt", "file")])
            dest = os.path.join(d, "out")
            safe_extract_tar(tar_path, dest)
            self.assertTrue(os.path.isfile(os.path.join(dest, "item", "a.txt")))
            self.assertEqual(single_child_dir(dest), os.path.join(dest, "item"))

    def test_rejects_traversal_member(self):
        with tempfile.TemporaryDirectory() as d:
            tar_path = os.path.join(d, "evil.tar")
            _tar(tar_path, [("good.txt", "file"), ("../escape.txt", "file")])
            with self.assertRaises(ValueError):
                safe_extract_tar(tar_path, os.path.join(d, "out"))
            # nothing extracted outside
            self.assertFalse(os.path.exists(os.path.join(d, "escape.txt")))

    def test_rejects_absolute_member(self):
        with tempfile.TemporaryDirectory() as d:
            tar_path = os.path.join(d, "abs.tar")
            _tar(tar_path, [("/etc/evil", "file")])
            with self.assertRaises(ValueError):
                safe_extract_tar(tar_path, os.path.join(d, "out"))

    def test_rejects_symlink_member(self):
        with tempfile.TemporaryDirectory() as d:
            tar_path = os.path.join(d, "sym.tar")
            _tar(tar_path, [("link", "sym")])
            with self.assertRaises(ValueError):
                safe_extract_tar(tar_path, os.path.join(d, "out"))

    def test_rejects_too_many_members(self):
        with tempfile.TemporaryDirectory() as d:
            tar_path = os.path.join(d, "many.tar")
            _tar(tar_path, [("a.txt", "file"), ("b.txt", "file")])
            with self.assertRaises(ValueError):
                safe_extract_tar(tar_path, os.path.join(d, "out"), max_members=1)

    def test_rejects_summed_size_over_cap(self):
        with tempfile.TemporaryDirectory() as d:
            tar_path = os.path.join(d, "big.tar")
            # each member is 4 bytes of "data"; two members = 8 > cap of 5.
            _tar(tar_path, [("a.txt", "file"), ("b.txt", "file")])
            with self.assertRaises(ValueError):
                safe_extract_tar(tar_path, os.path.join(d, "out"), max_bytes=5)
            # nothing landed
            self.assertFalse(os.path.exists(os.path.join(d, "out", "a.txt")))

    def test_no_disk_free_bound_without_cap(self):
        # The box runs under proot, whose statvfs under-reports free space, so safe_extract_tar
        # must never reject an extraction based on disk free. With no max_bytes it extracts even
        # when the disk reports nearly full; if the disk were consulted, this mock would fire.
        with tempfile.TemporaryDirectory() as d:
            tar_path = os.path.join(d, "ok.tar")
            _tar(tar_path, [("item/a.txt", "file"), ("item/b.txt", "file")])
            dest = os.path.join(d, "out")
            orig = shutil.disk_usage

            def boom(_path):
                raise AssertionError("free space must not be consulted during import extraction")

            shutil.disk_usage = boom
            try:
                safe_extract_tar(tar_path, dest)  # no max_bytes
            finally:
                shutil.disk_usage = orig
            self.assertTrue(os.path.isfile(os.path.join(dest, "item", "a.txt")))


if __name__ == "__main__":
    unittest.main()
