/*---------------------------------------------------------------
 *  Copyright 2026 by the Radiological Society of North America
 *
 *  This source software is released under the terms of the
 *  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
 *----------------------------------------------------------------*/

package org.rsna.ctp.stdstages;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.objects.FileObject;
import org.rsna.ctp.pipeline.AbstractPipelineStage;
import org.rsna.ctp.pipeline.Processor;
import org.rsna.ctp.plugin.StabilityNotificationPlugin;
import org.rsna.util.FileUtil;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;

/**
 * A Processor stage that tracks DICOM objects grouped by series, study, or patient
 * and notifies a StabilityNotificationPlugin once the group has been
 * idle (no new objects) for a configurable timeout period.
 *
 * Non-DICOM objects pass through unmodified without any tracking.
 * All DICOM objects pass through to the next stage unmodified regardless of whether
 * they are tracked.
 *
 * Configuration attributes:
 *   id           - optional stage identifier
 *   name         - display name
 *   root         - working directory; representative files stored in root/rep/
 *   level        - grouping level: series, study, or patient  (default: series)
 *   targetID     - id of the StabilityNotificationPlugin to call
 *   timeout      - inactivity timeout in seconds before firing  (default: 60)
 *   dicomScript  - optional CTP filter script; only matching objects are tracked
 */
public class StabilityMonitorProcessor extends AbstractPipelineStage implements Processor, Scriptable {

	static final Logger logger = Logger.getLogger(StabilityMonitorProcessor.class);

	private final String targetID;
	private final String level;
	private final long timeoutMs;
	private final File repDir;

	private File dicomScriptFile = null;
	private volatile StabilityNotificationPlugin plugin = null;
	private volatile String lastTrigger = "";
	private volatile long lastTriggerTime = 0;

	private final ConcurrentHashMap<String, GroupRecord> groups = new ConcurrentHashMap<>();
	private Notifier notifier = null;

	/**
	 * Construct the StabilityMonitorProcessor PipelineStage.
	 * @param element the XML element from the configuration file.
	 */
	public StabilityMonitorProcessor(Element element) {
		super(element);

		targetID = element.getAttribute("targetID").trim();

		String lvl = element.getAttribute("level").trim().toLowerCase();
		level = lvl.isEmpty() ? "series" : lvl;

		long t = 60L;
		try { t = Long.parseLong(element.getAttribute("timeout").trim()); }
		catch (NumberFormatException ignored) {}
		timeoutMs = t * 1000L;

		dicomScriptFile = getFilterScriptFile(element.getAttribute("dicomScript"));

		// Directory for representative object copies; root is set by AbstractPipelineStage
		File base = (root != null) ? root : new File("roots/StabilityMonitorProcessor");
		repDir = new File(base, "rep");
		repDir.mkdirs();

		notifier = new Notifier();
	}

	/**
	 * Start the stage. Resolves the plugin reference and starts the Notifier thread.
	 * Called after all stages and plugins have been instantiated.
	 */
	@Override
	public void start() {
		Configuration config = Configuration.getInstance();
		if (!targetID.isEmpty()) {
			Object p = config.getRegisteredPlugin(targetID);
			if (p instanceof StabilityNotificationPlugin) {
				plugin = (StabilityNotificationPlugin) p;
				logger.info(name + ": resolved StabilityNotificationPlugin id=\"" + targetID + "\"");
			} else if (p != null) {
				logger.warn(name + ": targetID \"" + targetID + "\" does not reference a StabilityNotificationPlugin");
			} else {
				logger.warn(name + ": targetID \"" + targetID + "\" does not reference any registered Plugin");
			}
		} else {
			logger.warn(name + ": no targetID configured; no notifications will be sent");
		}

		// Purge any stale representative files left by an unclean previous shutdown
		File[] stale = repDir.listFiles();
		if (stale != null && stale.length > 0) {
			for (File f : stale) f.delete();
			logger.info(name + ": purged " + stale.length + " stale representative file(s) from " + repDir);
		}

		notifier.start();
		logger.info(name + ": started (level=" + level + ", timeout=" + (timeoutMs / 1000) + "s)");
	}

	/**
	 * Stop the stage and interrupt the Notifier thread.
	 * Deletes representative files for any groups that never stabilised.
	 */
	@Override
	public synchronized void shutdown() {
		stop = true;
		if (notifier != null) notifier.interrupt();
		for (GroupRecord record : groups.values()) {
			if (record.repFile != null) record.repFile.delete();
		}
		groups.clear();
	}

	// --- Scriptable ---

	@Override
	public File[] getScriptFiles() {
		return new File[] { dicomScriptFile, null, null };
	}

	// --- Processor ---

	/**
	 * Track a DICOM object for stability monitoring and pass it through unchanged.
	 * Non-DICOM objects bypass tracking entirely.
	 * @param fileObject the object to process.
	 * @return the same FileObject, unmodified.
	 */
	@Override
	public FileObject process(FileObject fileObject) {
		lastFileIn = new File(fileObject.getFile().getAbsolutePath());
		lastTimeIn = System.currentTimeMillis();

		if (fileObject instanceof DicomObject) {
			DicomObject dob = (DicomObject) fileObject;

			// Only track objects that pass the filter script (if configured)
			if (dob.matches(dicomScriptFile)) {
				String groupKey = getGroupKey(dob);
				if (groupKey != null && !groupKey.isEmpty()) {
					trackGroup(groupKey, fileObject);
				}
			}
		}

		lastFileOut = new File(fileObject.getFile().getAbsolutePath());
		lastTimeOut = System.currentTimeMillis();
		return fileObject;
	}

