/*
 * ============================================================================
 * Name        : Aria2Exit.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4895. Pure rule: what an aria2 exit code means.
 *               No Android, no I/O.
 * ============================================================================
 */
package org.appdevforall.k2go.download.domain;

/**
 * What aria2 was telling us when it stopped.
 *
 * <p>Until now every non-zero exit produced the same message — "Aria2c native process failed with
 * code N" — and the caller failed the whole install on all of them. That throws away the one thing
 * aria2 is careful about: it distinguishes a network hiccup from a missing file from a full disk,
 * and only one of those is worth trying again. Retry, resume and any honest report to the user all
 * need this distinction first, which is why it lands before them.
 *
 * <p><b>Describes, does not decide.</b> This class says what happened; it does not say how many
 * times to retry or how long to wait. That policy belongs with the caller that owns the download,
 * and keeping it out of here is what lets the rootfs path and the content paths make different
 * choices from the same reading.
 *
 * <p><b>Unknown is its own answer</b>, not a synonym for fatal. aria2's own code 1 is literally
 * "unknown error" and in practice it covers transient conditions as well as real ones; folding it
 * into either bucket would be inventing information we do not have. The caller decides what
 * caution means for it.
 */
public final class Aria2Exit {

    /** What kind of stop this was. */
    public enum Kind {
        /** Everything downloaded. */
        SUCCESS,
        /** A condition that commonly clears by itself: timeouts, resolution, network drops. */
        TRANSIENT,
        /** aria2 gave up because the transfer was too slow ({@code --lowest-speed-limit}). */
        STALLED,
        /** Trying again with the same inputs cannot help: not found, no disk space, bad auth. */
        PERMANENT,
        /** aria2 did not say, or said something this table does not cover. */
        UNKNOWN
    }

    private Aria2Exit() {
    }

    /**
     * Classify an aria2 process exit code.
     *
     * <p>The table below is aria2's documented set. Codes outside it are {@link Kind#UNKNOWN}
     * rather than assumed, so a future aria2 that adds one does not get silently mislabelled.
     */
    public static Kind kindOf(int exitCode) {
        switch (exitCode) {
            case 0:  return Kind.SUCCESS;

            // Worth another attempt: the far side or the link misbehaved.
            case 2:  // timeout
            case 6:  // network problem
            case 7:  // unfinished downloads remained
            case 19: // name resolution failed
            case 22: // bad HTTP response header
            case 29: // server temporarily unavailable
                return Kind.TRANSIENT;

            // aria2 aborted the transfer itself for being below the floor we set.
            case 5:
                return Kind.STALLED;

            // Same inputs, same outcome. Retrying only wastes the user's data.
            case 3:  // resource not found
            case 4:  // resource not found too many times
            case 8:  // remote server does not support resume
            case 9:  // not enough disk space
            case 16: // could not open or create the file
            case 24: // HTTP authorization failed
                return Kind.PERMANENT;

            case 1:  // aria2's own "unknown error"
            default:
                return Kind.UNKNOWN;
        }
    }

    /**
     * A short, stable English phrase for logs and for the failure text we keep in the install
     * state. Not a user-facing string: those are localized resources chosen by the presentation
     * layer, which can map {@link Kind} however it needs to.
     */
    public static String label(int exitCode) {
        switch (exitCode) {
            case 0:  return "ok";
            case 1:  return "unspecified aria2 error";
            case 2:  return "connection timed out";
            case 3:  return "file not found on the server";
            case 4:  return "file not found on any mirror";
            case 5:  return "transfer too slow, aborted";
            case 6:  return "network problem";
            case 7:  return "download left unfinished";
            case 8:  return "server does not support resuming";
            case 9:  return "not enough disk space";
            case 16: return "could not write the file";
            case 19: return "could not resolve the host";
            case 22: return "bad response from the server";
            case 24: return "server rejected the request";
            case 29: return "server temporarily unavailable";
            default: return "aria2 exit code " + exitCode;
        }
    }

}
