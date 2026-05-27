# Open Items

## Certificate Trust

- Current SSL verification used an untrusted/self-signed trust path for browser testing.
- Replace with CA-signed or enterprise-trusted certificate chain for production users.

## LoginServlet Behavior (Optional Hardening)

- False-positive probe warnings are now suppressed at logger level.
- If desired, next step is a code-level refinement in LoginServlet host/redirect validation logic (requires util.jar source ownership or override strategy).

## Credential Hygiene

- Remove fallback keystore password usage where possible and require environment-driven secret injection.
- Continue reducing plaintext credential dependence in stage configuration.

## Performance Track

- Continue Stage 17 performance roadmap after decompressor removal decision is finalized in active config.
