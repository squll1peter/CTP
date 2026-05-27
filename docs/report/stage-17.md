# Stage 17 — Performance Follow-Up and Stop-Point Handover

## Summary

This stage focused on production-like profiling review and low-risk runtime
stability/performance improvements.

No behavior-changing concurrency was introduced.

## Input Profile Analysis (DeepBraiM Anony Pipeline)

Observed from user-provided status snapshot:

1. `DirectoryImportService` and `DicomFilter` saw 38,691 objects.
2. Downstream stages (`DicomDecompressor` onward) processed 7,131 objects.
3. Approximate pass-through ratio from filter to decompressor path: 18.4%.

Per-object hotspots in the downstream path:

1. `DicomDecompressor`: ~27.8 ms/object.
2. `IDMap`: ~22.6 ms/object.
3. `DicomAnonymizer`: ~21.0 ms/object.
4. `ObjectCache`: ~10.4 ms/object.

Interpretation:

1. Main cost center is decompressor + IDMap + anonymizer stack, not import queue
   polling.
2. Windows file I/O and endpoint antivirus scanning likely amplify these stages.

## Code Changes Completed

### 1) `Runner` startup hardening

File:
- `source/java/org/rsna/runner/Runner.java`

Changes:

1. Added fallback from `config.xml` to `examples/example-config.xml` when
   `config.xml` is missing/unreadable.
2. Added null guards in attribute lookup path to avoid startup NPE.
3. Added explicit error messages and non-zero exit when neither config can load.

### 2) `DirectoryImportService` poller NPE guard

File:
- `source/java/org/rsna/ctp/stdstages/DirectoryImportService.java`

Changes:

1. Added null/exists/isDirectory guard in recursive `addFiles(...)`.
2. Added null guard for `listFiles()` returning null.

Result:

1. Poller thread no longer crashes when directory listing fails transiently.

### 3) `DicomAnonymizer` stage-level property caching

File:
- `source/java/org/rsna/ctp/stdstages/DicomAnonymizer.java`

Changes:

1. Added cached script/lookup `Properties` fields.
2. Added cached file timestamps (`lastModified`).
3. Added synchronized refresh method that reloads only when source files change.
4. Kept anonymization behavior unchanged while removing per-object lookup/script
   retrieval overhead.

## Multithreading Clarification

Current state:

1. `DicomAnonymizer` processing remains single-threaded in the pipeline path.

Feasibility:

1. Parallel anonymization is possible, but requires strict handling of ordering,
   `IntegerTable` synchronization semantics, and export ordering guarantees.
2. This stage intentionally did not introduce parallel execution.

## Scope Decision for Next Work

User confirmed decompressor optimization is not needed because the decompressor
will be removed from the pipeline.

Therefore, immediate next optimization scope is:

1. Maintain anonymizer/IDMap-oriented improvements.
2. Re-profile after pipeline config removes decompressor to identify the new
   top bottleneck.

## Validation

1. `ant clean test` passed after each code change batch.
2. `ant all` passed and produced updated runtime artifacts.
