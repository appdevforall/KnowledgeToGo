package org.appdevforall.k2go.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * K2GO-463. The return codes open the same Activities and differ only by extras, so they must all
 * differ (the K2GO-461 bug: all of them were code 0). Service-action codes target their own service
 * component, so an equal value does not collide: they are deliberately not checked here.
 */
public class NotificationRequestCodesTest {

    @Test
    public void everyReturnRequestCodeIsUnique() {
        Map<Integer, String> seen = new LinkedHashMap<>();
        int count = 0;
        for (Field f : NotificationRequestCodes.Return.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
            int value;
            try {
                value = f.getInt(null);
            } catch (IllegalAccessException e) {
                throw new AssertionError("cannot read " + f.getName(), e);
            }
            count++;
            String prior = seen.put(value, f.getName());
            if (prior != null) {
                fail("return request code " + value + " is used by both " + prior + " and " + f.getName()
                        + " : return codes open the same component and must be unique");
            }
        }
        assertEquals("expected every Return request code to be distinct", count, seen.size());
    }
}
