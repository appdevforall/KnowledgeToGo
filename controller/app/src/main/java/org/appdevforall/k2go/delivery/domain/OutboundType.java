package org.appdevforall.k2go.delivery.domain;

/** What a queued item carries, so one Worker endpoint can route by type. */
public enum OutboundType {
    FEEDBACK("feedback"),
    ANALYTICS("analytics");

    private final String wire;

    OutboundType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static OutboundType fromWire(String w) {
        for (OutboundType t : values()) {
            if (t.wire.equals(w)) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown outbound type: " + w);
    }
}
