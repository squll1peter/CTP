# Performance Improvement Plan (Post-Review)

## Agreed objectives (from Q&A 2026-05-27)

| Question | Answer |
|---|---|
| Performance goal | Throughput — too few objects per second |
| Target load | Tens of thousands of DICOM files per hour (~28/sec minimum) |
| Pipeline count | One pipeline currently |
| Hottest stage | Unknown — measure first |
| Ordering requirement | DICOM export must be delivered in arrival order; other stages may overlap |
| Risk tolerance | Moderate — bounded worker pool only where shared state is clearly safe |

---

## Threading and shared-state inventory

This table summarises every piece of shared or synchronized state in the
filter/anonymizer/storage hot path. It must be consulted before adding any
concurrent workers.

| Class | Shared? | How protected | Concurrent-safe? |
|---|---|---|---|
| `DAScript` static cache (`scripts`) | Static singleton | `static synchronized` on `getInstance` | Safe for multiple readers after load; contention risk at the class lock under high parallelism |
| `DAScript.toProperties()` | Instance | `synchronized` on instance | Safe |
| `LookupTable` static cache (`tables`) | Static singleton | `static synchronized` on `getInstance` | Same as DAScript |
| `LookupTable.properties` (`Properties`) | Instance | Not locked after construction | Read-only after load — safe for concurrent reads; unsafe for concurrent writes |
| `IntegerTable` / JDBM `index` | Per-stage instance | `synchronized` on `getInteger` | Serialized at the method level; safe for one-at-a-time access but would serialize all workers if shared |
| `BasicFileStorageService.store(...)` | Per-stage instance | `synchronized` on method | All storage is serialized; one writer at a time even now |
| `QueueManager` | Per-stage instance | `synchronized` on `enqueue`/`dequeue` | Safe; already synchronized |
| `Pipeline.processObjects()` | Per-pipeline thread | No additional locking | Currently single-threaded by design |

---

## Risk summary

Before any parallelism is introduced:

1. **`IntegerTable` is not safe for concurrent calls from multiple workers.**
   It holds a JDBM database with per-commit state. Concurrent calls would race
   on `index.put` even though `getInteger` is `synchronized` on the instance —
   because multiple workers would each hold the lock only for their own call,
   but the JDBM commit sequence across calls would interleave.
   **Resolution**: each worker thread would need its own `IntegerTable` instance,
   or a striped read/write lock around the integer allocation path.

2. **`DAScript` and `LookupTable` static synchronized getInstance** becomes a
   chokepoint under a large worker pool because all threads contend on the same
   class-level lock.
   **Resolution**: call `getInstance` once per batch and pass the result to the
   worker, rather than calling it per-object.

3. **Export ordering**: if pre-export stages are parallelised, objects can arrive
   at the export queue out of order.
   **Resolution**: assign a sequence number to each object as it enters the
   pipeline, and use an ordered-merge gate (a reorder buffer) before the export
   stage drains the queue.

---

## Implementation phases

### Phase 1 — Measure (prerequisite for all other phases)

**Goal**: identify which stage(s) consume the most wall-clock time per object.

**Work**:
1. Add per-stage elapsed time tracking to `Pipeline.processObjects()` using
   `System.nanoTime()` before and after each stage call.
2. Accumulate totals in a `long[]` array indexed by stage position.
3. Expose the totals via `getStatusHTML()` in `AbstractPipelineStage`.
4. Run a representative load (thousands of real DICOM files) and read the
   status page to find the slow stage(s).

**Done criteria**: status page shows per-stage average ms/object and total
objects processed.

**Current status**: timing counters have been added to the shared stage base
class and are recorded from the pipeline loop. The next step is to review the
status output under a representative workload and identify the hottest stage.

---

### Phase 2 — Reduce single-thread overhead in the hot path

**Goal**: make the existing single-threaded path faster before adding workers.

**Work** (confirm which apply after Phase 1 data):

| Item | File | Change |
|---|---|---|
| Avoid re-fetching `DAScript.getInstance()` on every object | `DicomAnonymizer.process()` | Cache the last script instance at the stage level; re-fetch only when `isCurrent()` returns false |
| Avoid re-fetching `LookupTable.getProperties()` on every object | `DicomAnonymizer.process()` | Same pattern as above |
| Eliminate redundant `FileObject.getInstance(file)` re-parse after storage | `BasicFileStorageService.store()` | Re-examine whether the re-parse is needed when `returnStoredFile=true` |
| Remove `synchronized` from pure-read status methods | Various `getStatusHTML()` | Widen to read-only access where no mutable state is touched |

