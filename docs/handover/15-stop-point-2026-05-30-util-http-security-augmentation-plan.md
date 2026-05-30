# Stop-Point Handover (2026-05-30, Util HTTP Server Security Augmentation Plan)

## Purpose

This checkpoint is a critical source-level review of `Util/`, focusing on:

1. embedded HTTP server behavior
2. request parsing and response handling
3. login/auth/session handling
4. attack detection
5. attack logging
6. outbound HTTPS helper behavior that affects attack-log enrichment and integrations

No Java implementation files were changed in this checkpoint.

## Review Verdict

The Util HTTP stack is a compact legacy server that can work in controlled intranet deployments, but it lacks several controls expected for an internet-facing or compliance-sensitive administrative surface. The primary risk is not one single bug. It is the combination of permissive parsing, weak log hygiene, legacy credential paths, sparse attack classification, blocking enrichment, and limited resource guardrails.

The hardening should be done in stages. The first stage should remove credential exposure and easy denial-of-service shapes without changing broad product behavior. Later stages should add stricter request validation, richer attack events, redirect/proxy policy, and prevention controls.

## Source Facts Verified

### HTTP server and threading

1. `HttpServer` creates an unbounded `LinkedBlockingQueue<Runnable>` and a fixed-size `ThreadPoolExecutor`.
2. `HttpServer` accepts sockets and submits a new `HttpHandler` for each accepted connection.
3. `HttpService` has the same unbounded queue pattern for service-specific HTTP listeners.
4. `HttpRequest` sets a 60 second socket read timeout, but there is no separate header-line length limit, header count limit, URL length limit, query size limit, or global request-body size limit for form posts.

### Request parsing

1. `HttpRequest.getLine()` reads until newline into a `ByteArrayOutputStream` without a maximum line length.
2. Header parsing reads lines until an empty line, with no maximum header count or aggregate header-byte limit.
3. URL path is decoded once, then normalized to prevent `..` traversal above root.
4. Query parsing uses `split("=")`, which loses values containing additional equals signs.
5. Form POST/PUT bodies with `application/x-www-form-urlencoded` are read fully into memory according to `Content-Length`.
6. If `Content-Length` is negative, malformed, or very large, current form-body allocation behavior is not safely bounded.
7. Multipart parsing has a caller-supplied `maxPostSize`, but the core form parser does not.

### Response handling

1. `HttpHandler` catches exceptions and returns an HTML page containing the Java stack trace.
2. That error response is sent with HTTP 200, explicitly so the browser displays the page.
3. `HttpResponse.redirect()` writes the `Location` header directly from the supplied string.
4. Default `OPTIONS` reflects the request `Origin`, requested method, and requested headers.

### Authentication and sessions

1. `LoginServlet` still accepts GET query credentials for direct login and `/ajax` login.
2. `LoginServlet` logs `req.toVerboseString()`, raw username, and raw password at debug level.
3. `Authenticator` accepts Basic auth.
4. `Authenticator` also accepts plaintext custom `RSNA` header credentials.
5. Session cookies are emitted as `RSNASESSION=<id>; path=/` only.
6. Logout cookie expiration does not mirror path/security attributes beyond `Max-Age=0`.
7. Session IDs are MD5 over username, IP address, and current millisecond time.
8. Session validation binds sessions to the remote socket IP address.
9. Local XML users are stored as unsalted MD5-derived digests.
10. Default `users.xml` bootstrap creates `king/password` and `admin/password` if no users file exists.

### Attack detection and logging

1. `LoginServlet.isAttack()` only checks a small set of substrings in redirect/path values and logs a warning.
2. `LoginServlet.isAttack()` does not record to `AttackLog`.
3. `HttpRequest.getLine()` records to `AttackLog` only when line reading throws an exception.
4. `AttackLog.addAttack()` is synchronized and performs geolocation lookup before returning.
5. `AttackLog.getInfo()` calls `https://secure.geobytes.com/...` with a 60 second read timeout.
6. `AttackLog` aggregates only IP, city, region, country, count, and last timestamp.
7. Attack log state is memory-only and lost on restart.

### Outbound HTTPS helper

1. `HttpUtil.getConnection()` disables HTTPS hostname verification.
2. `HttpUtil.getConnection()` installs an all-trusting X509 trust manager for HTTPS connections.
3. This affects attack-log geolocation and any other Util caller using `HttpUtil.getConnection()`.

## Critical Findings

### 1. Credential material can be exposed in logs

Evidence:

- `LoginServlet.doGet()` and `doPost()` log `req.toVerboseString()`.
- `HttpRequest.toVerboseString()` includes headers, cookies, and parameters.
- `HttpRequest.toString()` includes POST body content for form posts.
- `LoginServlet.login()` logs raw username and raw password values.

