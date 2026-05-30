package org.rsna.server;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

public class HttpRequestParsingLimitsTest {
    private Users originalUsers;

    @Before
    public void setUp() throws Exception {
        originalUsers = getStaticUsers();
        setStaticUsers(new Users() { });
    }

    @After
    public void tearDown() throws Exception {
        setStaticUsers(originalUsers);
    }

    @Test
    public void rejectsTooLongQueryString() throws Exception {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < 9000; i++) q.append('a');
        String req = "GET /path?" + q + " HTTP/1.1\r\nHost: localhost\r\n\r\n";

        HttpParseException ex = expectParseFailure(req);
        assertEquals(HttpResponse.badrequest, ex.getStatusCode());
    }

    @Test
    public void rejectsTooManyHeaders() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("GET / HTTP/1.1\\r\\nHost: localhost\\r\\n");
        for (int i = 0; i < 120; i++) {
            sb.append("X-H-").append(i).append(": v\\r\\n");
        }
        sb.append("\\r\\n");
        String req = sb.toString().replace("\\r", "\r").replace("\\n", "\n");

        HttpParseException ex = expectParseFailure(req);
        assertEquals(431, ex.getStatusCode());
    }

    @Test
    public void rejectsOverlargeFormBodyByContentLength() throws Exception {
        String req = "POST /login HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Content-Length: 1048577\r\n\r\n";

        HttpParseException ex = expectParseFailure(req);
        assertEquals(413, ex.getStatusCode());
    }

    @Test
    public void preservesEqualsInParameterValue() throws Exception {
        String req = "GET /p?token=a=b=c HTTP/1.1\r\nHost: localhost\r\n\r\n";
        HttpRequest parsed = parseRequest(req);
        assertEquals("a=b=c", parsed.getParameter("token"));
    }

    @Test
    public void constructorAppliesHeaderReadTimeout() throws Exception {
        String req = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
        Socket socket = Mockito.mock(Socket.class);
        when(socket.getInputStream()).thenReturn(
                new ByteArrayInputStream(req.getBytes(StandardCharsets.UTF_8)));
        when(socket.getRemoteSocketAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 45678));

        new HttpRequest(socket, null, false);

        Mockito.verify(socket).setSoTimeout(15000);
    }

    private HttpRequest parseRequest(String rawRequest) throws Exception {
        Socket socket = Mockito.mock(Socket.class);
        when(socket.getInputStream()).thenReturn(
                new ByteArrayInputStream(rawRequest.getBytes(StandardCharsets.UTF_8)));
        when(socket.getRemoteSocketAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 45678));
        return new HttpRequest(socket, null, false);
    }

    private HttpParseException expectParseFailure(String rawRequest) throws Exception {
        try {
            parseRequest(rawRequest);
            fail("Expected HttpParseException");
            return null;
        }
        catch (HttpParseException ex) {
            return ex;
        }
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
}
