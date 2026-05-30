# Next Iteration Plan

## 1) SSL Production Readiness

- Install trusted certificate and full chain.
- Verify browser behavior on Chrome and Firefox with no warning interstitial.
- Re-check login and logout flows on HTTPS only.

## 2) Util Login/Auth Containment

- Execute M1 from `13-stop-point-2026-05-30-util-security-review-plan.md`.
- Remove or redact credential-bearing debug logs.
- Disable GET credential login unless a compatibility flag is explicitly approved.
- Add `HttpOnly`, HTTPS-conditional `Secure`, and selected `SameSite` session cookie attributes.
- Replace MD5/time-based session IDs with cryptographic random tokens.

## 3) Authentication and LDAP Review

- Decide the future of the legacy plaintext `RSNA` auth header.
- Validate LDAP providerURL uses ldaps:// in all active deployments.
- Confirm no plaintext secrets are committed in config snapshots.

## 4) Redirect, Proxy, and Attack-Log Follow-Up

- Confirm direct-to-app versus reverse-proxy deployment model.
- Choose relative-only redirects or configured absolute redirect allowlist.
- Move attack-log geolocation lookup out of synchronized request-path logging before adding richer event context.
- Define brute-force observe/throttle/lockout defaults before implementing prevention controls.

## 5) Stable Notification Alignment

- Decide whether command notifications are in scope for stable-group events.
- If command notifications are in scope, introduce a small shared interface for notification plugins and let `StabilityMonitorProcessor` resolve either webhook or exec plugins through that interface.
- If command notifications are not in scope, remove the drop-in alternative claim from templates and operator docs.
- Fix examples to use current argument syntax and remove stale `otherArguments` examples unless runtime support is added.

## 6) Performance Continuation

- Run workload after decompressor removal in active pipeline.
- Compare stage timings for IDMap, anonymizer, and storage stages.
