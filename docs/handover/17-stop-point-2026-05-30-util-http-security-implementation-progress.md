# Stop-Point Handover (2026-05-30, Util HTTP Security Implementation Progress)

## Purpose

This stop-point records implementation progress against `docs/handover/16-implementation-plan-2026-05-30-util-http-security-and-handover-fixes.md` for Util HTTP/security hardening.

This checkpoint includes **code changes and tests**. It is an implementation status handover, not a planning-only handover.

## Scope Completed In This Checkpoint

Implemented and validated portions of Part 1:

1. M1 credential/session containment (substantially complete)
2. M2 HTTP parser and server guardrails (core implementation complete)
3. M3 structured attack logging/event pipeline (core implementation complete)

Not implemented yet in this checkpoint:

1. M4 CORS/CSRF/password migration/throttling
2. M5 outbound HTTPS cleanup

## Locked Decisions Applied

The following locked decisions from `#16` are reflected in code in this checkpoint:

1. Compatibility-breaking hardening changes are acceptable.
2. Session cookie default includes `SameSite=Lax`.
3. Basic auth is accepted only over HTTPS.
4. Plaintext `RSNA` header auth is disabled by default.
5. Login redirects are relative-only.
6. Attack-log geolocation no longer runs in request-handling path.
7. Response headers reject CR/LF injection.

## Code Changes Implemented

### 1) Login/auth/session containment

Updated files:

1. `Util/source/java/org/rsna/servlets/LoginServlet.java`
2. `Util/source/java/org/rsna/server/Authenticator.java`
3. `Util/source/java/org/rsna/server/Session.java`
4. `Util/source/java/org/rsna/service/HttpService.java`
5. `Util/source/java/org/rsna/server/HttpHandler.java`
6. `Util/source/java/org/rsna/server/HttpResponse.java`
7. `Util/source/java/org/rsna/server/HttpRequest.java`

Behavior changes:

1. GET credential login is rejected (including `/login/ajax` credential GET path).
2. POST login remains supported.
3. Session IDs switched from MD5-derived to 192-bit `SecureRandom` tokens (base64url, no padding).
4. Session cookies now include `Path=/; HttpOnly; SameSite=Lax` and conditional `Secure` for HTTPS.
5. Logout cookie mirrors security attributes and uses `Max-Age=0`.
6. Basic auth is rejected on non-HTTPS requests.
7. `RSNA` header auth is off by default (`org.rsna.auth.rsnaHeader.enabled=false` unless explicitly enabled).
8. Internal server errors return HTTP 500 with generic client body (no stack trace leak).
9. Response headers reject CR/LF in header names/values.
10. Request logging is redacted by default for auth/cookie/secret-bearing data.

### 2) HTTP parser/resource guardrails

Updated files:

1. `Util/source/java/org/rsna/server/HttpRequest.java`
2. `Util/source/java/org/rsna/server/HttpHandler.java`
3. `Util/source/java/org/rsna/service/HttpService.java`
4. `Util/source/java/org/rsna/server/HttpServer.java`
5. `Util/source/java/org/rsna/service/HttpService.java`
6. `Util/source/java/org/rsna/server/HttpParseException.java` (new)

Behavior changes:

1. Added parser limits:
   - request line: 8 KiB
   - header line: 8 KiB
   - total headers: 64 KiB
   - header count: 100
   - query string: 8 KiB
   - URL-encoded form body: 1 MiB
2. Added typed parse failure (`HttpParseException`) with status mapping:
   - malformed request/content length -> 400
   - oversized form body -> 413
   - oversized header/count -> 431
3. Preserved `=` inside parameter values (split-on-first-`=` fix).
4. Added control-char rejection in request line and header lines.
5. Added bounded worker queues in both `HttpServer` and `HttpService`:
   - capacity `max(100, maxThreads*50)`
6. Added explicit queue rejection handler sending minimal 503 and closing socket.

### 3) Structured attack logging

Updated files:

1. `Util/source/java/org/rsna/util/AttackLog.java`
2. `Util/source/java/org/rsna/servlets/AttackLogServlet.java`
3. `Util/source/java/org/rsna/server/HttpHandler.java`
4. `Util/source/java/org/rsna/service/HttpService.java`
5. `Util/source/java/org/rsna/servlets/LoginServlet.java`

