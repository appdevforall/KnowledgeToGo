/*
 * ============================================================================
 * Name        : SystemPresence.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5137. The rule that replaces setup_complete: does this
 *               device have a system, or is one on its way? Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.system.domain;

/**
 * Whether this device has a system, or one is being put there right now.
 *
 * <p><b>What this replaces, and why.</b> {@code setup_complete} was a claim about the past — "setup
 * happened" — used to answer a question about the present: "should this person see the wizard?".
 * Four sites wrote it, none cleared it, and it was written on intent rather than on a finished
 * install, so it could say yes while the device had nothing. That combination is findings 3 and 5 of
 * {@code state-spine.svg}: an app that routes past the wizard forever, onto a screen whose only way
 * forward has no button.
 *
 * <p>The fix is not to give that flag a lifecycle but to stop storing it. The answer is already on
 * the device, in three facts that each die with the thing they describe — the rootfs with the rootfs,
 * the install marker with the install, the lock with the operation. A fact that can be derived should
 * not be kept, because a kept copy has to be maintained in agreement with the original forever, and
 * that agreement is exactly what broke.
 *
 * <p><b>Why a disjunction and not just "is there a rootfs".</b> Two of the three are about work in
 * flight rather than about a system that exists. Without them, a user who is halfway through a first
 * install — or halfway through receiving a clone, which has no rootfs yet by definition — would be
 * sent back to the wizard on every relaunch, which is the same dead end in the other direction. The
 * question is not "is it finished" but "is anything there or coming".
 *
 * <p>Pure: booleans in, boolean out, no Android. {@code SystemFactsReader.hereOrOnTheWay} gathers the
 * three facts — the one reader for what is true about the box, so this question does not stand up a
 * second one beside it.
 */
public final class SystemPresence {

    private SystemPresence() {
    }

    /**
     * @param rootfsOnDisk      a rootfs exists — asked of the disk, not of a flag
     * @param installInProgress the durable install marker is set
     * @param deepOpInFlight    a clone, backup or restore owns the environment lock
     * @return false only when the device has nothing and nothing is on its way, which is the one
     *         case where the wizard is the right place to be
     */
    public static boolean hereOrOnTheWay(boolean rootfsOnDisk, boolean installInProgress,
                                         boolean deepOpInFlight) {
        return rootfsOnDisk || installInProgress || deepOpInFlight;
    }
}
