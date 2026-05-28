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

## Object Branching Follow-Up

- Add an example configuration showing two-pipeline `ObjectFork`/`ObjectRouter` + `ObjectInlet` topology.
- Evaluate queue guardrails for inlet injection bursts (high-water alarms, optional max depth policy).
- Decide whether routing for non-DICOM objects is required in future revisions.
