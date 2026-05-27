# Stage 5 — Maintainability Cleanup

## Background

The CTP codebase was written in the Java 5/6 era and retains patterns from that period: synchronized collection classes used in single-threaded contexts, star imports that obscure dependencies, duplicate imports from careless edits, and stale build metadata referencing files that no longer exist.

None of these changes affect correctness or security. All are safe to apply mechanically with IDE tooling. The 38+ test suite from Stages 1–4 is the full regression net: if any cleanup breaks behavior, a test will catch it.

Stages 3, 4, and 5 may proceed in parallel once Stage 2 is complete.

---

## Summary

Five cleanup passes targeting over-synchronized collections (~200 sites), a legacy `Hashtable` in a concurrent context, one remaining `Vector`, duplicate/wildcard imports in key files, and stale `build.xml` metadata.

No new tests are introduced in this stage. All changes are verified by `ant compile` (zero new warnings) and `ant test` (all tests GREEN).

---

## Steps

### Step 5.1 — `StringBuffer` → `StringBuilder` (~200 sites)

**Background:** `StringBuffer` is a Java 1.0 class with `synchronized` on every method. In CTP, it appears in over 200 places — building HTML strings, log messages, and diagnostic output — all within single methods with local scope. No instance is shared across threads. The synchronization adds overhead with no benefit. `StringBuilder` is the non-synchronized equivalent and has been the Java style standard since Java 5.

**Action:** IDE bulk-replace `new StringBuffer()` and `new StringBuffer(...)` with `new StringBuilder()` / `new StringBuilder(...)` across all source files. Before committing, verify each replacement is in a local variable context (not a field accessed from multiple threads). No field-level `StringBuffer` usages exist in the current codebase, but confirm with:

```
grep -rn "StringBuffer" source/java/ | grep -v "//\|test"
```

Then compile and test:
```
ant compile && ant test
```

**Goal:** No `StringBuffer` remains anywhere in `source/java/` except in genuinely thread-shared contexts (none found in current codebase). Compiler generates zero new warnings after this change.

---

### Step 5.2 — `Hashtable` → `ConcurrentHashMap` in `Configuration.java`

**File:** [Configuration.java:144](../../source/java/org/rsna/ctp/Configuration.java)

**Background:** The `stages` and `plugins` maps in `Configuration` are declared as `Hashtable<String, PipelineStage>` and `Hashtable<String, Plugin>`. `Hashtable` is synchronized on every operation, which is correct in this multi-threaded context. However, `ConcurrentHashMap` provides the same thread-safety guarantee with better concurrent-read performance (reads do not block each other). The API is a drop-in replacement: `get()`, `put()`, `values()`, `keySet()`, `containsKey()` are all present with identical signatures.

**Action:**
1. Change type declaration: `Hashtable<String, PipelineStage>` → `ConcurrentHashMap<String, PipelineStage>`
2. Change instantiation: `new Hashtable<>()` → `new ConcurrentHashMap<>()`
3. Update import: `java.util.Hashtable` → `java.util.concurrent.ConcurrentHashMap`
4. Compile and test.

**Goal:** Internal maps use the standard modern concurrent collection. No `Hashtable` in `Configuration.java`.

---

### Step 5.3 — `Vector` → `ArrayList` in `Regions.java`

**File:** [Regions.java:73](../../source/java/org/rsna/ctp/stdstages/anonymizer/dicom/Regions.java)

**Background:** This is the single remaining `new Vector<>(...)` usage in the production source. `Vector` is synchronized; this usage is local to a single-threaded DICOM anonymization context.

**Action:**
1. Change `new Vector<>(...)` to `new ArrayList<>(...)`
2. Update import: `java.util.Vector` → `java.util.ArrayList`
3. Compile and test.

**Goal:** No `Vector` usage in the production source.

---

### Step 5.4 — Duplicate and wildcard import cleanup

**Files:**
- [CTPServlet.java:11,13](../../source/java/org/rsna/ctp/servlets/CTPServlet.java) — duplicate `import java.util.LinkedList;`
- [Runner.java](../../source/java/org/rsna/runner/Runner.java) — star imports (`import java.io.*`, `import java.util.*`)
- [Installer.java](../../source/java/org/rsna/installer/Installer.java) — star imports
- [Launcher.java](../../source/java/org/rsna/launcher/Launcher.java) — star imports
- [ClinicalTrialProcessor.java](../../source/java/org/rsna/ctp/ClinicalTrialProcessor.java) — star imports

**Background:** The duplicate `import java.util.LinkedList;` on lines 11 and 13 of `CTPServlet.java` is a clear sign of careless editing. Star imports obscure which specific classes a file actually depends on, making refactoring and dependency analysis harder and masking potential ambiguous-class-name errors.

**Action:**
1. Remove the duplicate `import java.util.LinkedList;` from `CTPServlet.java` (keep one).
2. In each of the four named files with star imports, use IDE "Optimize Imports" to replace star imports with explicit imports.
3. Compile and test.

**Goal:** No duplicate imports. No star imports in the five named files.

---

### Step 5.5 — Stale `build.xml` classpath metadata

**File:** [build.xml:42](../../build.xml), [build.xml:110](../../build.xml)

**Background:** The `jarclasspath` property (line 42) and the `Class-Path` manifest attribute for `CTP.jar` (line 110) both reference `libraries/log4j.jar`. This file does not exist — log4j 1.x was replaced by three log4j2 JARs under `libraries/log4j/`:

- `libraries/log4j/log4j-1.2-api-2.17.2.jar`
- `libraries/log4j/log4j-api-2.17.2.jar`
- `libraries/log4j/log4j-core-2.17.2.jar`

The missing `log4j.jar` reference produces a `Class-Path` manifest warning on every JVM startup (`WARNING: jar file 'libraries/log4j.jar' not found; ignored`) and can confuse any automated classpath tool or IDE project import.

**Action:** In `build.xml`, update the `jarclasspath` property value and the `Class-Path` manifest attribute to list the three actual log4j2 JARs (relative paths as they currently appear for other entries). Remove the stale `log4j.jar` reference.

**Goal:** `Class-Path` manifest attribute accurately reflects the JARs present on disk. No classpath warning appears on startup.

---

## Stage 5 Checkpoint

| Check | Expected |
|-------|---------|
| `ant compile` | Zero new warnings |
| `ant test` (all tests) | All GREEN |
| `grep -r "StringBuffer" source/java/` | No results |
| `grep -r "new Vector" source/java/` | No results |
| `grep -rn "import.*\.\*;" source/java/org/rsna/runner/ source/java/org/rsna/installer/ source/java/org/rsna/launcher/` | No results |
| JVM startup log | No `log4j.jar not found` warning |

---

## Note on Deferred Work: Crypto Migration

This stage does not migrate `AnonymizerFunctions.hash()` from MD5 to SHA-256, or `encrypt()`/`decrypt()` from Blowfish to AES-GCM. That migration is intentionally deferred because it requires a dataset migration strategy: every existing pseudonymized patient ID in every linked clinical trial dataset must be remapped from its old MD5-derived value to a new SHA-256-derived value. This is a data-integrity operation requiring coordination with trial administrators and data custodians.

The `AnonymizerFunctionsTest` from Stage 2 freezes the current algorithm behavior as executable documentation. When the crypto migration is planned, those tests will define exactly what behavioral changes are expected — making the migration an auditable, deliberate change rather than an accidental one.
