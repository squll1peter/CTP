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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * Tests for ObjectRouter: pass-through vs. divert routing, script control,
 * disabled mode, non-DICOM pass-through, quarantine fallback, counters,
 * and status HTML.
 *
 * start() (which requires Configuration.getInstance()) is NOT called;
 * resolvedTarget is wired directly via reflection where needed.
 */
public class ObjectRouterTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("router-test").toFile();
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

    private Element buildRouterElement(String... attrPairs) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element pipeline = doc.createElement("Pipeline");
        pipeline.setAttribute("root", "");
        doc.appendChild(pipeline);
        Element el = doc.createElement("Processor");
        el.setAttribute("name", "TestRouter");
        el.setAttribute("id", "router1");
        el.setAttribute("root", new File(tempDir, "router-root").getAbsolutePath());
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

    /** Wire the resolved target into the router via reflection. */
    private void wireTarget(ObjectRouter router, ObjectInlet inlet) throws Exception {
        Field f = ObjectRouter.class.getDeclaredField("resolvedTarget");
        f.setAccessible(true);
        f.set(router, inlet);
    }

    /** Create a mock DicomObject backed by a real temp file with controlled matches() result. */
    private DicomObject mockDicom(File backingFile, boolean matchesResult) {
        DicomObject dob = mock(DicomObject.class);
        when(dob.getFile()).thenReturn(backingFile);
        when(dob.matches(nullable(File.class))).thenReturn(matchesResult);
        return dob;
    }

    /** Create a mock FileObject backed by a real temp file. */
    private FileObject mockFileObject(File backingFile) {
        FileObject fo = mock(FileObject.class);
        when(fo.getFile()).thenReturn(backingFile);
        return fo;
    }

    // ------------------------------------------------------------------
    // Non-DICOM always passes through
    // ------------------------------------------------------------------

    @Test
    public void process_nonDicom_passesThrough() throws Exception {
        ObjectRouter router = new ObjectRouter(buildRouterElement());
        FileObject fo = mockFileObject(createTestFile("text"));
        assertSame("non-DICOM must pass through", fo, router.process(fo));
    }

    @Test
    public void process_nonDicom_incrementsPassedThrough() throws Exception {
        ObjectRouter router = new ObjectRouter(buildRouterElement());
        router.process(mockFileObject(createTestFile("a")));
        Field f = ObjectRouter.class.getDeclaredField("passedThroughCount");
        f.setAccessible(true);
        assertEquals(1, (int) f.get(router));
    }

    // ------------------------------------------------------------------
    // Disabled mode
    // ------------------------------------------------------------------

    @Test
    public void process_disabled_passesThrough() throws Exception {
        ObjectRouter router = new ObjectRouter(buildRouterElement("enable", "no"));
        DicomObject dob = mockDicom(createTestFile("dcm"), false); // would divert if enabled
        assertSame("disabled router must pass everything through", dob, router.process(dob));
    }

    @Test
    public void process_disabled_doesNotInjectIntoInlet() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectRouter router = new ObjectRouter(buildRouterElement("enable", "no", "target", "i1"));
        wireTarget(router, inlet);

        DicomObject dob = mockDicom(createTestFile("dcm"), false);
        router.process(dob);

        assertEquals("disabled router must not inject into target", 0, inlet.getQueueSize());
    }

    // ------------------------------------------------------------------
    // DICOM pass-through (script matches)
    // ------------------------------------------------------------------

    @Test
    public void process_dicom_matches_passesThrough() throws Exception {
        File script = new File(tempDir, "pass.script");
        Files.write(script.toPath(), "true".getBytes("UTF-8"));
        ObjectRouter router = new ObjectRouter(buildRouterElement("script", script.getAbsolutePath()));

        DicomObject dob = mockDicom(createTestFile("dcm"), true);
        assertSame("DICOM matching script must pass through", dob, router.process(dob));
    }

    @Test
    public void process_dicom_matches_returnsNonNull() throws Exception {
        ObjectRouter router = new ObjectRouter(buildRouterElement());
        DicomObject dob = mockDicom(createTestFile("dcm"), true);
        assertNotNull(router.process(dob));
    }

    // ------------------------------------------------------------------
    // DICOM divert path (script does not match)
    // ------------------------------------------------------------------

    @Test
    public void process_dicom_noMatch_returnsNull() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        File script = new File(tempDir, "reject.script");
        Files.write(script.toPath(), "false".getBytes("UTF-8"));
        ObjectRouter router = new ObjectRouter(buildRouterElement("script", script.getAbsolutePath()));
        wireTarget(router, inlet);

        DicomObject dob = mockDicom(createTestFile("dcm"), false);
        assertNull("non-matching DICOM must be diverted (null return)", router.process(dob));
    }

    @Test
    public void process_dicom_noMatch_withTarget_injectsIntoInlet() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectRouter router = new ObjectRouter(buildRouterElement("target", "i1"));
        wireTarget(router, inlet);

        DicomObject dob = mockDicom(createTestFile("dcm"), false);
        router.process(dob);

        assertEquals("non-matching DICOM must be injected into target inlet", 1, inlet.getQueueSize());
    }

    @Test
    public void process_dicom_noMatch_noTarget_incrementsQuarantined() throws Exception {
        // No resolved target, no quarantine configured → quarantinedCount increments
        ObjectRouter router = new ObjectRouter(buildRouterElement("target", "missing-inlet"));
        // resolvedTarget stays null (start() not called)

        DicomObject dob = mockDicom(createTestFile("dcm"), false);
        router.process(dob);

        Field f = ObjectRouter.class.getDeclaredField("quarantinedCount");
        f.setAccessible(true);
        assertEquals("quarantinedCount must increment when no target available", 1, (int) f.get(router));
    }

    // ------------------------------------------------------------------
    // Counters
    // ------------------------------------------------------------------

    @Test
    public void counts_trackCorrectly() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectRouter router = new ObjectRouter(buildRouterElement("target", "i1"));
        wireTarget(router, inlet);

        // 2 pass-through (non-DICOM), 2 non-matching DICOM (diverted), 1 matching DICOM
        router.process(mockFileObject(createTestFile("a")));
        router.process(mockFileObject(createTestFile("b")));
        router.process(mockDicom(createTestFile("c"), false));
        router.process(mockDicom(createTestFile("d"), false));
        router.process(mockDicom(createTestFile("e"), true));

        Field countF = ObjectRouter.class.getDeclaredField("count");
        countF.setAccessible(true);
        assertEquals("total count must be 5", 5, (int) countF.get(router));

        Field passedF = ObjectRouter.class.getDeclaredField("passedThroughCount");
        passedF.setAccessible(true);
        assertEquals("passedThroughCount must be 3 (2 non-DICOM + 1 matching)", 3, (int) passedF.get(router));

        Field divertedF = ObjectRouter.class.getDeclaredField("divertedCount");
        divertedF.setAccessible(true);
        assertEquals("divertedCount must be 2", 2, (int) divertedF.get(router));
    }

    @Test
    public void lastDivertTime_updatedAfterDivert() throws Exception {
        ObjectInlet inlet = new ObjectInlet(buildInletElement("i1", "i1-root"));
        ObjectRouter router = new ObjectRouter(buildRouterElement("target", "i1"));
        wireTarget(router, inlet);

        long before = System.currentTimeMillis();
        router.process(mockDicom(createTestFile("dcm"), false));
        long after = System.currentTimeMillis();

        Field f = ObjectRouter.class.getDeclaredField("lastDivertTime");
        f.setAccessible(true);
        long ts = (long) f.get(router);
        assertTrue("lastDivertTime must be between before and after", ts >= before && ts <= after);
    }

    // ------------------------------------------------------------------
    // getStatusHTML
    // ------------------------------------------------------------------

    @Test
    public void statusHTML_containsStageName() throws Exception {
        ObjectRouter router = new ObjectRouter(buildRouterElement());
        assertTrue("status HTML must contain stage name", router.getStatusHTML().contains("TestRouter"));
    }

    @Test
    public void statusHTML_showsEnabledState() throws Exception {
        ObjectRouter router = new ObjectRouter(buildRouterElement("enable", "no"));
        assertTrue("status HTML must show disabled state", router.getStatusHTML().contains("false"));
    }

    @Test
    public void statusHTML_showsLastDivertNever_initially() throws Exception {
        ObjectRouter router = new ObjectRouter(buildRouterElement());
        assertTrue("status HTML must show 'Never' before any divert",
                router.getStatusHTML().contains("Last divert at:</td><td>Never"));
    }

    @Test
    public void statusHTML_showsTargetNotFound_whenUnresolved() throws Exception {
        ObjectRouter router = new ObjectRouter(buildRouterElement("target", "missing-inlet"));
        assertTrue("status HTML must show target not found when unresolved",
                router.getStatusHTML().contains("not found"));
    }
}
