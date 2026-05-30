# Stop-Point Handover (2026-05-30, Util HTTP Handler Auth Gate)

## Purpose

This stop-point records the relocation of the server-wide authentication gate from servlet selection to the HTTP handler layer.

## What Is Done

1. Moved the `requireAuthentication` gate into `HttpHandler` so it applies before servlet dispatch.
2. Reverted `ServletSelector` to routing-only behavior.
3. Kept a small explicit public set:
   - login flow
   - login page asset(s)
   - `ping`
4. Preserved the local shutdown exception for `servicemanager` requests from localhost.
5. Replaced the selector-level regression test with an HTTP-layer regression test.

## Code Changes

Updated files:

1. `Util/source/java/org/rsna/server/HttpHandler.java`
2. `Util/source/java/org/rsna/server/ServletSelector.java`
3. `source/test/java/org/rsna/server/HttpHandlerSecurityTest.java`

Behavior changes:

1. When `requireAuthentication="yes"`, unauthenticated requests are blocked before servlet dispatch unless they are on the public allowlist.
2. The selector no longer performs the global auth gate.
3. The HTTP-layer gate now owns the policy decision for all requests.

## Validation

1. `ant clean test` completed successfully.
2. Touched files passed syntax/error validation.

## Status Delta vs Checkpoint 19

1. Checkpoint `19` recorded the full-suite refresh.
2. Checkpoint `20` adds the policy correction that moves server-wide auth enforcement to the HTTP handler layer.
3. `20` is now the current source of truth for auth-gate placement.

## Current Operational State

1. Full CTP suite currently passes in this workspace.
2. Global server auth is now enforced at the correct layer.
3. The selector remains responsible only for path-to-servlet routing.

## Files Updated In This Stop-Point

1. `docs/handover/20-stop-point-2026-05-30-util-http-handler-auth-gate.md`
2. `docs/handover/00-overview.md`

## Immediate Next Start

1. Keep checkpoint `20` as the current source of truth for auth gating.
2. Continue any remaining follow-up items from earlier checkpoints only if still relevant.
