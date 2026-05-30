# CTP Handover (Current)

## Scope

This is the active handover set after the 2026-05-30 critical review.

Superseded handover set was archived to:
- docs/handover-archive/2026-05-27-pre-ssl

## Current Status

- SSL rollout issue is operationally resolved.
- Login flow now uses POST from the admin client popup.
- LoginServlet warning spam from browser SSL probing is suppressed to reduce false-positive noise.
- Default server setting for stage profiling is now disabled.
- Object branching stages (`ObjectInlet`, `ObjectFork`, `ObjectRouter`) are implemented and tested.
- RSNA delta documentation has been aligned to RSNA-style composition and corrected for identified behavior mismatches.
- Util login/auth/session and attack-log security review was completed; implementation is now underway per the locked decisions in `16-implementation-plan-2026-05-30-util-http-security-and-handover-fixes.md`.
- Handover consistency review found and documented stale/incorrect guidance, especially around stable notification wiring and old "commit/rebuild next" notes.
- Util HTTP security hardening implementation has progressed through M1-M4, with M5 secure-by-default `HttpUtil` changes applied, follow-up M4 fixes completed, and focused tests passing.
- Global server authentication gating is now enforced at the HTTP handler layer, with the selector reverted to routing-only behavior.
- Stability monitor target resolution now accepts both webhook and exec notification plugins through `StabilityNotificationPlugin`.
- `DicomAuditLogger level="study"` now indexes audit entries at study granularity without object UID indexing.
- `StabilityExecPlugin arguments` now uses command-line templates with DirectoryStorageService-style DICOM placeholders.
- Script editor saves now stay on the editor page and show an update confirmation popup.
- Stage profiling is now disabled by default at runtime unless `enableStageProfiling="yes"` is explicitly set.
- Upstream check confirmed `DicomAuditLogger` has no original true `series` level; it supports `patient`, `study`, and per-object `instance` behavior.
- Latest implementation checkpoint and current truth: `22-stop-point-2026-05-30-runtime-polish-and-defaults.md`.
- Full CTP suite currently passes in this workspace (`ant test`: BUILD SUCCESSFUL).

## Read Order

1. 01-completed-work.md
2. 02-open-items.md
3. 03-next-iteration-plan.md
4. 04-performance-plan.md
5. 05-stop-point-2026-05-27-ssl.md
6. 09-stop-point-2026-05-28-notifications.md
7. 10-stop-point-2026-05-28-object-branching.md
8. 11-stop-point-2026-05-30-build-env-windows.md
9. 12-stop-point-2026-05-30-rsna-delta-doc-alignment.md
10. 13-stop-point-2026-05-30-util-security-review-plan.md
11. 14-stop-point-2026-05-30-handover-critical-review.md
12. 15-stop-point-2026-05-30-util-http-security-augmentation-plan.md
13. 16-implementation-plan-2026-05-30-util-http-security-and-handover-fixes.md
14. 17-stop-point-2026-05-30-util-http-security-implementation-progress.md
15. 18-stop-point-2026-05-30-util-http-security-m4-m5-progress.md
16. 19-stop-point-2026-05-30-util-http-security-full-suite-refresh.md
17. 20-stop-point-2026-05-30-util-http-handler-auth-gate.md
18. 21-stop-point-2026-05-30-stability-exec-and-audit-level-fixes.md
19. 22-stop-point-2026-05-30-runtime-polish-and-defaults.md
