package org.rsna.ctp.pipeline;

import org.junit.Test;
import static org.junit.Assert.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Verifies that Pipeline.shutdown() sets the stop flag and interrupts the thread.
 */
public class PipelineShutdownTest {

    private Element makeMinimalPipelineElement() throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root = doc.createElement("Server");
        doc.appendChild(root);
        Element pipe = doc.createElement("Pipeline");
        pipe.setAttribute("name", "test-pipeline");
        pipe.setAttribute("enabled", "yes");
        root.appendChild(pipe);
        return pipe;
    }

    @Test
    public void shutdown_setsStopFlag() throws Exception {
        Element el = makeMinimalPipelineElement();
        Pipeline pipeline = new Pipeline(el, 0);

        // Initially not stopped
        assertFalse("Pipeline should not be stopped initially", pipeline.stop);

        pipeline.shutdown();

        assertTrue("Pipeline stop flag should be true after shutdown()", pipeline.stop);
    }

    @Test
    public void shutdown_interruptsThreadWhenRunning() throws Exception {
        Element el = makeMinimalPipelineElement();
        Pipeline pipeline = new Pipeline(el, 0);
        pipeline.start();

        // Give the thread a moment to start up
        Thread.sleep(50);

        pipeline.shutdown();

        // The thread should terminate within 2 seconds (it has no stages so run() exits quickly)
        pipeline.join(2000);

        assertFalse("Pipeline thread should have terminated after shutdown()",
            pipeline.isAlive());
    }

    @Test
    public void isDown_returnsTrueAfterShutdownAndTermination() throws Exception {
        Element el = makeMinimalPipelineElement();
        Pipeline pipeline = new Pipeline(el, 0);
        pipeline.start();

        Thread.sleep(50);
        pipeline.shutdown();
        pipeline.join(2000);

        assertTrue("Pipeline.isDown() should return true after termination", pipeline.isDown());
    }
}