Impact:

Debug logging can expose passwords, Basic auth headers, `RSNA` auth headers, and session cookies. This is the highest-priority containment item.

### 2. Request parsing has denial-of-service risks

Evidence:

- Unbounded executor queues in `HttpServer` and `HttpService`.
- No line/header count/header-byte limits in `HttpRequest`.
- Form bodies are read fully into memory without a core maximum.
- Long/slow reads can hold handler threads for up to 60 seconds per read.

Impact:

A remote client can consume memory, queue capacity, and worker threads with oversized headers, many connections, malformed content lengths, or slow bodies.

### 3. Server error responses disclose stack traces to clients

Evidence:

- `HttpHandler` returns the exception stack trace in an HTML response.
- It sends that response with HTTP 200 instead of 500.

Impact:

Implementation details, paths, class names, and request data can leak to unauthenticated clients. The 200 status also weakens monitoring and client behavior.

### 4. Legacy credential paths remain active

Evidence:

- GET login credentials are accepted.
- Basic auth is accepted.
- Plaintext `RSNA` header credentials are accepted.

Impact:

GET credentials leak easily through URLs and logs. The `RSNA` header is plaintext by design. Basic auth may be acceptable only over TLS and with careful logging redaction.

### 5. Outbound HTTPS verification is disabled globally for Util callers using `HttpUtil`

Evidence:

- `HttpUtil` installs `AcceptAllHostnameVerifier` and `AcceptAllX509TrustManager`.

Impact:

Any security-relevant outbound HTTPS call using this helper is vulnerable to interception. For attack-log enrichment, this is particularly poor tradeoff: an optional enrichment call weakens trust and can block request handling.

## High Findings

### 1. Session cookies lack browser protections

Add `HttpOnly`, `Secure` on HTTPS requests, and an explicit SameSite policy. Recommended default is `SameSite=Lax` unless deployments require cross-site SSO/browser flows.

### 2. Session IDs and password digests use MD5

Session IDs should be random tokens from `SecureRandom`. Password storage should migrate away from unsalted MD5-derived digests to a slow salted KDF if compatibility permits.

### 3. Redirect and OpenAM URL construction trust request host

Login redirect checks compare absolute targets against `req.getHost()`, which comes from the Host header. OpenAM redirect construction also uses protocol and host from the request. Host/proxy trust needs explicit policy.

### 4. CORS preflight behavior is too permissive

The base servlet reflects `Origin`, requested methods, and requested headers. This may be harmless for endpoints that do not include credentials or sensitive responses, but it is not a safe default for an administrative server.

### 5. Attack detection is too narrow and disconnected from attack logging

The login detector is a substring filter and only logs a warning. It should be replaced with structured detection events and reason codes, then fed into the same attack-event pipeline as parser anomalies.

## Medium Findings

1. Query parsing loses parameter values containing `=`.
2. Header duplicate handling overwrites previous values instead of preserving or rejecting duplicates.
3. Cookie parsing is simplistic and may mishandle quoted or unusual values.
4. `isReferredFrom()` uses Referer as a CSRF-like check; Referer is useful as a defense-in-depth signal but should not be the only write-protection mechanism for admin endpoints.
5. `AttackLog` lacks capacity/retention limits.
6. `AttackLogServlet` renders aggregated geolocation and counts only; it has limited forensic value.
7. Default bootstrap users with default passwords are risky if any deployment can start from a missing `users.xml`.

## Recommended Augmentation Plan

### M1: Immediate containment

Target: low-risk changes that reduce exposure quickly.

1. Add redaction helpers to `HttpRequest`:
   - redact `Authorization`
   - redact `RSNA`
   - redact `Cookie` and `Set-Cookie`
   - redact parameters named `password`, `passwd`, `pwd`, `token`, `session`, `secret`, `key`
2. Change `LoginServlet` logging:
   - remove raw password logging
   - avoid `toVerboseString()` on credential paths
   - log username only if needed and never log password
3. Disable GET credential login:
   - retain POST login
   - retain logout GET only if accepted
   - optional temporary compatibility flag only if project owner requires it
4. Harden session cookie emission:
   - `Path=/`
   - `HttpOnly`
   - `Secure` when request protocol is HTTPS
   - `SameSite=Lax` by default
   - logout cookie mirrors path/flags and expires reliably
5. Replace session ID generation:
   - use `SecureRandom`
   - at least 128 bits, preferably 192 or 256 bits
   - encode as URL/cookie-safe base64url or hex
