package org.rsna.util;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Hashtable;
import java.util.List;

import static org.junit.Assert.*;

public class AttackLogSecurityEventTest {

    @Before
    public void resetAttackLogSingleton() throws Exception {
        Field field = AttackLog.class.getDeclaredField("attackLog");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    public void recordEventMaintainsCategoryCounts() {
        AttackLog log = AttackLog.getInstance();

        log.recordEvent(new AttackLog.SecurityEvent(System.currentTimeMillis(), "1.2.3.4", "GET", "/a", "localhost", "malformed request", "warn", "bad", "", "ua"));
        log.recordEvent(new AttackLog.SecurityEvent(System.currentTimeMillis(), "1.2.3.5", "GET", "/b", "localhost", "malformed request", "warn", "bad", "", "ua"));
        log.recordEvent(new AttackLog.SecurityEvent(System.currentTimeMillis(), "1.2.3.4", "GET", "/c", "localhost", "suspicious redirect", "warn", "bad", "", "ua"));

        Hashtable<String,Integer> counts = log.getCategoryCounts();
        assertEquals(Integer.valueOf(2), counts.get("malformed request"));
        assertEquals(Integer.valueOf(1), counts.get("suspicious redirect"));
    }

    @Test
    public void recentEventsIsBoundedToOneThousand() {
        AttackLog log = AttackLog.getInstance();

        for (int i = 0; i < 1200; i++) {
            log.recordEvent(new AttackLog.SecurityEvent(
                    i,
                    "1.2.3." + (i % 10),
                    "GET",
                    "/p" + i,
                    "localhost",
                    "malformed request",
                    "warn",
                    "d" + i,
                    "",
                    "ua"));
        }

        List<AttackLog.SecurityEvent> events = log.getRecentEvents();
        assertEquals(1000, events.size());
        assertEquals("/p1199", events.get(0).path);
        assertEquals("/p200", events.get(events.size() - 1).path);
    }
}
