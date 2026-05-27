# Stage 13 — Admin UI Refresh, Cache Busting, and Launcher Startup Hardening

## Summary

This stage focused on practical operator-facing improvements and startup
robustness while preserving runtime behavior:

1. Modernized the admin shell and key servlet pages for readability/usability.
2. Added static-asset cache busting so CSS/JS updates are visible immediately.
3. Added lightweight servlet-side caching on bootstrap responses.
4. Hardened launcher startup by auto-seeding `config.xml` when missing.
5. Kept runtime defaults aligned to non-privileged port `8080`.

Note: CSS/JS updates in deployed CTP instances only take effect after restart
when serving from a previously built runtime tree.

---

## Changes Applied

### 1. Admin shell + servlet visual refresh

Files:
- `source/resources/JSCTP.css`
- `source/resources/JSCTP.js`
- `source/resources/SummaryServlet.css`
- `source/resources/AuditLogServlet.css`
- `source/resources/LookupServlet.css`
- `source/resources/LookupTableCheckerServlet.css`
- `source/resources/QuarantineServlet.css`
- `source/resources/BaseStyles.css` (new)
- `source/resources/JSSplitPane.css` (new)
- `source/resources/JSSplitPane.js` (new)

Highlights:
- Improved shell spacing, hierarchy, split-pane ergonomics, and contrast.
- Added divider collapse/restore and persisted split position behavior.
- Refined servlet table/card readability and shared baseline stylesheet.
- Added guard logic in shell JS to avoid null-config bootstrapping errors.

### 2. Cache-busting for admin assets

Files:
- `source/files/ROOT/example-index.html`
- `source/java/org/rsna/ctp/servlets/SummaryServlet.java`

Highlights:
- Appended version query suffixes on CSS/JS links to bypass stale browser cache.

### 3. Servlet-side response caching

File:
- `source/java/org/rsna/ctp/servlets/ServerServlet.java`

Highlights:
- Added short-TTL in-memory caching for frequently repeated bootstrap/type checks.

### 4. Launcher startup resilience

File:
- `source/java/org/rsna/launcher/Configuration.java`

Highlights:
- Added automatic initialization of default `config.xml` when absent to prevent
  first-run startup failure.

### 5. Default runtime configuration adjustments

File:
- `source/files/examples/example-config.xml`

Highlights:
- Default HTTP listener uses `8080`.
- Added required `import` configuration for Client Directory Import in examples.

---

## Validation

Commands:
- `ant clean all`
- `ant test`

Result:
- Build and tests passed in this stage cycle.

---

## Operational Notes

1. Apply by rebuilding and restarting CTP runtime.
2. If visual updates still appear stale in browser, force reload after restart.

---

## Next Step

Resume suppression-scope reduction as the primary maintainability track,
starting with the remaining eight unchecked suppressions in targeted legacy
classes.
