package org.rsna.ctp.servlets;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.server.HttpRequest;
import org.rsna.server.User;

import java.io.File;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that loadParameters() returns immutable request-local auth state.
 */
public class CTPServletAuthStateTest {

    private CTPServlet servlet;
    private HttpRequest req;
    private User adminUser;

    @Before
    public void setUp() {
        servlet = new CTPServlet(new File("."), "/ctp") {
            // concrete subclass — CTPServlet is not abstract but has no doGet
        };
        req = Mockito.mock(HttpRequest.class);
        adminUser = Mockito.mock(User.class);
    }

    @Test
    public void adminUser_setsAuthStateAdminTrue() {
        when(req.userHasRole("admin")).thenReturn(true);
        when(req.getUser()).thenReturn(adminUser);
        when(req.getProtocol()).thenReturn("http");
        when(req.getHeader("Host")).thenReturn("localhost");
        when(req.hasParameter("suppress")).thenReturn(false);

        CTPServlet.AuthState authState = servlet.loadParameters(req);

        assertTrue("admin role should set isAdmin=true", authState.isAdmin);
        assertTrue("admin role should set isAuthorized=true", authState.isAuthorized);
    }

    @Test
    public void nonAdminUser_setsAuthStateAdminFalse() {
        when(req.userHasRole("admin")).thenReturn(false);
        when(req.getUser()).thenReturn(adminUser);
        when(req.getProtocol()).thenReturn("http");
        when(req.getHeader("Host")).thenReturn("localhost");
        when(req.hasParameter("suppress")).thenReturn(false);

        CTPServlet.AuthState authState = servlet.loadParameters(req);

        assertFalse("non-admin role should set isAdmin=false", authState.isAdmin);
    }

}
