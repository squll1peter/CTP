# Implementation Plan (2026-05-30, Util HTTP Security and Handover Fixes)

## Purpose

This document turns the approved Util HTTP security hardening plan into an implementation-ready plan and adds a companion plan for fixing the handover/documentation oddities found in the critical handover review.

No Java implementation has been done in this checkpoint. This is the plan to execute next.

## Next-Agent Notes

1. Treat this document as the implementation source of truth, but verify the current source before editing because the workspace may already contain uncommitted handover changes.
2. Keep implementation phases small. Finish code, focused tests, and handover update for one phase before starting the next.
3. Add tests under `source/test/java`, matching the existing JUnit 4 layout. There is no separate Util test tree in this checkout.
4. Use `ant test` for the full suite and targeted JUnit runs only when faster feedback is needed. The Ant test classpath already includes JUnit, Hamcrest, Mockito, and Byte Buddy.
5. Do not edit generated files under `build/`. Edit sources under `Util/source/java`, `source/java`, `source/resources`, and docs only.
6. Avoid changing public compatibility beyond the locked security decisions. When a behavior break is intentional, document it in the stop-point.
7. Preserve existing coding style unless the change needs a small helper class for clarity, for example typed HTTP parse exceptions or security event records.

## Locked Decisions

1. Compatibility-breaking security changes are acceptable.
2. Deployment model is direct-to-app. Do not honor `Forwarded` or `X-Forwarded-*` headers.
3. `SameSite=Lax` is the session-cookie default.
4. Basic auth remains available only over HTTPS.
5. Plaintext `RSNA` auth header is disabled by default.
6. CORS is disabled by default.
7. Attack-log geolocation is disabled by default and must not run on request-handling threads.
8. Password migration uses JDK `PBKDF2WithHmacSHA256`; no new dependency.
9. Brute-force protection is throttling only; no account lockout.
10. Response header values are treated as untrusted. Reject or sanitize CR/LF in all response headers, including redirects.
11. First-run local-user bootstrap must not create remote-usable default credentials. Prefer localhost-only setup until an admin password is changed.

## Part 1: Util HTTP Security Implementation Plan

### 1. Credential and session containment

Implement first.

1. Add redacted request-formatting helpers in `HttpRequest`.
   - Redact `Authorization`, `RSNA`, `Cookie`, and any future `Set-Cookie`-style values.
   - Redact parameter names containing `password`, `passwd`, `pwd`, `token`, `session`, `secret`, or `key`.
   - Make existing request string/list helpers safe by default or add clearly named unsafe/internal variants.
   - Redact duplicate-parameter lists as well as first-value parameter accessors.
   - Truncate very long logged values after redaction checks so attacker-controlled input cannot flood logs.
2. Update `LoginServlet`.
   - Remove raw username/password debug lines.
   - Replace verbose request logging with redacted request logging.
   - Reject GET requests carrying `username` or `password`; do not authenticate them.
   - Keep POST login.
   - Keep logout flow.
3. Update `Authenticator` and `Session`.
   - Generate session IDs with `SecureRandom` using at least 192 bits.
   - Encode session IDs as cookie-safe base64url or hex.
   - Emit session cookies as `Path=/; HttpOnly; SameSite=Lax`.
   - Add `Secure` when `req.getProtocol()` is `https`.
   - Logout cookie must mirror `Path`, `HttpOnly`, `SameSite`, and HTTPS-conditional `Secure`.
   - Basic auth succeeds only on HTTPS requests.
   - Ensure HTTPS detection works for both `HttpServer` and `HttpService`; pass server/SSL context into `HttpRequest` rather than relying on the current `new HttpRequest(socket)` default.
   - `RSNA` header auth is disabled by default. Do not preserve it silently.
4. Update server error handling in `HttpHandler`.
   - Return HTTP 500, not 200.
   - Client body is generic and contains no stack trace.
   - Server log keeps stack trace, using redacted request summary.
5. Update `HttpResponse`.
   - Reject or safely normalize CR/LF in all header names and values.
   - Apply this to `setHeader(...)`, `redirect(...)`, and generated header output.
   - Keep login redirect targets relative-only, but do not rely on caller validation alone for response-splitting protection.
   - Add tests for `Location` response splitting attempts such as `%0d%0aSet-Cookie:`.

### 2. HTTP parser and server guardrails

Implement after containment.

1. Bound server queues in `HttpServer` and `HttpService`.
   - Queue capacity: `max(100, maxThreads * 50)`.
   - On rejection, send minimal `503 Service Unavailable` when possible, then close socket.
   - Use an explicit rejection handler; do not rely on default `AbortPolicy` stack traces.
