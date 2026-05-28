/*---------------------------------------------------------------
 *  Copyright 2026 by the Radiological Society of North America
 *
 *  This source software is released under the terms of the
 *  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
 *----------------------------------------------------------------*/

package org.rsna.ctp.plugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.log4j.Logger;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.servlets.SummaryLink;
import org.rsna.server.User;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;

/**
 * A CTP Plugin that executes a local OS command on behalf of StabilityMonitorProcessor.
 * Arguments are configured as semicolon-delimited {@code key=value} pairs in the
 * {@code arguments} attribute.  A value wrapped in {@code {DicomKeyword}} (e.g.
 * {@code pid={PatientID}}) is resolved from the representative DicomObject at
 * notification time; a bare value (e.g. {@code source=CTP}) is used as a literal.
 * The same {@code {DicomKeyword}} substitution is applied to each token of the
 * {@code command} attribute, allowing the executable path or base arguments to
 * reference DICOM field values.  Executions are serialised through a bounded queue
 * and run by a single daemon worker thread.
 *
 * <p>Configuration attributes:
 * <ul>
 *   <li>{@code id}            – unique identifier referenced by the processor's targetID</li>
 *   <li>{@code name}          – display name</li>
 *   <li>{@code root}          – optional plugin working directory (inherited from AbstractPlugin)</li>
 *   <li>{@code command}       – command template; whitespace-delimited tokens may contain {@code {DicomKeyword}}</li>
 *   <li>{@code arguments}     – semicolon-delimited key=value pairs; values in {@code {…}} are resolved
 *                               from the DICOM object, bare values are literals,
 *                               e.g. {@code "pid={PatientID};suid={0020000D};source=CTP"}</li>
 *   <li>{@code dryRun}        – yes|no (default no): log the resolved command without executing it</li>
 *   <li>{@code minInterval}   – minimum milliseconds between command starts (default 0 = no throttle)</li>
 *   <li>{@code maxQueueSize}  – maximum pending tasks; excess notifications are dropped (default 100)</li>
 *   <li>{@code workingDir}    – working directory for the spawned process (default: plugin root, then CTP home)</li>
 *   <li>{@code enable}        – yes|no (default yes)</li>
 * </ul>
 */
public class StabilityExecPlugin extends AbstractPlugin {

	static final Logger logger = Logger.getLogger(StabilityExecPlugin.class);

	private static final Pattern KEYWORD_PATTERN = Pattern.compile("\\{([^}]+)\\}");

	private final boolean enable;
	private final boolean dryRun;
	private final String commandTemplate;
	private final String[] argKeys;
	private final String[] argValues;
	private final long minInterval;
	private final int maxQueueSize;
	private final File workingDir;

	private final ArrayBlockingQueue<List<String>> queue;

	private volatile Thread workerThread;
	private volatile long lastTriggeredTime = 0;
	private volatile String lastCommand = "";
	private volatile int lastExitCode = Integer.MIN_VALUE;
	private volatile long lastExitTime = 0;
	private volatile long droppedCount = 0;

	/**
	 * Construct the plugin.
	 * @param element the XML element from the configuration file.
	 */
	public StabilityExecPlugin(Element element) {
		super(element);

		enable = !element.getAttribute("enable").trim().equalsIgnoreCase("no");
		dryRun = element.getAttribute("dryRun").trim().equalsIgnoreCase("yes");

		commandTemplate = element.getAttribute("command").trim();

		// Parse arguments: semicolon-delimited key=value pairs.
		// A value wrapped in {DicomKeyword} is resolved from the DICOM object at runtime;
		// a bare value is used as a literal.  e.g. "pid={PatientID};suid={0020000D};source=CTP"
		String argsAttr = element.getAttribute("arguments").trim();
		if (!argsAttr.isEmpty()) {
			String[] pairs = argsAttr.split(";");
			argKeys   = new String[pairs.length];
			argValues = new String[pairs.length];
			for (int i = 0; i < pairs.length; i++) {
				String[] parsed = parseConfiguredPair(pairs[i]);
				argKeys[i]   = parsed[0];
				argValues[i] = parsed[1];
			}
		} else {
			argKeys   = new String[0];
			argValues = new String[0];
		}

		long mi = 0;
		try { mi = Long.parseLong(element.getAttribute("minInterval").trim()); }
		catch (NumberFormatException ignored) {}
		minInterval = Math.max(0, mi);

		int mqs = 100;
		try { mqs = Integer.parseInt(element.getAttribute("maxQueueSize").trim()); }
		catch (NumberFormatException ignored) {}
		maxQueueSize = Math.max(1, mqs);

		// Working directory: workingDir attr → root attr → null (inherit CTP home)
		String wdPath = element.getAttribute("workingDir").trim();
		if (!wdPath.isEmpty()) {
			workingDir = new File(wdPath);
		} else if (root != null) {
			workingDir = root;
		} else {
			workingDir = null;
		}

		queue = new ArrayBlockingQueue<>(maxQueueSize);

		if (commandTemplate.isEmpty()) {
			logger.warn(name + ": command attribute is missing or empty");
		}
	}

