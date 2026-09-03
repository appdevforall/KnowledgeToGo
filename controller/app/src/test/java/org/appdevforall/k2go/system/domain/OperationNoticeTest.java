package org.appdevforall.k2go.system.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The rule the module action sheet reads to decide whether a row must state its consequence
 * before it is tapped: an operation that is not live takes the services down while it runs.
 *
 * <p>Not a class of its own — the rule is {@code !op.isLive()}, and giving a one-line predicate
 * a home of its own would be the same over-building as the enum this replaced. What the sheet
 * needed was not a new type but to <em>ask</em>, and what is worth pinning is that asking gives
 * the right answer for each way an operation is built. A review found the sheet had instead
 * typed the class in by hand at four call sites, in a view class, with the model in scope.
 */
public class OperationNoticeTest {

    @Test
    public void installingAPlatformIsNotLiveSoItMustSaySo() {
        assertFalse(Operation.appInstall("kolibri").isLive());
        assertFalse(Operation.appInstall("maps").isLive());
    }

    @Test
    public void aMissingPlatformNameDoesNotChangeTheClass() {
        // The sheet builds this from a card key that is nullable in its defensive path. The
        // class must not depend on whether the name arrived.
        assertFalse(Operation.appInstall(null).isLive());
        assertFalse(Operation.appInstall("").isLive());
    }

    @Test
    public void addingContentIsLiveSoThereIsNothingToWarnAbout() {
        // The counterpart, and the reason the rule is worth reading rather than assuming: not
        // everything the user asks for takes the box down, and the two must not look alike.
        assertTrue(Operation.content("kolibri").isLive());
        assertTrue(Operation.content("zim").isLive());
    }

    @Test
    public void replacingTheSystemIsNotLiveEither() {
        // Not offered from this sheet today, but it is the same question and the same answer,
        // so a surface that starts offering it inherits the notice rather than needing one.
        assertFalse(Operation.system().isLive());
    }
}
