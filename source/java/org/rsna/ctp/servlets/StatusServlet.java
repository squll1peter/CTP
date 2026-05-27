/*---------------------------------------------------------------
*  Copyright 2015 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package org.rsna.ctp.servlets;

import java.io.File;
import java.util.Iterator;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.ctp.pipeline.Pipeline;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;
import org.rsna.servlets.Servlet;
import org.rsna.util.HtmlUtil;

/**
 * The StatusServlet. This implementation returns the
 * status of all pipelines as an HTML page.
 */
public class StatusServlet extends Servlet {

	static final Logger logger = Logger.getLogger(StatusServlet.class);
	private static final long STATUS_CACHE_TTL_MS = 1000;
	String home = "/";
	private volatile String cachedAdminStatusHtml = null;
	private volatile long cachedAdminStatusTime = 0;
	private volatile String cachedUserStatusHtml = null;
	private volatile long cachedUserStatusTime = 0;

	/**
	 * Construct a StatusServlet.
	 * @param root the root directory of the server.
	 * @param context the path identifying the servlet.
	 */
	public StatusServlet(File root, String context) {
		super(root, context);
	}

	/**
	 * The GET handler: return a page displaying the status of the system.
	 * @param req the request object
	 * @param res the response object
	 */
	public void doGet(HttpRequest req, HttpResponse res) {
		Configuration config = Configuration.getInstance();
		boolean isAdmin = req.userHasRole("admin");
		String html = getCachedOrBuildStatusHtml(config, isAdmin, req.hasParameter("suppress"));

		//Send the response;
		res.disableCaching();
		res.write(html);
		res.setContentType("html");
		res.setContentEncoding(req);
		res.send();
	}

	private String getCachedOrBuildStatusHtml(Configuration config, boolean isAdmin, boolean suppressCloseBox) {
		long now = System.currentTimeMillis();
		if (isAdmin) {
			String html = cachedAdminStatusHtml;
			if ((html != null) && ((now - cachedAdminStatusTime) < STATUS_CACHE_TTL_MS)) return withCloseBox(html, suppressCloseBox);
			html = buildStatusHtml(config, true);
			cachedAdminStatusHtml = html;
			cachedAdminStatusTime = now;
			return withCloseBox(html, suppressCloseBox);
		}
		String html = cachedUserStatusHtml;
		if ((html != null) && ((now - cachedUserStatusTime) < STATUS_CACHE_TTL_MS)) return withCloseBox(html, suppressCloseBox);
		html = buildStatusHtml(config, false);
		cachedUserStatusHtml = html;
		cachedUserStatusTime = now;
		return withCloseBox(html, suppressCloseBox);
	}

	private String withCloseBox(String html, boolean suppressCloseBox) {
		if (suppressCloseBox) return html;
		return html.replace("<body>", "<body>" + HtmlUtil.getCloseBox(home));
	}

	private String buildStatusHtml(Configuration config, boolean isAdmin) {
		StringBuilder sb = new StringBuilder();
		sb.append("<html>");
		sb.append("<head>");
		sb.append("<title>Status</title>");
		sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
		sb.append("<link rel=\"Stylesheet\" type=\"text/css\" media=\"all\" href=\"/BaseStyles.css\"></link>");
		sb.append("<style>");
		sb.append("body {margin:0; padding:0; background:#edf3fa; color:#1d2a36;}");
		sb.append(".status-shell {padding:12px 14px 14px 14px;}");
		sb.append(".status-header {display:flex; align-items:flex-end; justify-content:space-between; gap:12px; flex-wrap:wrap; margin-bottom:10px;}");
		sb.append(".status-title {margin:0; font-size:34px; line-height:1.1; color:#1f5f94; letter-spacing:0.2px;}");
		sb.append(".status-subtitle {margin:4px 0 0 0; font-size:13px; color:#48627a;}");
		sb.append(".status-banner {margin:0; padding:7px 11px; border:1px solid #c6d4e3; border-radius:8px; background:#f6f9fc; font-size:14px; color:#2a465f;}");
		sb.append(".status-pill {display:inline-block; margin-left:6px; padding:2px 9px; border-radius:999px; border:1px solid #8fb0cf; background:white; font-weight:700; text-transform:uppercase; font-size:12px; letter-spacing:0.3px;}");
		sb.append(".status-content {padding:2px 0 0 0;}");
		sb.append(".status-content table {box-shadow:0 1px 3px rgba(14,44,68,0.12);}");
		sb.append(".status-content td {background-color:white;}");
		sb.append("@media (max-width: 760px) {");
		sb.append("  .status-shell {padding:10px;}");
		sb.append("  .status-title {font-size:28px;}");
		sb.append("  .status-subtitle {font-size:12px;}");
		sb.append("  .status-banner {width:100%; box-sizing:border-box;}");
		sb.append("}");
		sb.append("</style>");
		sb.append("</head><body>");
		sb.append("<div class=\"status-shell\">");
		sb.append("<div class=\"status-header\">");
		sb.append("<div>");
		sb.append("<h1 class=\"status-title\">Status</h1>");
		sb.append("<p class=\"status-subtitle\">Live pipeline and stage runtime state</p>");
		sb.append("</div>");
		if (isAdmin) {
			sb.append("<p class=\"status-banner\">Stage profiling <span class=\"status-pill\">");
			sb.append(config.getEnableStageProfiling() ? "enabled" : "disabled");
			sb.append("</span></p>");
		}
		sb.append("</div>");
		sb.append("<div class=\"status-content\">");
		Iterator<Pipeline> pit = config.getPipelines().iterator();
		while (pit.hasNext()) sb.append(pit.next().getStatusHTML());
		sb.append("</div>");
		sb.append("</div>");
		sb.append("</body></html>");
		return sb.toString();
	}

}

