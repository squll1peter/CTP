/*---------------------------------------------------------------
*  Copyright 2015 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package org.rsna.ctp.servlets;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.log4j.Logger;
import org.rsna.ctp.Configuration;
import org.rsna.server.HttpRequest;
import org.rsna.server.HttpResponse;
import org.rsna.servlets.Servlet;
import org.rsna.util.XmlUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.rsna.ctp.pipeline.Pipeline;
import org.rsna.ctp.pipeline.PipelineStage;
import org.rsna.ctp.stdstages.Scriptable;
import org.rsna.ctp.stdstages.ScriptableDicom;

/**
 * The ServerServlet. This servlet is intended for use by Ajax
 * calls on web pages which need to know key parameters of the server
 * (IP, port, CTP build).
 */
public class ServerServlet extends Servlet {

	static final Logger logger = Logger.getLogger(ServerServlet.class);
	private static final long CACHE_TTL_MS = 2000L;
	private static volatile long configCacheExpires = 0L;
	private static volatile String configCacheXml = null;
	private static final ConcurrentHashMap<String, TypeCacheEntry> typeCache = new ConcurrentHashMap<String, TypeCacheEntry>();

	private static class TypeCacheEntry {
		final boolean value;
		final long expires;
		TypeCacheEntry(boolean value, long expires) {
			this.value = value;
			this.expires = expires;
		}
	}

	/**
	 * Construct a ServerServlet.
	 * @param root the root directory of the server.
	 * @param context the path identifying the servlet.
	 */
	public ServerServlet(File root, String context) {
		super(root, context);
	}

	/**
	 * The GET handler: return an XML structure containing
	 * information about the server and the configuration.
	 * @param req the request object
	 * @param res the response object
	 */
	public void doGet(HttpRequest req, HttpResponse res) {
		if (!req.userHasRole("admin")) {
			res.setResponseCode(res.forbidden);
			res.send();
			return;
		}
		res.setContentType("xml");
		res.disableCaching();
		Configuration config = Configuration.getInstance();
		if (req.hasParameter("type")) {
			String type = req.getParameter("type");
			Boolean cached = getTypeCache(type);
			if (cached != null) {
				res.write(cached.booleanValue() ? "<true/>" : "<false/>");
			}
			else {
				boolean found = false;
				try {
					Class<?> c = Class.forName(type);
					for (Pipeline pipe : config.getPipelines()) {
						if (pipe.isEnabled()) {
							for (PipelineStage stage : pipe.getStages()) {
								if (c.isAssignableFrom(stage.getClass())) {
									found = true;
									break;
								}
							}
							if (found) break;
						}
					}
				}
				catch (Exception ex) { }
				putTypeCache(type, found);
				res.write(found ? "<true/>" : "<false/>");
			}
		}
		else {
			String cachedXml = getConfigCache();
			if (cachedXml != null) {
				res.write(cachedXml);
				res.send();
				return;
			}
			try {
				String ip = config.getIPAddress();
				String port = Integer.toString(config.getServerPort());
				String build = config.getCTPBuild();
				String java = config.getCTPJava();
				Document configXML = config.getConfigurationDocument();
				Document doc = XmlUtil.getDocument();
				Element root = doc.createElement("CTP");
				doc.appendChild(root);
				root.setAttribute("ip", ip);
				root.setAttribute("port", port);
				root.setAttribute("build", build);
				root.setAttribute("java", java);
				root.appendChild( doc.importNode( configXML.getDocumentElement(), true ) );
				String xml = XmlUtil.toPrettyString(doc);
				putConfigCache(xml);
				res.write(xml);
			}
			catch (Exception ex) { res.write("<Server/>"); }
		}
		res.send();
	}

	private static Boolean getTypeCache(String type) {
		TypeCacheEntry entry = typeCache.get(type);
		if ((entry == null) || (System.currentTimeMillis() > entry.expires)) return null;
		return Boolean.valueOf(entry.value);
	}

	private static void putTypeCache(String type, boolean value) {
		typeCache.put(type, new TypeCacheEntry(value, System.currentTimeMillis() + CACHE_TTL_MS));
	}

	private static String getConfigCache() {
		if ((configCacheXml != null) && (System.currentTimeMillis() <= configCacheExpires)) return configCacheXml;
		return null;
	}

	private static void putConfigCache(String xml) {
		configCacheXml = xml;
		configCacheExpires = System.currentTimeMillis() + CACHE_TTL_MS;
	}

}
