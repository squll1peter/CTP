package org.rsna.ctp.stdstages.email;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verifies that EmailSender sets mail.smtp.starttls.required when tls=true,
 * preventing a downgrade to plain-text SMTP (STARTTLS stripping attack).
 */
public class EmailSenderTlsTest {

    @Before
    public void clearProperties() {
        System.clearProperty("mail.smtp.starttls.enable");
        System.clearProperty("mail.smtp.starttls.required");
    }

    @After
    public void restoreProperties() {
        System.clearProperty("mail.smtp.starttls.enable");
        System.clearProperty("mail.smtp.starttls.required");
    }

    @Test
    public void tlsTrue_setsStarttlsRequired() {
        // Constructor will fail to connect but will still set properties
        try {
            new EmailSender("localhost", "587", null, null, true);
        } catch (Exception ignored) { }

        assertEquals("mail.smtp.starttls.required must be 'true' when tls=true",
            "true", System.getProperty("mail.smtp.starttls.required"));
    }

    @Test
    public void tlsFalse_setsStarttlsRequiredFalse() {
        try {
            new EmailSender("localhost", "25", null, null, false);
        } catch (Exception ignored) { }

        assertEquals("mail.smtp.starttls.required must be 'false' when tls=false",
            "false", System.getProperty("mail.smtp.starttls.required"));
    }
}