2. Add parser limits in `HttpRequest`.
   - Request line: 8 KiB.
   - Single header line: 8 KiB.
   - Total headers: 64 KiB.
   - Header count: 100.
   - Query string: 8 KiB.
   - URL-encoded form body: 1 MiB.
   - Apply limits before URL decoding or array allocation.
   - Reject raw control characters in the request line and header values except for the HTTP line terminators being parsed.
3. Add typed parse failure handling.
   - Malformed request line or content length: 400.
   - Body too large: 413.
   - Header too large/header count exceeded: 431.
   - Unsupported method: 405.
   - Implement explicit parse exceptions carrying HTTP status, security-event category, and safe detail.
   - Catch typed parse exceptions in both `HttpHandler` and `HttpService.Handler`.
   - Do not let `HttpRequest.getLine()` swallow parser failures and return an empty line for limit/EOF/malformed cases that should produce a response.
4. Use a 15 second setup/header-read timeout.
   - Preserve existing body/socket behavior unless tests reveal a regression.
   - Restore or document any longer body-read timeout needed for legitimate uploads after headers are parsed.
5. Fix form parsing edge cases while adding limits.
   - Reject negative/missing invalid `Content-Length` where a form body must be read.
   - Avoid allocating arrays from unchecked content length.
   - Handle EOF cleanly.
   - Preserve `=` characters inside parameter values by splitting on the first `=` only.

### 3. Structured attack detection and logging

Implement after parser limits are in place.

1. Add a structured security event model.
   - Fields: timestamp, remote IP, method, normalized path, host, category, severity, safe detail, optional username, and truncated or hashed user-agent.
   - Categories: malformed request, header too large, body too large, invalid content length, suspicious redirect, auth failure, throttle event, CSRF/referer failure, unsupported method, internal error.
   - Keep `safe detail` short and non-sensitive; it should describe the class of failure, not echo raw attack input.
2. Update `AttackLog`.
   - Add `recordEvent(SecurityEvent event)`.
   - Keep compatibility `addAttack(String ip)`, but make it record a generic local parser event only.
   - Keep a bounded recent-event ring buffer of 1000 events.
   - Keep aggregate counters by IP and category.
   - Provide snapshot accessors for servlet rendering so callers do not iterate mutable internal structures.
   - Store structured events in memory only in this phase.
   - Remove synchronous geolocation from `addAttack`.
3. Update detection call sites.
   - Parser failures record structured events.
   - Suspicious login redirects record structured events.
   - Authentication failures and throttling record structured events.
   - Server internal errors record structured events without credential data.
4. Update `AttackLogServlet`.
   - Show recent events and aggregate counts.
   - Do not show credentials, cookies, tokens, or full auth headers.
   - Keep admin-only access.

### 4. Auth, CORS, redirects, CSRF, and passwords

Implement after structured events are available.

1. Redirect policy.
   - Login redirect targets are relative-only.
   - Reject absolute redirect targets for now.
   - Permit only root-relative paths beginning with `/`; reject scheme-relative paths beginning with `//`.
   - Normalize and reject backslash-containing targets.
   - Do not use `Forwarded` or `X-Forwarded-*`.
2. CORS.
   - Default `OPTIONS` must not reflect arbitrary `Origin`, methods, or headers.
   - Return minimal allowed methods without cross-origin headers unless future config adds an allowlist.
3. CSRF.
   - Add session-bound CSRF tokens for admin POST endpoints currently relying on Referer checks.
   - Apply first to user-management and logger-level changes.
   - Inventory every POST endpoint that relies on `HttpRequest.isReferredFrom(...)`.
   - Explicitly mark any endpoint left on Referer-only protection as accepted residual risk for that phase.
   - Keep Referer validation as defense in depth after adding tokens, unless it breaks legitimate same-origin flows.
4. Password hashing.
   - Add PBKDF2 storage format, for example `pbkdf2$iterations$salt$hash`.
   - Use at least 100,000 iterations, 128-bit random salt, and a 256-bit derived key unless compatibility testing forces a different value.
   - Recognize legacy MD5 digest values.
   - On successful legacy login, rewrite that user to PBKDF2.
   - Preserve the XML user/role model; do not introduce a new dependency or database.
   - Decide whether the root `mode` attribute remains `digest` for mixed legacy/PBKDF2 values or is changed only after all users migrate.
   - If `users.xml` is missing, do not create remote-usable `king/password` or `admin/password`.
   - First-run bootstrap should require explicit setup or be localhost-only until password change.
   - Use constant-time comparison for password hash verification.
