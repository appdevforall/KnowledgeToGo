package org.appdevforall.k2go.hotspot.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.appdevforall.k2go.hotspot.domain.ApAddress.Candidate;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** K2GO-471: the single AP-IP decision. One rule, one place, one set of rows. */
public class ApAddressTest {

    private static Candidate c(String name, String ip, boolean up, boolean siteLocal, boolean loopback) {
        return new Candidate(name, ip, up, siteLocal, loopback);
    }

    @Test
    public void hint_matches_its_interface_even_when_not_site_local() {
        // Wi-Fi Direct named p2p-wlan0-0; trust that interface's real address (not a guess).
        List<Candidate> cs = Arrays.asList(
                c("wlan0", "10.0.0.5", true, true, false),
                c("p2p-wlan0-0", "192.168.49.1", true, true, false));
        assertEquals("192.168.49.1", ApAddress.pick(cs, "p2p-wlan0-0", "10.0.0.5"));
    }

    @Test
    public void hint_is_trusted_even_with_an_odd_non_site_local_subnet() {
        // The OnePlus/LineageOS lesson: read the real assigned IP, do not assume a subnet.
        List<Candidate> cs = Collections.singletonList(
                c("p2p-wlan0-0", "25.13.4.1", true, false, false));
        assertEquals("25.13.4.1", ApAddress.pick(cs, "p2p-wlan0-0", null));
    }

    @Test
    public void known_hotspot_name_when_no_hint() {
        List<Candidate> cs = Arrays.asList(
                c("wlan0", "10.0.0.5", true, true, false),
                c("ap0", "192.168.43.1", true, true, false));
        assertEquals("192.168.43.1", ApAddress.pick(cs, null, "10.0.0.5"));
    }

    @Test
    public void site_local_fallback_for_an_unknown_interface_name() {
        // OEM named the AP outside the whitelist: pass 2 still finds the site-local, non-station IP.
        List<Candidate> cs = Arrays.asList(
                c("wlan0", "10.0.0.5", true, true, false),
                c("softap-x", "192.168.50.1", true, true, false));
        assertEquals("192.168.50.1", ApAddress.pick(cs, null, "10.0.0.5"));
    }

    @Test
    public void never_returns_the_station_wifi_address() {
        List<Candidate> cs = Collections.singletonList(
                c("wlan0", "192.168.1.22", true, true, false));
        assertNull(ApAddress.pick(cs, null, "192.168.1.22"));
    }

    @Test
    public void skips_down_loopback_and_null_candidates() {
        List<Candidate> cs = Arrays.asList(
                c("ap0", "192.168.43.1", false, true, false),   // down
                c("lo", "127.0.0.1", true, false, true),         // loopback
                c("ap1", null, true, true, false));              // no ipv4
        assertNull(ApAddress.pick(cs, null, null));
    }

    @Test
    public void unresolved_returns_null_so_the_caller_can_terminate() {
        // The Itel case: nothing qualifies -> null -> caller publishes the unresolved terminal
        // (never a 192.168.49.1 guess).
        assertNull(ApAddress.pick(Collections.emptyList(), "p2p-wlan0-0", null));
        assertNull(ApAddress.pick(null, null, null));
    }

    @Test
    public void hint_pass_beats_an_unrelated_site_local_interface() {
        List<Candidate> cs = Arrays.asList(
                c("rmnet0", "172.20.5.9", true, true, false),   // cellular-ish, also site-local
                c("p2p-wlan0-0", "192.168.49.1", true, true, false));
        assertEquals("192.168.49.1", ApAddress.pick(cs, "p2p-wlan0-0", null));
    }

    @Test
    public void hint_is_strict_and_does_not_fall_through_to_other_interfaces() {
        // K2GO-471 review: the hinted group interface has no IPv4 yet; do NOT grab a lingering ap0,
        // return null so Wi-Fi Direct keeps polling its own group.
        List<Candidate> cs = Arrays.asList(
                c("p2p-wlan0-0", null, true, false, false),           // group iface, no address yet
                c("ap0", "192.168.43.1", true, true, false));         // a lingering/unrelated AP iface
        assertNull(ApAddress.pick(cs, "p2p-wlan0-0", null));
    }

    @Test
    public void no_hint_does_not_match_a_p2p_interface_by_name() {
        // K2GO-471 review: a stale non-site-local p2p group must not win a LocalOnlyHotspot (no-hint)
        // resolution; p2p is matched only via an explicit hint.
        List<Candidate> cs = Collections.singletonList(
                c("p2p-wlan0-0", "25.13.4.1", true, false, false));
        assertNull(ApAddress.pick(cs, null, null));
    }
}
