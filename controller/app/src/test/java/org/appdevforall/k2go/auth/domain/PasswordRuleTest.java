package org.appdevforall.k2go.auth.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PasswordRuleTest {

    @Test public void onlyTheLengthCounts() {
        assertTrue(PasswordRule.longEnough("perros", 6));
        assertFalse(PasswordRule.longEnough("perro", 6));
        assertFalse(PasswordRule.longEnough("perross", 8));   // Forgejo asks for 8
    }

    @Test public void countsCharactersNotUnits() {
        assertTrue(PasswordRule.longEnough("\u00d1and\u00fas", 6));
        assertTrue(PasswordRule.longEnough("\ud83d\udd11abcde", 6));   // a key emoji is one character
        assertFalse(PasswordRule.longEnough("\ud83d\udd11abcd", 6));
    }

    @Test public void unknownMinimumPasses() {
        assertTrue(PasswordRule.longEnough("a", 0));
    }

    @Test public void needsALetterAndADigit() {
        assertTrue(PasswordRule.hasLetterAndDigit("perro1"));
        assertFalse(PasswordRule.hasLetterAndDigit("perros"));
        assertFalse(PasswordRule.hasLetterAndDigit("123456"));
        assertFalse(PasswordRule.hasLetterAndDigit("changeme"));   // the factory password
        assertTrue(PasswordRule.hasLetterAndDigit("\u0645\u0664"));     // Arabic letter and Arabic-Indic digit
    }

    @Test public void bothFieldsMustBeTypedAndTheSame() {
        assertTrue(PasswordRule.same("perros", "perros"));
        assertFalse(PasswordRule.same("perros", "perro"));
        assertFalse(PasswordRule.same("", ""));
    }
}
