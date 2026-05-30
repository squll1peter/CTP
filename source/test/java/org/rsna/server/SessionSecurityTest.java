package org.rsna.server;

import org.junit.Test;

import static org.junit.Assert.*;

public class SessionSecurityTest {

    @Test
    public void sessionIdIsUrlSafeAndStrongLength() throws Exception {
        Session session = new Session(new User("user", "hash"), "127.0.0.1");

        assertNotNull(session.id);
        assertTrue(session.id.matches("^[A-Za-z0-9_-]+$"));
        assertTrue(session.id.length() >= 32);
    }
}
