/*---------------------------------------------------------------
 *  Copyright 2026 by the Radiological Society of North America
 *
 *  This source software is released under the terms of the
 *  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
 *----------------------------------------------------------------*/

package org.rsna.ctp.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import org.apache.log4j.Logger;
import org.rsna.ctp.objects.DicomObject;
import org.rsna.ctp.servlets.SummaryLink;
import org.rsna.server.User;
import org.rsna.util.StringUtil;
import org.w3c.dom.Element;

/**
 * A CTP Plugin that fires an HTTP REST call on behalf of StabilityMonitorProcessor.
 * Arguments are configured as semicolon-delimited {@code key=value} pairs in the
 * {@code arguments} attribute.  A value wrapped in {@code {DicomKeyword}} (e.g.
 * {@code pid={PatientID}}) is resolved from the representative DicomObject at
 * notification time; a bare value (e.g. {@code source=CTP}) is used as a literal.
 *
 * Configuration attributes:
 *   id            - unique identifier referenced by the processor's targetID
 *   name          - display name
 *   root          - optional working directory (inherited from AbstractPlugin)
 *   baseUrl       - REST endpoint URL
 *   method        - HTTP method: GET, POST, PUT  (default: POST)
 *   contentType   - body format for POST/PUT: json or form  (default: json)
 *   arguments     - semicolon-delimited key=value pairs; values in {…} are resolved
 *                   from the DICOM object, bare values are literals,
 *                   e.g. "pid={PatientID};suid={0020000D};source=CTP"
 *   timeout       - HTTP connect+read timeout in ms  (default: 5000)
 *   retry         - retry attempts on failure  (default: 3)
 *   enable        - yes/no  (default: yes)
 *   logDetails    - log full response body on success  (default: no)
 */
public class StabilityWebhookPlugin extends AbstractPlugin {

	static final Logger logger = Logger.getLogger(StabilityWebhookPlugin.class);

	private final String baseUrl;
	private final String method;
	private final String contentType;
	private final int connectTimeout;
	private final int readTimeout;
	private final int retryCount;
	private final boolean enable;
	private final boolean logDetails;
	private volatile long lastTriggeredTime = 0;
	private volatile String lastCalledUrl = "";

	private final String[] argKeys;
	private final String[] argValues;

	/**
	 * Construct the plugin.
	 * @param element the XML element from the configuration file.
	 */
	public StabilityWebhookPlugin(Element element) {
		super(element);

		baseUrl = element.getAttribute("baseUrl").trim();

		String rawMethod = element.getAttribute("method").trim().toUpperCase();
		method = rawMethod.isEmpty() ? "POST" : rawMethod;

		String rawCt = element.getAttribute("contentType").trim().toLowerCase();
		contentType = rawCt.isEmpty() ? "json" : rawCt;

		int t = 5000;
		try { t = Integer.parseInt(element.getAttribute("timeout").trim()); }
		catch (NumberFormatException ignored) {}
		connectTimeout = t;
		readTimeout = t;

		int r = 3;
		try { r = Integer.parseInt(element.getAttribute("retry").trim()); }
		catch (NumberFormatException ignored) {}
		retryCount = r;

		enable = !element.getAttribute("enable").trim().equalsIgnoreCase("no");
		logDetails = element.getAttribute("logDetails").trim().equalsIgnoreCase("yes");

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

		if (baseUrl.isEmpty()) {
			logger.warn(name + ": baseUrl attribute is missing or empty");
		}
	}

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
	 * Start the plugin. No background thread is needed.
	 */
	@Override
	public void start() {
		logger.info(name + ": StabilityWebhookPlugin started (method=" + method
				+ ", contentType=" + contentType + ", baseUrl=" + baseUrl + ")");
	}

