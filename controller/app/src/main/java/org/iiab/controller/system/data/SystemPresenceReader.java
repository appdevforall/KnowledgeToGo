/*
 * ============================================================================
 * Name        : SystemPresenceReader.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5137. Gathers the three facts SystemPresence needs, from
 *               the disk and the two markers. The only place that gathers them.
 * ============================================================================
 */
package org.iiab.controller.system.data;

import android.content.Context;

import org.iiab.controller.InstallGuard;
import org.iiab.controller.SystemStateEvaluator;
import org.iiab.controller.env.EnvironmentLock;
import org.iiab.controller.system.domain.SystemPresence;

/**
 * Asks the device whether it has a system, or one on the way.
 *
 * <p>The rule is in {@link SystemPresence}, where it can be tested without a device. This is the
 * Android half: three reads, in one place. It matters that it is one place — four screens each
 * assembling the same three calls would be the duplication ADFA-5137 exists to remove, arriving by a
 * different door.
 *
 * <p><b>Each fact is read from the thing it describes</b>, which is the property the old flag lacked:
 *
 * <ul>
 *   <li>the rootfs, from the disk — {@code rootfsPresent()} rather than {@code isSystemInstalled()},
 *       because the latter answers false for the whole time an install marker is set, which is
 *       correct for its own callers and useless here: the marker is one of the three things we are
 *       asking about.</li>
 *   <li>the install, from {@code InstallGuard}'s durable marker, which survives the process being
 *       killed mid-install — the case that made this question hard in the first place.</li>
 *   <li>a clone, backup or restore, from the environment lock's owner marker, which self-heals on
 *       the next read rather than needing anyone to clear it.</li>
 * </ul>
 *
 * <p>Touches the filesystem, so it is not free; all three reads are file existence checks, which is
 * the same cost the launch path already pays.
 */
public final class SystemPresenceReader {

    private SystemPresenceReader() {
    }

    /** False only when the device has nothing and nothing is on its way. */
    public static boolean hereOrOnTheWay(Context ctx) {
        if (ctx == null) {
            // No context, no evidence. Answering true keeps a caller from routing a user into setup
            // on the strength of a missing argument; the wizard is a decision, not a default.
            return true;
        }
        return SystemPresence.hereOrOnTheWay(
                SystemStateEvaluator.rootfsPresent(ctx),
                InstallGuard.inProgress(ctx),
                EnvironmentLock.ownerHeld(ctx));
    }
}
