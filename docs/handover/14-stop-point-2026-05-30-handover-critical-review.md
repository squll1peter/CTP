# Stop-Point Handover (2026-05-30, Critical Review of Active Handovers)

## Purpose

This checkpoint reviews the active `docs/handover` set for stale, contradictory, bizarre, or implementation-mismatched guidance. The goal is to make the next work session start from a coherent plan instead of inheriting old stop-point instructions as if they were still current.

## Review Scope

Reviewed active handover files:

1. `00-overview.md`
2. `01-completed-work.md`
3. `02-open-items.md`
4. `03-next-iteration-plan.md`
5. `04-performance-plan.md`
6. `05-stop-point-2026-05-27-ssl.md`
7. `06-stable-notification-spec.md`
8. `07-stable-notify-local-receiver.md`
9. `08-local-command-notification-spec.md`
10. `09-stop-point-2026-05-28-notifications.md`
11. `10-stop-point-2026-05-28-object-branching.md`
12. `11-stop-point-2026-05-30-build-env-windows.md`
13. `12-stop-point-2026-05-30-rsna-delta-doc-alignment.md`
14. `13-stop-point-2026-05-30-util-security-review-plan.md`

Source was spot-checked where handover claims looked suspicious, especially the stability notification plugins and `StabilityMonitorProcessor`.

## Critical Findings

### 1. `StabilityExecPlugin` was documented as wired, but it is not currently wired

Several handovers/specs described `StabilityExecPlugin` as a drop-in target for `StabilityMonitorProcessor`. Current source does not support that:

- `StabilityMonitorProcessor` imports and stores `StabilityWebhookPlugin`.
- `start()` accepts only registered plugins that are instances of `StabilityWebhookPlugin`.
- A `targetID` pointing to `StabilityExecPlugin` will warn and drop notifications.

This was the most dangerous handover issue because it would lead to a plausible but non-working configuration.

Status after this review:

- Corrected `01-completed-work.md`, `02-open-items.md`, `03-next-iteration-plan.md`, `06-stable-notification-spec.md`, `08-local-command-notification-spec.md`, `09-stop-point-2026-05-28-notifications.md`, and `12-stop-point-2026-05-30-rsna-delta-doc-alignment.md`.
- Added a plan item to decide whether command notifications are truly in scope. If yes, the processor needs a shared notification interface.

### 2. Argument syntax was inconsistent and sometimes wrong

Older docs said `arguments="patientID=PatientID"` meant "resolve PatientID from DICOM." Current plugin code does not do that. Current runtime behavior is:

- `arguments` is parsed as `key=value`.
- Values wrapped in braces, for example `{PatientID}`, are resolved from DICOM.
- Bare values, for example `source=CTP`, are literals.
- `:` is accepted as a fallback delimiter.

Status after this review:

- Updated local receiver and specs to show `patientID={PatientID}` style examples.
- Clarified that static values should currently go directly in `arguments`.

### 3. `otherArguments` appears in templates/docs but is not consumed by runtime code

The templates and some docs expose `otherArguments`, but current plugin source parses only `arguments`.

This is not necessarily a bug if the product decision is to keep one mixed argument list. It is a documentation/template mismatch either way.

Status after this review:

- Marked `otherArguments` as template-only/stale in the handover docs.
- Added a plan decision: either implement `otherArguments` or remove it from templates and operator docs.

### 4. Historical "commit/rebuild next" notes were being presented like current instructions

Some stop-points correctly described state at the time they were written, but their "Immediate Next Start" sections had aged badly. Examples:

- `09-stop-point-2026-05-28-notifications.md` said binaries had not been rebuilt.
- `11-stop-point-2026-05-30-build-env-windows.md` later said `ant jar` and `ant installer` had been run.
- Several files said "commit this stop-point set" without first saying to check current `git status`.

Status after this review:

- Reworded stale repository-state sections as historical.
- Changed immediate-next wording to require checking current `git status` before assuming old pending work still exists.

### 5. Security hardening priority needed to be promoted

