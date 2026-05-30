# Next Iteration Plan

## 1) Auth Gate Stabilization

- Use `20-stop-point-2026-05-30-util-http-handler-auth-gate.md` as the latest implementation baseline.
- Keep global `requireAuthentication` enforcement in `HttpHandler`; keep `ServletSelector` routing-only.
- Keep the unauthenticated public allowlist minimal and explicit: login flow, current login page asset `BaseStyles.css`, and `ping`.
- Add or expand HTTP-handler tests for:
  - unauthenticated protected paths returning `LoginServlet`
  - authenticated protected paths reaching the selected servlet
  - exact public allowlist paths bypassing the auth gate
  - non-allowlisted static-looking paths remaining protected
  - localhost `servicemanager` shutdown bypass
  - remote shutdown remaining protected
- Add integration-style coverage for unsupported-method and internal-error structured security events.
- Run focused security tests, then `ant test`.

## 2) Util Outbound HTTPS Caller Audit

- Audit all `HttpUtil.getConnection(...)` call sites after secure-by-default change.
- For legacy endpoints with non-trusted cert paths, migrate specific callers to explicit `HttpUtil.getInsecureConnection(...)` only when unavoidable.
- Record each compatibility exception with owner and removal plan.

## 3) SSL Production Readiness

- Install trusted certificate and full chain.
- Verify browser behavior on Chrome and Firefox with no warning interstitial.
- Re-check login and logout flows on HTTPS only.

## 4) Authentication and LDAP Review

- Decide final deprecation/removal timeline for the legacy plaintext `RSNA` auth header (currently disabled by default).
- Validate LDAP providerURL uses ldaps:// in all active deployments.
- Confirm no plaintext secrets are committed in config snapshots.

## 5) Stable Notification Alignment

- Validate the live `StabilityExecPlugin` path using the active deployment config.
- Confirm `arguments="-i=/.../{StudyInstanceUID} ..."` resolves the same DICOM placeholder style as `DirectoryStorageService structure`.
- If runtime validation passes, rebuild any deployment package or installer artifact that must carry the updated template and jar.

## 6) Performance Continuation

- Run workload after decompressor removal in active pipeline.
- Compare stage timings for IDMap, anonymizer, and storage stages.
