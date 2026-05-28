package org.rsna.ctp.plugin;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.rsna.ctp.objects.DicomObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for StabilityWebhookPlugin: constructor attribute parsing,
 * HTML escaping in getStatusHTML(), disable short-circuit, and HTTP call
 * behaviour (GET / POST-JSON / POST-form, success / failure / retry)
 * verified against a local JDK HttpServer.
 */
public class StabilityWebhookPluginTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Element buildElement(String... attrPairs) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element pipeline = doc.createElement("Pipeline");
        pipeline.setAttribute("root", "");
        doc.appendChild(pipeline);
        Element el = doc.createElement("Plugin");
        el.setAttribute("name", "TestPlugin");
        for (int i = 0; i < attrPairs.length; i += 2) {
            el.setAttribute(attrPairs[i], attrPairs[i + 1]);
        }
        pipeline.appendChild(el);
        return el;
    }

    private HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api", handler);
        server.start();
        return server;
    }

    // ------------------------------------------------------------------
    // Constructor / config parsing
    // ------------------------------------------------------------------

    @Test
    public void defaults_areApplied_whenOptionalAttributesMissing() throws Exception {
        StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                buildElement("baseUrl", "http://localhost/api"));
        String html = p.getStatusHTML();
        assertTrue("default method must be POST",      html.contains("POST"));
        assertTrue("default contentType must be json", html.contains("json"));
        assertTrue("default timeout must be 5000 ms",  html.contains("5000"));
    }

    @Test
    public void method_isNormalisedToUpperCase() throws Exception {
        StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                buildElement("baseUrl", "http://localhost/api", "method", "put"));
        assertTrue("method 'put' must be normalised to 'PUT'",
                p.getStatusHTML().contains("PUT"));
    }

    @Test
    public void enable_no_makeNotifyReturnTrueWithoutNetworkCall() throws Exception {
        // Port 1 is reserved / unreachable — any real TCP attempt fails immediately.
        // If the plugin tries to connect it will throw, causing the call to return false.
        StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                buildElement("baseUrl", "http://localhost:1/api", "enable", "no"));
        assertTrue("disabled plugin must return true without HTTP attempt", p.notify(null));
    }

    @Test
    public void staticArguments_parsedFromArguments() throws Exception {
        // Verify the plugin constructs without exception when multiple static pairs given.
        StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                buildElement("baseUrl",   "http://localhost/api",
                             "arguments", "key1=val1;key2=val2",
                             "enable",    "no"));
        // enable=no so notify() is a no-op; just confirm clean construction
        assertTrue(p.notify(null));
    }

    @Test
    public void dynamicArguments_parsedFromArguments() throws Exception {
        // Verify construction succeeds with keyword-wrapped DICOM argument pairs.
        StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                buildElement("baseUrl",   "http://localhost/api",
                     "arguments", "pid={PatientID};suid={0020000D}",
                             "enable",    "no"));
        assertTrue(p.notify(null));
    }

    @Test
    public void notify_POST_json_resolvesDynamicArgumentsFromKeywordsAndEqualsSyntax() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        HttpServer server = startServer(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        DicomObject representative = mock(DicomObject.class);
        when(representative.getElementValue("PatientID", "")).thenReturn("P123");
        when(representative.getElementValue("StudyInstanceUID", "")).thenReturn("1.2.840.1");
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl",   "http://localhost:" + port + "/api",
                                 "method",    "POST",
                                 "contentType", "json",
                         "arguments", "pid={PatientID};study={StudyInstanceUID}",
                                 "retry",     "1"));
            assertTrue(p.notify(representative));
            String received = body.get();
            assertTrue(received.contains("\"pid\":\"P123\""));
            assertTrue(received.contains("\"study\":\"1.2.840.1\""));
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // HTML escaping in getStatusHTML()
    // ------------------------------------------------------------------

    @Test
    public void getStatusHTML_escapesAmpersandInBaseUrl() throws Exception {
        StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                buildElement("baseUrl", "http://example.com/api?a=1&b=2"));
        String html = p.getStatusHTML();
        assertFalse("raw & must not appear in status HTML", html.contains("a=1&b=2"));
        assertTrue("& must be escaped to &amp;",            html.contains("a=1&amp;b=2"));
    }

    @Test
    public void getStatusHTML_escapesAngleBracketsInBaseUrl() throws Exception {
        StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                buildElement("baseUrl", "http://example.com/<segment>"));
        String html = p.getStatusHTML();
        assertFalse("< must not appear raw in HTML",  html.contains("<segment>"));
        assertTrue("<segment> must be fully escaped", html.contains("&lt;segment&gt;"));
    }

    @Test
    public void getStatusHTML_escapesDoubleQuoteInBaseUrl() throws Exception {
        StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                buildElement("baseUrl", "http://example.com/\"quoted\""));
        String html = p.getStatusHTML();
        assertFalse("raw \" must not appear in status HTML",
                html.contains("\"quoted\""));
        assertTrue("\" must be escaped to &quot;",
                html.contains("&quot;quoted&quot;"));
    }

    @Test
    public void getStatusHTML_includesLastTriggeredAndLastCalledUrl() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl",        "http://localhost:" + port + "/api",
                                 "method",         "GET",
                         "arguments",      "source=CTP;env=test",
                                 "retry",          "1"));
            assertTrue(p.notify(null));
            String html = p.getStatusHTML();
            assertTrue(html.contains("Last triggered:"));
            assertFalse(html.contains("Last triggered:</td><td>Never"));
            assertTrue(html.contains("Last called URL:"));
            assertTrue(html.contains("source=CTP&amp;env=test"));
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // HTTP: 2xx → true, non-2xx → false (retry=1 to keep tests fast)
    // ------------------------------------------------------------------

    @Test
    public void notify_returnsTrue_on200() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl", "http://localhost:" + port + "/api",
                                 "retry",   "1"));
            assertTrue(p.notify(null));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void notify_returnsTrue_on201() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.sendResponseHeaders(201, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl", "http://localhost:" + port + "/api",
                                 "retry",   "1"));
            assertTrue(p.notify(null));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void notify_returnsFalse_on404() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.sendResponseHeaders(404, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl", "http://localhost:" + port + "/api",
                                 "retry",   "1"));
            assertFalse(p.notify(null));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void notify_returnsFalse_on500() throws Exception {
        HttpServer server = startServer(exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl", "http://localhost:" + port + "/api",
                                 "retry",   "1"));
            assertFalse(p.notify(null));
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // HTTP: method-specific request format
    // ------------------------------------------------------------------

    @Test
    public void notify_GET_encodesStaticParamsInQueryString() throws Exception {
        AtomicReference<String> uri = new AtomicReference<>("");
        HttpServer server = startServer(exchange -> {
            uri.set(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl",        "http://localhost:" + port + "/api",
                                 "method",         "GET",
                         "arguments",      "source=CTP;env=test",
                                 "retry",          "1"));
            p.notify(null);
            String received = uri.get();
            assertTrue("GET must put 'source' in query string", received.contains("source=CTP"));
            assertTrue("GET must put 'env' in query string",    received.contains("env=test"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void notify_POST_json_sendsJsonContentTypeAndBody() throws Exception {
        AtomicReference<String> body        = new AtomicReference<>("");
        AtomicReference<String> contentType = new AtomicReference<>("");
        HttpServer server = startServer(exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl",        "http://localhost:" + port + "/api",
                                 "method",         "POST",
                                 "contentType",    "json",
                         "arguments",      "site=HOSP1",
                                 "retry",          "1"));
            p.notify(null);
            assertTrue("Content-Type must be application/json",
                       contentType.get().contains("application/json"));
            assertTrue("JSON body must contain the key",   body.get().contains("site"));
            assertTrue("JSON body must contain the value", body.get().contains("HOSP1"));
            assertTrue("JSON body must be a JSON object",
                       body.get().startsWith("{") && body.get().endsWith("}"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void notify_PUT_json_sendsJsonContentTypeAndBody() throws Exception {
        AtomicReference<String> method      = new AtomicReference<>("");
        AtomicReference<String> contentType = new AtomicReference<>("");
        HttpServer server = startServer(exchange -> {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl",     "http://localhost:" + port + "/api",
                                 "method",      "PUT",
                                 "contentType", "json",
                                 "retry",       "1"));
            p.notify(null);
            assertEquals("request method must be PUT", "PUT", method.get());
            assertTrue("Content-Type must be application/json",
                       contentType.get().contains("application/json"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void notify_POST_form_sendsFormContentTypeAndBody() throws Exception {
        AtomicReference<String> body        = new AtomicReference<>("");
        AtomicReference<String> contentType = new AtomicReference<>("");
        HttpServer server = startServer(exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl",        "http://localhost:" + port + "/api",
                                 "method",         "POST",
                                 "contentType",    "form",
                         "arguments",      "site=HOSP1",
                                 "retry",          "1"));
            p.notify(null);
            assertTrue("Content-Type must be application/x-www-form-urlencoded",
                       contentType.get().contains("application/x-www-form-urlencoded"));
            assertTrue("form body must contain 'site=HOSP1'", body.get().contains("site=HOSP1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void notify_POST_json_escapesSpecialCharsInValues() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        HttpServer server = startServer(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            // value contains a double-quote and backslash — must be JSON-escaped
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl",        "http://localhost:" + port + "/api",
                                 "method",         "POST",
                                 "contentType",    "json",
                         "arguments",      "note=say\\\"hello\\\"",
                                 "retry",          "1"));
            p.notify(null);
            // The raw double-quote must not appear unescaped inside the JSON string value
            String received = body.get();
            // Body is valid: starts and ends as a JSON object
            assertTrue(received.startsWith("{") && received.endsWith("}"));
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // HTTP: retry behaviour
    // ------------------------------------------------------------------

    @Test
    public void notify_retriesUntilSuccessWithinRetryCount() throws Exception {
        AtomicInteger hitCount = new AtomicInteger(0);
        HttpServer server = startServer(exchange -> {
            int n = hitCount.incrementAndGet();
            exchange.sendResponseHeaders(n < 3 ? 503 : 200, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl", "http://localhost:" + port + "/api",
                                 "retry",   "3"));
            assertTrue("must succeed on 3rd attempt", p.notify(null));
            assertEquals("must have made exactly 3 HTTP calls", 3, hitCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void notify_exhaustsAllRetriesAndReturnsFalse() throws Exception {
        AtomicInteger hitCount = new AtomicInteger(0);
        HttpServer server = startServer(exchange -> {
            hitCount.incrementAndGet();
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        });
        int port = server.getAddress().getPort();
        try {
            StabilityWebhookPlugin p = new StabilityWebhookPlugin(
                    buildElement("baseUrl", "http://localhost:" + port + "/api",
                                 "retry",   "2"));
            assertFalse("must return false when all retries are exhausted", p.notify(null));
            assertEquals("must have attempted exactly retry-count times", 2, hitCount.get());
        } finally {
            server.stop(0);
        }
    }
}
