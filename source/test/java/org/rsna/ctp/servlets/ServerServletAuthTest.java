package org.rsna.ctp.servlets;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;

import java.io.File;

import static org.mockito.Mockito.*;

public class ServerServletAuthTest {

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
    public void unauthenticated_setsForbiddenResponseCode() {
        when(req.userHasRole("admin")).thenReturn(false);
        servlet.doGet(req, res);
        verify(res).setResponseCode(HttpResponse.forbidden);
    }

    @Test
    public void unauthenticated_doesNotWriteBody() {
        when(req.userHasRole("admin")).thenReturn(false);
        servlet.doGet(req, res);
        verify(res, never()).write(anyString());
    }

    @Test
    public void authenticated_doesNotSetForbiddenCode() {
        when(req.userHasRole("admin")).thenReturn(true);
        when(req.hasParameter("type")).thenReturn(false);
        servlet.doGet(req, res);
        verify(res, never()).setResponseCode(HttpResponse.forbidden);
    }
}