5. Login throttling.
   - Track failures by remote IP and username.
   - After 5 failures in 5 minutes, return 429 or apply exponential delay.
   - Reset counters on successful login or 15 minutes of inactivity.
   - No account lockout.
   - Keep throttle state bounded and in memory for this phase.

### 5. Outbound HTTPS cleanup

Implement after the request-path security work.

1. Stop using all-trusting HTTPS by default in `HttpUtil`.
2. Default outbound HTTPS must validate certificates and hostnames.
3. If insecure HTTPS is still needed for tests or legacy integrations, expose it through a clearly named opt-in compatibility path and log a warning when used.
4. Attack-log geolocation remains disabled by default and should not be reintroduced unless configured as async opt-in with validated HTTPS.
5. Audit current callers before changing the default, especially HTTP export/import services, STOW-RS services, polling import, and OpenAM utilities.

## Part 2: Handover and Documentation Fix Plan

Execute this after the Util HTTP security phases unless a documentation mismatch blocks a security change. The Util security work should not be blocked by the `StabilityExecPlugin` product decision.

### 1. Fix stable-notification runtime/documentation mismatch

Current oddity:

- Docs previously implied `StabilityExecPlugin` was a drop-in `StabilityMonitorProcessor` target.
- Runtime currently resolves only `StabilityWebhookPlugin`.

Plan:

1. Implement a shared notification plugin interface only if command notifications are still desired for stable groups.
2. Recommended implementation: introduce a small interface with `boolean notify(DicomObject representative)` and have both webhook and exec plugins implement it.
3. Update `StabilityMonitorProcessor` to resolve the target plugin through that interface.
4. Add tests proving webhook and exec targets both resolve and fire.
5. If implementation is deferred, keep docs explicitly saying exec is implemented but not wired.

### 2. Remove stale `otherArguments` surface unless runtime support is added

Current oddity:

- Templates expose `otherArguments`.
- Runtime parses only `arguments`.
- Runtime already supports literals in `arguments`, so `otherArguments` is redundant.

Plan:

1. Recommended fix: remove `otherArguments` from templates and examples.
2. Keep one argument syntax: `key={DicomKeyword}` for DICOM values and `key=literal` for static values.
3. Update `docs/CTP-Delta-From-RSNA-MIRC-CTP.md`, handover examples, and template help text.
4. If product owner later wants separate static arguments, implement code support first, then re-document it.

### 3. Clean historical stop-point instructions

Current oddity:

- Some stop-points still contain historical "pending commit/rebuild" language.

Plan:

1. Do not delete historical stop-point facts.
2. Label historical repository-state notes as historical.
3. Keep `00-overview.md`, `02-open-items.md`, and `03-next-iteration-plan.md` as the active entry points.
4. Add a short "current truth" note to old specs when they are not safe implementation sources.

### 4. Align RSNA delta documentation after runtime decisions

Plan:

1. After the stability notification decision, update the RSNA delta doc to match runtime.
2. Ensure examples use current syntax only.
3. Remove "current runtime parses only arguments" notes once templates and runtime agree.
4. Keep operator docs component-first and concise.

### 5. Update active handover after implementation

Plan:

1. Add a new stop-point after each implemented phase.
2. Each stop-point must state:
   - code changed
   - tests run
   - behavior changed
   - compatibility break
   - remaining risk
3. Update `00-overview.md`, `02-open-items.md`, and `03-next-iteration-plan.md` after each phase.

## Test Plan

### Util HTTP/security tests

Add focused JUnit 4 tests under `source/test/java/org/rsna/server`, `source/test/java/org/rsna/servlets`, and `source/test/java/org/rsna/util` as appropriate. Prefer small socket-level or direct-object tests over broad application startup tests unless the behavior requires an actual listener.

1. POST login succeeds.
2. GET credentials do not authenticate.
3. Basic auth works only over HTTPS.
4. `RSNA` header does not authenticate by default.
5. Session cookie includes `Path=/`, `HttpOnly`, `SameSite=Lax`, and HTTPS-conditional `Secure`.
6. Logout cookie clears with matching attributes.
7. Redacted request helpers do not expose credentials, cookies, tokens, or session IDs.
8. Oversized request line, headers, query, and form body return expected status codes.
9. Malformed content length returns 400.
10. Queue rejection returns/closes with 503 behavior.
11. Servlet exception returns generic 500 with no stack trace in response.
12. Parser failures, suspicious redirects, auth failures, and throttles create security events.
13. Attack-log event buffers are bounded and perform no network I/O.
14. Default OPTIONS no longer reflects arbitrary CORS headers.
15. Admin POST endpoints require CSRF tokens.
16. Legacy MD5 password login migrates to PBKDF2.
17. Missing `users.xml` does not create remote-usable default admin credentials.
18. Response headers reject or neutralize CR/LF injection.
19. `HttpService` HTTPS requests are recognized as HTTPS for auth/cookie decisions.
20. Password hash verification uses constant-time comparison.
21. Throttle state is bounded and resets according to the configured windows.