**Done criteria**: per-stage average ms/object drops measurably for the stage
identified in Phase 1 without changing any test outcomes.

**Current status**: stage-level script/lookup caching in `DicomAnonymizer` is
implemented. Decompressor optimization is currently out of immediate scope
because decompressor is expected to be removed from the active pipeline.

---

### Phase 3 — Add a bounded worker pool for the anonymizer (if it is the bottleneck)

**Goal**: process N DICOM objects through the anonymizer concurrently without
breaking ordering at the export stage.

**Preconditions before starting this phase**:
- Phase 1 data confirms anonymizer is the hot stage.
- `IntegerTable` threading concern is resolved (see below).
- Export ordering mechanism is designed.

**Design**:

```
Pipeline thread (1)
  │
  ▼
[DicomFilter]  — synchronous, fast — keep inline
  │
  ▼
[Sequence stamp]  — assign monotonic integer to each object
  │
  ▼
[AnonymizerPool]  — bounded BlockingQueue + N worker threads
  │  (each worker calls DICOMAnonymizer.anonymize on its own copy of script/lookup props)
  │  (IntegerTable: one shared instance, accessed under its existing synchronized lock)
  ▼
[Reorder buffer]  — hold completed objects until the next expected sequence number is ready
  │
  ▼
[StorageService]  — write in order
  │
  ▼
[ExportService]  — receives objects in original arrival order
```

**IntegerTable resolution option A (simplest, moderate risk)**:
Keep a single `IntegerTable` per stage. The existing `synchronized getInteger`
ensures serialized access. Workers contend on that one lock but do not corrupt
state. Throughput gain for non-integer-mapping workloads is preserved.

**IntegerTable resolution option B (higher throughput, higher complexity)**:
Stripe integer allocations across N sub-tables or use a
`ReentrantReadWriteLock` around the JDBM commit boundary.
Only pursue if Phase 1 shows integer-table contention is a real bottleneck.

**Reorder buffer implementation**:
- Use a `TreeMap<Long, FileObject>` keyed by sequence number.
- The buffer releases to the next stage only when the head entry equals the
  next expected sequence number.
- Buffer size should be capped (e.g. 2 × worker count) to bound memory use.
- If the buffer grows beyond the cap, the pipeline back-pressures by blocking
  new accepts.

**Worker count**: start with `Runtime.getRuntime().availableProcessors() - 1`,
configurable via a new `workerCount` attribute on the pipeline element.
Default to 1 (no change from current behaviour) unless explicitly set.

**Done criteria**:
- Full test suite green.
- Throughput improves proportionally to worker count up to N = CPU count.
- DICOM export receives objects in arrival order (verified by sequence number
  logging).

---

### Phase 4 — Tune throttles and batch behaviour

**Goal**: remove artificial delays in the export path if external systems can
handle the full throughput.

**Work**:
1. Review `throttle` and `interval` attributes on all `AbstractExportService`
   subclasses in the live config.
2. Lower `throttle` to 0 and `interval` to `minInterval` (1000 ms) for any
   export target that is not rate-limited.
3. For `DatabaseExportService`, batch inserts if the database supports it.

**Done criteria**: export queue drains without growth under sustained input load.

---

## Files to change (Phase 1 and Phase 2 only — Phase 3 is design-complete but not started)

| Phase | File | Change |
|---|---|---|
| 1 | `Pipeline.java` | Add per-stage `nanoTime` accumulator and expose via status |
| 1 | `AbstractPipelineStage.java` | Add `stageTotalTime` and `stageObjectCount` fields with getter |
| 2 | `DicomAnonymizer.java` | Cache `DAScript` and `LookupTable.properties` at stage level |
| 2 | Various `getStatusHTML` | Remove `synchronized` from read-only status accessors |

Phase 3 files (reserved for later):
- New class: `AnonymizerWorkerPool.java`
- New class: `ReorderBuffer.java`
- Modified: `Pipeline.java` (insert pool/buffer between filter and storage)
- Modified: `DicomAnonymizer.java` (refactor process logic into a static helper)

---

## What not to do

- Do not parallelise `BasicFileStorageService.store()` — JDBM index writes are
  not safe for concurrent access and disk I/O is typically not the bottleneck.
- Do not remove ordering from the export queue — the export stage contract
  requires FIFO delivery.
- Do not raise the worker count without first running Phase 1 — adding threads
  to a non-CPU-bound path will not improve throughput and will add complexity.
- Do not remove the `synchronized` keyword from `IntegerTable.getInteger()`
  without a full replacement of the JDBM layer with a concurrent alternative.