6. Change internal server error response:
   - return HTTP 500
   - generic body for clients
   - keep stack trace in server logs only, redacted

Acceptance criteria:

1. Login POST still succeeds.
2. GET credentials no longer authenticate unless explicit compatibility is enabled.
3. Debug logs no longer expose password/auth/session values.
4. Session cookie attributes are present and logout clears the cookie.
5. A forced servlet exception returns 500 without stack trace in response.

### M2: Request parser and server resource guardrails

Target: reduce DoS exposure and make invalid requests classify cleanly.

1. Bound server queues:
   - replace unbounded `LinkedBlockingQueue` with bounded queue
   - define rejection behavior as 503 or immediate socket close
   - expose active/queued/rejected counts
2. Add request parsing limits:
   - maximum request line length
   - maximum header line length
   - maximum header count
   - maximum aggregate header bytes
   - maximum query string length
   - maximum form body size
3. Validate `Content-Length`:
   - reject missing/negative where body is required
   - reject values over configured maximum
   - handle EOF without infinite loop or negative byte count accumulation
4. Introduce typed parse failures:
   - malformed request line -> 400
   - header too large -> 431
   - body too large -> 413
   - method not allowed -> 405
5. Keep socket timeouts but consider shorter header-read timeout than body-read timeout.

Acceptance criteria:

1. Oversized headers are rejected deterministically.
2. Oversized form bodies are rejected before allocation.
3. Thread queue cannot grow without bound.
4. Parse failures generate structured attack/security events.

### M3: Structured attack event pipeline

Target: turn "attack logging" from IP-count aggregation into useful security telemetry.

1. Introduce `AttackEvent` or `SecurityEvent` model:
   - timestamp
   - remote address
   - optional trusted client address
   - method
   - normalized path
   - host
   - user-agent hash or truncated value
   - category/reason code
   - severity
   - authenticated username if available
   - safe detail string
2. Categories should include:
   - malformed request line
   - header too large
   - body too large
   - invalid content length
   - suspicious redirect parameter
   - authentication failure
   - rate-limit event
   - CSRF/referer failure
   - unsupported method
3. Keep a bounded in-memory ring buffer for recent events.
4. Keep aggregate counters by IP and reason.
5. Add optional append-only JSONL persistence with retention/rotation.
6. Update `AttackLogServlet` to show:
   - recent events
   - aggregate counts
   - top IPs
   - top reason codes
   - last event time

Acceptance criteria:

1. Login suspicious URL checks and parser anomalies both reach the same event pipeline.
2. Attack logging never performs network I/O on request-handling threads.
3. UI has enough context for triage without exposing credentials.

### M4: Attack enrichment redesign

Target: remove request-path blocking and privacy surprises.

1. Remove synchronous geolocation from `AttackLog.addAttack()`.
2. Choose one:
   - disable geolocation by default
   - make geolocation explicitly opt-in
   - remove geolocation entirely
3. If kept:
   - perform enrichment asynchronously
   - cache by IP
   - use bounded queue
   - set short connect/read timeouts
   - use validated HTTPS
   - document privacy implications
4. Remove the embedded Geobytes API key from source or move provider config out of code.

Acceptance criteria:

1. `addAttack`/`recordEvent` is constant-time local work.
2. External lookup failure cannot block request processing.
3. Enrichment can be disabled cleanly.

### M5: Auth, CSRF, redirect, and proxy trust policy

Target: harden behavior that depends on deployment shape.

1. Define trusted host/proxy model:
   - direct-to-app only
   - reverse proxy only
   - both, with trusted source ranges
2. Reject or canonicalize unexpected Host headers.
3. Use configured external base URL for OpenAM/login redirects where possible.
4. Prefer relative-only post-login redirects.
5. If absolute redirects remain, use allowlist.
6. Add CSRF tokens for admin POST endpoints:
   - `UserManagerServlet`
   - `LoggerLevelServlet`
   - password/user-changing endpoints
7. Decide Basic auth policy:
   - allow only over HTTPS
   - optionally disable for browser/admin endpoints
8. Decide `RSNA` header policy:
   - remove
   - HTTPS-only
   - trusted-network-only
   - disabled-by-default compatibility flag

Acceptance criteria:

1. Host header manipulation cannot influence allowed redirect targets.
2. Admin POST endpoints require session and CSRF token.
3. Legacy auth behavior is explicit and documented.

### M6: Password and bootstrap hardening

Target: reduce long-term credential risk.

1. Replace local user password storage with a salted slow KDF:
   - PBKDF2, bcrypt, scrypt, or Argon2 depending available dependencies
2. Support migration:
   - recognize legacy digest
   - on successful login, rewrite to new hash
