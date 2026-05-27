# Stage 6 — Performance

## Summary

Stage 6 applied targeted, low-risk performance improvements to the hot paths in the
pixel-anonymiser and DICOM I/O subsystems: buffered file output and lazy debug-log
string evaluation guards.  42/42 tests remain GREEN.

---

## Changes Applied

### 1. Buffered file output in pixel-anonymiser hot paths (5 files)

Each pixel-anonymiser and converter class writes a temporary output file one
pixel-row (or even one byte) at a time.  Without a buffer, every `out.write()`
call is a syscall.  Adding a `BufferedOutputStream` wrapper batches those into
8 kB kernel transfers, which reduces syscall overhead by roughly two orders of
magnitude for a 4 k × 4 k image.

| File | Change |
|---|---|
| `DICOMPixelAnonymizer.java` | `FileOutputStream` → `BufferedOutputStream(FileOutputStream)` |
| `DICOMPlanarConfigurationConverter.java` | same |
| `DICOMPhotometricInterpretationConverter.java` | same |
| `DICOMMammoPixelAnonymizer.java` | same |
| `DicomObject.java` | same (save-to-file hot path) |

The declared type was widened to `OutputStream` so no other callers needed changing.

### 2. `isDebugEnabled()` guards on 135 logger.debug() calls (15 files)

Every `logger.debug("text" + expression)` call evaluates the concatenation before
calling `debug()`.  When debug logging is disabled (the normal production setting)
this string is built and immediately discarded.  For calls inside per-frame or
per-element loops this creates measurable GC pressure.

A single-line regex transformation wrapped all such calls:

```java
// Before
logger.debug("FMI TransferSyntaxUID = " + prefEncodingUID);

// After
if (logger.isDebugEnabled()) logger.debug("FMI TransferSyntaxUID = " + prefEncodingUID);
```

Files and guard counts:

| File | Guards added |
|---|---|
| `DICOMAnonymizer.java` | 29 |
| `DICOMDecompressor.java` | 19 |
| `DicomStorageSCU.java` | 16 |
| `DICOMPixelAnonymizer.java` | 13 |
| `PollingHttpImportService.java` | 9 |
| `DICOMPlanarConfigurationConverter.java` | 8 |
| `IntegerTable.java` | 7 |
| `FtpsExportService.java` | 6 |
| `DirectoryStorageService.java` | 6 |
| `PictureStorageService.java` | 5 |
| `FileSystem.java` | 4 |
| `DatabaseVerifier.java` | 4 |
| `DicomObject.java` | 4 |
| `HttpExportService.java` | 2 |
| `PDFStorageService.java` | 3 |
| **Total** | **135** |

---

## Deferred Items

- **Parameterised logging** (`logger.debug("{} = {}", key, value)`): the `log4j-1.2-api`
  bridge exposes Log4j2's parameterised API, which would be an alternative to
  `isDebugEnabled()` guards and avoids the guard boilerplate.  Adopting it across the
  entire codebase is a larger mechanical change and is deferred to a dedicated refactor.
- **Buffer size tuning**: the default 8 kB `BufferedOutputStream` size may be suboptimal
  for DICOM files; profiling under realistic load would guide a more precise choice.

---

## Test Results

| Suite | Tests | Pass | Fail | Error |
|---|---|---|---|---|
| DicomObjectCharacterisationTest | 5 | 5 | 0 | 0 |
| ConfigurationXmlTest | 1 | 1 | 0 | 0 |
| XxeProtectionTest | 4 | 4 | 0 | 0 |
| FtpsTlsConfigTest | 3 | 3 | 0 | 0 |
| PipelineShutdownTest | 3 | 3 | 0 | 0 |
| QuarantineLoggingTest | 3 | 3 | 0 | 0 |
| DicomAnonymizerCharacterisationTest | 3 | 3 | 0 | 0 |
| InstallerPasswordTest | 8 | 8 | 0 | 0 |
| AcceptAllHostnameVerifierTest | 2 | 2 | 0 | 0 |
| XxeProtectionRunnerTest | 5 | 5 | 0 | 0 |
| RunnerClassTest | 2 | 2 | 0 | 0 |
| XxeProtectionDicomTest | 3 | 3 | 0 | 0 |
| **Total** | **42** | **42** | **0** | **0** |

`BUILD SUCCESSFUL`

---

## Next Steps

Stage 7 — Observability: add structured metrics (counters, timers) at pipeline stage
boundaries so that throughput and latency can be monitored at runtime without enabling
verbose debug logging.
