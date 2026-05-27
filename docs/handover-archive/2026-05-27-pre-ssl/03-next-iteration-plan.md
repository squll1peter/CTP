# Next Iteration Plan

## Goal

Improve throughput in the DICOM filter/anonymizer/storage path with minimal risk.

## Sequence

1. Rebuild and restart only when runtime assets or code change.
2. Run Phase 1 (per-stage timing) to identify the bottleneck stage before
   touching any concurrency code.
3. Remove/disable decompressor in the active pipeline config as agreed, then
   re-run the same workload and capture a fresh profile baseline.
4. If the updated profile shows anonymizer/IDMap as the hot stages, proceed to
   targeted single-thread overhead reductions before any worker-pool work.
5. Do not proceed to Phase 3 without reading the shared-state risk table in
   `04-performance-plan.md`.
6. Run `ant clean test` after any nontrivial change.
7. Write a new stage report with the exact delta for each batch.

## Done Criteria Per Batch

- No functional behavior changes.
- Full suite green.
- Report updated with exact, auditable diff summary.
