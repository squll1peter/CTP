/*---------------------------------------------------------------
*  Copyright 2026 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package org.rsna.ctp.stdstages;

import java.io.File;
import org.apache.log4j.Logger;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.w3c.dom.Element;

/**
 * A DicomAuditLogger variant that can selectively log DICOM objects
 * using an optional dicomScript filter.
 *
 * If dicomScript is not configured, this stage behaves exactly like
 * DicomAuditLogger.
 */
public class DicomConditionalAuditLogger extends DicomAuditLogger implements Scriptable {

	static final Logger logger = Logger.getLogger(DicomConditionalAuditLogger.class);

	private final String dicomScriptAttribute;
	private File dicomScriptFile = null;

	/**
	 * Construct the DicomConditionalAuditLogger PipelineStage.
	 * @param element the XML element from the configuration file
	 * specifying the configuration of the stage.
	 */
	public DicomConditionalAuditLogger(Element element) {
		super(element);
		dicomScriptAttribute = element.getAttribute("dicomScript").trim();
		dicomScriptFile = resolveConfiguredScriptFile(dicomScriptAttribute);
	}

	@Override
	public void start() {
		super.start();

		// Fail open: if script is configured but invalid, warn and disable filtering.
		if (!dicomScriptAttribute.equals("")) {
			if ((dicomScriptFile == null) || !dicomScriptFile.exists() || !dicomScriptFile.isFile()) {
				logger.warn(name + ": dicomScript \"" + dicomScriptAttribute
					+ "\" was configured but is not readable; filtering disabled (fail-open)");
				dicomScriptFile = null;
			}
		}
	}

	@Override
	public FileObject process(FileObject fileObject) {
		if (!(fileObject instanceof DicomObject)) {
			return super.process(fileObject);
		}

		if (dicomScriptFile == null) {
			return super.process(fileObject);
		}

		DicomObject currentObject = (DicomObject)fileObject;
		if (currentObject.matches(dicomScriptFile)) {
			return super.process(fileObject);
		}

		// Script did not match: pass through unchanged, no audit entry.
		lastFileIn = new File(fileObject.getFile().getAbsolutePath());
		lastTimeIn = System.currentTimeMillis();
		lastFileOut = new File(fileObject.getFile().getAbsolutePath());
		lastTimeOut = System.currentTimeMillis();
		return fileObject;
	}

	@Override
	public File[] getScriptFiles() {
		return new File[] { dicomScriptFile, null, null };
	}

	private File resolveConfiguredScriptFile(String scriptPath) {
		if (scriptPath.equals("")) return null;

		File file = new File(scriptPath);
		if (!file.isAbsolute() && !pipelinePath.equals("")) {
			file = new File(pipelineRoot, scriptPath);
		}
		return file;
	}
}
