package org.iiab.controller.deploy.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ArchiveEntry} — the path-traversal guard that closes
 * D11 (malicious tar member names escaping the extraction directory).
 */
public class ArchiveEntryTest {

    @Test
    public void allowsNormalRelativeEntries() {
        assertFalse(ArchiveEntry.escapesRoot("etc/hosts"));
        assertFalse(ArchiveEntry.escapesRoot("./etc/hosts"));
        assertFalse(ArchiveEntry.escapesRoot("installed-rootfs/usr/bin/bash"));
        assertFalse(ArchiveEntry.escapesRoot("a/b/c/"));        // directory entry
        assertFalse(ArchiveEntry.escapesRoot("a/../b"));        // dips in then stays inside
    }

    @Test
    public void blocksParentTraversal() {
        assertTrue(ArchiveEntry.escapesRoot("../etc/passwd"));
        assertTrue(ArchiveEntry.escapesRoot("a/../../b"));
        assertTrue(ArchiveEntry.escapesRoot("foo/../../../bar"));
        assertTrue(ArchiveEntry.escapesRoot("..")); // bare parent
    }

    @Test
    public void blocksAbsolutePaths() {
        assertTrue(ArchiveEntry.escapesRoot("/etc/passwd"));
        assertTrue(ArchiveEntry.escapesRoot("/"));
    }

    @Test
    public void blocksBackslashTraversal() {
        // Normalize backslashes so a Windows-style payload can't sneak past.
        assertTrue(ArchiveEntry.escapesRoot("..\\..\\x"));
    }

    @Test
    public void ignoresBlankOrNull() {
        assertFalse(ArchiveEntry.escapesRoot(null));
        assertFalse(ArchiveEntry.escapesRoot(""));
        assertFalse(ArchiveEntry.escapesRoot("   "));
    }
}
