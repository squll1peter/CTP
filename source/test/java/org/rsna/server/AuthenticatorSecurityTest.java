package org.rsna.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AuthenticatorSecurityTest {

    private Users originalUsers;
    private Authenticator originalAuthenticator;

    @Before
    public void setUp() throws Exception {
        originalUsers = getStaticUsers();
        originalAuthenticator = getStaticAuthenticator();
        setStaticUsers(new TestUsers());
        setStaticAuthenticator(new Authenticator());
    }

    @After
    public void tearDown() throws Exception {
        setStaticUsers(originalUsers);
        setStaticAuthenticator(originalAuthenticator);
    }

    @Test
    public void basicAuthRejectedOnHttp() {
        HttpRequest req = Mockito.mock(HttpRequest.class);
        when(req.getCookie(anyString())).thenReturn(null);
        when(req.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
        when(req.getProtocol()).thenReturn("http");
        when(req.getRemoteAddress()).thenReturn("127.0.0.1");

        User user = Authenticator.getInstance().authenticate(req);

        assertNull(user);
    }

    @Test
    public void basicAuthAcceptedOnHttps() {
        HttpRequest req = Mockito.mock(HttpRequest.class);
        when(req.getCookie(anyString())).thenReturn(null);
        when(req.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");
        when(req.getProtocol()).thenReturn("https");

        User user = Authenticator.getInstance().authenticate(req);

        assertNotNull(user);
        assertEquals("user", user.getUsername());
    }

    @Test
    public void rsnaHeaderDisabledByDefault() {
        HttpRequest req = Mockito.mock(HttpRequest.class);
        when(req.getCookie(anyString())).thenReturn(null);
        when(req.getHeader("Authorization")).thenReturn(null);
        when(req.getHeader("RSNA")).thenReturn("user:pass");
        when(req.getProtocol()).thenReturn("https");

        User user = Authenticator.getInstance().authenticate(req);

        assertNull(user);
    }

    @Test
    public void sessionCookieContainsSecurityAttributes() {
        HttpRequest req = Mockito.mock(HttpRequest.class);
        HttpResponse res = Mockito.mock(HttpResponse.class);
        when(req.getRemoteAddress()).thenReturn("127.0.0.1");
        when(req.getProtocol()).thenReturn("https");

        boolean ok = Authenticator.getInstance().createSession(new User("user", "pw"), req, res);

        assertTrue(ok);
        verify(res).setHeader(eq("Set-Cookie"), contains("; Path=/; HttpOnly; SameSite=Lax; Secure"));
    }

    @Test
    public void logoutCookieMirrorsSecurityAttributes() {
        HttpRequest req = Mockito.mock(HttpRequest.class);
        HttpResponse res = Mockito.mock(HttpResponse.class);
        when(req.getCookie(anyString())).thenReturn("session123");
        when(req.getProtocol()).thenReturn("https");

        Authenticator.getInstance().closeSession(req, res);

        verify(res).setHeader(eq("Set-Cookie"), contains("; Path=/; HttpOnly; SameSite=Lax; Secure; Max-Age=0"));
    }

    @Test
    public void csrfTokenValidationWorksForSessionBoundToken() {
        HttpRequest loginReq = Mockito.mock(HttpRequest.class);
        HttpResponse loginRes = Mockito.mock(HttpResponse.class);
        when(loginReq.getRemoteAddress()).thenReturn("127.0.0.1");
        when(loginReq.getProtocol()).thenReturn("https");
        Authenticator.getInstance().createSession(new User("user", "pw"), loginReq, loginRes);

        final java.util.concurrent.atomic.AtomicReference<String> cookieValue = new java.util.concurrent.atomic.AtomicReference<String>("");
        doAnswer(invocation -> {
            String header = invocation.getArgument(1);
            if (header.startsWith("RSNASESSION=")) cookieValue.set(header);
            return null;
        }).when(loginRes).setHeader(eq("Set-Cookie"), any(String.class));

        Authenticator.getInstance().createSession(new User("user", "pw"), loginReq, loginRes);
        String sessionCookie = cookieValue.get();
        String sessionId = sessionCookie.substring("RSNASESSION=".length(), sessionCookie.indexOf(";"));

        HttpRequest req = Mockito.mock(HttpRequest.class);
        when(req.getCookie("RSNASESSION")).thenReturn(sessionId);
        String token = Authenticator.getInstance().getCsrfToken(req);
        when(req.getParameter("csrfToken", "")).thenReturn(token);
        when(req.getHeader("X-CSRF-Token", "")).thenReturn("");

        assertTrue(Authenticator.getInstance().validateCsrfToken(req));
    }

    private static Users getStaticUsers() throws Exception {
        Field field = Users.class.getDeclaredField("users");
        field.setAccessible(true);
        return (Users) field.get(null);
    }

    private static void setStaticUsers(Users users) throws Exception {
        Field field = Users.class.getDeclaredField("users");
        field.setAccessible(true);
        field.set(null, users);
    }

    private static Authenticator getStaticAuthenticator() throws Exception {
        Field field = Authenticator.class.getDeclaredField("authenticator");
        field.setAccessible(true);
        return (Authenticator) field.get(null);
    }

    private static void setStaticAuthenticator(Authenticator authenticator) throws Exception {
        Field field = Authenticator.class.getDeclaredField("authenticator");
        field.setAccessible(true);
        field.set(null, authenticator);
    }

    private static class TestUsers extends Users {
        @Override
        public User authenticate(String username, String password) {
            if ("user".equals(username) && "pass".equals(password)) return new User("user", "hash");
            return null;
        }
    }
}
