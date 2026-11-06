package org.appdevforall.k2go.deploy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Unit tests for {@link TarVerboseLine} — parsing GNU tar -tv lines (bundled libtar 1.35) into
 * path + byte size for byte-weighted extract progress (K2GO-555). The sample lines are verbatim
 * from the bundled tar on device.
 */
public class TarVerboseLineTest {

    @Test
    public void parsesRegularFileSize() {
        TarVerboseLine e = TarVerboseLine.parse("-rw-rw-rw- shell/shell   45678 2026-11-06 08:00 ./sub/file_b.dat");
        assertEquals("./sub/file_b.dat", e.path);
        assertEquals(45678L, e.size);
    }

    @Test
    public void parsesPathWithSpaces() {
        TarVerboseLine e = TarVerboseLine.parse("-rw-rw-rw- shell/shell       7 2026-11-06 08:00 ./name with space.txt");
        assertEquals("./name with space.txt", e.path);
        assertEquals(7L, e.size);
    }

    @Test
    public void parsesLargeSize() {
        TarVerboseLine e = TarVerboseLine.parse("-rw-rw-rw- shell/shell 1048577 2026-11-06 08:00 ./big_over_1mb.bin");
        assertEquals(1048577L, e.size);
    }

    @Test
    public void directoryHasZeroSize() {
        TarVerboseLine e = TarVerboseLine.parse("drwxrwxrwx shell/shell       0 2026-11-06 08:00 ./empty_dir/");
        assertEquals("./empty_dir/", e.path);
        assertEquals(0L, e.size);
    }

    @Test
    public void symlinkStripsTargetAndCountsZero() {
        TarVerboseLine e = TarVerboseLine.parse("lrwxrwxrwx shell/shell       0 2026-11-06 08:00 ./link_c -> file_a.txt");
        assertEquals("./link_c", e.path);   // matches the plain path the extract pass prints
        assertEquals(0L, e.size);
    }

    @Test
    public void hardlinkStripsTargetAndCountsZero() {
        TarVerboseLine e = TarVerboseLine.parse("hrw-rw-rw- shell/shell       0 2026-11-06 08:00 ./hard_d.txt link to ./file_a.txt");
        assertEquals("./hard_d.txt", e.path);
        assertEquals(0L, e.size);
    }

    @Test
    public void modeWithAclSuffixStillParses() {
        // GNU tar may append '+' or '.' to the mode for an ACL / SELinux context; the type char is still first.
        TarVerboseLine e = TarVerboseLine.parse("-rw-rw-rw-+ shell/shell   100 2026-11-06 08:00 ./acl_file");
        assertEquals("./acl_file", e.path);
        assertEquals(100L, e.size);
    }

    @Test
    public void rejectsNonMemberLines() {
        assertNull(TarVerboseLine.parse(null));
        assertNull(TarVerboseLine.parse(""));
        assertNull(TarVerboseLine.parse("tar: some warning on stderr"));
        assertNull(TarVerboseLine.parse("./plain-name-line"));   // extract-pass style line, not a verbose listing
    }
}
