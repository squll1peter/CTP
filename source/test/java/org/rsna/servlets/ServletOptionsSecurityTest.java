package org.rsna.servlets;

import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;

import java.io.File;

import static org.mockito.Mockito.*;

public class ServletOptionsSecurityTest {

    @Test
    public void defaultOptionsDoesNotReflectCorsHeaders() throws Exception {
        Servlet servlet = new Servlet(new File("."), "");
        HttpRequest req = Mockito.mock(HttpRequest.class);
        HttpResponse res = Mockito.mock(HttpResponse.class);

        when(req.getHeader("Origin")).thenReturn("https://evil.example");
        when(req.getHeader("Access-Control-Request-Method")).thenReturn("DELETE");
        when(req.getHeader("Access-Control-Request-Headers")).thenReturn("X-Evil");

        servlet.doOptions(req, res);

        verify(res).setResponseCode(HttpResponse.ok);
        verify(res).setHeader("Allow", "GET, POST, PUT, DELETE, OPTIONS");
        verify(res, never()).setHeader(eq("Access-Control-Allow-Origin"), anyString());
        verify(res, never()).setHeader(eq("Access-Control-Allow-Methods"), anyString());
        verify(res, never()).setHeader(eq("Access-Control-Allow-Headers"), anyString());
        verify(res).send();
    }
}
