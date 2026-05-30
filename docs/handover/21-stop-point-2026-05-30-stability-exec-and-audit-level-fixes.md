# Stop-Point Handover (2026-05-30, Stability Exec and Audit Level Fixes)

## Purpose

This stop-point records two runtime fixes found during local CTP validation:

1. `StabilityMonitorProcessor` rejected `targetID="StabilityExec"` because it only accepted `StabilityWebhookPlugin`.
2. `DicomAuditLogger` with `level="study"` still indexed entries by SOP Instance UID, making the audit log observable at object/series granularity.

## What Is Done

1. Added a shared `StabilityNotificationPlugin` contract with `notify(DicomObject representative)`.
2. Made both stability target plugins implement the contract:
   - `StabilityWebhookPlugin`
   - `StabilityExecPlugin`
3. Updated `StabilityMonitorProcessor` to resolve `targetID` against `StabilityNotificationPlugin` instead of the webhook concrete class.
4. Updated `DicomAuditLogger` AuditLog indexing so configured level controls index granularity:
   - `patient`: patient index only
   - `study`: patient and study indexes only
   - default/object behavior: patient, study, and object indexes
5. Preserved cached-object audit behavior while preventing object UID references at `level="study"`.
6. Rebuilt `libraries/CTP.jar`.

## Code Changes

Updated files:

1. `source/java/org/rsna/ctp/plugin/StabilityNotificationPlugin.java`
2. `source/java/org/rsna/ctp/plugin/StabilityWebhookPlugin.java`
3. `source/java/org/rsna/ctp/plugin/StabilityExecPlugin.java`
4. `source/java/org/rsna/ctp/stdstages/StabilityMonitorProcessor.java`
5. `source/java/org/rsna/ctp/stdstages/DicomAuditLogger.java`
6. `source/test/java/org/rsna/ctp/stdstages/StabilityMonitorProcessorTest.java`
7. `source/test/java/org/rsna/ctp/stdstages/DicomAuditLoggerTest.java`
8. `libraries/CTP.jar`

Behavior changes:

1. `StabilityMonitorProcessor targetID="StabilityExec"` now resolves successfully when the registered plugin is a `StabilityExecPlugin`.
2. The warning text now refers to `StabilityNotificationPlugin` rather than only `StabilityWebhookPlugin`.
3. `DicomAuditLogger level="study"` no longer writes SOP Instance UID to the AuditLog object index.
4. Cached-object audit entries at `level="study"` no longer add current-object SOP Instance UID references.

## Validation

Focused tests:

```sh
java -javaagent:libraries/test/byte-buddy-agent-1.15.10.jar -Dnet.bytebuddy.experimental=true --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED -cp "build:build/test-classes:libraries/*:libraries/test/*" org.junit.runner.JUnitCore org.rsna.ctp.stdstages.StabilityMonitorProcessorTest org.rsna.ctp.stdstages.DicomAuditLoggerTest
```

Result:

```text
OK (27 tests)
```

Full suite:

```sh
ant -q test
```

Result:

```text
BUILD SUCCESSFUL
Total time: 30 seconds
```

Notes:

1. The first full-suite run inside the sandbox failed at `StabilityWebhookPluginTest`, consistent with local socket restrictions.
2. The full suite passed when rerun outside the sandbox.
3. `ant -q jar` completed successfully and rebuilt `libraries/CTP.jar`.
4. `unzip -l libraries/CTP.jar` verified the jar contains:
   - `org/rsna/ctp/plugin/StabilityNotificationPlugin.class`
   - `org/rsna/ctp/plugin/StabilityExecPlugin.class`
   - `org/rsna/ctp/stdstages/StabilityMonitorProcessor.class`
   - `org/rsna/ctp/stdstages/DicomAuditLogger.class`

## Operational Notes

1. `Launcher.jar` is not present at the repository root in this workspace.
2. `ant -q jar` produced `build/CTP/Launcher.jar`.
3. The current runnable CTP classes were rebuilt into `libraries/CTP.jar`.
4. The prior `java -jar Launcher.jar` GLIBC/snap error is an environment/runtime issue, not caused by these code changes.

## Current Workspace Caveats

The worktree was already dirty before this stop-point. Do not treat unrelated modified or untracked files as part of this change without checking ownership.

Relevant files from this stop-point:

1. `source/java/org/rsna/ctp/plugin/StabilityNotificationPlugin.java`
2. `source/java/org/rsna/ctp/plugin/StabilityWebhookPlugin.java`
3. `source/java/org/rsna/ctp/plugin/StabilityExecPlugin.java`
4. `source/java/org/rsna/ctp/stdstages/StabilityMonitorProcessor.java`
5. `source/java/org/rsna/ctp/stdstages/DicomAuditLogger.java`
6. `source/test/java/org/rsna/ctp/stdstages/StabilityMonitorProcessorTest.java`
7. `source/test/java/org/rsna/ctp/stdstages/DicomAuditLoggerTest.java`
8. `libraries/CTP.jar`

## Immediate Next Start

1. Run the application with `build/CTP/Launcher.jar` or the expected packaged launcher path, not a missing repository-root `Launcher.jar`.
2. Re-test the live `config.xml` path where `StabilityMonitorProcessor targetID="StabilityExec"` points at `StabilityExecPlugin`.
3. Validate that `DicomAuditLogger level="study"` now creates study-indexed audit entries without object UID search/index hits.
4. If runtime validation passes, update the user-facing deployment package or installer as needed.
