# Stage Summary (0-12)

## Current State

Modernization stages 0 through 12 are complete with green test gates on the
current branch.

Core outcomes:
- Build/test infrastructure stabilized under Ant + JUnit4.
- Critical security remediations implemented (config redaction, server auth gate,
  XML hardening, TLS policy tightening, credential handling improvements).
- Dependency modernization applied with legacy JARs moved out of active runtime paths.
- Reliability fixes applied in pipeline and queue semantics.
- Maintainability work progressed through suppression-scope reduction.
- Servlet authorization race risk removed via immutable request-local auth state.

---

## Key Confirmed Deliverables

1. Security and auth hardening
- `CTPServlet` now returns immutable `AuthState` from `loadParameters(...)`.
- Subclass servlets updated to consume request-local state rather than shared booleans.
- Concurrency regression tests added and passing.

2. Dependency hygiene
- Legacy JARs moved to archive path:
  - `libraries/archive/legacy-2026-05-27`
  - `libraries/archive/legacy-2026-05-27/ftp`
- Active library paths now contain modernized artifacts.
- `build.xml` manifest classpath updated to remove stale `log4j.jar` reference.

3. Reliability semantics corrections
- Export pass counting now records only successful enqueue events.
- Quarantine counters now increment only when insertion actually occurs.

4. Stage 4 integration-style tests completed
- `ServerServletHttpIntegrationTest` added and passing.
- `ConfigLogPasswordIntegrationTest` added and passing.

5. Test status
- `ant clean test` is green after all above changes.

---

## Explicit Exception Decision

A previous attempted `Vector` to `List` migration in PixelMed-integration code was
reverted where API compatibility requires `Vector<Shape>`.

Decision:
- Keep compatibility-required `Vector<Shape>` in that boundary.
- Continue modernization in safe zones only (no behavioral risk at legacy API boundaries).

---

## Remaining Work Queue (post-Stage-12)

1. Continue suppression narrowing in remaining high-volume legacy classes.
2. Keep each batch behavior-preserving and test-gated.
3. Update stage report per batch with exact suppression delta and validation evidence.
