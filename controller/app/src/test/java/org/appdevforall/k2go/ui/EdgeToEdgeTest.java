package org.appdevforall.k2go.ui;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/**
 * Unit tests for {@link EdgeToEdge#resolvePadding} (K2GO-439): the pure rule that adds the selected
 * inset edges to the view's original padding. Padding is [left, top, right, bottom].
 */
public class EdgeToEdgeTest {

    @Test
    public void padAllAddsEveryEdgeToTheOriginal() {
        assertArrayEquals(new int[]{15, 25, 35, 45},
                EdgeToEdge.resolvePadding(5, 5, 5, 5, 10, 20, 30, 40,
                        true, true, true, true));
    }

    @Test
    public void padTopAddsOnlyTheTopEdge() {
        assertArrayEquals(new int[]{5, 25, 5, 5},
                EdgeToEdge.resolvePadding(5, 5, 5, 5, 10, 20, 30, 40,
                        true, false, false, false));
    }

    @Test
    public void padBottomAddsOnlyTheBottomEdge() {
        assertArrayEquals(new int[]{5, 5, 5, 45},
                EdgeToEdge.resolvePadding(5, 5, 5, 5, 10, 20, 30, 40,
                        false, false, false, true));
    }

    @Test
    public void noEdgesKeepsTheOriginalPadding() {
        assertArrayEquals(new int[]{5, 5, 5, 5},
                EdgeToEdge.resolvePadding(5, 5, 5, 5, 10, 20, 30, 40,
                        false, false, false, false));
    }

    @Test
    public void addsToTheOriginalNotToZero() {
        // The original padding is preserved and the inset is added on top (not replaced).
        assertArrayEquals(new int[]{100, 107, 100, 100},
                EdgeToEdge.resolvePadding(100, 100, 100, 100, 1, 7, 3, 9,
                        true, false, false, false));
    }
}
