# Stage 2 Report — Characterisation Tests

**Date:** 2025-05-26  
**Tests before:** 24  
**Tests after:** 36 (all GREEN)  
**Build:** `ant test` → BUILD SUCCESSFUL

---

## Summary

Stage 2 added characterisation tests that document existing behaviour of two important subsystems.
All tests were written to pass GREEN immediately — their purpose is to prevent regressions rather than drive new implementation.

---

## Tests Added

### AnonymizerFunctionsTest (8 tests)
**File:** `source/test/java/org/rsna/ctp/stdstages/anonymizer/AnonymizerFunctionsTest.java`

Covers the static utility methods most frequently used in anonymization scripts:

| Method | Test | Observed Behaviour |
|---|---|---|
| `initials(String)` | null input | Returns `"X"` |
| `initials(String)` | empty input | Returns `"X"` |
| `initials(String)` | `"Smith^Fred^Michael"` | Returns `"FMS"` (first word is last name; last initial moved to end) |
| `round(String, int)` | null input | Returns `""` |
| `round(String, int)` | `"55Y"` width 5 | Returns `"055Y"` (zero-padded to even length) |
| `round(String, int)` | `"54Y"` width 5 | Returns `"055Y"` (rounds to nearest 5, zero-padded) |
| `hash(String)` | same input twice | Returns identical deterministic hash |
| `hash(String)` | different inputs | Returns different hashes |

**Notable finding:** `round()` zero-pads the result to even length. The test expectation was initially wrong (`"55Y"` expected, `"055Y"` actual) — corrected to document actual behaviour.

---

### AbstractPipelineStageConfigHtmlTest (4 tests)
**File:** `source/test/java/org/rsna/ctp/pipeline/AbstractPipelineStageConfigHtmlTest.java`

Covers the `getConfigHTML(User user)` method on `AbstractPipelineStage`:

| Test | Behaviour verified |
|---|---|
| Admin user | Sees actual password value in HTML |
| Non-admin user | Password is replaced with `[suppressed]` |
| Non-admin user | Non-sensitive attributes (e.g. `port`) remain visible |
| Null user | Password is suppressed (null treated as non-admin) |

**Infrastructure note:** `AbstractPipelineStage` constructor calls `element.getParentNode()` and casts it to `Element` to read the pipeline `root` attribute. The test element must therefore be appended to a parent element in the document — a free-floating element (not attached to the tree) causes a `NullPointerException`.

---

## Deviations from Plan

None. Both test classes were implemented as planned and all 12 new tests pass GREEN.

---

## Next

Proceed to **Stage 3 — Dependency Modernisation** (update commons-compress, commons-net, commons-vfs2; replace JSch with mwiede fork; add SLF4J bridge).