	/**
	 * Split "key=value" or "key:value" into a two-element array.
	 * Falls back to colon delimiter when no equals sign is present.
	 */
	private static String[] parseConfiguredPair(String pairText) {
		String pair = pairText.trim();
		int split = pair.indexOf('=');
		if (split < 0) split = pair.indexOf(':');
		if (split > 0) {
			return new String[] {
				pair.substring(0, split).trim(),
				pair.substring(split + 1).trim()
			};
		}
		return new String[] { pair, "" };
	}

	/**
	 * Resolve a single argument value.
	 * If {@code rawValue} is wrapped in {@code {…}}, the enclosed keyword or tag is
	 * looked up in the representative DicomObject; otherwise the value is a literal.
	 */
	private static String resolveValue(String rawValue, DicomObject representative) {
		if (rawValue.length() > 2
				&& rawValue.charAt(0) == '{'
				&& rawValue.charAt(rawValue.length() - 1) == '}') {
			String tag = rawValue.substring(1, rawValue.length() - 1);
			return (representative != null) ? representative.getElementValue(tag, "") : "";
		}
		return rawValue;
	}

	/**
	 * Substitute all {@code {keyword}} occurrences within a single command token.
	 * Non-{keyword} text is preserved as-is.
	 */
	private static String resolveKeywords(String token, DicomObject representative) {
		if (!token.contains("{")) return token;
		Matcher m = KEYWORD_PATTERN.matcher(token);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String tag = m.group(1);
			String value = (representative != null) ? representative.getElementValue(tag, "") : "";
			m.appendReplacement(sb, Matcher.quoteReplacement(value));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	/**
	 * Start the plugin and its worker thread.
	 */
	@Override
	public void start() {
		workerThread = new Thread(this::workerLoop, name + "-worker");
		workerThread.setDaemon(true);
		workerThread.start();
		logger.info(name + ": StabilityExecPlugin started"
				+ " (dryRun=" + dryRun
				+ ", minInterval=" + minInterval + "ms"
				+ ", maxQueueSize=" + maxQueueSize + ")");
	}

	/**
	 * Stop the plugin and interrupt the worker thread.
	 */
	@Override
	public synchronized void shutdown() {
		super.shutdown();
		Thread t = workerThread;
		if (t != null) t.interrupt();
	}

	/**
	 * Enqueue a command execution resolved from the representative DICOM object.
	 * This method is non-blocking: it returns immediately after queuing the task.
	 * @param representative the first DicomObject received for the group; may be null
	 * @return true if the command was enqueued; false if the queue was full (dropped)
	 */
	public boolean notify(DicomObject representative) {
		if (!enable) {
			logger.debug(name + ": disabled, skipping notification");
			return true;
		}
		if (commandTemplate.isEmpty()) {
			logger.error(name + ": command is not configured, skipping notification");
			return false;
		}

		// Apply {keyword} substitution to each command token, then add --key value args
		String[] cmdTokens = commandTemplate.split("\\s+");
		List<String> tokens = new ArrayList<>(cmdTokens.length + argKeys.length * 2);
		for (String t : cmdTokens) {
			tokens.add(resolveKeywords(t, representative));
		}
		for (int i = 0; i < argKeys.length; i++) {
			tokens.add("--" + argKeys[i]);
			tokens.add(resolveValue(argValues[i], representative));
		}

		lastTriggeredTime = System.currentTimeMillis();

		if (!queue.offer(tokens)) {
			droppedCount++;
			logger.warn(name + ": queue full (capacity=" + maxQueueSize
					+ "), notification dropped (total dropped=" + droppedCount + ")");
			return false;
		}
		return true;
	}

	// ------------------------------------------------------------------
	// Worker thread
	// ------------------------------------------------------------------

	private void workerLoop() {
		long lastStartTime = 0;
		while (!stop) {
			List<String> tokens;
			try {
				tokens = queue.take();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}

			// Enforce minimum interval between command starts
			if (minInterval > 0) {
				long elapsed = System.currentTimeMillis() - lastStartTime;
				if (elapsed < minInterval) {
					try {
						Thread.sleep(minInterval - elapsed);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}

			String cmdString = String.join(" ", tokens);

			if (dryRun) {
				logger.info(name + ": [dryRun] would execute: " + cmdString);
				lastCommand = cmdString;
				lastExitCode = 0;
				lastExitTime = System.currentTimeMillis();
				lastStartTime = lastExitTime;
			} else {
				lastCommand = cmdString;
				lastStartTime = System.currentTimeMillis();
				try {
					ProcessBuilder pb = new ProcessBuilder(tokens);
					pb.redirectErrorStream(true);        // merge stderr into stdout
					pb.redirectInput(ProcessBuilder.Redirect.PIPE); // avoid inheriting CTP stdin
					if (workingDir != null) pb.directory(workingDir);
					Process process = pb.start();
					try { process.getOutputStream().close(); } catch (IOException ignored) {}
					// Drain stdout (combined with stderr) to prevent process blocking on full pipe
					process.getInputStream().transferTo(OutputStream.nullOutputStream());
					int exitCode = process.waitFor();
					lastExitCode = exitCode;
					lastExitTime = System.currentTimeMillis();
					if (exitCode == 0) {
						logger.info(name + ": command exited 0: " + cmdString);
					} else {
						logger.warn(name + ": command exited " + exitCode + ": " + cmdString);
					}
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					break;
				} catch (Exception ex) {
					logger.error(name + ": failed to execute: " + cmdString + " — " + ex.getMessage());
					lastExitCode = -1;
					lastExitTime = System.currentTimeMillis();
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Status
	// ------------------------------------------------------------------

	/**
	 * Get status HTML for the admin UI.
	 */
	@Override
	public String getStatusHTML() {
		StringBuilder sb = new StringBuilder();
		sb.append("<table border=\"1\" cellpadding=\"2\">");
		sb.append("<tr><td>Enabled:</td><td>").append(enable ? "yes" : "no").append("</td></tr>");
		sb.append("<tr><td>Dry Run:</td><td>").append(dryRun ? "yes" : "no").append("</td></tr>");
		sb.append("<tr><td>Command:</td><td>").append(htmlEscape(commandTemplate)).append("</td></tr>");
		sb.append("<tr><td>Min Interval (ms):</td><td>").append(minInterval).append("</td></tr>");
		sb.append("<tr><td>Max Queue Size:</td><td>").append(maxQueueSize).append("</td></tr>");
		sb.append("<tr><td>Queue depth:</td><td>").append(queue.size()).append("</td></tr>");
		sb.append("<tr><td>Total dropped:</td><td>").append(droppedCount).append("</td></tr>");
		sb.append("<tr><td>Last triggered:</td><td>")
			.append(lastTriggeredTime != 0 ? StringUtil.getDateTime(lastTriggeredTime, "&nbsp;&nbsp;&nbsp;") : "Never")
			.append("</td></tr>");
		sb.append("<tr><td>Last command:</td><td>")
			.append(lastCommand.isEmpty() ? "Never" : htmlEscape(lastCommand))
			.append("</td></tr>");
		String exitStr = (lastExitCode == Integer.MIN_VALUE) ? "N/A" : String.valueOf(lastExitCode);
		sb.append("<tr><td>Last exit code:</td><td>").append(exitStr).append("</td></tr>");
		sb.append("<tr><td>Last exit at:</td><td>")
			.append(lastExitTime != 0 ? StringUtil.getDateTime(lastExitTime, "&nbsp;&nbsp;&nbsp;") : "Never")
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

	/**
	 * Get the list of links for display on the summary page.
	 */
	@Override
	public synchronized LinkedList<SummaryLink> getLinks(User user) {
		return new LinkedList<SummaryLink>();
	}
}
