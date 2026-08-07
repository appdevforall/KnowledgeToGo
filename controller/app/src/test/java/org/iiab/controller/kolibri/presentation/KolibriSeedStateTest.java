package org.iiab.controller.kolibri.presentation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for {@link KolibriSeedState} — the snapshot the seeding screens
 * render. Pure JVM: no Android, no service, no network.
 *
 * <p>Lives in the same package because the transitions are package-private:
 * only the service and this test have any business driving them.
 */
public class KolibriSeedStateTest {

    private static final String KHAN = "95a52b386f2c485cb97dd60901674a98";
    private static final String ASAF = "c150ea1d69495d37b5b0ac6f017e9bfb";
    private static final String STORY = "f9d3e0e46ea25789bbed672ff6a399ed";

    private static final long GB = 1024L * 1024 * 1024;

    private static KolibriSeedState.Item item(String id, String label, long bytes) {
        return KolibriSeedState.Item.pending(id, label, bytes);
    }

    private static KolibriSeedState three() {
        return KolibriSeedState.of(Arrays.asList(
                item(KHAN, "Khan Academy", 60L * GB),
                item(ASAF, "3asafeer", 3L * GB),
                item(STORY, "Storybooks", 1L * GB)));
    }

    @Test
    public void idleHasNoSession() {
        KolibriSeedState s = KolibriSeedState.idle();
        assertFalse(s.hasSession());
        assertFalse(s.isRunning());
        assertFalse(s.isComplete());
        assertEquals(0, s.size());
        assertEquals(0, s.overallPercent());
        assertNull(s.current());
    }

    @Test
    public void anEmptyOrNullQueueIsIdle() {
        assertFalse(KolibriSeedState.of(null).hasSession());
        assertFalse(KolibriSeedState.of(
                Collections.<KolibriSeedState.Item>emptyList()).hasSession());
        assertFalse(KolibriSeedState.of(
                Collections.<KolibriSeedState.Item>singletonList(null)).hasSession());
    }

    @Test
    public void aFreshSessionIsNotRunningUntilAnItemStarts() {
        KolibriSeedState s = three();
        assertTrue(s.hasSession());
        assertFalse(s.isRunning());
        assertEquals(3, s.size());
        assertEquals(0, s.firstPending());
    }

    @Test
    public void startingAnItemMakesItActiveAndTheSessionRunning() {
        KolibriSeedState s = three().startingItem(1);
        assertTrue(s.isRunning());
        assertEquals(1, s.index());
        assertEquals(KolibriSeedState.Status.ACTIVE, s.items().get(1).status());
        assertEquals("3asafeer", s.current().label());
    }

    @Test
    public void firstPendingSkipsWhatIsAlreadyTerminal() {
        KolibriSeedState s = three().finishItem(0, true).finishItem(1, false);
        assertEquals(2, s.firstPending());
    }

    @Test
    public void progressIsWeightedByBytesNotByItemCount() {
        // Kolibri channels differ by orders of magnitude — this catalog runs from
        // a few MB to 62 GB. Counting items would show a bar that sits still for
        // an hour and then jumps.
        KolibriSeedState s = three().finishItem(2, true);   // the 1 GB one
        assertEquals(1, s.overallPercent());                // not 33
    }

    @Test
    public void theItemInFlightContributesItsOwnFraction() {
        KolibriSeedState s = three()
                .finishItem(1, true)              // 3 of 64 GB
                .startingItem(0)
                .progress(0, 50, 1024L);          // half of 60 GB
        // (3 + 30) / 64 = 51%
        assertEquals(51, s.overallPercent());
        assertEquals(1024L, s.speedBytesPerSec());
    }

    @Test
    public void withNoKnownSizesProgressFallsBackToCountingItems() {
        KolibriSeedState s = KolibriSeedState.of(Arrays.asList(
                item(KHAN, "a", 0L), item(ASAF, "b", 0L)));
        assertEquals(0, s.overallPercent());
        assertEquals(50, s.finishItem(0, true).overallPercent());
    }

    @Test
    public void aFailedItemStillCountsAsTerminalForProgress() {
        // The bar must not stall on a channel that has definitively failed; the
        // batch has moved past it.
        KolibriSeedState s = three().finishItem(0, false);
        assertEquals(93, s.overallPercent());
    }

    @Test
    public void isCompleteNeedsEveryItemTerminalAndNothingInFlight() {
        // Each item is started before it is finished, which is what the service
        // does: startingItem is the only thing that marks the session running, so
        // a state built by finishing items that were never started would never
        // have been running and would go complete a step early.
        KolibriSeedState s = three()
                .startingItem(0).finishItem(0, true)
                .startingItem(1).finishItem(1, true);
        assertFalse("one item is still pending", s.isComplete());

        s = s.startingItem(2).finishItem(2, false);
        assertFalse("every item is terminal, but the loop has not stopped", s.isComplete());

        s = s.stopped();
        assertTrue(s.isComplete());
        assertEquals(1, s.failedCount());
    }

    @Test
    public void aSessionThatNeverStartedIsNotHeldOpen() {
        // The mirror of the case above. Nothing ever ran, so there is no loop to
        // wait for and every item being terminal is the whole story.
        KolibriSeedState s = three()
                .finishItem(0, true).finishItem(1, true).finishItem(2, true);
        assertFalse(s.isRunning());
        assertTrue(s.isComplete());
    }

