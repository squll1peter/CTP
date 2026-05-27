# Stage 0 Report — Test Infrastructure

**Date:** 2026-05-26  
**Status:** COMPLETE ✅

---

## Outcome

`ant test` succeeds. Tests run: 1, Failures: 0, Errors: 0. Build time ~3 seconds.

---

## What Was Done

### Step 0.1 — Test JARs
Downloaded all 5 JARs to `libraries/test/`:
- `junit-4.13.2.jar` (376 KB)
- `hamcrest-core-1.3.jar` (44 KB)
- `mockito-core-5.14.2.jar` (692 KB)
- `byte-buddy-1.15.10.jar` (8.1 MB)
- `objenesis-3.4.jar` (48 KB)

### Step 0.2 — `build.xml` Extensions
Added `test-classpath`, `compile-tests`, and `test` targets with `--add-opens` JVM args
for Mockito byte-buddy on JDK 9+. Both production and test `<javac>` tasks set
`source="21" target="21"` (see toolchain note below).

### Step 0.3 — Test Source Tree
Created `source/test/java/` with 7 package directories mirroring the production tree.
Added `SmokeTest.java` as pipeline validation.

---

## Deviations from Plan

### Ant was not installed
`ant` was missing from the system PATH. Installed via `apt install ant` (1.10.15,
released January 2026). **Updated prerequisite:** Ant must be installed before running
the build. Add to README.

### Java target version revised: Java 8 → Java 21
The plan specified "Java 8 target" based on the existing `build.xml` which had no
`source`/`target` attributes at all. Without them, JDK 25 produced bytecode version 69
(Java 25-only), which would throw `UnsupportedClassVersionError` on any older JVM where
CTP is deployed.

Investigation found:
- `--release N` does not work in this JDK 25 EA build (ct.sym cross-compilation tables
  absent for all target versions)
- `source="8" target="8"` works but is marked **obsolete** — JDK 25 warns it will be
  removed in a future release
- `source="21" target="21"` works cleanly with no deprecation warning and produces
  bytecode version 65 (Java 21)

**Decision: target Java 21.**

Rationale:
- Java 21 LTS is supported through ~2031
- Java 17 LTS support ends 2029
- Java 8 is deprecated as a compiler target and will be dropped from future JDKs
- The codebase is Java 5/6-era source; zero code changes needed to compile at Java 21
- If a Gradle migration follows this plan (recommended), Java 21 is the natural baseline

### `compile-tests` also needed `source/target` pin
The initial implementation only pinned the production `<javac>` task. The first test
produced bytecode version 69. Fixed by adding `source="21" target="21"` to
`compile-tests` as well. Both classes are now confirmed at version 65 (Java 21).

---

## Toolchain Recommendation

**Keep Ant for this plan. No Maven/Gradle migration during Stages 1–5.**

A future Gradle migration is recommended as a post-Stage-5 item. When that happens:
- Prefer **Gradle** over Maven: 8 vendored JARs don't exist in Maven Central
  (`util.jar`, `dcm4che.jar`, `edtftpj.jar`, `jdbm.jar`, etc.). Maven's `system`
  scope for local files is deprecated. Gradle's `implementation files(...)` handles
  this idiomatically.
- Prefer **Java 21** as the Gradle `sourceCompatibility` (already set).

---

## Stage 1 Plan Review

No changes needed. Proceed as documented in `02-security-fixes.md`.

Verified starting state:
- 169 production source files compile at Java 21, zero warnings
- 1 test compiles and passes
- `ant clean test` completes in ~3 seconds
