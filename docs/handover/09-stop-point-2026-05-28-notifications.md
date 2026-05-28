# Stop-Point Handover (2026-05-28, Stable-Group Notification Feature)

## What Is Done

### StabilityMonitorProcessor + StabilityWebhookPlugin (complete)

1. `StabilityMonitorProcessor` — groups DICOM objects by series / study / patient, detects inactivity after a configurable timeout, fires a registered `StabilityWebhookPlugin`.
2. `StabilityWebhookPlugin` — fires an HTTP REST call (GET / POST-JSON / POST-form) with DICOM-resolved and static arguments. Retry and timeout configurable.
3. Argument syntax: `key=DicomKeywordOrTag` using `=` delimiter; legacy `:` delimiter still accepted as fallback.
4. Status HTML: both stages expose "Last file received / Last file received at / Last trigger / Last trigger at" (processor) and "Last triggered / Last called URL" (plugin).
5. Configuration template entries added in `ConfigurationTemplates.xml`.
6. Python test receiver at `source/files/examples/stability_notify_receiver.py` — GET/POST/PUT, JSONL log, `--fail-once` retry mode.

### StabilityExecPlugin (complete)

7. `StabilityExecPlugin` — executes a local OS command when notified by `StabilityMonitorProcessor`.
   - Same `targetID` wiring as `StabilityWebhookPlugin`; drop-in alternative.
   - `ProcessBuilder` direct exec (no shell); args passed as `--key value` flag pairs.
   - `dryRun=yes` logs the resolved command without spawning a process.
   - `minInterval` throttles execution rate; a bounded `ArrayBlockingQueue` buffers pending work; excess notifications are dropped with a WARN log entry.
   - Single daemon worker thread; `notify()` is always non-blocking.
   - Status page shows: Enabled, Dry Run, Command, Min Interval, Max Queue Size, Queue depth, Total dropped, Last triggered, Last command, Last exit code, Last exit at.

### Tests

- 15 new tests for `StabilityExecPlugin` — all pass.
- Full suite: BUILD SUCCESSFUL (all test classes, 0 failures).

### Uncommitted Changes (as of this stop-point)

The following changes are not yet committed to git:

| Change | Files |
|---|---|
| Argument `=` syntax + DICOM keyword support | `StabilityWebhookPlugin.java`, `StabilityMonitorProcessorTest.java`, `StabilityWebhookPluginTest.java` |
| Status HTML fields (last file / last trigger / last called URL) | `StabilityMonitorProcessor.java`, `StabilityWebhookPlugin.java`, both test files |
| New `StabilityExecPlugin` | `StabilityExecPlugin.java`, `StabilityExecPluginTest.java` |
| Config template updates | `ConfigurationTemplates.xml` |
| Handover docs | `docs/handover/06-stable-notification-spec.md` (updated), `docs/handover/08-local-command-notification-spec.md` (new) |

---

## Current Operational State

- All code compiles and all tests pass.
- No binaries have been rebuilt yet since the last binary commit (`a7a8e9e`). A `ant jar` + `ant installer` rebuild is needed before deployment.
- The Python receiver is functional and committed separately.

---

## Immediate Next Start

1. **Commit all pending changes** (`git add -A && git commit -m "..."`)
2. **Rebuild binaries** (`ant jar && ant installer`) and commit updated JARs
3. **Integration test** with a live CTP instance wired to both plugin types
4. Continue from `docs/handover/03-next-iteration-plan.md` and `02-open-items.md`