	/**
	 * Fire the REST API call using tag values from the representative DICOM object.
	 * This method is thread-safe: all instance fields are final and all state is local.
	 * @param representative the first DicomObject received for the group; may be null
	 * @return true on HTTP 2xx response; false otherwise
	 */
	public boolean notify(DicomObject representative) {
		if (!enable) {
			logger.debug(name + ": disabled, skipping notification");
			return true;
		}

		// Build parameter map: resolve {keyword} values from DICOM object; pass literals through
		LinkedHashMap<String, String> params = new LinkedHashMap<>();
		for (int i = 0; i < argKeys.length; i++) {
			params.put(argKeys[i], resolveValue(argValues[i], representative));
		}

		// Execute with retries
		int maxAttempts = Math.max(1, retryCount);
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				boolean ok = doHttpCall(params);
				if (ok) return true;
				if (attempt < maxAttempts) {
					logger.warn(name + ": HTTP call failed (attempt " + attempt + " of " + maxAttempts + "), retrying");
				} else {
					logger.error(name + ": HTTP call failed after " + maxAttempts + " attempt(s)");
				}
			} catch (Exception ex) {
				if (attempt < maxAttempts) {
					logger.warn(name + ": HTTP call error (attempt " + attempt + " of " + maxAttempts + "): " + ex.getMessage());
				} else {
					logger.error(name + ": HTTP call error after " + maxAttempts + " attempt(s): " + ex.getMessage());
				}
			}
		}
		return false;
	}

	private boolean doHttpCall(LinkedHashMap<String, String> params) throws IOException {
		// Build the target URL: for GET, query params go in the URL; otherwise use baseUrl as-is
		String effectiveUrl = "GET".equals(method) ? baseUrl + buildQueryString(params) : baseUrl;
		lastTriggeredTime = System.currentTimeMillis();
		lastCalledUrl = effectiveUrl;
		HttpURLConnection conn = (HttpURLConnection) URI.create(effectiveUrl).toURL().openConnection();
		// Always disconnect, even on exception — covers getOutputStream(), getResponseCode(), and readResponse()
		try {
			conn.setConnectTimeout(connectTimeout);
			conn.setReadTimeout(readTimeout);

			if ("GET".equals(method)) {
				conn.setRequestMethod("GET");
			} else {
				conn.setRequestMethod(method);
				conn.setDoOutput(true);
				byte[] body;
				if ("form".equals(contentType)) {
					conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
					body = buildFormBody(params).getBytes(StandardCharsets.UTF_8);
				} else {
					conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
					body = buildJsonBody(params).getBytes(StandardCharsets.UTF_8);
				}
				conn.setRequestProperty("Content-Length", String.valueOf(body.length));
				try (OutputStream os = conn.getOutputStream()) {
					os.write(body);
				}
			}

			int responseCode = conn.getResponseCode();
			boolean success = (responseCode >= 200 && responseCode < 300);

			if (logDetails || !success) {
				String responseBody = readResponse(conn, success);
				if (success) {
					logger.info(name + ": notification succeeded (" + responseCode + "): " + responseBody);
				} else {
					logger.warn(name + ": notification returned " + responseCode + ": " + responseBody);
				}
			} else {
				logger.info(name + ": notification succeeded (" + responseCode + ")");
			}
			return success;
		} finally {
			conn.disconnect();
		}
	}

	private String buildQueryString(LinkedHashMap<String, String> params) {
		if (params.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("?");
		boolean first = true;
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (!first) sb.append("&");
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
			  .append("=")
			  .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
			first = false;
		}
		return sb.toString();
	}

	private String buildFormBody(LinkedHashMap<String, String> params) {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (!first) sb.append("&");
			sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
			  .append("=")
			  .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
			first = false;
		}
		return sb.toString();
	}

	private String buildJsonBody(LinkedHashMap<String, String> params) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> e : params.entrySet()) {
			if (!first) sb.append(",");
			sb.append(jsonEscape(e.getKey())).append(":").append(jsonEscape(e.getValue()));
			first = false;
		}
		sb.append("}");
		return sb.toString();
	}

	private String jsonEscape(String s) {
		if (s == null) return "\"\"";
		return "\"" + s.replace("\\", "\\\\")
		               .replace("\"", "\\\"")
		               .replace("\n", "\\n")
		               .replace("\r", "\\r")
		               .replace("\t", "\\t") + "\"";
	}

	private String readResponse(HttpURLConnection conn, boolean success) {
		try {
			InputStream is = success ? conn.getInputStream() : conn.getErrorStream();
			if (is == null) return "";
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(is, StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) sb.append(line).append("\n");
			reader.close();
			return sb.toString().trim();
		} catch (Exception ex) {
			return "(unable to read response: " + ex.getMessage() + ")";
		}
	}

	/**
	 * Get status HTML for the admin UI.
	 */
	@Override
	public String getStatusHTML() {
		StringBuilder sb = new StringBuilder();
		sb.append("<table border=\"1\" cellpadding=\"2\">");
		sb.append("<tr><td>Enabled:</td><td>").append(enable ? "yes" : "no").append("</td></tr>");
		sb.append("<tr><td>Base URL:</td><td>").append(htmlEscape(baseUrl)).append("</td></tr>");
		sb.append("<tr><td>Method:</td><td>").append(method).append("</td></tr>");
		sb.append("<tr><td>Content-Type:</td><td>").append(contentType).append("</td></tr>");
		sb.append("<tr><td>Timeout (ms):</td><td>").append(connectTimeout).append("</td></tr>");
		sb.append("<tr><td>Retry:</td><td>").append(retryCount).append("</td></tr>");
		sb.append("<tr><td>Log details:</td><td>").append(logDetails ? "yes" : "no").append("</td></tr>");
		sb.append("<tr><td>Last triggered:</td><td>")
			.append(lastTriggeredTime != 0 ? StringUtil.getDateTime(lastTriggeredTime, "&nbsp;&nbsp;&nbsp;") : "Never")
			.append("</td></tr>");
		sb.append("<tr><td>Last called URL:</td><td>")
			.append(lastCalledUrl.isEmpty() ? "Never" : htmlEscape(lastCalledUrl))
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
