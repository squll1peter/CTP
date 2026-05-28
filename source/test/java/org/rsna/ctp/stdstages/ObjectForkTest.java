package org.rsna.ctp.stdstages;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Tests for ObjectFork: pass-through contract, fork logic, script filtering,
 * disabled mode, non-DICOM handling, target ID deduplication, and status HTML.
 *
 * start() (which requires Configuration.getInstance()) is NOT called;
 * resolvedTargets is wired directly via reflection where needed.
 */
public class ObjectForkTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("fork-test").toFile();
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
    // Helpers
    // ------------------------------------------------------------------

    private Element buildForkElement(String... attrPairs) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element pipeline = doc.createElement("Pipeline");
        pipeline.setAttribute("root", "");
        doc.appendChild(pipeline);
        Element el = doc.createElement("Processor");
        el.setAttribute("name", "TestFork");
        el.setAttribute("id", "fork1");
        el.setAttribute("root", new File(tempDir, "fork-root").getAbsolutePath());
        for (int i = 0; i < attrPairs.length; i += 2) {
            el.setAttribute(attrPairs[i], attrPairs[i + 1]);
        }
        pipeline.appendChild(el);
        return el;
    }

    private Element buildInletElement(String id, String subdir) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element pipeline = doc.createElement("Pipeline");
        pipeline.setAttribute("root", "");
        doc.appendChild(pipeline);
        Element el = doc.createElement("ImportService");
        el.setAttribute("name", "TestInlet-" + id);
        el.setAttribute("id", id);
        el.setAttribute("root", new File(tempDir, subdir).getAbsolutePath());
        pipeline.appendChild(el);
        return el;
    }

    private File createTestFile(String content) throws Exception {
        File f = File.createTempFile("src-", ".bin", tempDir);
        Files.write(f.toPath(), content.getBytes("UTF-8"));
        return f;
    }

    /** Wire inlets into fork's resolvedTargets via reflection. */
    @SuppressWarnings("unchecked")
    private void wireTargets(ObjectFork fork, ObjectInlet... inlets) throws Exception {
        Field f = ObjectFork.class.getDeclaredField("resolvedTargets");
        f.setAccessible(true);
        List<ObjectInlet> list = (List<ObjectInlet>) f.get(fork);
        list.clear();
        for (ObjectInlet inlet : inlets) {
            list.add(inlet);
        }
    }

    /** Create a mock FileObject backed by a real temp file. */
    private FileObject mockFileObject(File backingFile) {
        FileObject fo = mock(FileObject.class);
        when(fo.getFile()).thenReturn(backingFile);
        return fo;
    }

    /** Create a mock DicomObject backed by a real temp file with controlled matches() result. */
    private DicomObject mockDicom(File backingFile, boolean matchesResult) {
        DicomObject dob = mock(DicomObject.class);
        when(dob.getFile()).thenReturn(backingFile);
        when(dob.matches(nullable(File.class))).thenReturn(matchesResult);
        return dob;
    }

    // ------------------------------------------------------------------
    // Pass-through contract
    // ------------------------------------------------------------------

    @Test
    public void process_alwaysReturnsOriginalObject_noTargets() throws Exception {
        ObjectFork fork = new ObjectFork(buildForkElement());
        FileObject fo = mockFileObject(createTestFile("x"));
        assertSame("process() must return the original object", fo, fork.process(fo));
    }

    @Test
    public void process_alwaysReturnsOriginalObject_withTargets() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectFork fork = new ObjectFork(buildForkElement("targets", "i1"));
        wireTargets(fork, inlet);
        File srcFile = createTestFile("payload");
        FileObject fo = mockFileObject(srcFile);
        assertSame("process() must return the original object even when forking", fo, fork.process(fo));
    }

    @Test
    public void process_whenDisabled_returnsObject() throws Exception {
        ObjectFork fork = new ObjectFork(buildForkElement("enable", "no"));
        FileObject fo = mockFileObject(createTestFile("x"));
        assertSame("disabled fork must still return the original object", fo, fork.process(fo));
    }

    // ------------------------------------------------------------------
    // Fork behaviour
    // ------------------------------------------------------------------

    @Test
    public void process_withResolvedTargets_injectsIntoAll() throws Exception {
        ObjectInlet inlet1 = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectInlet inlet2 = new ObjectInlet(buildInletElement("i2", "i2-root"));
        ObjectFork fork = new ObjectFork(buildForkElement("targets", "i1;i2"));
        wireTargets(fork, inlet1, inlet2);

        File src = createTestFile("payload");
        fork.process(mockFileObject(src));

        assertEquals("inlet1 queue must have one entry", 1, inlet1.getQueueSize());
        assertEquals("inlet2 queue must have one entry", 1, inlet2.getQueueSize());
    }

    @Test
    public void process_whenDisabled_doesNotFork() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectFork fork = new ObjectFork(buildForkElement("enable", "no", "targets", "i1"));
        wireTargets(fork, inlet);

        fork.process(mockFileObject(createTestFile("x")));

        assertEquals("disabled fork must not inject into target", 0, inlet.getQueueSize());
    }

    // ------------------------------------------------------------------
    // Script-controlled filtering
    // ------------------------------------------------------------------

    @Test
    public void process_withScript_dicom_matchTrue_forks() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        // A non-empty "script" attr pointing at a non-existent path yields a non-null
        // scriptFile reference so the `scriptFile != null` guard is exercised.
        // The mock DicomObject overrides matches(), so the file content is irrelevant.
        File fakeScript = new File(tempDir, "fake.script");
        Files.write(fakeScript.toPath(), "true".getBytes("UTF-8"));
        ObjectFork fork = new ObjectFork(buildForkElement("targets", "i1",
                "script", fakeScript.getAbsolutePath()));
        wireTargets(fork, inlet);

        DicomObject dob = mockDicom(createTestFile("dcm"), true);
        fork.process(dob);

        assertEquals("matching DICOM must be forked", 1, inlet.getQueueSize());
    }

    @Test
    public void process_withScript_dicom_matchFalse_doesNotFork() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        File fakeScript = new File(tempDir, "fake.script");
        Files.write(fakeScript.toPath(), "false".getBytes("UTF-8"));
        ObjectFork fork = new ObjectFork(buildForkElement("targets", "i1",
                "script", fakeScript.getAbsolutePath()));
        wireTargets(fork, inlet);

        DicomObject dob = mockDicom(createTestFile("dcm"), false);
        fork.process(dob);

        assertEquals("non-matching DICOM must not be forked", 0, inlet.getQueueSize());
    }

    @Test
    public void process_withScript_nonDicom_alwaysForked() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        File fakeScript = new File(tempDir, "fake.script");
        Files.write(fakeScript.toPath(), "false".getBytes("UTF-8"));
        ObjectFork fork = new ObjectFork(buildForkElement("targets", "i1",
                "script", fakeScript.getAbsolutePath()));
        wireTargets(fork, inlet);

        // Non-DICOM FileObject: matches() is never consulted
        FileObject fo = mockFileObject(createTestFile("notdcm"));
        fork.process(fo);

        assertEquals("non-DICOM objects must always be forked regardless of script", 1, inlet.getQueueSize());
    }

    @Test
    public void process_noScript_dicom_alwaysForked() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectFork fork = new ObjectFork(buildForkElement("targets", "i1"));
        wireTargets(fork, inlet);

        // mock DicomObject: without a script the fork should not consult matches() at all
        DicomObject dob = mock(DicomObject.class);
        when(dob.getFile()).thenReturn(createTestFile("dcm"));
        fork.process(dob);

        assertEquals("DICOM with no script must always be forked", 1, inlet.getQueueSize());
        verify(dob, never()).matches(nullable(File.class));
    }

    // ------------------------------------------------------------------
    // Target ID deduplication
    // ------------------------------------------------------------------

    @Test
    public void constructor_deduplicatesTargetIDs() throws Exception {
        ObjectFork fork = new ObjectFork(buildForkElement("targets", "a;b;a;c;b"));
        Field f = ObjectFork.class.getDeclaredField("targetIDs");
        f.setAccessible(true);
        String[] ids = (String[]) f.get(fork);
        assertEquals("duplicate IDs must be removed", 3, ids.length);
        assertEquals("a", ids[0]);
        assertEquals("b", ids[1]);
        assertEquals("c", ids[2]);
    }

    @Test
    public void constructor_emptyTargets_yieldsEmptyArray() throws Exception {
        ObjectFork fork = new ObjectFork(buildForkElement());
        Field f = ObjectFork.class.getDeclaredField("targetIDs");
        f.setAccessible(true);
        String[] ids = (String[]) f.get(fork);
        assertEquals("no targets attribute must yield empty array", 0, ids.length);
    }

    // ------------------------------------------------------------------
    // Counters and timing
    // ------------------------------------------------------------------

    @Test
    public void forkedCount_incrementsOnFork() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectFork fork = new ObjectFork(buildForkElement("targets", "i1"));
        wireTargets(fork, inlet);

        fork.process(mockFileObject(createTestFile("a")));
        fork.process(mockFileObject(createTestFile("b")));

        Field f = ObjectFork.class.getDeclaredField("forkedCount");
        f.setAccessible(true);
        assertEquals("forkedCount must equal number of fork operations", 2, f.get(fork));
    }

    @Test
    public void lastForkTime_updatedAfterFork() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectFork fork = new ObjectFork(buildForkElement("targets", "i1"));
        wireTargets(fork, inlet);

        long before = System.currentTimeMillis();
        fork.process(mockFileObject(createTestFile("x")));
        long after = System.currentTimeMillis();

        Field f = ObjectFork.class.getDeclaredField("lastForkTime");
        f.setAccessible(true);
        long ts = (long) f.get(fork);
        assertTrue("lastForkTime must be between before and after", ts >= before && ts <= after);
    }

    // ------------------------------------------------------------------
    // getStatusHTML
    // ------------------------------------------------------------------

    @Test
    public void statusHTML_containsStageName() throws Exception {
        ObjectFork fork = new ObjectFork(buildForkElement());
        assertTrue("status HTML must contain stage name", fork.getStatusHTML().contains("TestFork"));
    }

    @Test
    public void statusHTML_showsEnabledState() throws Exception {
        ObjectFork fork = new ObjectFork(buildForkElement("enable", "no"));
        assertTrue("status HTML must show disabled state", fork.getStatusHTML().contains("false"));
    }

    @Test
    public void statusHTML_showsLastForkNever_initially() throws Exception {
        ObjectFork fork = new ObjectFork(buildForkElement());
        assertTrue("status HTML must show 'Never' before any fork",
                fork.getStatusHTML().contains("Last fork at:</td><td>Never"));
    }
}
