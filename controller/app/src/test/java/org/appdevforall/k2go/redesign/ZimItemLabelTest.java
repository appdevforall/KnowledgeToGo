package org.appdevforall.k2go.redesign;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ZimItemLabel} — the naming rules that came out of
 * {@code ZimPreparingFragment} when ADFA-5074 moved the session start into the door.
 *
 * <p>Worth pinning because the labels are now computed once, handed to
 * {@code ZimDownloadService} and displayed much later by a different screen: a wrong label is
 * not visible at the moment it is made, only in a checklist the user reads afterwards.
 */
public class ZimItemLabelTest {

    @Test
    public void aKnownProjectUsesItsDisplayTitleRatherThanItsKey() {
        // "stack_exchange" is a mirror directory name; the user should never see it.
        String label = ZimItemLabel.of("stack_exchange", "Ask Ubuntu", "nopic");
        assertTrue(label, label.startsWith(KiwixCategories.byKey("stack_exchange").title));
        assertTrue(label, label.contains("Ask Ubuntu"));
    }

    @Test
    public void anUnknownProjectFallsBackToItsKeyRatherThanToNothing() {
        assertEquals("mystery · Someone", ZimItemLabel.of("mystery", "Someone", "all"));
    }

    // ---- the redundancy rules ------------------------------------------------

    @Test
    public void aCreatorThatRepeatsTheProjectIsDroppedInFavourOfTheFlavour() {
        // "Wikipedia · wikipedia · maxi" says the same thing twice.
        assertEquals("Wikipedia · maxi", ZimItemLabel.of("wikipedia", "wikipedia", "maxi"));
        assertEquals("Wikipedia · maxi", ZimItemLabel.of("wikipedia", "Wikipedia", "maxi"));
    }

    @Test
    public void aCreatorThatMerelyStartsWithTheProjectCountsAsRepeatingIt() {
        // The catalogue writes "wikipedia_en" and similar; that is still the project speaking.
        assertEquals("Wikipedia · nopic", ZimItemLabel.of("wikipedia", "wikipedia_en", "nopic"));
    }

    @Test
    public void aRealCreatorIsKeptAlongsideTheFlavour() {
        String label = ZimItemLabel.of("wikipedia", "Kiwix", "nopic");
        assertTrue(label, label.contains("Kiwix"));
        assertTrue(label, label.contains("nopic"));
    }

    @Test
    public void bothRedundantLeavesAllRatherThanAnEmptyTail() {
        // Dropping the creator AND the flavour would end the label on a separator, which reads
        // like a bug. This is the case the "All" literal exists for.
        assertEquals("Wikipedia · All", ZimItemLabel.of("wikipedia", "wikipedia", "all"));
    }

    @Test
    public void anAllFlavourWithARealCreatorNamesTheCreator() {
        assertEquals("Wikipedia · Kiwix", ZimItemLabel.of("wikipedia", "Kiwix", "all"));
    }

    // ---- machine tokens become words -----------------------------------------

    @Test
    public void underscoresAndHyphensInAFlavourAreSpaces() {
        assertEquals("Wikipedia · no pic", ZimItemLabel.of("wikipedia", "wikipedia", "no_pic"));
        assertEquals("Wikipedia · max full", ZimItemLabel.of("wikipedia", "wikipedia", "max-full"));
    }

    // ---- missing input --------------------------------------------------------

    @Test
    public void anAbsentFlavourIsTreatedAsAll() {
        assertEquals(ZimItemLabel.of("wikipedia", "Kiwix", "all"),
                ZimItemLabel.of("wikipedia", "Kiwix", null));
        assertEquals(ZimItemLabel.of("wikipedia", "Kiwix", "all"),
                ZimItemLabel.of("wikipedia", "Kiwix", ""));
    }

    @Test
    public void anAbsentCreatorDoesNotThrow() {
        assertEquals("Wikipedia · All", ZimItemLabel.of("wikipedia", null, "all"));
    }

    @Test
    public void anAbsentProjectDoesNotThrow() {
        // The catalogue should always carry one, but this runs on a user's tap and a crash here
        // would take down the start of a download that is otherwise fine.
        ZimItemLabel.of(null, "Kiwix", "maxi");
        ZimItemLabel.of(null, null, null);
    }
}
