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
import org.w3c.dom.Element;

/**
 * A CTP Plugin that fires an HTTP REST call on behalf of StabilityMonitorProcessor.
 * Dynamic argument values are resolved from a representative DicomObject; static
 * arguments are literal key=value pairs declared in the configuration.
 *
 * Configuration attributes:
 *   id            - unique identifier referenced by the processor's targetID
 *   name          - display name
 *   root          - optional working directory (inherited from AbstractPlugin)
 *   baseUrl       - REST endpoint URL
 *   method        - HTTP method: GET, POST, PUT  (default: POST)
 *   contentType   - body format for POST/PUT: json or form  (default: json)
 *   arguments     - semicolon-delimited key:dicomtag pairs  (e.g. "pid:00100020;suid:0020000D")
 *   otherArguments- semicolon-delimited key=value static pairs  (e.g. "source=CTP;site=HOSP1")
 *   timeout       - HTTP connect+read timeout in ms  (default: 5000)
 *   retry         - retry attempts on failure  (default: 3)
 *   enable        - yes/no  (default: yes)
 *   logDetails    - log full response body on success  (default: no)
 */
public class StableNotificationPlugin extends AbstractPlugin {

	static final Logger logger = Logger.getLogger(StableNotificationPlugin.class);

	private final String baseUrl;
	private final String method;
	private final String contentType;
	private final int connectTimeout;
	private final int readTimeout;
	private final int retryCount;
	private final boolean enable;
	private final boolean logDetails;

	private final String[] argKeys;
	private final String[] argTags;
	private final String[] staticKeys;
	private final String[] staticValues;

	/**
	 * Construct the plugin.
	 * @param element the XML element from the configuration file.
	 */
	public StableNotificationPlugin(Element element) {
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

		// Parse dynamic arguments: "patientID:00100020;studyUID:0020000D"
		String argsAttr = element.getAttribute("arguments").trim();
		if (!argsAttr.isEmpty()) {
			String[] pairs = argsAttr.split(";");
			argKeys = new String[pairs.length];
			argTags = new String[pairs.length];
			for (int i = 0; i < pairs.length; i++) {
				String pair = pairs[i].trim();
				int colon = pair.indexOf(':');
				if (colon > 0) {
					argKeys[i] = pair.substring(0, colon).trim();
					argTags[i] = pair.substring(colon + 1).trim();
				} else {
					argKeys[i] = pair;
					argTags[i] = "";
				}
			}
		} else {
			argKeys = new String[0];
			argTags = new String[0];
		}

		// Parse static arguments: "key=value;key2=value2"
		String othersAttr = element.getAttribute("otherArguments").trim();
		if (!othersAttr.isEmpty()) {
			String[] pairs = othersAttr.split(";");
			staticKeys = new String[pairs.length];
			staticValues = new String[pairs.length];
			for (int i = 0; i < pairs.length; i++) {
				String pair = pairs[i].trim();
				int eq = pair.indexOf('=');
				if (eq > 0) {
					staticKeys[i] = pair.substring(0, eq).trim();
					staticValues[i] = pair.substring(eq + 1).trim();
				} else {
					staticKeys[i] = pair;
					staticValues[i] = "";
				}
			}
		} else {
			staticKeys = new String[0];
			staticValues = new String[0];
		}

		if (baseUrl.isEmpty()) {
			logger.warn(name + ": baseUrl attribute is missing or empty");
		}
	}

	/**
	 * Start the plugin. No background thread is needed.
	 */
	@Override
	public void start() {
		logger.info(name + ": StableNotificationPlugin started (method=" + method
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

		// Build merged parameter map: dynamic first, then static
		LinkedHashMap<String, String> params = new LinkedHashMap<>();
		for (int i = 0; i < argKeys.length; i++) {
			String value = (representative != null && !argTags[i].isEmpty())
					? representative.getElementValue(argTags[i], "")
					: "";
			params.put(argKeys[i], value);
		}
		for (int i = 0; i < staticKeys.length; i++) {
			params.put(staticKeys[i], staticValues[i]);
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
