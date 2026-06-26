/*
 * ============================================================================
 * Name        : RootfsTreeHashTest.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Byte-parity gate for RootfsTreeHash. GOLDEN is produced by the
 *               frozen reference tools/rootfs-builder/iiab_tree_hash.py over a fixed fixture of
 *               logical members. Passing this test is what lets us BLOCK on a
 *               hash mismatch with confidence (the Java verifier agrees with the
 *               build side byte-for-byte).
 * ============================================================================
 */
package org.iiab.controller.deploy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.iiab.controller.deploy.domain.RootfsTreeHash.Member;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class RootfsTreeHashTest {

    /** python3 tools/rootfs-builder/iiab_tree_hash.py fixture.tar <integrity-member>. */
    private static final String GOLDEN =
            "e892069fb8eb7d5eb199780cbb86a25bfe6a77fec013445139d092cf139a58ec";

    private static final String IDENTITY = "installed-rootfs/iiab/.iiab-rootfs.json";
    private static final String INTEGRITY = "installed-rootfs/iiab/.iiab-rootfs.integrity.json";

    private static Member reg(final String name, final String content) {
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new Member() {
            public String name() { return name; }
            public char type() { return 'f'; }
            public String linkTarget() { return null; }
            public InputStream openContent() { return new ByteArrayInputStream(bytes); }
        };
    }

    private static Member dir(final String name) {
        return new Member() {
            public String name() { return name; }
            public char type() { return 'd'; }
            public String linkTarget() { return null; }
            public InputStream openContent() { return null; }
        };
    }

    private static Member link(final char type, final String name, final String target) {
        return new Member() {
            public String name() { return name; }
            public char type() { return type; }
            public String linkTarget() { return target; }
            public InputStream openContent() { return null; }
        };
    }

    private static List<Member> fixture() {
        List<Member> ms = new ArrayList<>();
        ms.add(reg(IDENTITY, "{\"schema\":1,\"kind\":\"iiab-rootfs\",\"arch\":\"arm64-v8a\"}"));
        ms.add(dir("installed-rootfs/bin/"));
        ms.add(reg("installed-rootfs/bin/hello", "hello world\n"));
        ms.add(link('l', "installed-rootfs/bin/hello-link", "hello"));
        ms.add(link('h', "installed-rootfs/bin/hello-hard", "installed-rootfs/bin/hello"));
        ms.add(reg(INTEGRITY, "{\"schema\":1,\"algo\":\"iiab-tree-sha256-v1\",\"treehash\":\"PLACEHOLDER\"}"));
        return ms;
    }

    @Test
    public void matchesReferenceGolden() throws Exception {
        assertEquals(GOLDEN, RootfsTreeHash.compute(fixture(), INTEGRITY));
    }

    @Test
    public void isOrderIndependent() throws Exception {
        List<Member> ms = fixture();
        Collections.shuffle(ms, new Random(42));
        assertEquals(GOLDEN, RootfsTreeHash.compute(ms, INTEGRITY));
    }

    @Test
    public void integrityMemberIsExcludedFromTheHash() throws Exception {
        List<Member> ms = fixture();
        ms.set(ms.size() - 1, reg(INTEGRITY, "{\"totally\":\"different\"}"));
        assertEquals(GOLDEN, RootfsTreeHash.compute(ms, INTEGRITY));
    }

    @Test
    public void flippingOneContentByteChangesTheHash() throws Exception {
        List<Member> ms = fixture();
        ms.set(2, reg("installed-rootfs/bin/hello", "hello WORLD\n"));
        assertNotEquals(GOLDEN, RootfsTreeHash.compute(ms, INTEGRITY));
    }

    @Test(expected = RootfsTreeHash.UnhashableMemberException.class)
    public void unhashableMemberTypeAborts() throws Exception {
        List<Member> ms = new ArrayList<>();
        ms.add(link('c', "installed-rootfs/dev/null", null)); // 'c' = char device
        RootfsTreeHash.compute(ms, "");
    }

    @Test
    public void normMatchesReferenceRules() {
        assertEquals("a", RootfsTreeHash.norm("./a//"));
        assertEquals("x/y", RootfsTreeHash.norm("/x/y/"));
        assertEquals("a/b", RootfsTreeHash.norm("a\\b"));
        assertEquals("", RootfsTreeHash.norm("/"));
    }
}
