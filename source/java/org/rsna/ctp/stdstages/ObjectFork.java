/*---------------------------------------------------------------
*  Copyright 2025 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package org.rsna.ctp.stdstages;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * A Processor stage that copies every processed object into one or more
 * {@link ObjectInlet} stages (possibly in other pipelines) while always
 * passing the original object downstream unchanged.
 *
 * <p>An optional DICOM filter script controls whether the fork should
 * actually fire for a given object.  Non-DICOM objects are always forked
 * regardless of any script setting.
 *
 * <p>Configuration attributes:
 * <ul>
 *   <li>{@code name}    (required) – display name</li>
 *   <li>{@code id}      (required) – unique stage ID</li>
 *   <li>{@code root}    (required) – working directory</li>
 *   <li>{@code targets} (required) – semicolon-separated list of
 *       {@link ObjectInlet} stage IDs to inject into</li>
 *   <li>{@code script}  (optional) – DICOM filter script; if absent,
 *       all DICOM objects are forked</li>
 *   <li>{@code enable}  (optional, default "yes")</li>
 * </ul>
 */
public class ObjectFork extends AbstractPipelineStage implements Processor, Scriptable {

	static final Logger logger = Logger.getLogger(ObjectFork.class);

	boolean enabled;
	File scriptFile;
	String[] targetIDs;
	List<ObjectInlet> resolvedTargets = new ArrayList<>();

	volatile int count = 0;
	volatile int forkedCount = 0;
	volatile long lastForkTime = 0;

	/**
	 * Construct an ObjectFork from its XML configuration element.
	 * @param element the XML element describing this stage
	 */
	public ObjectFork(Element element) {
		super(element);
		enabled = !element.getAttribute("enable").trim().equals("no");
		scriptFile = getFilterScriptFile(element.getAttribute("script"));

		// Parse and deduplicate target IDs (preserve insertion order)
		String targets = element.getAttribute("targets").trim();
		if (targets.isEmpty()) {
			targetIDs = new String[0];
		} else {
			LinkedHashSet<String> set = new LinkedHashSet<>();
			for (String id : targets.split(";")) {
				String trimmed = id.trim();
				if (!trimmed.isEmpty()) set.add(trimmed);
			}
			targetIDs = set.toArray(new String[0]);
		}
	}

	/**
	 * Resolve the configured target IDs to live {@link ObjectInlet} instances.
	 * Called by the pipeline when all stages have been constructed.
	 */
	@Override
	public synchronized void start() {
		resolvedTargets.clear();
		for (String id : targetIDs) {
			ObjectInlet inlet = findInlet(id);
			if (inlet == null) {
				logger.warn(name + ": target \"" + id + "\" not found or is not an ObjectInlet");
			} else {
				resolvedTargets.add(inlet);
			}
		}
	}

	/**
	 * Pass the object downstream (unchanged) and optionally inject a copy
	 * into each resolved {@link ObjectInlet}.
	 *
	 * @param fileObject the incoming object
	 * @return the same {@code fileObject} (never {@code null})
	 */
	public FileObject process(FileObject fileObject) {
		lastFileIn = new File(fileObject.getFile().getAbsolutePath());
		lastTimeIn = System.currentTimeMillis();
		count++;

		if (enabled) {
			boolean shouldFork = true;
			if (scriptFile != null && fileObject instanceof DicomObject) {
				shouldFork = ((DicomObject) fileObject).matches(scriptFile);
			}
			if (shouldFork) {
				List<ObjectInlet> targets;
				synchronized (this) {
					targets = new ArrayList<>(resolvedTargets);
				}
				for (ObjectInlet inlet : targets) {
					inlet.inject(fileObject.getFile());
				}
				if (!targets.isEmpty()) {
					forkedCount++;
					lastForkTime = System.currentTimeMillis();
				}
			}
		}

		lastFileOut = new File(fileObject.getFile().getAbsolutePath());
		lastTimeOut = System.currentTimeMillis();
		return fileObject;
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
		String forkTime = (lastForkTime == 0) ? "Never"
				: StringUtil.getDateTime(lastForkTime, "&nbsp;&nbsp;&nbsp;");
		String stageUniqueStatus =
			"<tr><td width=\"20%\">Enabled:</td><td>" + enabled + "</td></tr>"
			+ "<tr><td width=\"20%\">Script:</td><td>"
				+ (scriptFile != null ? scriptFile.getPath() : "(none)") + "</td></tr>"
			+ "<tr><td width=\"20%\">Targets:</td><td>"
				+ String.join("; ", targetIDs) + "</td></tr>"
			+ "<tr><td width=\"20%\">Resolved targets:</td><td>"
				+ resolvedTargets.size() + " / " + targetIDs.length + "</td></tr>"
			+ "<tr><td width=\"20%\">Objects processed:</td><td>" + count + "</td></tr>"
			+ "<tr><td width=\"20%\">Objects forked:</td><td>" + forkedCount + "</td></tr>"
			+ "<tr><td width=\"20%\">Last fork at:</td><td>" + forkTime + "</td></tr>";
		return super.getStatusHTML(stageUniqueStatus);
	}

	// -----------------------------------------------------------------------
	// Private helpers
	// -----------------------------------------------------------------------

	private ObjectInlet findInlet(String id) {
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