	private String getGroupKey(DicomObject dob) {
		if ("patient".equals(level)) return dob.getPatientID();
		if ("study".equals(level))   return dob.getStudyInstanceUID();
		return dob.getSeriesInstanceUID(); // default: series
	}

	private void trackGroup(String groupKey, FileObject fileObject) {
		GroupRecord existing = groups.get(groupKey);
		if (existing != null) {
			existing.lastSeen = System.currentTimeMillis();
			return;
		}

		// First time we see this group: save a copy of the representative object
		File repFile = new File(repDir, sanitizeKey(groupKey) + ".dcm");
		boolean copied = FileUtil.copy(fileObject.getFile(), repFile);
		if (!copied) {
			logger.warn(name + ": could not copy representative object for group " + groupKey);
		}
		GroupRecord newRecord = new GroupRecord(groupKey, repFile, System.currentTimeMillis());
		GroupRecord prev = groups.putIfAbsent(groupKey, newRecord);
		if (prev != null) {
			// Another thread won the race; use the existing record and discard our copy
			repFile.delete();
			prev.lastSeen = System.currentTimeMillis();
		}
	}

	private String sanitizeKey(String key) {
		// Prefix with an 8-hex-char hash of the original key so different keys that
		// produce the same cleaned string (e.g. "ABC DEF" vs "ABC_DEF") still get
		// distinct filenames.
		String hash = String.format("%08x", key.hashCode());
		String clean = key.replaceAll("[^a-zA-Z0-9._-]", "_");
		if (clean.length() > 64) clean = clean.substring(0, 64);
		return hash + "_" + clean;
	}

	// --- Inner classes ---

	static class GroupRecord {
		final String groupKey;
		final File repFile;
		volatile long lastSeen;

		GroupRecord(String groupKey, File repFile, long lastSeen) {
			this.groupKey = groupKey;
			this.repFile = repFile;
			this.lastSeen = lastSeen;
		}

		boolean isStable(long timeoutMs) {
			return (System.currentTimeMillis() - lastSeen) >= timeoutMs;
		}
	}

	class Notifier extends Thread {
		Notifier() {
			super(name + " - notifier");
			setDaemon(true);
		}

		@Override
		public void run() {
			long sleepMs = Math.max(1000L, timeoutMs / 2);
			while (!stop && !isInterrupted()) {
				try {
					sleep(sleepMs);
					processStableGroups();
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				} catch (Exception ex) {
					logger.warn(name + ": error in notifier loop", ex);
				}
			}
		}

		private void processStableGroups() {
			for (String key : groups.keySet()) {
				GroupRecord record = groups.get(key);
				if (record != null && record.isStable(timeoutMs)) {
					// Remove before notifying to prevent double-fire if notify is slow
					if (groups.remove(key, record)) {
						fireNotification(record);
					}
				}
			}
		}

		private void fireNotification(GroupRecord record) {
			lastTrigger = record.groupKey;
			lastTriggerTime = System.currentTimeMillis();

			DicomObject representative = null;
			if (record.repFile != null && record.repFile.exists()) {
				try {
					representative = new DicomObject(record.repFile);
				} catch (Exception ex) {
					logger.warn(name + ": could not load representative for group \""
							+ record.groupKey + "\": " + ex.getMessage());
				}
			}

			StabilityNotificationPlugin p = plugin;
			if (p != null) {
				boolean ok = p.notify(representative);
				if (!ok) {
					logger.warn(name + ": notification failed for group \"" + record.groupKey + "\"");
				}
			} else {
				logger.warn(name + ": no plugin available; dropping notification for group \""
						+ record.groupKey + "\"");
			}

			// Clean up temp representative file regardless of outcome
			if (record.repFile != null) record.repFile.delete();
		}
	}

	/**
	 * Get status HTML for the admin UI.
	 */
	@Override
	public String getStatusHTML() {
		StringBuilder sb = new StringBuilder();
		sb.append("<table border=\"1\" cellpadding=\"2\">");
		sb.append("<tr><td>Level:</td><td>").append(level).append("</td></tr>");
		sb.append("<tr><td>Timeout (s):</td><td>").append(timeoutMs / 1000).append("</td></tr>");
		sb.append("<tr><td>Target plugin ID:</td><td>").append(targetID).append("</td></tr>");
		sb.append("<tr><td>Plugin resolved:</td><td>").append(plugin != null ? "yes" : "no").append("</td></tr>");
		sb.append("<tr><td>Active groups:</td><td>").append(groups.size()).append("</td></tr>");
		sb.append("<tr><td>Last file received:</td><td>")
			.append(lastTimeIn != 0 && lastFileIn != null ? htmlEscape(lastFileIn.getAbsolutePath()) : "No activity")
			.append("</td></tr>");
		sb.append("<tr><td>Last file received at:</td><td>")
			.append(lastTimeIn != 0 ? StringUtil.getDateTime(lastTimeIn, "&nbsp;&nbsp;&nbsp;") : "Never")
			.append("</td></tr>");
		sb.append("<tr><td>Last trigger:</td><td>")
			.append(lastTriggerTime != 0 ? htmlEscape(lastTrigger) : "Never")
			.append("</td></tr>");
		sb.append("<tr><td>Last trigger at:</td><td>")
			.append(lastTriggerTime != 0 ? StringUtil.getDateTime(lastTriggerTime, "&nbsp;&nbsp;&nbsp;") : "Never")
			.append("</td></tr>");
		sb.append("</table>");
		return sb.toString();
	}

	private static String htmlEscape(String s) {
		if (s == null) return "";
		return s.replace("&", "&amp;")
		        .replace("<", "&lt;")
		        .replace(">", "&gt;")
		        .replace("\"", "&quot;");
	}
}
