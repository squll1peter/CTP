# Stage 16 — Performance Threading Assessment and Improvement Plan

## Summary

This stage does not change runtime behavior. It documents where CTP actually
uses threads today and where the DICOM filter/anonymizer/storage path remains
single-threaded inside the main pipeline.

The practical conclusion is:

1. The pipeline itself is sequential per object.
2. Import and export services may own helper threads.
3. DICOM filter, anonymizer, and storage stages execute on the pipeline thread,
   not in a per-object worker pool.
4. Current throttling is explicit in export-oriented stages, not in the filter
   or storage stages.

---

## Threading Findings

### 1. Pipeline execution is sequential per object

File:
- `source/java/org/rsna/ctp/pipeline/Pipeline.java`

Findings:
- `Pipeline` extends `Thread`, but it processes one object at a time in
  `processObjects()`.
- For each object, it walks the stage list and calls the stage method directly:
  - `Processor.process(...)`
  - `StorageService.store(...)`
  - `ExportService.export(...)`
- There is no stage-level worker pool in this path.

### 2. Import services can be threaded, but ingestion still feeds the pipeline

File:
- `source/java/org/rsna/ctp/pipeline/AbstractImportService.java`
- `source/java/org/rsna/ctp/stdstages/DirectoryImportService.java`

Findings:
- `AbstractImportService` is queue-based and synchronized.
- `DirectoryImportService` starts a dedicated `Poller` thread to scan a folder.
- The poller only enqueues work; the actual object processing still happens in
  the pipeline thread.

### 3. Export services may have helper threads and explicit throttles

Files:
- `source/java/org/rsna/ctp/pipeline/AbstractExportService.java`
- `source/java/org/rsna/ctp/pipeline/AbstractQueuedExportService.java`
- `source/java/org/rsna/ctp/stdstages/DicomDifferenceLogger.java`
- `source/java/org/rsna/ctp/stdstages/DicomExportService.java`

Findings:
- `AbstractExportService` creates a subordinate `Exporter` thread.
- `AbstractQueuedExportService` uses queue management but still serializes queue
  access.
- `DicomDifferenceLogger` has a throttle and sleeps inside its own exporter
  thread.
- `DicomExportService` sends through a dedicated sender object, but each export
  call is still executed serially by that stage’s worker thread.

### 4. Filter, anonymizer, and storage stages are not multithreaded per object

Files:
- `source/java/org/rsna/ctp/stdstages/DicomFilter.java`
- `source/java/org/rsna/ctp/stdstages/DicomAnonymizer.java`
- `source/java/org/rsna/ctp/stdstages/BasicFileStorageService.java`
- `source/java/org/rsna/ctp/stdstages/PictureStorageService.java`

Findings:
- `DicomFilter.process(...)` and `DicomAnonymizer.process(...)` are direct stage
  methods invoked by the pipeline thread.
- `BasicFileStorageService.store(...)` and `PictureStorageService.store(...)`
  are synchronized and run inline in the pipeline.
- There is no internal thread pool in these stages.

### 5. Queue and index structures are synchronized, not parallelized

Files:
- `source/java/org/rsna/ctp/pipeline/QueueManager.java`
- `source/java/org/rsna/ctp/pipeline/AbstractImportService.java`

Findings:
- Queue operations are synchronized to preserve file ordering and integrity.
- This prevents races, but it also means queue manipulation is not a
 throughput multiplier by itself.

---

## Performance Plan

### Phase 1: Measure before changing concurrency

1. Use the existing status/performance pages to identify the hottest stage(s).
2. Add stage timing around the slow path only if the current UI data is too coarse.
3. Confirm whether the real bottleneck is CPU, disk I/O, or queue contention.

### Phase 2: Reduce single-thread cost in the hot path

1. Cache expensive DICOM lookup/script state where safe.
2. Avoid repeated parsing or file-system probing inside `process(...)`/`store(...)`.
3. Reuse image writers/transform helpers where possible.
4. Reduce synchronized blocks on read-only status paths if they are inflating
   contention.

### Phase 3: Introduce optional concurrency only where it pays off

1. Keep the main pipeline order deterministic.
2. If the bottleneck is CPU-bound anonymization, consider an optional bounded
   worker queue for the anonymizer stage only.
3. If the bottleneck is storage I/O, consider decoupling storage into a bounded
   asynchronous writer with backpressure.
4. Do not parallelize every stage blindly; preserve ordering and quarantine
   semantics.

### Phase 4: Tune throttles and batch behavior

1. Review explicit throttles in export stages.
2. Lower or disable throttling only where external systems can absorb the load.
3. Batch queue/index maintenance where it is currently done one file at a time.

---

## Validation

Command:
- `ant clean test`

Result:
- `BUILD SUCCESSFUL`
- No code changes in this stage; this is an architectural assessment only.
