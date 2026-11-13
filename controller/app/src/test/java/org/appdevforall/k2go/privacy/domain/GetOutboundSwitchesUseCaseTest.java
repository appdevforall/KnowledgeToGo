package org.appdevforall.k2go.privacy.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class GetOutboundSwitchesUseCaseTest {

    private static OutboundSwitch sw(String id) { return new OutboundSwitch(id, true, false); }

    @Test
    public void keepsTheFixedOrderWhateverTheBoxOrder() {
        List<OutboundSwitch> out = GetOutboundSwitchesUseCase.known(
                Arrays.asList(sw(OutboundSwitch.FORGEJO), sw(OutboundSwitch.KOLIBRI)));
        assertEquals(2, out.size());
        assertEquals(OutboundSwitch.KOLIBRI, out.get(0).service());
        assertEquals(OutboundSwitch.FORGEJO, out.get(1).service());
    }

    @Test
    public void dropsAServiceThisAppCannotName() {
        List<OutboundSwitch> out = GetOutboundSwitchesUseCase.known(
                Arrays.asList(sw("future-service"), sw(OutboundSwitch.FORGEJO)));
        assertEquals(1, out.size());
        assertEquals(OutboundSwitch.FORGEJO, out.get(0).service());
    }

    @Test
    public void emptyStaysEmpty() {
        assertTrue(GetOutboundSwitchesUseCase.known(Collections.emptyList()).isEmpty());
    }

    @Test(expected = PrivacyException.class)
    public void passesTheRepositoryErrorThrough() throws Exception {
        PrivacyRepository failing = new PrivacyRepository() {
            @Override public List<OutboundSwitch> list() throws PrivacyException {
                throw new PrivacyException(PrivacyException.Reason.UNREACHABLE, "down");
            }
            @Override public OutboundSwitch set(String s, boolean o) { throw new AssertionError(); }
        };
        new GetOutboundSwitchesUseCase(failing).execute();
    }
}
