package org.rsna.ctp;

import org.apache.log4j.Appender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringWriter;

import static org.junit.Assert.assertFalse;

/**
 * Integration-style log test verifying that sanitized configuration text
 * never emits raw password values in log output.
 */
public class ConfigLogPasswordIntegrationTest {

    @Test
    public void startupLogContainsNoPlaintextPassword() throws Exception {
        String sentinel = "log-test-sentinel-xyz";

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root = doc.createElement("Configuration");
        doc.appendChild(root);
        Element stage = doc.createElement("StorageService");
        stage.setAttribute("password", sentinel);
        stage.setAttribute("name", "Test");
        root.appendChild(stage);

        Logger logger = Logger.getLogger(Configuration.class);
        StringWriter writer = new StringWriter();
        Appender appender = new WriterAppender(new PatternLayout("%m%n"), writer);
        logger.addAppender(appender);
        try {
            logger.info("Configuration:\n" + Configuration.sanitizeForLogging(root));
        }
        finally {
            try { logger.removeAppender(appender); }
            catch (Exception ignore) { }
            appender.close();
        }

        String logged = writer.toString();
        assertFalse("log output must not leak plaintext password", logged.contains(sentinel));
    }
}
