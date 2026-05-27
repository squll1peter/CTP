package org.rsna.installer;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Verifies the password-generation and SSL-defaults helpers extracted from
 * Installer to eliminate the hardcoded "edge1234" credential.
 */
public class InstallerPasswordTest {

    @Test
    public void generateInstallPassword_hasCorrectLength() {
        String pw = Installer.generateInstallPassword();
        assertEquals("Password must be exactly 20 characters", 20, pw.length());
    }

    @Test
    public void generateInstallPassword_containsOnlyAlphanumericChars() {
        String pw = Installer.generateInstallPassword();
        assertTrue("Password must be alphanumeric", pw.matches("[A-Za-z0-9]+"));
    }

    @Test
    public void generateInstallPassword_isNotHardcodedValue() {
        String pw = Installer.generateInstallPassword();
        assertNotEquals("Password must not be the old hardcoded value", "edge1234", pw);
    }

    @Test
    public void generateInstallPassword_producesDifferentValuesEachCall() {
        Set<String> passwords = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            passwords.add(Installer.generateInstallPassword());
        }
        assertTrue("10 calls should produce at least 9 distinct passwords (collision probability is negligible)",
            passwords.size() >= 9);
    }

    @Test
    public void applySslDefaults_setsAllRequiredAttributes() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element ssl = doc.createElement("SSL");

        Installer.applySslDefaults(ssl, "testPassword123");

        assertEquals("keystore.jks", ssl.getAttribute("keystore"));
        assertEquals("testPassword123", ssl.getAttribute("keystorePassword"));
        assertEquals("truststore.jks", ssl.getAttribute("truststore"));
        assertEquals("testPassword123", ssl.getAttribute("truststorePassword"));
    }
}
