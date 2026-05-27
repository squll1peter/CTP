package org.rsna.ctp.servlets;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration-style test exercising ServerServlet HTTP flow with mocked
 * request/response objects and real servlet logic.
 */
public class ServerServletHttpIntegrationTest {

    private ServerServlet servlet;
    private HttpRequest req;
    private HttpResponse res;

    @Before
    public void setUp() {
        servlet = new ServerServlet(new File("."), "/server");
        req = Mockito.mock(HttpRequest.class);
        res = Mockito.mock(HttpResponse.class);
    }

    @Test
    public void unauthenticatedGetServerReturns403WithoutBody() {
        when(req.userHasRole("admin")).thenReturn(false);

        servlet.doGet(req, res);

        verify(res).setResponseCode(HttpResponse.forbidden);
        verify(res, never()).write(anyString());
    }

    @Test
    public void authenticatedTypeProbeDoesNotLeakPasswords() {
        when(req.userHasRole("admin")).thenReturn(true);
        when(req.hasParameter("type")).thenReturn(true);
        when(req.getParameter("type")).thenReturn("java.lang.String");

        final java.util.concurrent.atomic.AtomicReference<String> body =
                new java.util.concurrent.atomic.AtomicReference<String>("");
        doAnswer(invocation -> {
            body.set(invocation.getArgument(0));
            return null;
        }).when(res).write(anyString());

        servlet.doGet(req, res);

        verify(res, never()).setResponseCode(HttpResponse.forbidden);
        String output = body.get();
        assertTrue("type probe should return a minimal boolean XML payload",
                "<true/>".equals(output) || "<false/>".equals(output));
        assertFalse("response must not contain obvious password attribute", output.contains("password=\""));
    }
}
