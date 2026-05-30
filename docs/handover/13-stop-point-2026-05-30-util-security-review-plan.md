# Stop-Point Handover (2026-05-30, Util Login and Attack-Log Security Review)

## Purpose

This checkpoint captures a source-level review of the Util login, authentication, request parsing, session-cookie, and attack-log paths. No implementation changes were made in this checkpoint. The intended next action is a policy-confirmed hardening pass, starting with the small containment items that do not require architecture decisions.

## Review Verdict

The current implementation is serviceable for legacy/intranet-style deployment assumptions, but it is not a strong baseline for internet-facing or compliance-sensitive operation. The largest risks are not in `AttackLog` by itself; they are the credential exposure and session/auth compatibility paths around it.

The handover this replaces understated the evidence trail and mixed confirmed defects with policy choices. This version separates confirmed source facts, risk interpretation, and decisions still needed before coding.

## Source Facts Verified

1. `LoginServlet.doGet` still accepts `username` and `password` query parameters for direct web login and `/ajax` login.
2. `LoginServlet.doPost` uses form parameters for login, and the popup client now posts credentials to `/login`.
3. `LoginServlet` logs `req.toVerboseString()` at debug level on GET and POST. `HttpRequest.toVerboseString()` includes headers, cookies, and parameters; `HttpRequest.toString()` includes POST body content when present.
4. `LoginServlet.login` logs the username and password values directly at debug level.
5. `LoginServlet.redirect` rejects empty, attack-looking, and cross-host targets, but same-host validation depends on `req.getHost()`, which comes from the request `Host` header when present.
6. `LoginServlet.isAttack` is a narrow string check over redirect/path input. It warns on newline, carriage return, angle brackets, percent signs, or lowercase `javascript`.
7. `Authenticator.createSession` sets `Set-Cookie: RSNASESSION=<id>; path=/` with no `Secure`, `HttpOnly`, or `SameSite` attribute.
8. `Authenticator.closeSession` expires the session cookie with `RSNASESSION=NONE; Max-Age=0`, also without path/flag parity.
9. `Authenticator.authenticate` accepts Basic auth and the legacy plaintext `RSNA` header.
10. `Session` creates IDs using MD5 over `username:ipAddress:currentTimeMillis`.
11. `HttpRequest.getLine` calls `AttackLog.getInstance().addAttack(ip)` only on request-line/header read exceptions.
12. `LoginServlet.isAttack` logs a warning but does not add entries to `AttackLog`.
13. `AttackLog.addAttack` is synchronized and calls `getInfo(attack)` before returning.
14. `AttackLog.getInfo` performs an HTTPS request to `secure.geobytes.com` with a 60 second read timeout when an IP has no country value.
15. `AttackLog` stores only IP, city, region, country, count, and last timestamp. It does not persist events across process restart.
16. `AttackLogServlet` is admin-only and renders the aggregated in-memory attack table.

## Critical Findings

1. Credential material can be written to debug logs.

   Evidence: `LoginServlet.login` logs raw password values, and request verbose logging can include query parameters, POST bodies, cookies, and authorization headers. This is the top containment item because it can expose reusable secrets without an active exploit beyond enabling or collecting debug logs.

2. GET login with query-string credentials remains active.

   Evidence: `LoginServlet.doGet` authenticates direct web requests and `/ajax` requests when `username` and `password` are present. Query-string credentials are likely to leak through browser history, referer chains, proxies, and request logs. The popup has already moved to POST, so the remaining GET path is legacy compatibility, not the primary UI flow.

## High Findings

1. Session cookies lack modern browser protection attributes.

   Evidence: session creation sets only cookie name, value, and `path=/`. Add `HttpOnly` by default, add `Secure` when the request is HTTPS, and set a SameSite policy. The logout cookie should use matching path/flag attributes so deletion works reliably.

2. Redirect validation trusts the request `Host` header.

   Evidence: `isSameHost` compares absolute redirect targets to `req.getHost()`. In direct deployments that trust the raw Host header, this can turn host-header manipulation into redirect-policy bypass or incorrect absolute URL construction. The right fix depends on whether deployments are direct-to-app or behind trusted proxies.

3. Legacy `RSNA` header authentication is plaintext by design.

   Evidence: `Authenticator.authenticate` accepts `RSNA: username:password` without encoding. This may be required by older CTP integrations, so removal is a product compatibility decision. If kept, it should be disabled by default or restricted to trusted networks/HTTPS.

## Medium Findings

1. Attack logging blocks request parsing on external lookup.

   Evidence: `AttackLog.addAttack` is synchronized and calls `getInfo`, which can wait up to 60 seconds for geolocation lookup. This creates a denial-of-service shape: malformed connections can serialize on the attack-log lock and consume request handling time.

