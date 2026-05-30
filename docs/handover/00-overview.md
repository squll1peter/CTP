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
- Util login/auth/session and attack-log security review was completed; implementation is intentionally deferred pending compatibility and deployment-policy confirmations.
- Handover consistency review found and documented stale/incorrect guidance, especially around stable notification wiring and old "commit/rebuild next" notes.
- Util HTTP server, parser, attack detection, and attack logging augmentation plan was completed; no Java implementation changes have been applied yet.
- Decision-complete implementation plan for Util HTTP security hardening and handover/documentation fixes is ready.

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
