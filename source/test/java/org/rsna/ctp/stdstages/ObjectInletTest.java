package org.rsna.ctp.stdstages;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rsna.ctp.objects.FileObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Tests for ObjectInlet: inject behaviour, queue visibility, dequeue,
 * edge-case guarding, and status HTML.
 */
public class ObjectInletTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("inlet-test").toFile();
    }

    @After
    public void tearDown() {
        deleteRecursive(tempDir);
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    // ------------------------------------------------------------------
    // XML element builder
    // ------------------------------------------------------------------

    private Element buildElement(String... attrPairs) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element pipeline = doc.createElement("Pipeline");
        pipeline.setAttribute("root", "");
        doc.appendChild(pipeline);
        Element el = doc.createElement("ImportService");
        el.setAttribute("name", "TestInlet");
        el.setAttribute("id", "inlet1");
        el.setAttribute("root", new File(tempDir, "inlet-root").getAbsolutePath());
        for (int i = 0; i < attrPairs.length; i += 2) {
            el.setAttribute(attrPairs[i], attrPairs[i + 1]);
        }
        pipeline.appendChild(el);
        return el;
    }

    private File createTestFile(String content) throws Exception {
        File f = File.createTempFile("src-", ".bin", tempDir);
        Files.write(f.toPath(), content.getBytes("UTF-8"));
        return f;
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    @Test
    public void constructor_createsDirectories() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        File root = new File(tempDir, "inlet-root");
        assertTrue("root dir must exist", root.isDirectory());
        assertTrue("temp subdir must exist", new File(root, "temp").isDirectory());
        assertTrue("active subdir must exist", new File(root, "active").isDirectory());
    }

    // ------------------------------------------------------------------
    // inject
    // ------------------------------------------------------------------

    @Test
    public void inject_queuesFile() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        File src = createTestFile("hello");
        inlet.inject(src);
        assertEquals("queue size must be 1 after one inject", 1, inlet.getQueueSize());
    }

    @Test
    public void inject_doesNotModifySourceFile() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        File src = createTestFile("hello");
        inlet.inject(src);
        assertTrue("source file must still exist", src.exists());
        assertEquals("source file content must be unchanged",
                "hello", new String(Files.readAllBytes(src.toPath()), "UTF-8"));
    }

    @Test
    public void inject_multipleFiles_queueSizeReflectsAll() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        inlet.inject(createTestFile("one"));
        inlet.inject(createTestFile("two"));
        inlet.inject(createTestFile("three"));
        assertEquals("queue size must be 3 after three injects", 3, inlet.getQueueSize());
    }

    @Test
    public void inject_withNullFile_doesNotThrow() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        inlet.inject(null); // must not throw
        assertEquals("queue must stay empty for null inject", 0, inlet.getQueueSize());
    }

    @Test
    public void inject_withMissingFile_doesNotThrow() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        File ghost = new File(tempDir, "does-not-exist.bin");
        inlet.inject(ghost); // must not throw
        assertEquals("queue must stay empty for missing file", 0, inlet.getQueueSize());
    }

    // ------------------------------------------------------------------
    // getNextObject
    // ------------------------------------------------------------------

    @Test
    public void getNextObject_returnsInjectedFile() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        File src = createTestFile("payload");
        inlet.inject(src);
        FileObject fo = inlet.getNextObject();
        assertNotNull("getNextObject must return the injected file", fo);
        assertTrue("dequeued file must exist", fo.getFile().exists());
    }

    @Test
    public void getNextObject_reducesQueueSize() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        inlet.inject(createTestFile("data"));
        inlet.getNextObject();
        assertEquals("queue must be empty after dequeue", 0, inlet.getQueueSize());
    }

    @Test
    public void getNextObject_onEmptyQueue_returnsNull() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        assertNull("getNextObject on empty queue must return null", inlet.getNextObject());
    }

    // ------------------------------------------------------------------
    // getStatusHTML
    // ------------------------------------------------------------------

    @Test
    public void statusHTML_showsQueueSize() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        inlet.inject(createTestFile("x"));
        String html = inlet.getStatusHTML();
        assertTrue("status HTML must mention queue size", html.contains("Queue size"));
    }

    @Test
    public void statusHTML_showsStageName() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildElement());
        String html = inlet.getStatusHTML();
        assertTrue("status HTML must show stage name", html.contains("TestInlet"));
    }
}
