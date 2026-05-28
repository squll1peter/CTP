/*---------------------------------------------------------------
*  Copyright 2025 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package org.rsna.ctp.stdstages;

import java.io.File;
import java.util.ArrayList;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.pipeline.AbstractPipelineStage;
import org.rsna.ctp.pipeline.Pipeline;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.pipeline.Processor;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;

/**
 * A Processor stage that routes DICOM objects to an alternate pipeline
 * based on a filter script.  Objects that <em>match</em> the script pass
 * downstream unchanged.  Objects that do <em>not</em> match are diverted to
 * a configured {@link ObjectInlet}; if no inlet is available they are
 * quarantined.  Non-DICOM objects always pass through.
 *
 * <p>Configuration attributes:
 * <ul>
 *   <li>{@code name}      (required) – display name</li>
 *   <li>{@code id}        (required) – unique stage ID</li>
 *   <li>{@code root}      (required) – working directory</li>
 *   <li>{@code script}    (required) – DICOM filter script</li>
 *   <li>{@code target}    (required) – ID of the {@link ObjectInlet} to
 *       divert non-matching objects to</li>
 *   <li>{@code quarantine}(optional) – quarantine directory</li>
 *   <li>{@code enable}    (optional, default "yes")</li>
 * </ul>
 */
public class ObjectRouter extends AbstractPipelineStage implements Processor, Scriptable {

	static final Logger logger = Logger.getLogger(ObjectRouter.class);

	boolean enabled;
	File scriptFile;
	String targetID;
	ObjectInlet resolvedTarget = null;

	volatile int count = 0;
	volatile int passedThroughCount = 0;
	volatile int divertedCount = 0;
	volatile int quarantinedCount = 0;
	volatile long lastDivertTime = 0;

	/**
	 * Construct an ObjectRouter from its XML configuration element.
	 * @param element the XML element describing this stage
	 */
	public ObjectRouter(Element element) {
		super(element);
		enabled = !element.getAttribute("enable").trim().equals("no");
		scriptFile = getFilterScriptFile(element.getAttribute("script"));
		targetID = element.getAttribute("target").trim();
	}

	/**
	 * Resolve the configured target ID to a live {@link ObjectInlet} instance.
	 * Called by the pipeline when all stages have been constructed.
	 */
	@Override
	public synchronized void start() {
		resolvedTarget = findInlet(targetID);
		if (resolvedTarget == null && !targetID.isEmpty()) {
			logger.warn(name + ": target \"" + targetID + "\" not found or is not an ObjectInlet");
		}
	}

	/**
	 * Route the object.
	 *
	 * <ul>
	 *   <li>If disabled or non-DICOM: pass through unchanged.</li>
	 *   <li>If DICOM and matches script: pass through unchanged.</li>
	 *   <li>If DICOM and does not match: divert to the target inlet
	 *       (or quarantine if no inlet), return {@code null}.</li>
	 * </ul>
	 *
	 * @param fileObject the incoming object
	 * @return {@code fileObject} if passed through, {@code null} if diverted
	 */
	public FileObject process(FileObject fileObject) {
		lastFileIn = new File(fileObject.getFile().getAbsolutePath());
		lastTimeIn = System.currentTimeMillis();
		count++;

		// Non-DICOM or disabled: always pass through
		if (!enabled || !(fileObject instanceof DicomObject)) {
			passedThroughCount++;
			lastFileOut = new File(fileObject.getFile().getAbsolutePath());
			lastTimeOut = System.currentTimeMillis();
			return fileObject;
		}

		// DICOM: evaluate the filter script
		boolean passes = ((DicomObject) fileObject).matches(scriptFile);
		if (passes) {
			passedThroughCount++;
			lastFileOut = new File(fileObject.getFile().getAbsolutePath());
			lastTimeOut = System.currentTimeMillis();
			return fileObject;
		}

		// Divert: inject into target inlet, or quarantine if none is available
		ObjectInlet target;
		synchronized (this) {
			target = resolvedTarget;
		}
		if (target != null) {
			target.inject(fileObject.getFile());
			divertedCount++;
			lastDivertTime = System.currentTimeMillis();
		} else {
			if (quarantine != null) quarantine.insert(fileObject);
			quarantinedCount++;
		}

		lastFileOut = null;
		lastTimeOut = System.currentTimeMillis();
		return null;
	}

	/**
	 * Return the script file(s) managed by this stage (may contain {@code null}).
	 */
	public File[] getScriptFiles() {
		return new File[] { scriptFile };
	}

	/**
	 * Get HTML text displaying the current status of the stage.
	 * @return HTML text displaying the current status of the stage.
	 */
	public synchronized String getStatusHTML() {
		String divertTime = (lastDivertTime == 0) ? "Never"
				: StringUtil.getDateTime(lastDivertTime, "&nbsp;&nbsp;&nbsp;");
		String stageUniqueStatus =
			"<tr><td width=\"20%\">Enabled:</td><td>" + enabled + "</td></tr>"
			+ "<tr><td width=\"20%\">Script:</td><td>"
				+ (scriptFile != null ? scriptFile.getPath() : "(none)") + "</td></tr>"
			+ "<tr><td width=\"20%\">Target:</td><td>"
				+ targetID + (resolvedTarget != null ? " (resolved)" : " (not found)") + "</td></tr>"
			+ "<tr><td width=\"20%\">Objects processed:</td><td>" + count + "</td></tr>"
			+ "<tr><td width=\"20%\">Objects passed through:</td><td>" + passedThroughCount + "</td></tr>"
			+ "<tr><td width=\"20%\">Objects diverted:</td><td>" + divertedCount + "</td></tr>"
			+ "<tr><td width=\"20%\">Objects quarantined:</td><td>" + quarantinedCount + "</td></tr>"
			+ "<tr><td width=\"20%\">Last divert at:</td><td>" + divertTime + "</td></tr>";
		return super.getStatusHTML(stageUniqueStatus);
	}

	// -----------------------------------------------------------------------
	// Private helpers
	// -----------------------------------------------------------------------

	private ObjectInlet findInlet(String id) {
		if (id == null || id.isEmpty()) return null;
		for (Pipeline pipe : Configuration.getInstance().getPipelines()) {
			for (PipelineStage stage : pipe.getStages()) {
				if (id.equals(stage.getID()) && stage instanceof ObjectInlet) {
					return (ObjectInlet) stage;
				}
			}
		}
		return null;
	}
}