2. Login attack detection is narrow and not a prevention control.

   Evidence: `isAttack` is a substring filter used for login redirect/url handling. It is not case-normalized, does not decode/parse URL targets structurally, and only logs warnings. It should not be treated as comprehensive XSS/open-redirect protection.

3. Attack events have weak forensic value.

   Evidence: the aggregate contains IP, approximate geolocation, count, and last timestamp only. It loses method, path, reason, user-agent, host, and representative request context.

4. Session IDs should move to cryptographic random generation.

   Evidence: the current ID is MD5 over username, IP, and current millisecond timestamp. It is bound to source IP during validation, which helps, but the generator should still be replaced with `SecureRandom` token bytes encoded safely for cookies.

5. Brute-force controls are absent from the reviewed login path.

   Evidence: failed login closes/clears the session but does not rate-limit, back off, or lock out by user/IP. Add this only after deciding operational thresholds and support procedures.

## Recommended vNext Order

### M1: Immediate containment

Do first because these changes reduce exposure and have clear acceptance criteria.

1. Remove or redact credential-bearing logs:
   - no raw password in `LoginServlet`
   - no auth headers, cookie values, password parameters, or POST credential body in verbose request logs
   - tests or targeted assertions for redaction helpers
2. Disable GET credential login:
   - POST remains supported
   - `/login/ajax` no longer accepts credentials in query string unless a temporary compatibility flag is explicitly enabled
   - logout GET behavior may remain if policy allows
3. Harden session cookies:
   - `HttpOnly`
   - `Secure` on HTTPS requests
   - SameSite policy selected by product decision, recommended default `Lax`
   - logout cookie mirrors path/attributes needed to clear the active cookie
4. Replace session ID generation with `SecureRandom`.

### M2: Redirect and host trust model

Do after deployment policy is confirmed.

1. Prefer relative-only post-login redirects when feasible.
2. If absolute redirects are required, validate against a configured allowlist instead of the raw request Host header.
3. Define whether forwarded host/proto headers are honored, and only honor them from trusted proxy source ranges.
4. Parse and normalize redirect targets structurally with `URI`, not substring checks.

### M3: Attack-log redesign

Do after M1 so logging does not remain in the request hot path.

1. Make `AttackLog.addAttack` a cheap in-memory update.
2. Move geolocation enrichment behind an optional asynchronous worker or remove it by default.
3. Store a bounded representative event context:
   - remote address
   - timestamp
   - reason/category
   - method and normalized path
   - host
   - user-agent hash or truncated value, depending privacy policy
4. Add capacity limits and retention policy.
5. Decide whether attack history should persist across restarts.

### M4: Prevention controls

Do after M1 and after support policy is set.

1. Add observe-only counters first.
2. Add IP and username throttling with configurable thresholds.
3. Add temporary lockout only with a documented admin recovery path.
4. Keep successful-login behavior and failure messaging deliberately non-enumerating.

## Decisions Needed Before Coding

1. Is a compatibility break acceptable for GET credential login, or is a one-release disabled-by-default flag required?
2. Should the legacy `RSNA` auth header be removed, HTTPS-only, trusted-network-only, or disabled by default?
3. Are deployments expected to run direct-to-app, behind a reverse proxy, or both?
4. If behind proxies, what source ranges are trusted to provide forwarded host/proto information?
5. Should post-login redirects be relative-only, or should absolute allowlisted targets remain supported?
6. What SameSite value is acceptable for real deployments: `Lax`, `Strict`, or configurable?
7. What brute-force defaults are operationally acceptable for clinical/admin users?
8. Is geolocation enrichment acceptable from a privacy/compliance perspective, and should it be enabled by default?

## Acceptance Criteria for the First Hardening Pass

1. Login with POST succeeds and logout still clears the session.
2. A GET request carrying `username`/`password` no longer authenticates unless an explicit compatibility setting enables it.
3. Debug logs do not contain raw passwords, Basic credentials, RSNA header credentials, session cookie values, or POST login bodies.
4. Session cookies include the selected security attributes and are cleared reliably on logout.
5. Session IDs are random tokens, not MD5 over predictable request/user inputs.
6. Existing Basic auth behavior is either preserved intentionally or changed with release-note coverage.
7. Unit or integration tests cover the changed login/session behavior where the current test harness allows it.

## Repository State

Files updated in this checkpoint:

1. `docs/handover/13-stop-point-2026-05-30-util-security-review-plan.md`
2. `docs/handover/00-overview.md`
3. `docs/handover/02-open-items.md`
4. `docs/handover/03-next-iteration-plan.md`

No Java implementation files were changed for this review checkpoint.

## Immediate Next Start

1. Answer the compatibility and deployment-policy decisions above.
2. Implement M1 as the first code change set.
3. Run focused auth/session tests and add missing tests around redaction and cookie attributes.
4. Update the handover again after M1 decisions are confirmed and implementation status changes.
