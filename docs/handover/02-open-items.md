# Open Items

## Certificate Trust

- Current SSL verification used an untrusted/self-signed trust path for browser testing.
- Replace with CA-signed or enterprise-trusted certificate chain for production users.

## Util Login/Auth Security Hardening

- Source review is complete in `13-stop-point-2026-05-30-util-security-review-plan.md`.
- Immediate containment work is no longer just optional warning cleanup:
  - remove or redact credential-bearing debug logs
  - disable GET credential login, or gate it behind an explicit compatibility setting
  - add session cookie security attributes
  - replace predictable session ID generation
- Confirm compatibility and deployment-policy decisions before coding redirect/proxy and legacy `RSNA` header changes.

## Credential Hygiene

- Remove fallback keystore password usage where possible and require environment-driven secret injection.
- Continue reducing plaintext credential dependence in stage configuration.
- Decide whether the legacy plaintext `RSNA` auth header is removed, restricted, or disabled by default.

## Performance Track

- Continue Stage 17 performance roadmap after decompressor removal decision is finalized in active config.

## Object Branching Follow-Up

- Add an example configuration showing two-pipeline `ObjectFork`/`ObjectRouter` + `ObjectInlet` topology.
- Evaluate queue guardrails for inlet injection bursts (high-water alarms, optional max depth policy).
- Decide whether routing for non-DICOM objects is required in future revisions.

## Stable Notification Follow-Up

- Decide whether `StabilityExecPlugin` should truly be a `StabilityMonitorProcessor` target.
- If yes, refactor the processor to target a shared notification interface instead of `StabilityWebhookPlugin` only.
- Align `ConfigurationTemplates.xml`, `docs/CTP-Delta-From-RSNA-MIRC-CTP.md`, and the handover examples with the current argument syntax: use `{DicomKeywordOrTag}` for DICOM-resolved values and bare values for literals.
- Remove or implement the stale `otherArguments` template attribute. Current runtime parses `arguments` only.
