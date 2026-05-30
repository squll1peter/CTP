package org.rsna.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.servlets.LoginServlet;
import org.rsna.servlets.Servlet;
import org.rsna.util.AttackLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class HttpHandlerSecurityTest {
    private Users originalUsers;

    @Before
    public void setUp() throws Exception {
        originalUsers = getStaticUsers();
        setStaticUsers(new Users() { });
        resetAttackLogSingleton();
    }

    @After
    public void tearDown() throws Exception {
        setStaticUsers(originalUsers);
        resetAttackLogSingleton();
    }

    @Test
    public void publicPingPathBypassesAuthGate() throws Exception {
        HttpRequest req = request("ping", false, false, null);
        HttpHandler handler = new HttpHandler(Mockito.mock(Socket.class), selector(req, new ProtectedServlet(tempRoot(), "ping")), Mockito.mock(HttpServer.class));

        Servlet servlet = selectServlet(handler, req);

        assertEquals(ProtectedServlet.class, servlet.getClass());
    }

    @Test
    public void loginFlowRemainsPublic() throws Exception {
        HttpRequest req = request("login", false, false, null);
        HttpHandler handler = new HttpHandler(Mockito.mock(Socket.class), selector(req, new LoginServlet(tempRoot(), "login")), Mockito.mock(HttpServer.class));

        Servlet servlet = selectServlet(handler, req);

        assertEquals(LoginServlet.class, servlet.getClass());
    }

    @Test
    public void nonPublicPathRequiresAuth() throws Exception {
        HttpRequest req = request("status", false, false, null);
        HttpHandler handler = new HttpHandler(Mockito.mock(Socket.class), selector(req, new ProtectedServlet(tempRoot(), "status")), Mockito.mock(HttpServer.class));

        Servlet servlet = selectServlet(handler, req);

        assertEquals(LoginServlet.class, servlet.getClass());
    }

    @Test
    public void authenticatedProtectedPathReachesSelectedServlet() throws Exception {
        HttpRequest req = request("status", false, true, null);
        HttpHandler handler = new HttpHandler(Mockito.mock(Socket.class), selector(req, new ProtectedServlet(tempRoot(), "status")), Mockito.mock(HttpServer.class));

        Servlet servlet = selectServlet(handler, req);

        assertEquals(ProtectedServlet.class, servlet.getClass());
    }

    @Test
    public void exactLoginPageAssetBypassesAuthGate() throws Exception {
        HttpRequest req = request("BaseStyles.css", false, false, null);
        HttpHandler handler = new HttpHandler(Mockito.mock(Socket.class), selector(req, new ProtectedServlet(tempRoot(), "BaseStyles.css")), Mockito.mock(HttpServer.class));

        Servlet servlet = selectServlet(handler, req);

        assertEquals(ProtectedServlet.class, servlet.getClass());
    }

    @Test
    public void loginHtmlBypassesAuthGate() throws Exception {
        HttpRequest req = request("login.html", false, false, null);
        HttpHandler handler = new HttpHandler(Mockito.mock(Socket.class), selector(req, new ProtectedServlet(tempRoot(), "login.html")), Mockito.mock(HttpServer.class));

        Servlet servlet = selectServlet(handler, req);

        assertEquals(ProtectedServlet.class, servlet.getClass());
    }

    @Test
    public void nonAllowlistedStaticLookingPathRequiresAuth() throws Exception {
        HttpRequest req = request("JSUtil.js", false, false, null);
        HttpHandler handler = new HttpHandler(Mockito.mock(Socket.class), selector(req, new ProtectedServlet(tempRoot(), "JSUtil.js")), Mockito.mock(HttpServer.class));

        Servlet servlet = selectServlet(handler, req);

        assertEquals(LoginServlet.class, servlet.getClass());
    }

    @Test
    public void localShutdownRequestStillBypassesAuthGate() throws Exception {
        HttpRequest req = request("shutdown", true, false, "shutdown");
        HttpHandler handler = new HttpHandler(Mockito.mock(Socket.class), selector(req, new ProtectedServlet(tempRoot(), "shutdown")), Mockito.mock(HttpServer.class));

        Servlet servlet = selectServlet(handler, req);

        assertEquals(ProtectedServlet.class, servlet.getClass());
    }

    @Test
    public void remoteShutdownRequestRequiresAuth() throws Exception {
        HttpRequest req = request("shutdown", false, false, "shutdown");
        HttpHandler handler = new HttpHandler(Mockito.mock(Socket.class), selector(req, new ProtectedServlet(tempRoot(), "shutdown")), Mockito.mock(HttpServer.class));

        Servlet servlet = selectServlet(handler, req);

        assertEquals(LoginServlet.class, servlet.getClass());
    }

    @Test
    public void unsupportedMethodRecordsSecurityEvent() throws Exception {
        HttpHandler handler = handlerForRawRequest(
                "PATCH /status HTTP/1.1\r\nHost: localhost\r\nUser-Agent: JUnit\r\n\r\n",
                new ProtectedServlet(tempRoot(), "status"));

        handler.run();

        assertCategoryCount("unsupported method", 1);
    }

    @Test
    public void servletExceptionRecordsInternalErrorEventAndReturns500() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpHandler handler = handlerForRawRequest(
                "GET /status HTTP/1.1\r\nHost: localhost\r\nUser-Agent: JUnit\r\n\r\n",
                new ThrowingServlet(tempRoot(), "status"),
                out);

        handler.run();

        assertCategoryCount("internal error", 1);
        assertTrue(out.toString("UTF-8").startsWith("HTTP/1.1 500"));
    }

    private ServletSelector selector(HttpRequest req, Servlet servlet) throws Exception {
        ServletSelector selector = Mockito.mock(ServletSelector.class);
        File root = tempRoot();
        when(selector.getRequireAuthentication()).thenReturn(true);
        when(selector.getRoot()).thenReturn(root);
        when(selector.getServlet(req)).thenReturn(servlet);
        return selector;
    }

    private HttpRequest request(String pathElement, boolean localHost, boolean authenticated, String serviceManagerHeader) throws Exception {
        HttpRequest req = Mockito.mock(HttpRequest.class);
        Path path = Mockito.mock(Path.class);
        when(req.getParsedPath()).thenReturn(path);
        when(path.element(0)).thenReturn(pathElement);
        when(path.length()).thenReturn(1);
        when(req.getUser()).thenReturn(authenticated ? Mockito.mock(User.class) : null);
        when(req.isFromLocalHost()).thenReturn(localHost);
        when(req.getHeader("servicemanager")).thenReturn(serviceManagerHeader);
        return req;
    }

    private Servlet selectServlet(HttpHandler handler, HttpRequest req) throws Exception {
        Method method = HttpHandler.class.getDeclaredMethod("selectServlet", HttpRequest.class);
        method.setAccessible(true);
        return (Servlet)method.invoke(handler, req);
    }

    private HttpHandler handlerForRawRequest(String rawRequest, Servlet servlet) throws Exception {
        return handlerForRawRequest(rawRequest, servlet, new ByteArrayOutputStream());
    }

    private HttpHandler handlerForRawRequest(String rawRequest, Servlet servlet, ByteArrayOutputStream out) throws Exception {
        Socket socket = Mockito.mock(Socket.class);
        when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(rawRequest.getBytes(StandardCharsets.UTF_8)));
        when(socket.getOutputStream()).thenReturn(out);
        when(socket.getRemoteSocketAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 45678));
        ServletSelector selector = Mockito.mock(ServletSelector.class);
        when(selector.getRequireAuthentication()).thenReturn(false);
        when(selector.getServlet(Mockito.any(HttpRequest.class))).thenReturn(servlet);
        return new HttpHandler(socket, selector, Mockito.mock(HttpServer.class));
    }

    private void assertCategoryCount(String category, int expected) {
        Hashtable<String,Integer> counts = AttackLog.getInstance().getCategoryCounts();
        assertEquals(Integer.valueOf(expected), counts.get(category));
    }

    private static Users getStaticUsers() throws Exception {
        Field field = Users.class.getDeclaredField("users");
        field.setAccessible(true);
        return (Users)field.get(null);
    }

    private static void setStaticUsers(Users users) throws Exception {
        Field field = Users.class.getDeclaredField("users");
        field.setAccessible(true);
        field.set(null, users);
    }

    private void resetAttackLogSingleton() throws Exception {
        Field field = AttackLog.class.getDeclaredField("attackLog");
        field.setAccessible(true);
        field.set(null, null);
    }

    private File tempRoot() throws Exception {
        File root = Files.createTempDirectory("http-handler").toFile();
        root.deleteOnExit();
        return root;
    }

    public static class ProtectedServlet extends Servlet {
        public ProtectedServlet(File root, String context) {
            super(root, context);
        }
    }

    public static class ThrowingServlet extends Servlet {
        public ThrowingServlet(File root, String context) {
            super(root, context);
        }

        public void doGet(HttpRequest req, HttpResponse res) throws Exception {
            throw new RuntimeException("intentional test failure");
        }
    }
}
