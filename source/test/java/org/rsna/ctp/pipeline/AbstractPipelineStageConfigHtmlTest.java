package org.rsna.ctp.pipeline;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.server.User;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Characterisation tests for AbstractPipelineStage.getConfigHTML().
 * Verifies password suppression for non-admin users and inclusion for admins.
 */
public class AbstractPipelineStageConfigHtmlTest {

    private AbstractPipelineStage stage;
    private User adminUser;
    private User guestUser;

    @Before
    public void setUp() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();

        // AbstractPipelineStage constructor calls element.getParentNode() and
        // casts it to Element to read the pipeline "root" attribute — the stage
        // element must therefore be a child of a parent element in the document.
        Element pipeline = doc.createElement("Pipeline");
        pipeline.setAttribute("root", "");
        doc.appendChild(pipeline);

        Element el = doc.createElement("Stage");
        el.setAttribute("name", "TestStage");
        el.setAttribute("username", "johndoe");
        el.setAttribute("password", "s3cret");
        el.setAttribute("port", "8080");
        pipeline.appendChild(el);

        // anonymous concrete subclass
        stage = new AbstractPipelineStage(el) {
            public void start() {}
            public void stop() {}
            public String getStatusHTML() { return ""; }
        };

        adminUser = Mockito.mock(User.class);
        when(adminUser.hasRole("admin")).thenReturn(true);

        guestUser = Mockito.mock(User.class);
        when(guestUser.hasRole("admin")).thenReturn(false);
    }

    @Test
    public void adminUser_seesPassword() {
        String html = stage.getConfigHTML(adminUser);
        assertTrue("Admin should see the actual password", html.contains("s3cret"));
    }

    @Test
    public void nonAdminUser_passwordIsSuppressed() {
        String html = stage.getConfigHTML(guestUser);
        assertFalse("Non-admin must not see the actual password", html.contains("s3cret"));
        assertTrue("Non-admin should see '[suppressed]'", html.contains("[suppressed]"));
    }

    @Test
    public void nonAdminUser_nonSensitiveAttributeIsVisible() {
        String html = stage.getConfigHTML(guestUser);
        assertTrue("Non-admin should see non-sensitive attributes", html.contains("8080"));
    }

    @Test
    public void nullUser_passwordIsSuppressed() {
        String html = stage.getConfigHTML(null);
        assertFalse("Null user must not see the actual password", html.contains("s3cret"));
    }
}
