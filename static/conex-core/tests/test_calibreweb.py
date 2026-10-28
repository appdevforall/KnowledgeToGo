import os
import tempfile
import unittest

from conex_core.adapters.calibreweb import _ConcatStream, _multipart_frame, safe_upload_name


class SafeUploadNameTest(unittest.TestCase):
    def test_strips_crlf_and_quotes_and_path(self):
        self.assertEqual(safe_upload_name('a"b.epub'), "ab.epub")
        self.assertEqual(safe_upload_name("evil\r\nContent-Disposition: x.epub"), "evilContent-Disposition: x.epub")
        self.assertEqual(safe_upload_name("../../etc/passwd"), "passwd")
        self.assertEqual(safe_upload_name('\r\n"'), "upload.bin")

    def test_header_cannot_be_injected(self):
        name = safe_upload_name('x"\r\nX-Injected: 1')
        self.assertNotIn("\r", name)
        self.assertNotIn("\n", name)
        self.assertNotIn('"', name)


class ConcatStreamTest(unittest.TestCase):
    def test_reads_prefix_file_suffix_in_order(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "f.bin")
            file_bytes = os.urandom(3 * 1024 * 1024 + 11)  # spans several reads
            with open(path, "wb") as f:
                f.write(file_bytes)
            prefix, suffix = b"PREFIX---", b"---SUFFIX"
            stream = _ConcatStream(prefix, path, suffix)
            try:
                got = stream.read()  # RawIOBase.read() drains via readinto
            finally:
                stream.close()
            self.assertEqual(got, prefix + file_bytes + suffix)

    def test_declared_length_matches_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "f.bin")
            with open(path, "wb") as f:
                f.write(b"12345")
            prefix, suffix = _multipart_frame("B", {"csrf_token": "t"}, "btn-upload", "x.epub")
            length = len(prefix) + os.path.getsize(path) + len(suffix)
            stream = _ConcatStream(prefix, path, suffix)
            try:
                self.assertEqual(len(stream.read()), length)
            finally:
                stream.close()


if __name__ == "__main__":
    unittest.main()