3. Remove or neutralize default `king/password` and `admin/password` bootstrap:
   - generate one-time install password
   - require first-run password creation
   - or bind default users to localhost-only setup until changed
4. Add password policy only if operationally acceptable.

Acceptance criteria:

1. Existing users can migrate safely.
2. A missing `users.xml` does not silently create internet-usable default admin credentials.

### M7: Outbound HTTPS cleanup

Target: stop normalizing insecure TLS.

1. Replace `HttpUtil.getConnection()` all-trust behavior with default JVM certificate validation.
2. Add a clearly named compatibility method for insecure test use only, if required.
3. Audit callers of `HttpUtil.getConnection()` before changing default behavior.
4. Add warnings if insecure mode is enabled.

Acceptance criteria:

1. Default outbound HTTPS validates certificates and hostnames.
2. Any insecure compatibility path is explicit, logged, and disabled by default.

## Decisions Needed From Project Owner

1. Is a breaking change acceptable for GET credential login?
2. Should Basic auth remain enabled for all endpoints, HTTPS-only, API-only, or configurable?
3. What is the future of the plaintext `RSNA` auth header?
4. Are deployments direct-to-app, reverse-proxy, or mixed?
5. What hostnames should be accepted as canonical external hosts?
6. Is `SameSite=Lax` acceptable for the session cookie, or is OpenAM/cross-site behavior expected to require `None`?
7. Should attack geolocation be removed, disabled by default, or kept as opt-in async enrichment?
8. What rate-limit defaults are acceptable for clinical/admin users?
9. Is adding a password-hashing dependency acceptable, or must the solution use only JDK APIs?
10. Is any cross-origin browser access required, or can CORS default to disabled/allowlisted?

## Proposed Implementation Order

1. M1 first: log redaction, POST-only credentials, session cookies, random session IDs, generic 500.
2. M2 next: request/parser limits and bounded queues.
3. M3/M4 together: structured attack events, non-blocking attack logging, optional async enrichment.
4. M5 after deployment answers: host/proxy/redirect/CSRF/auth policy.
5. M6 when migration policy is approved: password storage and bootstrap defaults.
6. M7 after caller audit: outbound HTTPS validation cleanup.

## Test Strategy

Add focused tests under the existing test tree where feasible:

1. `HttpRequest` parser limits:
   - long request line
   - too many headers
   - excessive header length
   - invalid/oversized content length
2. `LoginServlet`:
   - POST success
   - GET credentials rejected
   - redirect validation
   - no credential leakage in formatted request logging helpers
3. `Authenticator`/`Session`:
   - cookie attributes
   - logout cookie parity
   - random token format and uniqueness
4. `AttackLog`:
   - record event does not block on network
   - aggregate counts by reason/IP
   - capacity limits
5. `HttpHandler`:
   - servlet exception returns 500 and generic body
6. CORS/OPTIONS:
   - default no reflection unless allowlist configured

## Files Reviewed

Primary files:

1. `Util/source/java/org/rsna/server/HttpServer.java`
2. `Util/source/java/org/rsna/service/HttpService.java`
3. `Util/source/java/org/rsna/server/HttpHandler.java`
4. `Util/source/java/org/rsna/server/HttpRequest.java`
5. `Util/source/java/org/rsna/server/HttpResponse.java`
6. `Util/source/java/org/rsna/server/ServletSelector.java`
7. `Util/source/java/org/rsna/server/Authenticator.java`
8. `Util/source/java/org/rsna/server/Session.java`
9. `Util/source/java/org/rsna/server/UsersXmlFileImpl.java`
10. `Util/source/java/org/rsna/server/UsersLdapFileImpl.java`
11. `Util/source/java/org/rsna/server/UsersOpenAMImpl.java`
12. `Util/source/java/org/rsna/servlets/LoginServlet.java`
13. `Util/source/java/org/rsna/servlets/Servlet.java`
14. `Util/source/java/org/rsna/servlets/UserManagerServlet.java`
15. `Util/source/java/org/rsna/servlets/LoggerLevelServlet.java`
16. `Util/source/java/org/rsna/servlets/AttackLogServlet.java`
17. `Util/source/java/org/rsna/util/AttackLog.java`
18. `Util/source/java/org/rsna/util/Attack.java`
19. `Util/source/java/org/rsna/util/HttpUtil.java`
20. `Util/source/java/org/rsna/util/SSLConfiguration.java`

## Immediate Next Start

1. Answer the owner-decision questions that affect compatibility.
2. Implement M1 as a small, testable containment change set.
3. Update this handover after M1 with actual code/test status.
