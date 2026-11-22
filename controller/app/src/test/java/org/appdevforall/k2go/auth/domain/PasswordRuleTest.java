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
        assertTrue(PasswordRule.longEnough("Ñandús", 6));
        assertTrue(PasswordRule.longEnough("🔑abcde", 6));   // a key emoji is one character
        assertFalse(PasswordRule.longEnough("🔑abcd", 6));
    }

    @Test public void unknownMinimumPasses() {
        assertTrue(PasswordRule.longEnough("a", 0));
    }

    @Test public void bothFieldsMustBeTypedAndTheSame() {
        assertTrue(PasswordRule.same("perros", "perros"));
        assertFalse(PasswordRule.same("perros", "perro"));
        assertFalse(PasswordRule.same("", ""));
    }
}
