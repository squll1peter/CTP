# Stop-Point Handover (2026-05-30, Util HTTP Security M4+M5 Progress)

## Purpose

This stop-point captures implementation progress after `#17`, covering:

1. M4 auth/CORS/CSRF/password/throttling hardening
2. M5 outbound HTTPS default-trust hardening in `HttpUtil`

This checkpoint includes implementation and focused test evidence.

## Scope Completed In This Checkpoint

### M4 completed in this checkpoint

1. CORS default hardening (`OPTIONS` no longer reflective)
2. CSRF token enforcement for admin POST endpoints
3. Login throttling with exponential delay and `429`
4. PBKDF2 password storage + legacy MD5 migration on successful login
5. Localhost-only bootstrap behavior when `users.xml` is first created
6. Follow-up fixes from critical review:
   - throttle state is bounded in memory
   - user-manager CSRF rendering no longer uses shared servlet instance state
   - user-manager debug logging uses redacted request summaries
   - PBKDF2 hash creation fails closed instead of falling back to MD5

### M5 partially completed in this checkpoint

1. `HttpUtil` now uses secure HTTPS defaults (hostname and cert validation by platform defaults)
2. Insecure HTTPS path moved behind explicit compatibility method with warning logs

## Code Changes

### CORS and CSRF

Updated files:

1. `Util/source/java/org/rsna/servlets/Servlet.java`
2. `Util/source/java/org/rsna/server/Authenticator.java`
3. `Util/source/java/org/rsna/servlets/UserManagerServlet.java`
4. `Util/source/java/org/rsna/servlets/LoggerLevelServlet.java`
5. `Util/source/files/LoggerLevelServlet.xsl`
6. `Util/source/java/org/rsna/servlets/PasswordServlet.java`

Behavior changes:

1. Default `OPTIONS` now returns `Allow` header only and does not reflect `Origin`, methods, or request headers.
2. Session-bound CSRF token generation and validation added in `Authenticator`.
3. CSRF validation enforced on POST for:
   - `UserManagerServlet`
   - `LoggerLevelServlet`
   - `PasswordServlet`
4. Existing Referer checks retained as defense-in-depth.
5. CSRF token is now emitted in forms/pages for those endpoints.
6. `UserManagerServlet` renders CSRF tokens from request-local values instead of shared servlet fields.
7. `UserManagerServlet` POST debug logging no longer calls unsafe `req.toString()`.

### Login throttling and security events

Updated files:

1. `Util/source/java/org/rsna/servlets/LoginServlet.java`

Behavior changes:

1. Failure tracking key: `remoteIP|username`
2. Window: `5 failures / 5 minutes`
3. Reset: successful login or `15 minutes` inactivity
4. Throttled responses return `429` with bounded exponential delay
5. Structured security events emitted for auth failures and throttle events
6. Throttle state is pruned and capped at 1000 keys.

### PBKDF2 migration and bootstrap local-only policy

Updated files:

1. `Util/source/java/org/rsna/server/Users.java`
2. `Util/source/java/org/rsna/server/UsersXmlFileImpl.java`

Behavior changes:

1. Added request-aware auth path: `authenticate(username, password, req)`
2. New password format: `pbkdf2$iterations$salt$hash`
3. PBKDF2 algorithm: `PBKDF2WithHmacSHA256`
4. Legacy MD5 values are still accepted and rewritten to PBKDF2 on successful login.
5. When `users.xml` is first created, bootstrap mode marks authentication as localhost-only until user data is updated.
6. PBKDF2 hash creation now fails closed if the JDK KDF is unavailable.

### Outbound HTTPS hardening (M5)

Updated file:

1. `Util/source/java/org/rsna/util/HttpUtil.java`

Behavior changes:

1. `HttpUtil.getConnection(...)` now uses secure HTTPS defaults (no accept-all verifier/trust manager on normal path).
2. Added explicit insecure compatibility API:
   - `HttpUtil.getInsecureConnection(String)`
   - `HttpUtil.getInsecureConnection(URL)`
3. Insecure path logs a warning when used.

## Test Coverage Added/Updated

New tests:

1. `source/test/java/org/rsna/servlets/ServletOptionsSecurityTest.java`
2. `source/test/java/org/rsna/server/UsersXmlFileImplSecurityTest.java`
3. `source/test/java/org/rsna/servlets/UserManagerServletSecurityTest.java`

Updated tests:

1. `source/test/java/org/rsna/server/AuthenticatorSecurityTest.java`
   - CSRF token validation case added
2. `source/test/java/org/rsna/servlets/LoginServletSecurityTest.java`
   - bounded throttle-state regression case added
3. `source/test/java/org/rsna/server/HttpRequestParsingLimitsTest.java`
   - 15 second header/setup timeout regression case added

Previously added focused security tests retained:

1. `AuthenticatorSecurityTest`
2. `HttpResponseHeaderSanitizationTest`
3. `SessionSecurityTest`
4. `LoginServletSecurityTest`
5. `HttpRequestParsingLimitsTest`
6. `AttackLogSecurityEventTest`
7. `ServletOptionsSecurityTest`
8. `UsersXmlFileImplSecurityTest`
9. `UserManagerServletSecurityTest`

Focused suite result:

1. `OK (23 tests)`

## Build/Test Evidence

Commands run (sequentially):

1. `ant -q clean` (Util)
2. `ant -q clean` (CTP)
3. `ant -q clean jar deploy -Dctp=/home/squll1/Workspace/CTP` (Util)
4. `ant -q clean compile-tests` (CTP)
5. Focused JUnitCore security suite run with Byte Buddy javaagent args and `-Dnet.bytebuddy.experimental=true` for Java 25
6. `ant -q test` (CTP)

Current status:

1. Focused security suite: passing
2. CTP compile-tests: passing against the freshly deployed `libraries/util.jar`
3. Full suite: still baseline failing at `org.rsna.ctp.plugin.StabilityWebhookPluginTest`

## Compatibility Notes

Intentional behavior changes in this checkpoint:

1. Cross-origin preflight reflection removed by default in base servlet OPTIONS handler.
2. Admin POST requests now require CSRF token in addition to existing policy checks.
3. Login throttling now returns `429` after threshold.
4. Password storage for XML users transitions to PBKDF2.
5. Fresh bootstrap users file enforces localhost-only login behavior until local update.
6. Outbound HTTPS now validates cert/hostname by default.
7. HTTP request setup/header-read timeout is now 15 seconds.

## Remaining Work / Known Gaps

1. M4 follow-up validation still needed for end-to-end UI flows in running app for:
   - CSRF form propagation in all admin pages
   - login throttling UX/message handling
2. M5 caller audit remains to confirm whether any integrations still require explicit insecure mode.
3. Full-suite baseline failure remains outside this hardening scope.
4. Active handover index files (`00-overview.md`, `02-open-items.md`, `03-next-iteration-plan.md`) now reference this checkpoint, but historical stop-points still remain historical and should not be treated as current source of truth.
5. Remaining M2/M3 follow-up: verify unsupported-method and internal-error structured events in integration-style tests.

## Immediate Next Start

1. Run manual browser sanity checks on admin POST pages for CSRF token round-trip.
2. Audit all `HttpUtil.getConnection` callers for any now-failing legacy HTTPS endpoints; migrate only those specific cases to explicit `getInsecureConnection(...)` if unavoidable.
3. Add integration-style tests for unsupported-method and internal-error security events.
