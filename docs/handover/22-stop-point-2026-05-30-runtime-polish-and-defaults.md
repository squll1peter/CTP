# Stop-Point Handover (2026-05-30, Runtime Polish and Defaults)

## Purpose

This stop-point records follow-up runtime polish after checkpoint `21`, focused on:

1. `StabilityExecPlugin` argument parsing and working-directory diagnostics.
2. Script editor save confirmation behavior.
3. Upstream verification of `DicomAuditLogger` level semantics.
4. Stage profiling disabled-by-default behavior.

## What Is Done

### StabilityExecPlugin

1. Replaced the old semicolon `key=value` argument behavior for `StabilityExecPlugin`.
2. `arguments` is now a whitespace-delimited command-line template list.
3. DICOM placeholders use the same style as `DirectoryStorageService structure`, for example:
   - `{StudyInstanceUID}`
   - `(0020,000D)`
4. Command and argument template tokens are resolved through `DicomObject.getElementString(...)`.
5. Added working-directory validation during plugin construction:
   - missing `workingDir` logs a clear config error
   - non-directory `workingDir` logs a clear config error
   - invalid `workingDir` prevents enqueueing execution work
6. Status HTML now exposes:
   - working directory path
   - working directory validity
7. `ConfigurationTemplates.xml` was updated so `StabilityExecPlugin arguments` describes command-line templates, not `key=value` pairs.
8. Removed stale `otherArguments` from the `StabilityExecPlugin` template.

### Script Editor

1. `ScriptServlet` still returns to the script editor page after a successful update.
2. Successful update now injects a browser popup:

```js
alert('Script updated.');
```

This preserves the user's requested flow: stay on the same page, but confirm the save.

### DicomAuditLogger Upstream Check

Checked `upstream/master` directly:

1. Upstream `DicomAuditLogger` only special-cases:
   - `patient`
   - `study`
2. Any other level falls through to per-object logging.
3. Upstream `ConfigurationTemplates.xml` lists:

```text
patient|study|instance
```

4. Upstream `AuditLog` has indexes for:
   - patient ID
   - study UID
   - object UID
5. Upstream does not have a series UID index.

Conclusion: lack of a real `series` level in `DicomAuditLogger` is original upstream behavior. Adding true series-level behavior would be a new feature, not a template correction.

### Stage Profiling Default

1. Fixed runtime parsing so stage profiling is opt-in:

```java
enableStageProfiling = serverElement.getAttribute("enableStageProfiling").equals("yes");
```

2. Missing `enableStageProfiling` now means disabled.
3. `enableStageProfiling="no"` means disabled.
4. Only `enableStageProfiling="yes"` enables stage profiling.
5. Updated shipped defaults:
   - `source/config/config.xml`
   - `source/files/examples/example-config.xml`

## Code And Doc Changes

Updated files in this stop-point:

1. `source/java/org/rsna/ctp/plugin/StabilityExecPlugin.java`
2. `source/test/java/org/rsna/ctp/plugin/StabilityExecPluginTest.java`
3. `source/resources/ConfigurationTemplates.xml`
4. `docs/CTP-Delta-From-RSNA-MIRC-CTP.md`
5. `docs/handover/01-completed-work.md`
6. `docs/handover/02-open-items.md`
7. `docs/handover/03-next-iteration-plan.md`
8. `docs/handover/06-stable-notification-spec.md`
9. `docs/handover/08-local-command-notification-spec.md`
10. `source/java/org/rsna/ctp/servlets/ScriptServlet.java`
11. `source/java/org/rsna/ctp/Configuration.java`
12. `source/config/config.xml`
13. `source/files/examples/example-config.xml`
14. `libraries/CTP.jar`

Related earlier checkpoint files remain relevant:

1. `source/java/org/rsna/ctp/plugin/StabilityNotificationPlugin.java`
2. `source/java/org/rsna/ctp/plugin/StabilityWebhookPlugin.java`
3. `source/java/org/rsna/ctp/stdstages/StabilityMonitorProcessor.java`
4. `source/java/org/rsna/ctp/stdstages/DicomAuditLogger.java`
5. `source/test/java/org/rsna/ctp/stdstages/DicomAuditLoggerTest.java`

## Validation

Focused checks run during this stop-point:

1. `StabilityExecPluginTest` after working-directory validation:

```text
OK (16 tests)
```

2. `StabilityExecPluginTest` plus `StabilityMonitorProcessorTest` after argument parsing rewrite:

```text
OK (39 tests)
```

Build and full-suite checks:

1. `ant -q compile-tests`: passed.
2. `ant -q clean jar`: passed.
3. `ant -q test`: passed.

Notes:

1. Full test runs may require running outside the sandbox because `StabilityWebhookPluginTest` uses local sockets.
2. A clean jar build removed stale copied `config/config.xml` from the jar and rebuilt `Configuration.class`.
3. Current `libraries/CTP.jar` contains the updated runtime classes.

## Operational Notes

1. The active local `config.xml` currently omits `enableStageProfiling`; with the new parser this means profiling is disabled.
2. `StabilityExecPlugin workingDir` should be an existing directory. The previous `workingDir="Exec/"` failed because `Exec/` did not exist under the launch cwd.
3. The currently working local `workingDir` used during debugging is:

```text
/home/squll1/Workspace/fused_test/python/dist/fusedtest
```

4. `StabilityExecPlugin arguments` now matches the active local form:

```xml
arguments="-i=/home/squll1/Workspace/fused_test/in/{StudyInstanceUID} -o=/home/squll1/Workspace/fused_test/out --no-gui --quiet --delete-input-dir"
```

## Current Workspace Caveats

The worktree was already dirty before this stop-point. There are unrelated or earlier-checkpoint changes and untracked files. Do not revert or claim files outside the relevant change set without checking ownership.

Important caveats:

1. `build/CTP/config.xml` may not exist after `ant clean`; packaging/installer targets recreate runtime trees.
2. `libraries/CTP.jar` has been rebuilt.
3. `products/CTP-installer.jar` is modified from prior work and was not refreshed in this stop-point.

## Immediate Next Start

1. Restart CTP from the rebuilt jar/runtime tree.
2. Manually validate script editor update:
   - edit a script
   - submit
   - confirm the page stays on editor
   - confirm browser alert says `Script updated.`
3. Manually validate `StabilityExecPlugin` with the active local config:
   - no warning about invalid `workingDir`
   - command line contains resolved `{StudyInstanceUID}`
   - command executes from the configured directory
4. Decide whether to implement true `series` level for `DicomAuditLogger`. Upstream does not provide it.
