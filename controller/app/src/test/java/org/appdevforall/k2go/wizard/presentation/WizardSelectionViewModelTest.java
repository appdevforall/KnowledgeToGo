package org.appdevforall.k2go.wizard.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;

/**
 * Unit tests for {@link WizardSelectionViewModel}. Pure JVM — the class holds no
 * {@code Context}, which is the point of the factory that builds it.
 */
public class WizardSelectionViewModelTest {

    // A null handle means "no saved state": the flattening that goes into one is tested
    // in SelectionSnapshotTest, which needs no Bundle and so runs on a plain JVM too.
    private static WizardSelectionViewModel following() {
        return new WizardSelectionViewModel("en", "en", null);
    }

    private static WizardSelectionViewModel chosen() {
        return new WizardSelectionViewModel("es", "en", null);
    }

    // ---- the carts ----------------------------------------------------------

    @Test
    public void theCartsAreHandedOutLiveAndKeepWhatIsPutInThem() {
        // The catalog screens mutate these in place across several category screens.
        // If a later change returns copies instead, accumulation stops working with no
        // compile error and no symptom until a user loses their selection — so the
        // identity is pinned here on purpose.
        WizardSelectionViewModel vm = following();
        LinkedHashMap<String, Long> cart = vm.zimCart();
        cart.put("wikipedia|en|maxi", 4_000_000L);

        assertSame(cart, vm.zimCart());
        assertEquals(1, vm.zimCart().size());
        assertEquals(Long.valueOf(4_000_000L), vm.zimCart().get("wikipedia|en|maxi"));
    }

    @Test
    public void theTwoCartsAreIndependent() {
        WizardSelectionViewModel vm = following();
        vm.zimCart().put("wikipedia|en|maxi", 1L);
        vm.booksCart().put("1342", new String[]{"Pride and Prejudice", "Austen", "http://x"});

        assertEquals(1, vm.zimCart().size());
        assertEquals(1, vm.booksCart().size());
    }

    @Test
    public void aCartStartsEmpty() {
        assertTrue(following().zimCart().isEmpty());
        assertTrue(following().booksCart().isEmpty());
    }

    // ---- the content language ------------------------------------------------

    @Test
    public void followingThePhoneIsNotAManualChoice() {
        WizardSelectionViewModel vm = following();
        assertEquals("en", vm.contentLang());
        assertFalse(vm.isContentLangManual());
    }

    @Test
    public void startingOnSomethingElseAlreadyCountsAsChosen() {
        // The stored preference can differ from the phone before the user touches
        // anything in this screen; the wizard still has to say "chosen", not "following".
        assertTrue(chosen().isContentLangManual());
        assertEquals("es", chosen().contentLang());
    }

    @Test
    public void manualIsDerivedSoItCannotDriftFromTheValue() {
        WizardSelectionViewModel vm = following();
        vm.setContentLang("fr");
        assertTrue(vm.isContentLangManual());

        // Picking the phone's language by hand is still "following it" — there is no
        // separate flag that could disagree with what the value says.
        vm.setContentLang("en");
        assertFalse(vm.isContentLangManual());
    }

    @Test
    public void followSystemResetsBoth() {
        WizardSelectionViewModel vm = chosen();
        vm.followSystemLang();
        assertEquals("en", vm.contentLang());
        assertFalse(vm.isContentLangManual());
    }

    @Test
    public void nullIsTreatedAsFollowingRatherThanAsAChoice() {
        // Fails to the safer reading: an absent value must not present itself as a
        // deliberate selection under the language selector.
        WizardSelectionViewModel vm = chosen();
        vm.setContentLang(null);
        assertEquals("en", vm.contentLang());
        assertFalse(vm.isContentLangManual());

        WizardSelectionViewModel unknown = new WizardSelectionViewModel(null, null, null);
        assertEquals("", unknown.contentLang());
        assertFalse(unknown.isContentLangManual());
    }
}
