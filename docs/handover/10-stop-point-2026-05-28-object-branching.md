# Stop-Point Handover (2026-05-28, Object Branching Feature)

## What Is Done

### New pipeline stages (complete)

1. `ObjectInlet` (`ImportService`) added at:
   - `source/java/org/rsna/ctp/stdstages/ObjectInlet.java`
2. `ObjectFork` (`Processor`, `Scriptable`) added at:
   - `source/java/org/rsna/ctp/stdstages/ObjectFork.java`
3. `ObjectRouter` (`Processor`, `Scriptable`) added at:
   - `source/java/org/rsna/ctp/stdstages/ObjectRouter.java`

### Functional behavior

1. `ObjectInlet.inject(File)` copies source file to inlet temp area and enqueues using `fileReceived(...)`; source file remains untouched.
2. `ObjectFork` always returns the original `FileObject` downstream and can fork a copy to one or more `ObjectInlet` targets.
3. `ObjectFork` optional `script` applies only to DICOM objects.
4. `ObjectFork` always forks non-DICOM objects regardless of script.
5. `ObjectRouter` passes through non-DICOM objects and disabled mode traffic.
6. `ObjectRouter` passes through DICOM objects when script matches.
7. `ObjectRouter` diverts non-matching DICOM objects to target `ObjectInlet` and returns `null`.
8. `ObjectRouter` quarantines diverted objects if target cannot be resolved.

### Configuration template updates (complete)

`source/resources/ConfigurationTemplates.xml` now includes:

1. New `ImportService` template for `ObjectInlet`.
2. New `Processor` template for `ObjectFork`.
3. New `Processor` template for `ObjectRouter`.

### Tests (complete)

Added tests:

1. `source/test/java/org/rsna/ctp/stdstages/ObjectInletTest.java` (11 tests)
2. `source/test/java/org/rsna/ctp/stdstages/ObjectForkTest.java` (16 tests)
3. `source/test/java/org/rsna/ctp/stdstages/ObjectRouterTest.java` (15 tests)

Validation result:

- `ant test` -> BUILD SUCCESSFUL
- New feature tests passing: 42

## Key Notes

1. Target resolution for `ObjectFork` and `ObjectRouter` occurs in `start()` by scanning configured pipelines/stages via `Configuration.getInstance()`.
2. Missing or wrong-type targets log warnings and do not crash startup.
3. Mockito stubs use `nullable(File.class)` where script argument can be null.

## Known Constraints

1. No inter-pipeline ordering guarantee beyond queue arrival order.
2. No explicit back-pressure on `ObjectInlet` queue growth.
3. `ObjectRouter` intentionally leaves non-DICOM objects on pass-through path.

## Immediate Next Start

1. Add an integration config example showing a two-pipeline fork/router topology.
2. Consider optional queue guardrails for `ObjectInlet` (max depth and/or drop policy).
3. Add operational monitoring fields (queue high-water mark, divert/fork rates).
