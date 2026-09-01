/*
 * ============================================================================
 * Name        : AdbShellCommand.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Domain rule: is a string safe to run as a single ADB "shell:"
 *               command, i.e. it cannot chain/substitute extra commands.
 *               Closes tech-debt item S4 (arbitrary on-device shell).
 * ============================================================================
 */
package org.appdevforall.k2go.adb.domain;

/**
 * Pure (framework-free) guard for strings passed to
 * {@code IIABAdbManager.executeCommand}, which runs them as
 * {@code openStream("shell:" + command)} — an on-device shell over ADB.
 *
 * <p>Today the callers pass fixed, app-controlled commands, but the method
 * accepts any string; a value containing shell metacharacters ({@code ; | & $}
 * {@code ( ) < >}, backticks, quotes, newlines) could chain or substitute extra
 * commands run with ADB privileges — tech-debt item <b>S4</b>.
 *
 * <p>This rejects those metacharacters so only a single, self-contained command
 * (e.g. {@code settings put global <key> <value>}) can run. It does not try to
 * be a full shell parser; it fails closed on anything that could break out.
 * No {@code android.*}, so it is unit-testable on a plain JVM.
 */
public final class AdbShellCommand {

    private AdbShellCommand() {
        // Static utility; not instantiable.
    }

    /**
     * True if {@code command} is safe to run as a single ADB shell command:
     * non-empty and free of shell control/substitution/redirection/quote
     * characters and control characters. Normal tokens (letters, digits, spaces,
     * and {@code / . - _ = : ,}) are allowed.
     */
    public static boolean isSafe(String command) {
        if (command == null || command.trim().isEmpty()) {
            return false;
        }
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            switch (c) {
                case ';':   // command separator
                case '&':   // background / &&
                case '|':   // pipe / ||
                case '$':   // variable / $( ) substitution
                case '`':   // backtick substitution
                case '(':
                case ')':
                case '<':   // redirection
                case '>':
                case '\'':  // quoting
                case '"':
                case '\\':  // escaping
                case '\n':
                case '\r':
                case '\0':
                    return false;
                default:
                    if (c < 0x20) { // other control characters
                        return false;
                    }
            }
        }
        return true;
    }
}