### Handover/documentation tests

1. Search docs for stale argument examples such as `patientID=PatientID`.
2. Search docs/templates for unsupported `otherArguments`.
3. Search handovers for stale "commit this pending set" instructions.
4. Verify RSNA delta examples match runtime.
5. Verify active read order points to the newest implementation stop-points.

## Execution Order

1. Implement Util M1 containment.
2. Run focused login/auth/session/redaction tests.
3. Implement parser/server guardrails.
4. Run parser/server rejection tests.
5. Implement structured attack logging and update `AttackLogServlet`.
6. Run attack-log tests.
7. Implement CORS, CSRF, redirect, throttling, and PBKDF2 migration.
8. Run admin/auth/password tests.
9. Clean `HttpUtil` outbound HTTPS behavior.
10. Run caller-focused outbound HTTP tests.
11. Update active handover for the completed Util phases.
12. Decide whether `StabilityExecPlugin` should be a `StabilityMonitorProcessor` target.
13. Fix stable-notification runtime/doc mismatch according to that decision.
14. Clean docs/templates and update active handover.

## Definition of Done Per Phase

1. Code changes are limited to the phase scope unless a dependency is unavoidable.
2. New or updated tests cover the behavior changed in that phase.
3. `ant test` passes, or any failure is documented with the exact failing test and why it is unrelated or deferred.
4. No credential, cookie, token, or raw authorization value appears in added logs, servlet output, or test failure messages.
5. The stop-point handover records code changed, tests run, behavior changed, compatibility break, and remaining risk.
6. Active handover entry points are updated when the phase changes current truth.

## Files To Update

Likely Java targets:

1. `Util/source/java/org/rsna/server/HttpRequest.java`
2. `Util/source/java/org/rsna/server/HttpHandler.java`
3. `Util/source/java/org/rsna/server/HttpResponse.java`
4. `Util/source/java/org/rsna/server/HttpServer.java`
5. `Util/source/java/org/rsna/service/HttpService.java`
6. `Util/source/java/org/rsna/server/Authenticator.java`
7. `Util/source/java/org/rsna/server/Session.java`
8. `Util/source/java/org/rsna/server/UsersXmlFileImpl.java`
9. `Util/source/java/org/rsna/servlets/LoginServlet.java`
10. `Util/source/java/org/rsna/servlets/Servlet.java`
11. `Util/source/java/org/rsna/servlets/UserManagerServlet.java`
12. `Util/source/java/org/rsna/servlets/LoggerLevelServlet.java`
13. `Util/source/java/org/rsna/servlets/PasswordServlet.java`
14. `Util/source/java/org/rsna/servlets/AttackLogServlet.java`
15. `Util/source/java/org/rsna/util/AttackLog.java`
16. `Util/source/java/org/rsna/util/Attack.java`
17. `Util/source/java/org/rsna/util/HttpUtil.java`

Likely documentation targets:

1. `docs/handover/00-overview.md`
2. `docs/handover/02-open-items.md`
3. `docs/handover/03-next-iteration-plan.md`
4. `docs/CTP-Delta-From-RSNA-MIRC-CTP.md`
5. `source/resources/ConfigurationTemplates.xml`

Likely test targets to create or update:

1. `source/test/java/org/rsna/server/HttpRequestSecurityTest.java`
2. `source/test/java/org/rsna/server/HttpResponseSecurityTest.java`
3. `source/test/java/org/rsna/server/AuthenticatorSecurityTest.java`
4. `source/test/java/org/rsna/server/UsersXmlFileImplPasswordTest.java`
5. `source/test/java/org/rsna/servlets/LoginServletSecurityTest.java`
6. `source/test/java/org/rsna/servlets/ServletCorsCsrfTest.java`
7. `source/test/java/org/rsna/util/AttackLogSecurityEventTest.java`
8. `source/test/java/org/rsna/util/HttpUtilTlsTest.java`

## Immediate Next Start

1. Start with Util M1 containment.
2. Keep commits small by phase.
3. After each phase, add or update a stop-point handover before proceeding.
