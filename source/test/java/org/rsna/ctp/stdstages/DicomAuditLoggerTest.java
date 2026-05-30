package org.rsna.ctp.stdstages;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.LinkedList;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.stdplugins.AuditLog;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Tests for DicomAuditLogger level-specific AuditLog indexing.
 */
public class DicomAuditLoggerTest {

	private File tempDir;

	@Before
	public void setUp() throws Exception {
		tempDir = Files.createTempDirectory("dal-test").toFile();
	}

	@After
	public void tearDown() {
		deleteRecursive(tempDir);
	}

	private void deleteRecursive(File file) {
		if (file == null || !file.exists()) return;
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) deleteRecursive(child);
			}
		}
		file.delete();
	}

	private Element buildElement(String level) throws Exception {
		Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
		Element pipeline = doc.createElement("Pipeline");
		pipeline.setAttribute("root", "");
		doc.appendChild(pipeline);
		Element el = doc.createElement("Processor");
		el.setAttribute("name", "DicomAuditLogger");
		el.setAttribute("root", tempDir.getAbsolutePath());
		el.setAttribute("level", level);
		el.setAttribute("auditLogID", "audit");
		el.setAttribute("auditLogTags", "PatientID");
		pipeline.appendChild(el);
		return el;
	}

	private DicomAuditLogger buildLogger(String level, AuditLog auditLog) throws Exception {
		DicomAuditLogger logger = new DicomAuditLogger(buildElement(level));
		Field auditLogField = DicomAuditLogger.class.getDeclaredField("auditLog");
		auditLogField.setAccessible(true);
		auditLogField.set(logger, auditLog);
		return logger;
	}

	private DicomObject mockDicom(String patientID, String studyUID, String objectUID) throws Exception {
		DicomObject dob = mock(DicomObject.class);
		when(dob.getFile()).thenReturn(File.createTempFile("dicom", ".dcm", tempDir));
		when(dob.getPatientID()).thenReturn(patientID);
		when(dob.getStudyInstanceUID()).thenReturn(studyUID);
		when(dob.getSOPInstanceUID()).thenReturn(objectUID);
		when(dob.getSOPClassName()).thenReturn("CT Image Storage");
		when(dob.getElementValue(anyInt(), eq(""))).thenReturn("");
		when(dob.getElementValue(anyString(), eq(""))).thenReturn("");
		when(dob.getElementValue(eq("PatientID"), eq(""))).thenReturn(patientID);
		return dob;
	}

	@Test
	public void process_studyLevel_indexesStudyButNotObjectUID() throws Exception {
		AuditLog auditLog = mock(AuditLog.class);
		when(auditLog.getEntriesForStudyUID("STUDY-1")).thenReturn(new LinkedList<Integer>());
		when(auditLog.addEntry(anyString(), eq("xml"), eq("PAT-1"), eq("STUDY-1"), isNull())).thenReturn(Integer.valueOf(1));
		DicomAuditLogger logger = buildLogger("study", auditLog);

		logger.process(mockDicom("PAT-1", "STUDY-1", "OBJECT-1"));

		verify(auditLog).addEntry(anyString(), eq("xml"), eq("PAT-1"), eq("STUDY-1"), isNull());
	}

	@Test
	public void process_defaultLevel_indexesObjectUID() throws Exception {
		AuditLog auditLog = mock(AuditLog.class);
		when(auditLog.addEntry(anyString(), eq("xml"), eq("PAT-1"), eq("STUDY-1"), eq("OBJECT-1"))).thenReturn(Integer.valueOf(1));
		DicomAuditLogger logger = buildLogger("", auditLog);

		logger.process(mockDicom("PAT-1", "STUDY-1", "OBJECT-1"));

		verify(auditLog).addEntry(anyString(), eq("xml"), eq("PAT-1"), eq("STUDY-1"), eq("OBJECT-1"));
	}

	@Test
	public void process_studyLevelWithCachedObject_doesNotReferenceObjectUID() throws Exception {
		AuditLog auditLog = mock(AuditLog.class);
		when(auditLog.getEntriesForStudyUID("CACHED-STUDY")).thenReturn(new LinkedList<Integer>());
		when(auditLog.addEntry(anyString(), eq("xml"), eq("CACHED-PAT"), eq("CACHED-STUDY"), isNull())).thenReturn(Integer.valueOf(7));
		DicomAuditLogger logger = buildLogger("study", auditLog);
		ObjectCache cache = mock(ObjectCache.class);
		when(cache.getID()).thenReturn("ObjectCache");
		FileObject cachedObject = mockDicom("CACHED-PAT", "CACHED-STUDY", "CACHED-OBJECT");
		when(cache.getCachedObject()).thenReturn(cachedObject);
		Field cacheField = DicomAuditLogger.class.getDeclaredField("objectCache");
		cacheField.setAccessible(true);
		cacheField.set(logger, cache);

		logger.process(mockDicom("CURRENT-PAT", "CURRENT-STUDY", "CURRENT-OBJECT"));

		verify(auditLog).addEntry(anyString(), eq("xml"), eq("CACHED-PAT"), eq("CACHED-STUDY"), isNull());
		verify(auditLog).addEntryReference(eq(Integer.valueOf(7)), eq("CURRENT-PAT"), eq("CURRENT-STUDY"), isNull());
	}
}
