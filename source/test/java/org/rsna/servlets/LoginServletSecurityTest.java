package org.rsna.servlets;

import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class LoginServletSecurityTest {

    @Test
    public void ajaxGetWithCredentialsIsRejected() {
        LoginServlet servlet = new LoginServlet(new File("."), "login");
        HttpRequest req = Mockito.mock(HttpRequest.class);
        HttpResponse res = Mockito.mock(HttpResponse.class);

        when(req.getPath()).thenReturn("/login/ajax");
        when(req.getParameter("username")).thenReturn("user");
        when(req.getParameter("password")).thenReturn("pass");
        when(req.getParameter("logout")).thenReturn(null);
        when(req.isFromAuthenticatedUser()).thenReturn(false);
        when(req.getRemoteAddress()).thenReturn("127.0.0.1");

        servlet.doGet(req, res);

        verify(res).setResponseCode(HttpResponse.forbidden);
        verify(res).send();
    }

    @Test
    public void absoluteRedirectTargetIsRejected() {
        LoginServlet servlet = new LoginServlet(new File("."), "login");
        HttpRequest req = Mockito.mock(HttpRequest.class);
        HttpResponse res = Mockito.mock(HttpResponse.class);

        when(req.getPath()).thenReturn("/login");
        when(req.getParameter("username")).thenReturn(null);
        when(req.getParameter("password")).thenReturn(null);
        when(req.getParameter("logout")).thenReturn(null);
        when(req.hasParameter("skip")).thenReturn(true);
        when(req.isFromAuthenticatedUser()).thenReturn(true);
        when(req.getParameter("url")).thenReturn("https://example.com/attack");

        servlet.doGet(req, res);

        verify(res).redirect("/");
    }

    @Test
    public void throttleStateIsBounded() throws Exception {
        ConcurrentHashMap<String,Object> states = getThrottleStates();
        states.clear();

        LoginServlet servlet = new LoginServlet(new File("."), "login");
        HttpRequest req = Mockito.mock(HttpRequest.class);
        Method registerFailure = LoginServlet.class.getDeclaredMethod("registerFailure", HttpRequest.class, String.class);
        registerFailure.setAccessible(true);

        for (int i = 0; i < 1100; i++) {
            when(req.getRemoteAddress()).thenReturn("192.0.2." + i);
            registerFailure.invoke(servlet, req, "user");
        }

        assertTrue(states.size() <= 1000);
        states.clear();
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String,Object> getThrottleStates() throws Exception {
        Field field = LoginServlet.class.getDeclaredField("throttleStates");
        field.setAccessible(true);
        return (ConcurrentHashMap<String,Object>)field.get(null);
    }
}
