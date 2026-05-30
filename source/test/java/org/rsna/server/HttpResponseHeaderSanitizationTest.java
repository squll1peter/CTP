package org.rsna.server;

import org.junit.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.net.Socket;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

public class HttpResponseHeaderSanitizationTest {

    @Test
    public void rejectsCarriageReturnInHeaderValue() throws Exception {
        Socket socket = Mockito.mock(Socket.class);
        when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        HttpResponse res = new HttpResponse(socket);

        assertThrows(IllegalArgumentException.class,
                () -> res.setHeader("X-Test", "abc\r\nInjected: yes"));
    }

    @Test
    public void rejectsCarriageReturnInHeaderName() throws Exception {
        Socket socket = Mockito.mock(Socket.class);
        when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());

        HttpResponse res = new HttpResponse(socket);

        assertThrows(IllegalArgumentException.class,
                () -> res.setHeader("X-Test\r\nInjected", "ok"));
    }
}
