package org.rsna.ctp.servlets;

import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.server.HttpRequest;
import org.rsna.server.User;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Verifies request-local authorization state under concurrent access.
 */
public class CTPServletConcurrencyTest {

    @Test
    public void concurrentCallsProduceIndependentAuthState() throws Exception {
        final CTPServlet servlet = new CTPServlet(new File("."), "/ctp") {};
        final AtomicInteger mismatches = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Void>> tasks = new ArrayList<Callable<Void>>();

        for (int i = 0; i < 500; i++) {
            final boolean admin = (i % 2 == 0);
            tasks.add(new Callable<Void>() {
                public Void call() {
                    HttpRequest req = Mockito.mock(HttpRequest.class);
                    when(req.userHasRole("admin")).thenReturn(admin);
                    when(req.getUser()).thenReturn(Mockito.mock(User.class));
                    when(req.getProtocol()).thenReturn("http");
                    when(req.getHeader("Host")).thenReturn("localhost");
                    when(req.hasParameter("suppress")).thenReturn(false);

                    CTPServlet.AuthState state = servlet.loadParameters(req);
                    if (state.isAdmin != admin) mismatches.incrementAndGet();
                    return null;
                }
            });
        }

        pool.invokeAll(tasks);
        pool.shutdown();

        assertEquals("auth state should match its own request in concurrent calls", 0, mismatches.get());
    }
}
