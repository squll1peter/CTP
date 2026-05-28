/*---------------------------------------------------------------
*  Copyright 2025 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package org.rsna.ctp.stdstages;

import java.io.File;
import org.apache.log4j.Logger;
import org.rsna.ctp.pipeline.AbstractImportService;
import org.rsna.util.FileUtil;
import org.w3c.dom.Element;

/**
 * An ImportService that accepts objects injected programmatically by
 * ObjectFork or ObjectRouter stages running in other pipelines.
 *
 * <p>Configuration attributes:
 * <ul>
 *   <li>{@code name} (required) – display name</li>
 *   <li>{@code id}   (required) – unique stage ID used by fork/router targets</li>
 *   <li>{@code root} (required) – queue root directory</li>
 *   <li>{@code enable} (optional, default "yes") – set to "no" to disable</li>
 * </ul>
 */
public class ObjectInlet extends AbstractImportService {

	static final Logger logger = Logger.getLogger(ObjectInlet.class);

	/**
	 * Construct an ObjectInlet from its XML configuration element.
	 * @param element the XML element describing this stage
	 * @throws Exception if the base class constructor fails
	 */
	public ObjectInlet(Element element) throws Exception {
		super(element);
	}

	/**
	 * Inject a file into this inlet's processing queue.
	 * The source file is copied to a temporary file in the stage's temp
	 * directory; then {@link #fileReceived(File)} enqueues the copy and
	 * deletes it from temp.  The caller's original file is never modified.
	 *
	 * @param srcFile the file to enqueue; must exist and be non-null
	 */
	public synchronized void inject(File srcFile) {
		if (srcFile == null || !srcFile.exists()) {
			logger.warn(name + ": inject called with null or missing source file");
			return;
		}
		File tempDir = getTempDirectory();
		if (tempDir == null) {
			logger.error(name + ": cannot inject – root directory is not configured");
			return;
		}
		try {
			File tempFile = File.createTempFile("INLET-", ".tmp", tempDir);
			if (FileUtil.copy(srcFile, tempFile)) {
				fileReceived(tempFile);
			} else {
				logger.error(name + ": failed to copy file for injection: " + srcFile);
				tempFile.delete();
			}
		} catch (Exception ex) {
			logger.error(name + ": exception during inject from " + srcFile, ex);
		}
	}
}
