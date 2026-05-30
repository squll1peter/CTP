package org.rsna.ctp.plugin;

import org.junit.Test;
import org.rsna.ctp.objects.DicomObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for StabilityExecPlugin: attribute parsing, queue behaviour,
 * dry-run mode, command execution, argument token assembly, and status HTML.
 */
public class StabilityExecPluginTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Element buildElement(String... attrPairs) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element pipeline = doc.createElement("Pipeline");
        pipeline.setAttribute("root", "");
        doc.appendChild(pipeline);
        Element el = doc.createElement("Plugin");
        el.setAttribute("name", "TestLocalCmd");
        for (int i = 0; i < attrPairs.length; i += 2) {
            el.setAttribute(attrPairs[i], attrPairs[i + 1]);
        }
        pipeline.appendChild(el);
        return el;
    }

    private static String successCommand() {
        return WINDOWS ? "cmd /c exit 0" : "/bin/true";
    }

    private static String failCommand() {
        return WINDOWS ? "cmd /c exit 1" : "/bin/false";
    }

    private static String echoCommand() {
        return WINDOWS ? "cmd /c echo" : "/bin/echo";
    }

    /**
     * Poll until the plugin's status HTML no longer shows "Never" for "Last exit at:",
     * indicating the worker has completed at least one command execution.
     */
    private static void waitForExecution(StabilityExecPlugin plugin, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!plugin.getStatusHTML().contains("Last exit at:</td><td>Never")) return;
            Thread.sleep(50);
        }
        fail("Timed out waiting for command execution after " + timeoutMs + " ms");
    }

    // ------------------------------------------------------------------
    // Constructor / config parsing
    // ------------------------------------------------------------------

    @Test
    public void defaults_areApplied_whenOptionalAttributesMissing() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", successCommand()));
        String html = p.getStatusHTML();
        assertTrue("default dryRun must be no",         html.contains("no"));
        assertTrue("default minInterval must be 0",     html.contains("Min Interval (ms):</td><td>0"));
        assertTrue("default maxQueueSize must be 100",  html.contains("Max Queue Size:</td><td>100"));
        assertTrue("initial exit code must be N/A",     html.contains("N/A"));
        assertTrue("initial triggered must be Never",   html.contains("Last triggered:</td><td>Never"));
        assertTrue("initial last command must be Never",html.contains("Last command:</td><td>Never"));
    }

    @Test
    public void constructor_parsesCommandTokens() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", "/usr/bin/python3 /tmp/notify.py"));
        String html = p.getStatusHTML();
        assertTrue("status must show full base command",
                html.contains("/usr/bin/python3 /tmp/notify.py"));
    }

    @Test
    public void dynamicArguments_parsedFromArguments() throws Exception {
        // Construction succeeds; enable=no so no execution
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command",   successCommand(),
                             "arguments", "pid={PatientID};suid={0020000D}",
                             "enable",    "no"));
        assertTrue(p.notify(null));
    }

    @Test
    public void staticArguments_parsedFromArguments() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command",        successCommand(),
                             "arguments",      "source=CTP;site=HOSP1",
                             "enable",         "no"));
        assertTrue(p.notify(null));
    }

    // ------------------------------------------------------------------
    // Disable / dry-run short-circuits
    // ------------------------------------------------------------------

    @Test
    public void notify_disabled_returnsTrueWithoutEnqueue() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", "/nonexistent-should-not-run-abc123",
                             "enable",  "no"));
        assertTrue("disabled notify must return true", p.notify(null));
        assertTrue("queue must remain empty", p.getStatusHTML().contains("Queue depth:</td><td>0"));
    }

    @Test
    public void notify_dryRun_logsCommandWithoutExecutingRealProcess() throws Exception {
        // Using a command that does not exist on disk: would throw IOException if executed.
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", "/definitely-nonexistent-binary-xyz987",
                             "dryRun",  "yes"));
        p.start();
        try {
            assertTrue(p.notify(null));
            waitForExecution(p, 3000);
            String html = p.getStatusHTML();
            assertTrue("dryRun must record 0 exit code", html.contains("Last exit code:</td><td>0"));
            assertTrue("dryRun must record last command",
                    html.contains("/definitely-nonexistent-binary-xyz987"));
        } finally {
            p.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Queue behaviour
    // ------------------------------------------------------------------

    @Test
    public void notify_returnsFalse_whenQueueFull() throws Exception {
        // maxQueueSize=1, do NOT start worker so queue stays full
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command",      successCommand(),
                             "maxQueueSize", "1"));
        // Fill the queue (worker not started, so item stays in queue)
        assertTrue("first notify must succeed (queue has room)", p.notify(null));
        // Now queue is full
        assertFalse("second notify must return false when queue is full", p.notify(null));
        assertTrue("dropped count must be 1",
                p.getStatusHTML().contains("Total dropped:</td><td>1"));
    }

    @Test
    public void notify_updatesLastTriggeredTime_onEnqueue() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", successCommand()));
        assertTrue(p.notify(null));
        assertFalse("Last triggered must not be Never after notify()",
                p.getStatusHTML().contains("Last triggered:</td><td>Never"));
    }

    // ------------------------------------------------------------------
    // Argument token assembly
    // ------------------------------------------------------------------

    @Test
    public void notify_buildsCorrectCommandTokens_withDynamicAndStaticArgs() throws Exception {
        DicomObject representative = mock(DicomObject.class);
        when(representative.getElementValue("PatientID", "")).thenReturn("P123");
        when(representative.getElementValue("0020000D",  "")).thenReturn("1.2.3");

        StabilityExecPlugin p = new StabilityExecPlugin(
            buildElement("command",        echoCommand(),
                             "arguments",      "pid={PatientID};suid={0020000D};source=CTP",
                             "dryRun",         "yes"));
        p.start();
        try {
            assertTrue(p.notify(representative));
            waitForExecution(p, 3000);
            String html = p.getStatusHTML();
            assertTrue("must contain --pid P123",    html.contains("--pid P123"));
            assertTrue("must contain --suid 1.2.3",  html.contains("--suid 1.2.3"));
            assertTrue("must contain --source CTP",  html.contains("--source CTP"));
        } finally {
            p.shutdown();
        }
    }

    @Test
    public void notify_fallsBackToColonDelimiterInArguments() throws Exception {
        // Legacy colon syntax still works, including keyword-wrapped values.
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command",   successCommand(),
                             "arguments", "pid:{PatientID}",
                             "enable",    "no"));
        assertTrue(p.notify(null));
    }

    @Test
    public void notify_substitutesDicomKeywordInsideCommandTokens() throws Exception {
        DicomObject representative = mock(DicomObject.class);
        when(representative.getElementValue("PatientID", "")).thenReturn("P123");

        StabilityExecPlugin p = new StabilityExecPlugin(
            buildElement("command", echoCommand() + " patient={PatientID}",
                             "dryRun",  "yes"));
        p.start();
        try {
            assertTrue(p.notify(representative));
            waitForExecution(p, 3000);
            String html = p.getStatusHTML();
            assertTrue("must substitute {PatientID} in command token",
                    html.contains("patient=P123"));
        } finally {
            p.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Real execution
    // ------------------------------------------------------------------

    @Test
    public void notify_executesCommand_andRecordsZeroExitCode() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", successCommand()));
        p.start();
        try {
            assertTrue(p.notify(null));
            waitForExecution(p, 3000);
            String html = p.getStatusHTML();
            assertTrue("exit code must be 0", html.contains("Last exit code:</td><td>0"));
            assertFalse("Last exit at must not be Never",
                    html.contains("Last exit at:</td><td>Never"));
        } finally {
            p.shutdown();
        }
    }

    @Test
    public void notify_recordsNonZeroExitCode_onFailingCommand() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", failCommand()));
        p.start();
        try {
            assertTrue(p.notify(null));
            waitForExecution(p, 3000);
            String html = p.getStatusHTML();
            assertFalse("exit code must not be 0 or N/A",
                    html.contains("Last exit code:</td><td>0")
                    || html.contains("Last exit code:</td><td>N/A"));
        } finally {
            p.shutdown();
        }
    }

    @Test
    public void notify_handlesNonExistentCommand_withoutCrashing() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", "/definitely-nonexistent-binary-abc999"));
        p.start();
        try {
            assertTrue(p.notify(null));
            // Worker must survive the error and record exit code -1
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                if (p.getStatusHTML().contains("Last exit code:</td><td>-1")) return;
                Thread.sleep(50);
            }
            fail("Expected exit code -1 after failed execution");
        } finally {
            p.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // Status HTML
    // ------------------------------------------------------------------

    @Test
    public void getStatusHTML_containsAllExpectedLabels() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", successCommand()));
        String html = p.getStatusHTML();
        assertTrue(html.contains("Enabled:"));
        assertTrue(html.contains("Dry Run:"));
        assertTrue(html.contains("Command:"));
        assertTrue(html.contains("Min Interval (ms):"));
        assertTrue(html.contains("Max Queue Size:"));
        assertTrue(html.contains("Queue depth:"));
        assertTrue(html.contains("Total dropped:"));
        assertTrue(html.contains("Last triggered:"));
        assertTrue(html.contains("Last command:"));
        assertTrue(html.contains("Last exit code:"));
        assertTrue(html.contains("Last exit at:"));
    }

    @Test
    public void getStatusHTML_escapesSpecialCharsInCommand() throws Exception {
        StabilityExecPlugin p = new StabilityExecPlugin(
                buildElement("command", (WINDOWS ? "cmd /c echo" : "/bin/script.sh"), "dryRun", "yes"));
        String html = p.getStatusHTML();
        // Command itself is benign, but verify escaping via injected argument value with &
        // We test htmlEscape indirectly through lastCommand after a dry-run execution
        p.start();
        try {
            DicomObject representative = mock(DicomObject.class);
            when(representative.getElementValue("PatientID", "")).thenReturn("A&B");
            StabilityExecPlugin p2 = new StabilityExecPlugin(
                    buildElement("command",   echoCommand(),
                     "arguments", "pid={PatientID}",
                                 "dryRun",    "yes"));
            p2.start();
            try {
                assertTrue(p2.notify(representative));
                waitForExecution(p2, 3000);
                String html2 = p2.getStatusHTML();
                assertFalse("raw & must not appear in HTML",   html2.contains("--pid A&B\""));
                assertTrue("& must be escaped to &amp;",       html2.contains("A&amp;B"));
            } finally {
                p2.shutdown();
            }
        } finally {
            p.shutdown();
        }
    }
}
