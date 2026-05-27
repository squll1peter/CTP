package org.rsna.ctp;

import org.junit.Test;
import static org.junit.Assert.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.rsna.ctp.Configuration;

public class ConfigLogSanitizationTest {

    private Element buildConfig(String... attrPairs) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().newDocument();
        Element root = doc.createElement("Configuration");
        doc.appendChild(root);
        Element stage = doc.createElement("StorageService");
        for (int i = 0; i < attrPairs.length; i += 2) {
            stage.setAttribute(attrPairs[i], attrPairs[i + 1]);
        }
        root.appendChild(stage);
        return root;
    }

    @Test
    public void testPasswordAttributeIsRedacted() throws Exception {
        Element root = buildConfig("password", "supersecret");
        String output = Configuration.sanitizeForLogging(root);
        assertFalse("password value must not appear in log output",
                output.contains("supersecret"));
        assertTrue("redacted marker must appear", output.contains("[redacted]"));
    }

    @Test
    public void testKeystorePasswordAttributeIsRedacted() throws Exception {
        Element root = buildConfig("keystorePassword", "ks-secret-value");
        String output = Configuration.sanitizeForLogging(root);
        assertFalse("keystorePassword must not appear", output.contains("ks-secret-value"));
        assertTrue(output.contains("[redacted]"));
    }

    @Test
    public void testTruststorePasswordAttributeIsRedacted() throws Exception {
        Element root = buildConfig("truststorePassword", "ts-secret-value");
        String output = Configuration.sanitizeForLogging(root);
        assertFalse("truststorePassword must not appear", output.contains("ts-secret-value"));
        assertTrue(output.contains("[redacted]"));
    }

    @Test
    public void testNonSensitiveAttributesArePreserved() throws Exception {
        Element root = buildConfig("url", "http://example.com", "port", "8080", "name", "MyStage");
        String output = Configuration.sanitizeForLogging(root);
        assertTrue("url must be preserved", output.contains("http://example.com"));
        assertTrue("port must be preserved", output.contains("8080"));
        assertTrue("name must be preserved", output.contains("MyStage"));
    }

    @Test
    public void testUsernameIsNotRedacted() throws Exception {
        Element root = buildConfig("username", "admin-user", "password", "p@ssw0rd");
        String output = Configuration.sanitizeForLogging(root);
        assertTrue("username must not be redacted", output.contains("admin-user"));
        assertFalse("password must be redacted", output.contains("p@ssw0rd"));
    }
}
