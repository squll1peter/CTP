package org.rsna.ctp.pipeline;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

import java.io.File;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Tests that AtomicLong counters in AbstractPipelineStage
 * behave correctly: initial state, increment, and display in status HTML.
 */
public class PipelineStageCountersTest {

    /** Minimal concrete subclass for testing the base class */
    static class StubStage extends AbstractPipelineStage {
        StubStage(Element el) { super(el); }
        public void run() { }
        public void shutdown() { stop = true; }
    }

    private StubStage stage;

    @Before
    public void setUp() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element el = doc.createElement("TestStage");
        el.setAttribute("name", "CounterTestStage");
        doc.appendChild(el);
        // wrap in parent to satisfy pipelinePath attribute lookup
        Element parent = doc.createElement("Pipeline");
        parent.setAttribute("root", "");
        doc.replaceChild(parent, el);
        parent.appendChild(el);
        stage = new StubStage(el);
    }

    @Test
    public void counters_initiallyZero() {
        assertEquals(0L, stage.totalIn.get());
        assertEquals(0L, stage.totalPassed.get());
        assertEquals(0L, stage.totalQuarantined.get());
    }

    @Test
    public void recordFileIn_incrementsTotalIn() {
        File f = new File("dummy.dcm");
        stage.recordFileIn(f);
        assertEquals(1L, stage.totalIn.get());
        assertEquals(f, stage.lastFileIn);
    }

    @Test
    public void recordFileOut_incrementsTotalPassed() {
        File f = new File("out.dcm");
        stage.recordFileOut(f);
        assertEquals(1L, stage.totalPassed.get());
        assertEquals(f, stage.lastFileOut);
    }

    @Test
    public void recordQuarantine_incrementsTotalQuarantined() {
        stage.recordQuarantine();
        stage.recordQuarantine();
        assertEquals(2L, stage.totalQuarantined.get());
    }

    @Test
    public void statusHTML_includesCounters() {
        stage.recordFileIn(new File("a.dcm"));
        stage.recordFileOut(new File("b.dcm"));
        stage.recordQuarantine();
        String html = stage.getStatusHTML();
        assertTrue("HTML should show received count", html.contains("Files received:</td><td>1"));
        assertTrue("HTML should show passed count",   html.contains("Files passed:</td><td>1"));
        assertTrue("HTML should show quarantine count", html.contains("Files quarantined:</td><td>1"));
    }
}