    @Test
    public void retryPutsAFailedItemBackInTheQueue() {
        KolibriSeedState s = three().finishItem(0, false).stopped();
        assertEquals(1, s.failedCount());

        s = s.retry(0);
        assertEquals(KolibriSeedState.Status.PENDING, s.items().get(0).status());
        assertEquals(0, s.failedCount());
        assertEquals(0, s.firstPending());
    }

    @Test
    public void retryDoesNothingToAnItemThatDidNotFail() {
        // Re-queueing a finished channel would download it twice.
        KolibriSeedState done = three().finishItem(0, true);
        assertEquals(KolibriSeedState.Status.DONE, done.retry(0).items().get(0).status());
        assertEquals(KolibriSeedState.Status.PENDING, done.retry(1).items().get(1).status());
    }

    @Test
    public void outOfRangeTransitionsAreIgnoredRatherThanThrowing() {
        // The service indexes from callbacks that can arrive after a session was
        // replaced; a stale index must not take the process down.
        KolibriSeedState s = three();
        assertEquals(s, s.startingItem(9));
        assertEquals(s, s.progress(-1, 50, 0L));
        assertEquals(s, s.finishItem(42, true));
        assertEquals(s, s.retry(99));
    }

    @Test
    public void statusOrdinalsMatchTheChecklistConvention() {
        // ProvisioningChecklist reads PENDING as 0 and anything that is neither
        // doneVal nor failedVal as "active", so ACTIVE must sit between them.
        assertEquals(0, KolibriSeedState.Status.PENDING.ordinal());
        KolibriSeedState s = three().startingItem(0).finishItem(1, true).finishItem(2, false);
        assertArrayEquals(new int[]{
                KolibriSeedState.Status.ACTIVE.ordinal(),
                KolibriSeedState.Status.DONE.ordinal(),
                KolibriSeedState.Status.FAILED.ordinal()}, s.statusOrdinals());
    }

    @Test
    public void indeterminateProgressDoesNotInflateTheBar() {
        // Kolibri reports no percentage while it writes the content DB; -1 is the
        // indeterminate convention and must not be read as 1%.
        KolibriSeedState s = three().startingItem(0).progress(0, -1, 0L);
        assertEquals(0, s.overallPercent());
    }

    @Test
    public void currentIsNullWhenNothingIsInFlight() {
        assertNull(three().current());
        assertNull(three().startingItem(0).stopped().current());
    }

    @Test
    public void everySnapshotIsANewValueAndTheItemListIsImmutable() {
        KolibriSeedState before = three();
        KolibriSeedState after = before.finishItem(0, true);
        assertEquals(KolibriSeedState.Status.PENDING, before.items().get(0).status());
        assertEquals(KolibriSeedState.Status.DONE, after.items().get(0).status());
        try {
            before.items().add(item(KHAN, "x", 1L));
            fail("expected the item list to be immutable");
        } catch (UnsupportedOperationException expected) {
            // as designed
        }
    }

    @Test
    public void theSubtreeSelectionSurvivesEveryStatusTransition() {
        // The regression this guards: the selection used to live in a field of the
        // service. The service stops itself when the queue drains, so a Retry ran
        // in a fresh instance with that field empty and the import silently widened
        // to the whole channel — tens of GB nobody asked for, with nothing failing.
        List<String> nodes = Arrays.asList(
                "23a7dc9c73635cd2abbd3e8aab13c3ca", "a3eac3983b085e1594eeb18d1f173260");
        KolibriSeedState s = KolibriSeedState.of(Collections.singletonList(
                KolibriSeedState.Item.pending(KHAN, "Khan Academy", 60L * GB, nodes)));

        s = s.startingItem(0).progress(0, 40, 1024L).finishItem(0, false).stopped().retry(0);

        KolibriSeedState.Item after = s.items().get(0);
        assertEquals(KolibriSeedState.Status.PENDING, after.status());
        assertEquals(nodes, after.nodeIds());
        assertFalse(after.isWholeChannel());
    }

    @Test
    public void noSelectionMeansTheWholeChannel() {
        assertTrue(KolibriSeedState.Item.pending(KHAN, "x", 1L).isWholeChannel());
        assertTrue(KolibriSeedState.Item.pending(KHAN, "x", 1L, null).isWholeChannel());
        assertTrue(KolibriSeedState.Item.pending(KHAN, "x", 1L,
                Collections.<String>emptyList()).isWholeChannel());
    }

    @Test
    public void theSelectionIsCopiedAndCannotBeMutatedLater() {
        List<String> caller = new ArrayList<>();
        caller.add("23a7dc9c73635cd2abbd3e8aab13c3ca");
        KolibriSeedState.Item i = KolibriSeedState.Item.pending(KHAN, "x", 1L, caller);
        caller.add("a3eac3983b085e1594eeb18d1f173260");
        assertEquals(1, i.nodeIds().size());
        try {
            i.nodeIds().add("f9d3e0e46ea25789bbed672ff6a399ed");
            fail("expected the node id list to be immutable");
        } catch (UnsupportedOperationException expected) {
            // as designed
        }
    }

    @Test
    public void negativeBytesAndNullLabelsAreNormalised() {
        KolibriSeedState.Item i = KolibriSeedState.Item.pending(KHAN, null, -5L);
        assertEquals("", i.label());
        assertEquals(0L, i.bytes());
    }
}
