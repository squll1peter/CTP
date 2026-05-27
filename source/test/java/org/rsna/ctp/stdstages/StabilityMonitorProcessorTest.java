package org.rsna.ctp.stdstages;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import org.rsna.ctp.pipeline.AbstractPipelineStage;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

/**
 * Tests for StabilityMonitorProcessor: GroupRecord stability logic,
 * sanitizeKey collision-prevention and length-capping, getGroupKey
 * level dispatch, repDir creation, non-DICOM pass-through, and
 * the shutdown clean-up path.
 *
 * start() (which requires Configuration.getInstance()) is NOT called
 * in any test; the Notifier thread is therefore never running.
 */
public class StabilityMonitorProcessorTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("smp-test").toFile();
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

    /**
     * Builds a minimal stage element whose "root" attribute points to
     * tempDir, so repDir is created there rather than in the CWD.
     */
    private Element buildElement(String... attrPairs) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element pipeline = doc.createElement("Pipeline");
        pipeline.setAttribute("root", "");          // pipelinePath — can be empty
        doc.appendChild(pipeline);
        Element el = doc.createElement("Processor");
        el.setAttribute("name", "TestProcessor");
        el.setAttribute("root", tempDir.getAbsolutePath()); // stage root → controls repDir
        for (int i = 0; i < attrPairs.length; i += 2) {
            el.setAttribute(attrPairs[i], attrPairs[i + 1]);
        }
        pipeline.appendChild(el);
        return el;
    }

    // ------------------------------------------------------------------
    // Reflection helpers for private methods
    // ------------------------------------------------------------------

    private String invokeSanitizeKey(StabilityMonitorProcessor proc, String key) throws Exception {
        Method m = StabilityMonitorProcessor.class.getDeclaredMethod("sanitizeKey", String.class);
        m.setAccessible(true);
        return (String) m.invoke(proc, key);
    }

    private String invokeGetGroupKey(StabilityMonitorProcessor proc, DicomObject dob) throws Exception {
        Method m = StabilityMonitorProcessor.class.getDeclaredMethod("getGroupKey", DicomObject.class);
        m.setAccessible(true);
        return (String) m.invoke(proc, dob);
    }

    // ------------------------------------------------------------------
    // GroupRecord.isStable
    // ------------------------------------------------------------------

    @Test
    public void groupRecord_isStable_returnsFalse_whenTimeoutNotElapsed() {
        StabilityMonitorProcessor.GroupRecord record = new StabilityMonitorProcessor.GroupRecord("key", new File("rep.dcm"), System.currentTimeMillis());
        assertFalse("record created now must not yet be stable with a 60-second timeout",
                record.isStable(60_000L));
    }

    @Test
    public void groupRecord_isStable_returnsTrue_whenTimeoutElapsed() {
        long ninetySecondsAgo = System.currentTimeMillis() - 90_000L;
        StabilityMonitorProcessor.GroupRecord record = new StabilityMonitorProcessor.GroupRecord("key", new File("rep.dcm"), ninetySecondsAgo);
        assertTrue("record last-seen 90 s ago must be stable with a 60-second timeout",
                record.isStable(60_000L));
    }

    @Test
    public void groupRecord_lastSeen_isVolatile_updatable() {
        StabilityMonitorProcessor.GroupRecord record = new StabilityMonitorProcessor.GroupRecord("key", new File("rep.dcm"), System.currentTimeMillis() - 90_000L);
        assertTrue("starts stable", record.isStable(60_000L));
        // Simulate new activity: reset lastSeen
        record.lastSeen = System.currentTimeMillis();
        assertFalse("after touching lastSeen the record must no longer be stable",
                record.isStable(60_000L));
    }

    // ------------------------------------------------------------------
    // sanitizeKey
    // ------------------------------------------------------------------

    @Test
    public void sanitizeKey_preservesAllowedCharsInDicomUid() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        String result = invokeSanitizeKey(proc, "1.2.840.10008.5.1.4.1");
        // Dots are allowed by the regex [a-zA-Z0-9._-]; they must NOT be replaced
        assertTrue("dots in DICOM UIDs must be preserved", result.contains("."));
        assertFalse("sanitized key must not contain /", result.contains("/"));
    }

    @Test
    public void sanitizeKey_replacesSpacesWithUnderscore() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        String result = invokeSanitizeKey(proc, "Patient Name");
        assertFalse("spaces must be replaced", result.contains(" "));
        // The cleaned part should contain underscore in place of the space
        assertTrue("space must be replaced by underscore", result.contains("Patient_Name"));
    }

    @Test
    public void sanitizeKey_addHashPrefix_preventsCrossKeyCollision() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        // Both keys clean to the same string but have different hashCodes
        String result1 = invokeSanitizeKey(proc, "ABC DEF"); // "ABC_DEF" after cleaning
        String result2 = invokeSanitizeKey(proc, "ABC_DEF"); // already clean
        assertNotEquals(
                "Different keys that produce the same cleaned form must yield distinct file names",
                result1, result2);
    }

    @Test
    public void sanitizeKey_capsCleanedPartAt64Chars() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        // 100 alphanumeric chars — all allowed, but the clean part is capped at 64
        String result = invokeSanitizeKey(proc, "a".repeat(100));
        // format: {8-hex-hash}_{≤64-char-clean}  →  max length = 8 + 1 + 64 = 73
        assertTrue("sanitized key must not exceed 73 characters", result.length() <= 73);
    }

    @Test
    public void sanitizeKey_startsWithEightHexDigits() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        String result = invokeSanitizeKey(proc, "some-series-uid");
        // First 8 characters must be hex digits
        String prefix = result.substring(0, 8);
        assertTrue("sanitized key must start with 8 hex digits",
                prefix.matches("[0-9a-f]{8}"));
        assertEquals("9th character must be underscore separator", '_', result.charAt(8));
    }

    // ------------------------------------------------------------------
    // getGroupKey level dispatch
    // ------------------------------------------------------------------

    @Test
    public void getGroupKey_seriesLevel_returnsSeriesInstanceUID() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1", "level", "series"));
        DicomObject dob = Mockito.mock(DicomObject.class);
        when(dob.getSeriesInstanceUID()).thenReturn("1.2.3.series");
        assertEquals("1.2.3.series", invokeGetGroupKey(proc, dob));
    }

    @Test
    public void getGroupKey_studyLevel_returnsStudyInstanceUID() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1", "level", "study"));
        DicomObject dob = Mockito.mock(DicomObject.class);
        when(dob.getStudyInstanceUID()).thenReturn("1.2.3.study");
        assertEquals("1.2.3.study", invokeGetGroupKey(proc, dob));
    }

    @Test
    public void getGroupKey_patientLevel_returnsPatientID() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1", "level", "patient"));
        DicomObject dob = Mockito.mock(DicomObject.class);
        when(dob.getPatientID()).thenReturn("PAT001");
        assertEquals("PAT001", invokeGetGroupKey(proc, dob));
    }

    @Test
    public void getGroupKey_defaultsToSeriesLevel() throws Exception {
        // No "level" attribute — default is "series"
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        DicomObject dob = Mockito.mock(DicomObject.class);
        when(dob.getSeriesInstanceUID()).thenReturn("1.2.3.default");
        assertEquals("1.2.3.default", invokeGetGroupKey(proc, dob));
    }

    // ------------------------------------------------------------------
    // Constructor / repDir creation
    // ------------------------------------------------------------------

    @Test
    public void constructor_createsRepSubdirectory() throws Exception {
        new StabilityMonitorProcessor(buildElement("targetID", "plugin1"));
        File repDir = new File(tempDir, "rep");
        assertTrue("rep/ sub-directory must be created under the stage root",
                repDir.isDirectory());
    }

    // ------------------------------------------------------------------
    // process() — non-DICOM pass-through
    // ------------------------------------------------------------------

    @Test
    public void process_nonDicomObject_passesThrough_unchanged() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        File dummyFile = File.createTempFile("test", ".xml", tempDir);
        FileObject nonDicom = Mockito.mock(FileObject.class);
        when(nonDicom.getFile()).thenReturn(dummyFile);

        FileObject result = proc.process(nonDicom);

        assertSame("non-DICOM object must be returned without modification", nonDicom, result);
    }

    @Test
    public void process_nonDicomObject_doesNotAddToGroupsMap() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        File dummyFile = File.createTempFile("test", ".xml", tempDir);
        FileObject nonDicom = Mockito.mock(FileObject.class);
        when(nonDicom.getFile()).thenReturn(dummyFile);

        proc.process(nonDicom);

        assertTrue("groups map must stay empty for non-DICOM objects",
                proc.getStatusHTML().contains("Active groups:</td><td>0"));
    }

    // ------------------------------------------------------------------
    // process() — DICOM tracking
    // ------------------------------------------------------------------

    @Test
    public void process_dicomObject_addsGroupToMap() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1", "level", "series"));
        File dummyDcm = File.createTempFile("test", ".dcm", tempDir);

        DicomObject mockDob = Mockito.mock(DicomObject.class);
        when(mockDob.getFile()).thenReturn(dummyDcm);
        when(mockDob.matches((File) null)).thenReturn(true);   // no filter script → pass
        when(mockDob.getSeriesInstanceUID()).thenReturn("1.2.3.4");

        proc.process(mockDob);

        assertTrue("one DICOM group must be tracked after processing one object",
                proc.getStatusHTML().contains("Active groups:</td><td>1"));
    }

    @Test
    public void process_dicomObject_returnsSameObjectUnchanged() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        File dummyDcm = File.createTempFile("test", ".dcm", tempDir);

        DicomObject mockDob = Mockito.mock(DicomObject.class);
        when(mockDob.getFile()).thenReturn(dummyDcm);
        when(mockDob.matches((File) null)).thenReturn(true);
        when(mockDob.getSeriesInstanceUID()).thenReturn("1.2.3.4");

        FileObject result = proc.process(mockDob);
        assertSame("DICOM object must be returned unchanged", mockDob, result);
    }

    @Test
    public void process_dicomObject_filteredOut_notTracked() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        File dummyDcm = File.createTempFile("test", ".dcm", tempDir);

        DicomObject mockDob = Mockito.mock(DicomObject.class);
        when(mockDob.getFile()).thenReturn(dummyDcm);
        when(mockDob.matches((File) null)).thenReturn(false);  // filter rejects this object

        proc.process(mockDob);

        assertTrue("rejected DICOM object must not add to groups map",
                proc.getStatusHTML().contains("Active groups:</td><td>0"));
    }

    @Test
    public void process_duplicateDicomObject_sameSeries_countsOnce() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1", "level", "series"));
        File dummyDcm = File.createTempFile("test", ".dcm", tempDir);

        DicomObject mockDob = Mockito.mock(DicomObject.class);
        when(mockDob.getFile()).thenReturn(dummyDcm);
        when(mockDob.matches((File) null)).thenReturn(true);
        when(mockDob.getSeriesInstanceUID()).thenReturn("1.2.3.4");

        proc.process(mockDob);
        proc.process(mockDob);  // second image from the same series

        assertTrue("same-series objects must produce only one group entry",
                proc.getStatusHTML().contains("Active groups:</td><td>1"));
    }

    // ------------------------------------------------------------------
    // shutdown()
    // ------------------------------------------------------------------

    @Test
    public void shutdown_setsStopFlag() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1"));
        Field stopField = AbstractPipelineStage.class.getDeclaredField("stop");
        stopField.setAccessible(true);
        assertFalse("stop must be false before shutdown", (Boolean) stopField.get(proc));
        proc.shutdown();
        assertTrue("stop must be true after shutdown", (Boolean) stopField.get(proc));
    }

    @Test
    public void shutdown_deletesRepFilesForUnsettledGroups() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1", "level", "series"));
        File dummyDcm = File.createTempFile("test", ".dcm", tempDir);

        // Track one DICOM object so a representative file is created in repDir
        DicomObject mockDob = Mockito.mock(DicomObject.class);
        when(mockDob.getFile()).thenReturn(dummyDcm);
        when(mockDob.matches((File) null)).thenReturn(true);
        when(mockDob.getSeriesInstanceUID()).thenReturn("1.2.3.4");
        proc.process(mockDob);

        // repDir must contain the representative copy
        File repDir = new File(tempDir, "rep");
        File[] before = repDir.listFiles();
        assertNotNull(before);
        assertEquals("one rep file must exist before shutdown", 1, before.length);

        proc.shutdown();

        File[] after = repDir.listFiles();
        int remaining = (after == null) ? 0 : after.length;
        assertEquals("all rep files must be deleted by shutdown()", 0, remaining);
    }

    @Test
    public void shutdown_clearsGroupsMap() throws Exception {
        StabilityMonitorProcessor proc = new StabilityMonitorProcessor(
                buildElement("targetID", "plugin1", "level", "series"));
        File dummyDcm = File.createTempFile("test", ".dcm", tempDir);

        DicomObject mockDob = Mockito.mock(DicomObject.class);
        when(mockDob.getFile()).thenReturn(dummyDcm);
        when(mockDob.matches((File) null)).thenReturn(true);
        when(mockDob.getSeriesInstanceUID()).thenReturn("1.2.3.4");
        proc.process(mockDob);

        proc.shutdown();

        assertTrue("groups map must be empty after shutdown()",
                proc.getStatusHTML().contains("Active groups:</td><td>0"));
    }
}
