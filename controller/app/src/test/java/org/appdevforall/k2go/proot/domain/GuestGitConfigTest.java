package org.appdevforall.k2go.proot.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GuestGitConfigTest {

    private static final String BOTH =
            "[core]\n\tcreateObject = rename\n[transfer]\n\tunpackLimit = 1\n";

    @Test
    public void emptyOrMissingGetsBothSettings() {
        assertEquals(BOTH, GuestGitConfig.toAppend(""));
        assertEquals(BOTH, GuestGitConfig.toAppend(null));
    }

    @Test
    public void bakedConfigNeedsNothing() {
        String baked = "[pack]\n\twriteReverseIndex = false\n" + BOTH;
        assertEquals("", GuestGitConfig.toAppend(baked));
    }

    @Test
    public void oldBakeGetsOnlyTheMissingSettings() {
        String old = "[pack]\n\twriteReverseIndex = false\n";
        assertEquals(BOTH, GuestGitConfig.toAppend(old));
        String half = old + "[core]\n\tcreateObject = rename\n";
        assertEquals("[transfer]\n\tunpackLimit = 1\n", GuestGitConfig.toAppend(half));
    }

    @Test
    public void lastValueWinsLikeGit() {
        // An earlier rename overridden by a later link is NOT in effect: append again.
        String overridden = BOTH + "[core]\n\tcreateobject = link\n";
        assertEquals("[core]\n\tcreateObject = rename\n", GuestGitConfig.toAppend(overridden));
    }

    @Test
    public void keysAndSectionsAreCaseInsensitiveAndCommentsIgnored() {
        String text = "[CORE]\n  CreateObject=rename   # proot\n[Transfer]\nunpacklimit = 1 ; keep packs\n";
        assertEquals("", GuestGitConfig.toAppend(text));
    }

    @Test
    public void subsectionsAndOtherSectionsDoNotCount() {
        String text = "[core \"x\"]\n\tcreateObject = rename\n[user]\n\tunpackLimit = 1\n";
        assertEquals(BOTH, GuestGitConfig.toAppend(text));
        assertNull(GuestGitConfig.lastValue(text, "core", "createobject"));
    }

    @Test
    public void missingFinalNewlineIsAddedBeforeTheBlock() {
        assertEquals("\n" + BOTH, GuestGitConfig.toAppend("[pack]\n\twriteReverseIndex = false"));
    }
}
