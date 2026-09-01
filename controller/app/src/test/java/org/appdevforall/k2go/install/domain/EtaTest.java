package org.appdevforall.k2go.install.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for {@link Eta} — the calm, rounded ETA shape for display (ADFA-5228). */
public class EtaTest {

    @Test
    public void negativeIsUnknown() {
        assertEquals(Eta.Kind.UNKNOWN, Eta.of(-1L).kind);
    }

    @Test
    public void underOneMinute() {
        assertEquals(Eta.Kind.UNDER_MINUTE, Eta.of(0L).kind);
        assertEquals(Eta.Kind.UNDER_MINUTE, Eta.of(59L).kind);
    }

    @Test
    public void wholeMinutesRounded() {
        assertEquals(Eta.Kind.MINUTES, Eta.of(60L).kind);
        assertEquals(1, Eta.of(60L).minutes);
        assertEquals(1, Eta.of(75L).minutes);    // round(1.25) = 1
        assertEquals(2, Eta.of(90L).minutes);    // round(1.5)  = 2
        assertEquals(10, Eta.of(600L).minutes);
    }
}
