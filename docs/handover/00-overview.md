# CTP Handover (Current)

## Scope

This is the active handover after Stage 18.

Superseded handover set was archived to:
- docs/handover-archive/2026-05-27-pre-ssl

## Current Status

- SSL rollout issue is operationally resolved.
- Login flow now uses POST from the admin client popup.
- LoginServlet warning spam from browser SSL probing is suppressed to reduce false-positive noise.
- Default server setting for stage profiling is now disabled.
- Object branching stages (`ObjectInlet`, `ObjectFork`, `ObjectRouter`) are implemented and tested.
- RSNA delta documentation has been aligned to RSNA-style composition and corrected for identified behavior mismatches.

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
