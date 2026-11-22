/*
 * ============================================================================
 * Name        : PasswordRule.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-552. The live checks under "Change password": the length (the box sends the
 *               minimum per service in the status), a letter and a digit, and the same two fields.
 *               The box checks the length again; this only shows the user what is missing.
 * ============================================================================
 */
package org.appdevforall.k2go.auth.domain;

public final class PasswordRule {
    private PasswordRule() {}

    /** Characters, not UTF-16 units (an emoji is one). A minimum of 0 or less is "not known": it passes,
     *  and the box decides. */
    public static boolean longEnough(String pw, int minLength) {
        return minLength <= 0 || (pw != null && pw.codePointCount(0, pw.length()) >= minLength);
    }

    /** At least one letter and one digit, in any script. */
    public static boolean hasLetterAndDigit(String pw) {
        if (pw == null) return false;
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < pw.length(); ) {
            int c = pw.codePointAt(i);
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
            i += Character.charCount(c);
        }
        return letter && digit;
    }

    /** Both fields typed and the same. */
    public static boolean same(String pw, String again) {
        return pw != null && !pw.isEmpty() && pw.equals(again);
    }
}