Older files framed LoginServlet work as optional warning cleanup. The Util source review in stop-point 13 shows it is now a real security-hardening track:

- credential-bearing debug logs
- GET credential login
- bare session cookies
- MD5/time-based session IDs
- plaintext legacy `RSNA` auth header decision

Status after this review:

- `02-open-items.md` and `03-next-iteration-plan.md` now make Util login/auth containment a top next-iteration item.

## Bizarre or Inappropriate Points Now Clarified

1. "Exec plugin is a drop-in alternative" was inappropriate because current source rejects it as the target plugin type.
2. "otherArguments" was inappropriate as a supported runtime feature because current source ignores it.
3. "arguments values are DICOM keywords" was misleading because current source requires braces for DICOM resolution.
4. "No binaries rebuilt yet" was stale after later Windows build validation.
5. "Commit this pending set" was stale as an instruction without first checking the current worktree.
6. "Optional LoginServlet hardening" understated the security exposure after source review.

## Decisions Needed From Project Owner

These are the points where the handover should not pretend certainty:

1. Should `StabilityExecPlugin` be a real target of `StabilityMonitorProcessor`?
2. Should `otherArguments` be implemented, or should it be removed from templates/docs?
3. Is a compatibility break acceptable for GET credential login, or is a disabled-by-default compatibility flag required?
4. Should the legacy plaintext `RSNA` auth header be removed, restricted, or retained behind configuration?
5. Are deployments direct-to-app, reverse-proxy, or both?
6. Should post-login redirects be relative-only, or should absolute allowlisted redirects remain supported?
7. Should stable-notification docs be consolidated into the RSNA delta doc after runtime/doc alignment?

## Updated Plan

### P0: Stabilize handover truth

Complete in this checkpoint:

1. Mark stale notification specs as historical/current-source-corrected.
2. Correct argument examples to use `{DicomKeywordOrTag}`.
3. Flag `otherArguments` as not consumed by runtime.
4. Flag `StabilityExecPlugin` as implemented but not wired to `StabilityMonitorProcessor`.
5. Reframe old commit/rebuild notes as historical.

### P1: Util login/auth containment

Do next unless project owner redirects:

1. Remove/redact credential-bearing logs.
2. Disable GET credential login or add an explicit compatibility flag.
3. Add secure session cookie attributes.
4. Replace session ID generation with cryptographic random tokens.
5. Add focused tests for the changed login/session behavior.

### P2: Stable notification runtime/doc alignment

Do after the `StabilityExecPlugin` decision:

1. If exec notifications are in scope, introduce a shared notification interface and update `StabilityMonitorProcessor` target resolution.
2. If exec notifications are not in scope, remove drop-in-target language from templates and docs.
3. Implement or remove `otherArguments`.
4. Re-run affected tests and update examples.

### P3: Deployment policy and redirect hardening

Do after deployment model is confirmed:

1. Define direct/proxy trust model.
2. Choose relative-only redirects or absolute allowlist.
3. Normalize redirect handling structurally with `URI`.
4. Decide forwarded-host/proto behavior only for trusted proxies.

### P4: Performance and object-branching follow-up

Resume after P1/P2 risk is under control:

1. Add object-branching integration config example.
2. Evaluate `ObjectInlet` queue guardrails and monitoring.
3. Continue performance baseline comparison.

## Files Updated In This Checkpoint

1. `docs/handover/00-overview.md`
2. `docs/handover/01-completed-work.md`
3. `docs/handover/02-open-items.md`
4. `docs/handover/03-next-iteration-plan.md`
5. `docs/handover/06-stable-notification-spec.md`
6. `docs/handover/07-stable-notify-local-receiver.md`
7. `docs/handover/08-local-command-notification-spec.md`
8. `docs/handover/09-stop-point-2026-05-28-notifications.md`
9. `docs/handover/11-stop-point-2026-05-30-build-env-windows.md`
10. `docs/handover/12-stop-point-2026-05-30-rsna-delta-doc-alignment.md`
11. `docs/handover/14-stop-point-2026-05-30-handover-critical-review.md`

No Java implementation files were changed in this checkpoint.
