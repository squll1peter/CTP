# Open Items

## Certificate Trust

- Current SSL verification used an untrusted/self-signed trust path for browser testing.
- Replace with CA-signed or enterprise-trusted certificate chain for production users.

## Util HTTP Security Hardening Follow-Up

- Implementation progress through M4 and M5 is recorded in `18-stop-point-2026-05-30-util-http-security-m4-m5-progress.md`.
- Remaining security work items:
  - stabilize and test checkpoint `20` auth-gate behavior at the HTTP-handler layer
  - add integration-style coverage for unsupported-method and internal-error structured security events
  - perform manual browser validation of CSRF token round-trip on admin POST pages
  - audit all `HttpUtil.getConnection` callers for any legacy HTTPS endpoints that now require explicit `getInsecureConnection(...)` compatibility mode
  - remove or fully retire any now-unused legacy attack-log geolocation helper path

## Credential Hygiene

- Remove fallback keystore password usage where possible and require environment-driven secret injection.
- Continue reducing plaintext credential dependence in stage configuration.
- Legacy plaintext `RSNA` auth header is now disabled by default; decide final deprecation/removal timeline.

## Performance Track

- Continue Stage 17 performance roadmap after decompressor removal decision is finalized in active config.

## Object Branching Follow-Up

- Add an example configuration showing two-pipeline `ObjectFork`/`ObjectRouter` + `ObjectInlet` topology.
- Evaluate queue guardrails for inlet injection bursts (high-water alarms, optional max depth policy).
- Decide whether routing for non-DICOM objects is required in future revisions.

## Stable Notification Follow-Up

- Runtime now allows `StabilityExecPlugin` as a `StabilityMonitorProcessor` target through `StabilityNotificationPlugin`.
- Runtime now uses whitespace-delimited command-line templates for `StabilityExecPlugin arguments`.
- Remaining item: perform live validation of `StabilityExecPlugin arguments` with DirectoryStorageService-style placeholders in the active deployment config.
