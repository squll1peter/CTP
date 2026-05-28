# Stage 19 - Object Branching Stages (ObjectInlet, ObjectFork, ObjectRouter)

## Summary

This stage introduced configurable cross-pipeline object branching using a queue-safe import inlet and two processor-side routing primitives.

## Functional Outcomes

1. Added `ObjectInlet` import stage for programmatic file injection into pipeline queues.
2. Added `ObjectFork` processor to duplicate flow objects into one or more `ObjectInlet` targets while always passing original objects downstream.
3. Added `ObjectRouter` processor to divert non-matching DICOM objects to a target inlet and pass matching/non-DICOM objects through.
4. Added status HTML fields for fork/router counters and last action timestamps.
5. Added configuration templates for all three new stages.

## Validation

1. Added 42 new unit tests:
   - `ObjectInletTest` (11)
   - `ObjectForkTest` (16)
   - `ObjectRouterTest` (15)
2. Full suite run completed successfully:
   - `ant test` -> BUILD SUCCESSFUL

## Implementation Notes

1. Injection is copy-based (`FileUtil.copy`) to avoid interfering with the source pipeline's file ownership and release flow.
2. Target stage lookup is deferred to `start()` and performed by scanning configured pipelines/stages by ID.
3. Missing targets are treated as operational warnings, not startup-fatal errors.
4. Router quarantine fallback preserves data when diversion target is unresolved.

## Residual Risks / Follow-Up

1. Queue growth controls are not yet enforced for inlet injection bursts.
2. Cross-pipeline ordering guarantees are not explicit.
3. Operational docs should include a concrete two-pipeline topology example using the new stages.
