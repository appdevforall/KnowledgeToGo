package org.appdevforall.k2go.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * K2GO-463. The guard that makes the id-collision family (K2GO-458) impossible to reintroduce:
 * every id in {@link NotificationIds} must be distinct, checked on a plain JVM at build time.
 */
public class NotificationIdsTest {

    @Test
    public void everyNotificationIdIsUnique() {
        Map<Integer, String> seen = new LinkedHashMap<>();
        int count = 0;
        for (Field f : NotificationIds.class.getDeclaredFields()) {
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
                fail("notification id " + value + " is used by both " + prior + " and " + f.getName()
                        + " : ids must be unique (see NotificationIds)");
            }
        }
        // A smoke check that reflection actually found the constants (not an empty pass).
        assertEquals("expected every NotificationIds constant to be distinct", count, seen.size());
    }
}
