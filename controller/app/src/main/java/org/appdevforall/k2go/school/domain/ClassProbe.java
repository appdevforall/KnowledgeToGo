/*
 * ============================================================================
 * Name        : ClassProbe.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2Go School: port to read the open class of a box (its code).
 * ============================================================================
 */
package org.appdevforall.k2go.school.domain;

/** The box is the one owner of "a class is open" and of its code. The app asks; it keeps no copy. */
public interface ClassProbe {

    /**
     * BLOCKS: one GET of the class state on the box at {@code host} (an IPv4 address, or
     * "localhost" for this phone's box). Returns the box answer, or null when no box answers there
     * (no connection, a time-out, or an answer that is not a class state).
     */
    Answer ask(String host);

    /** BLOCKS: the code of the open class at {@code host}, or null (no class open, or no answer). */
    default String codeAt(String host) {
        Answer a = ask(host);
        return a == null ? null : a.code;
    }

    /** What a box answered: the code of its open class, or null when the box has no class open. */
    final class Answer {
        public final String code;

        public Answer(String code) { this.code = code; }
    }
}
