/*
 * ============================================================================
 * Name        : DirectLinkRepair.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : When does the receiver drop the direct Wi-Fi link (TDLS) to
 *               the sender and try once more (K2GO-587)? Pure JVM.
 *               Only a connect with no answer points to a dead direct link: a
 *               refusal proves that packets reach the sender. The gateway (a
 *               router, or the sender's own hotspot) has no direct link. One
 *               repair per run of failed checks; an answer ends the run.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.domain;

public final class DirectLinkRepair {

    /** The result of one connect to the sender. */
    public enum Outcome { ANSWERS, REFUSED, NO_ANSWER }

    private DirectLinkRepair() {
    }

    /** Drop the direct link and connect once more? */
    public static boolean shouldRepair(Outcome first, boolean hostIsGateway, boolean triedInThisRun) {
        return first == Outcome.NO_ANSWER && !hostIsGateway && !triedInThisRun;
    }

    /** Is a repair already used in this run, after a check that {@code repaired} and got {@code answers}? */
    public static boolean triedAfter(boolean tried, boolean repaired, boolean answers) {
        return !answers && (tried || repaired);
    }
}
