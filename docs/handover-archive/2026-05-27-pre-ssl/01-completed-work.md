# Completed Work

## Build/Test Baseline

- Ant test pipeline stabilized and used as mandatory gate.
- Current branch validates with `ant clean test`.

## Security/Authorization

- Request-shared servlet auth booleans replaced by immutable request-local `AuthState`.
- Affected servlet subclasses migrated to `AuthState` checks.
- Concurrency regression coverage added for auth behavior.

## Dependency Modernization

- Old JARs were moved (not copied) out of active library paths into:
  - `libraries/archive/legacy-2026-05-27`
  - `libraries/archive/legacy-2026-05-27/ftp`
- Build manifest classpath corrected for modern log4j2 artifacts.

## Reliability/Behavior Corrections

- Pass counters and quarantine counters corrected to count only real events.
- Prior stage reports updated where semantics had drifted from implementation.

## Stage 4 Integration-Style Test Closure

- Added `ServerServletHttpIntegrationTest`.
- Added `ConfigLogPasswordIntegrationTest`.
- Both run in normal `ant test` suite and pass.

## Vector Compatibility Decision

- PixelMed boundary that requires `Vector<Shape>` remains on `Vector`.
- Generic `List` migration at that boundary was reverted to preserve compatibility.

## Launcher/Runtime Robustness

- Launcher now auto-seeds default `config.xml` when missing to avoid first-run
  startup failure.
- Example runtime configuration is aligned to port `8080` and includes required
  Client Directory Import `import` attribute in shipped example config.

## Admin UI and Servlet UX Refresh

- Admin shell (`JSCTP`) styling and split-pane behavior were modernized.
- Summary and related servlet pages were restyled for readability and visual
  consistency.
- Shared servlet baseline stylesheet (`BaseStyles.css`) was introduced.

## Caching Improvements

- `ServerServlet` now includes short-TTL in-memory caching for repeated bootstrap
  requests.
- Static admin assets include explicit version query suffixes to defeat stale
  browser cache after upgrades.

## Suppression Reduction Progress

- Stage 15 (batch #6) removed the final four unchecked suppressions in:
  - `DicomDifferenceLogger`
  - `DatabaseVerifier`
  - `DatabaseExportService`
  - `ObjectTracker`
- Remaining unchecked suppressions reduced to zero.

## Performance Threading Assessment

- Verified that the main pipeline processes one object at a time in
  `Pipeline.processObjects(...)`.
- Verified that `DicomFilter`, `DicomAnonymizer`, `BasicFileStorageService`,
  and `PictureStorageService` run inline on the pipeline thread.
- Verified that import/export services can have helper threads, but they do not
  make the filter/anonymizer/storage hot path multithreaded by default.
- Implemented Phase 1 timing instrumentation in `AbstractPipelineStage` and
  `Pipeline` so each stage now records elapsed processing time and invocation
  counts in status pages.

## Stage 17 Runtime and Performance Follow-Up

- Hardened `Runner` startup config loading to avoid null-config NPE and provide
  fallback to `examples/example-config.xml` when needed.
- Added null-safe guards in `DirectoryImportService` poller directory traversal
  to prevent `listFiles()` null crashes.
- Implemented stage-level anonymizer script/lookup property caching in
  `DicomAnonymizer` with `lastModified` refresh checks to reduce per-object
  lookup overhead without changing anonymization behavior.
