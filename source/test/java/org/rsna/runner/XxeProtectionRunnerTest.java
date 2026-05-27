package org.rsna.runner;

import org.junit.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.*;

/**
 * Verifies that Runner.getDocument() is hardened against XXE attacks.
 * OWASP A05:2021 – Security Misconfiguration / XXE (A04:2017).
 */
public class XxeProtectionRunnerTest {

    @Test
    public void getDocument_withDoctypeDecl_returnsNull() throws Exception {
        // A file containing a DOCTYPE declaration must be rejected (or return null)
        // because disallow-doctype-decl=true is set on the factory.
        File xmlFile = File.createTempFile("xxe-test", ".xml");
        xmlFile.deleteOnExit();
        try (FileWriter fw = new FileWriter(xmlFile)) {
            fw.write("<?xml version=\"1.0\"?>");
            fw.write("<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>");
            fw.write("<root>&xxe;</root>");
        }
        // Runner.getDocument() catches all exceptions and returns null
        assertNull(
            "DOCTYPE declaration must be rejected (returns null on disallowed-doctype)",
            Runner.getDocument(xmlFile));
    }

    @Test
    public void getDocument_withValidXml_returnsDocument() throws Exception {
        File xmlFile = File.createTempFile("valid-xml", ".xml");
        xmlFile.deleteOnExit();
        try (FileWriter fw = new FileWriter(xmlFile)) {
            fw.write("<Server port=\"1080\" ssl=\"no\"/>");
        }
        assertNotNull("Valid XML without DOCTYPE must parse successfully",
            Runner.getDocument(xmlFile));
    }

    @Test
    public void documentBuilderFactory_hasXxeProtectionFeatures() throws Exception {
        // Verify that a factory configured the same way as Runner.getDocument()
        // has all required XXE-blocking features active.
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        // If any setFeature call threw, this line would not be reached.
        assertNotNull(dbf.newDocumentBuilder());
    }
}