Behavior changes:

1. Added structured `AttackLog.SecurityEvent` model.
2. Added `AttackLog.recordEvent(SecurityEvent)`.
3. `AttackLog.addAttack(ip)` preserved for compatibility but now records a generic structured parser event.
4. Removed synchronous geolocation lookup from request-path event recording.
5. Added bounded recent-event buffer (`1000` max).
6. Added aggregate category counts.
7. Parse failures and suspicious login redirects now emit structured events.
8. `AttackLogServlet` now includes:
   - existing attacker aggregates
   - category aggregate counts
   - recent structured event list

## Test Coverage Added/Updated

New tests added under `source/test/java`:

1. `org/rsna/server/AuthenticatorSecurityTest.java`
2. `org/rsna/server/HttpResponseHeaderSanitizationTest.java`
3. `org/rsna/server/SessionSecurityTest.java`
4. `org/rsna/servlets/LoginServletSecurityTest.java`
5. `org/rsna/server/HttpRequestParsingLimitsTest.java`
6. `org/rsna/util/AttackLogSecurityEventTest.java`

Covered behaviors include:

1. Basic auth over HTTPS only.
2. `RSNA` header disabled by default.
3. Session cookie/login/logout security attributes.
4. CR/LF header injection rejection (name and value).
5. Session ID strength/format.
6. GET credential login rejection.
7. Relative-only redirect behavior.
8. Query/header/body parser limits (`400/413/431` cases).
9. `=` preservation in query values.
10. Structured attack-event aggregation and bounded retention.

## Commands Run / Evidence

### Build and test commands used

1. `ant -q clean` (Util)
2. `ant -q clean` (CTP)
3. `ant -q clean jar deploy -Dctp=/home/squll1/Workspace/CTP` (Util)
4. `ant -q clean compile-tests` (CTP)
5. Focused JUnitCore security suite run with Byte Buddy javaagent arguments
6. `ant -q test` full suite run (CTP)

### Current test status

1. Focused HTTP/security suite: **passes** (`OK (16 tests)`).
2. Full CTP suite: still fails on existing baseline test
   - `org.rsna.ctp.plugin.StabilityWebhookPluginTest`

## Compatibility / Behavior Breaks

Intentional behavior changes in this checkpoint:

1. GET credential login no longer authenticates.
2. Absolute login redirect targets are rejected (relative-only policy).
3. Basic auth over plaintext HTTP is rejected.
4. `RSNA` auth header no longer works by default.
5. Parser now rejects oversized/malformed requests with explicit status codes.

## Risks / Known Gaps

1. M4 not started in this checkpoint:
   - default CORS hardening
   - CSRF tokens for admin POSTs
   - throttling (`429` + exponential delay)
   - password PBKDF2 migration
   - localhost-only bootstrap on missing `users.xml`
2. M5 outbound HTTPS trust hardening not started.
3. AttackLogServlet XSL rendering assumptions for new XML sections should be manually reviewed in UI.
4. Full suite failure (`StabilityWebhookPluginTest`) remains baseline noise; unrelated to these changes but still blocks a globally green `ant test` signal.

## Repository State Notes

1. Util is now managed as a submodule in CTP and pointed at fork URL:
   - `https://github.com/squll1peter/Util.git`
2. CTP uses deployed `libraries/util.jar` from local Util build for test runs.
3. During implementation, one transient `util.jar` corruption occurred when Util deploy and CTP clean/compile were run in parallel; resolved by rerunning sequentially.
4. Recommendation: keep Util deploy and CTP clean/compile/test **sequential**, not parallel.

## Immediate Next Start

1. Start M4 implementation in this order:
   - CORS default hardening (`OPTIONS` non-reflective)
   - CSRF token support for admin POST endpoints currently relying on Referer
   - throttling with both exponential delay and `429`
   - PBKDF2 migration + legacy MD5 compatibility rewrite-on-login
   - localhost-only bootstrap behavior when `users.xml` missing
2. Add focused tests for each M4 item before moving to M5.
3. Update active handover entry points (`00-overview.md`, `02-open-items.md`, `03-next-iteration-plan.md`) after M4 checkpoint.
