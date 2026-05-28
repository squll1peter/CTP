# Spec: Local Command Notification Plugin

**Date:** 2026-05-28  
**Status:** Implemented, tests passing  
**Feature:** Execute a local OS command when a DICOM series / study / patient group becomes stable, as an alternative to the REST-based `StabilityWebhookPlugin`.

---

## Overview

`StabilityExecPlugin` is a direct counterpart to `StabilityWebhookPlugin`. It is notified by `StabilityMonitorProcessor` and runs a configurable local executable instead of making an HTTP call.

| Component | Type | Package |
|---|---|---|
| `StabilityExecPlugin` | CTP Plugin | `org.rsna.ctp.plugin` |
| `StabilityMonitorProcessor` | CTP Processor stage | `org.rsna.ctp.stdstages` |

The plugin does not block the pipeline. `notify()` enqueues the resolved command and returns immediately. A single background worker thread executes commands one at a time, respecting the configured minimum interval between starts.

---

## Files

| File | Purpose |
|---|---|
| `source/java/org/rsna/ctp/plugin/StabilityExecPlugin.java` | Plugin implementation |
| `source/test/java/org/rsna/ctp/plugin/StabilityExecPluginTest.java` | 15 unit tests |
| `source/resources/ConfigurationTemplates.xml` | Admin UI template entry added |

---

## Configuration Attributes

| Attribute | Required | Default | Description |
|---|---|---|---|
| `id` | yes | — | Unique ID; referenced by processor's `targetID` |
| `name` | yes | — | Display name |
| `root` | no | — | Plugin working directory (inherited from `AbstractPlugin`) |
| `command` | yes | — | Path (and optional base args) of the executable |
| `arguments` | no | — | Semicolon-delimited `key=DicomKeywordOrTag` pairs for dynamic values |
| `otherArguments` | no | — | Semicolon-delimited `key=value` static pairs |
| `dryRun` | no | `no` | `yes` = log resolved command without executing |
| `minInterval` | no | `0` | Min ms between command starts. `0` = no throttle |
| `maxQueueSize` | no | `100` | Max queued executions. Excess notifications are dropped |
| `workingDir` | no | plugin `root` | Working directory for the spawned process |
| `enable` | no | `yes` | `no` = disable without removing from config |

---

## Argument Syntax

Identical to `StabilityWebhookPlugin`. Values for `arguments` are resolved against the representative DICOM object at call time; `otherArguments` are literal.

```xml
<Plugin class="org.rsna.ctp.plugin.StabilityExecPlugin"
        id="StabilityExec"
        name="StabilityExec"
        command="/opt/scripts/on-stable.sh"
        arguments="patientID=PatientID;studyUID=StudyInstanceUID;accession=AccessionNumber"
        otherArguments="source=CTP;site=HOSP1"
        minInterval="10000"
        maxQueueSize="50"
        dryRun="no"
        enable="yes"/>
```

---

## Command Invocation Format

The plugin uses `ProcessBuilder` (no shell). Each resolved key=value pair is appended as a separate `--key value` flag pair. Dynamic args appear first (in declaration order), then static args.

For the config above, a typical invocation would be:

```
/opt/scripts/on-stable.sh --patientID P123 --studyUID 1.2.840.10008.5 --accession ACC001 --source CTP --site HOSP1
```

Arguments with spaces in values are passed as separate tokens, so no shell quoting is required.

---

## Threading Model

```
StabilityMonitorProcessor.Notifier
    └─► plugin.notify(DicomObject)          # non-blocking; builds token list, calls queue.offer()
            │                               # returns true (enqueued) or false (dropped, queue full)
            ▼
    ArrayBlockingQueue<List<String>>        # bounded by maxQueueSize; excess dropped with WARN log
            │
            ▼
    Worker thread (daemon)
        ├─ queue.take()                     # blocks until work available
        ├─ enforce minInterval gap          # from previous command start time
        ├─ ProcessBuilder(tokens).start()   # direct exec, no shell
        ├─ drain stdout+stderr              # prevents child blocking on full pipe
        └─ process.waitFor()               # blocks until command completes
```

Key properties:
- `notify()` is always non-blocking
- Commands are serialised (one at a time); a long-running command does not prevent enqueuing of later notifications
- `minInterval` is measured between command *starts*, not completions
- Worker is a daemon thread; it exits cleanly when `shutdown()` is called (via interrupt)

---

## Dry-Run Mode

Set `dryRun="yes"` to validate argument assembly without executing anything:

```
[dryRun] would execute: /opt/scripts/on-stable.sh --patientID P123 --studyUID 1.2.840.10008.5 --source CTP
```

Exit code is recorded as `0` in the status page. Any command path can be used, including nonexistent ones, since no process is spawned.

---

## Status Page

The admin UI status table shows:

| Field | Description |
|---|---|
| Enabled | yes / no |
| Dry Run | yes / no |
| Command | Base command tokens |
| Min Interval (ms) | Configured throttle |
| Max Queue Size | Configured cap |
| Queue depth | Current pending count |
| Total dropped | Cumulative dropped notifications |
| Last triggered | Timestamp of last `notify()` call |
| Last command | Full resolved command string of last execution |
| Last exit code | Exit code of last completed command (`N/A` if never run) |
| Last exit at | Timestamp of last command completion |

---

## Relating to StabilityMonitorProcessor

`StabilityExecPlugin` is wired to the processor via the same `targetID` mechanism as `StabilityWebhookPlugin`. Only one plugin type can be the target of a given processor:

```xml
<Processor class="org.rsna.ctp.stdstages.StabilityMonitorProcessor"
           name="StabilityMonitor"
           targetID="StabilityExec"
           level="series"
           timeout="120000" .../>

<Plugin class="org.rsna.ctp.plugin.StabilityExecPlugin"
        id="StabilityExec" .../>
```

---

## Tests (15 total — all pass)

| Test | What it verifies |
|---|---|
| `defaults_areApplied_whenOptionalAttributesMissing` | Default values present in status HTML |
| `constructor_parsesCommandTokens` | Multi-token base command (e.g. `/usr/bin/python3 script.py`) |
| `dynamicArguments_parsedFromArguments` | `key=DicomKeyword` pairs construct without error |
| `staticArguments_parsedFromOtherArguments` | `key=value` static pairs construct without error |
| `notify_disabled_returnsTrueWithoutEnqueue` | `enable=no` short-circuits immediately |
| `notify_dryRun_logsCommandWithoutExecutingRealProcess` | Nonexistent binary + dryRun=yes → no IOException |
| `notify_returnsFalse_whenQueueFull` | `maxQueueSize=1`, fill queue, second notify returns false |
| `notify_updatesLastTriggeredTime_onEnqueue` | `lastTriggeredTime` is stamped on enqueue |
| `notify_buildsCorrectCommandTokens_withDynamicAndStaticArgs` | `--pid P123 --suid 1.2.3 --source CTP` in lastCommand |
| `notify_fallsBackToColonDelimiterInArguments` | `pid:PatientID` (legacy syntax) accepted |
| `notify_executesCommand_andRecordsZeroExitCode` | `/bin/true` → exit code 0 in status HTML |
| `notify_recordsNonZeroExitCode_onFailingCommand` | `/bin/false` → non-zero exit code |
| `notify_handlesNonExistentCommand_withoutCrashing` | Nonexistent binary → exit code -1, worker survives |
| `getStatusHTML_containsAllExpectedLabels` | All 11 row labels present |
| `getStatusHTML_escapesSpecialCharsInCommand` | `&` in resolved DICOM value → `&amp;` in HTML |

---

## Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Argument style | `--key value` separate tokens | Q1=A; most compatible with CLI tools |
| Shell | No (ProcessBuilder direct) | Q2; avoids injection risk |
| Queue overflow | Drop newest, log warning | Q3; simplest, predictable backpressure |
| Concurrency | Single worker | Preserves ordering; prevents runaway parallelism |
| notify() return on drop | `false` | Signals caller; consistent with StabilityWebhookPlugin failure semantics |
| minInterval basis | Command *start* time | More intuitive for throttling rate; long commands don't artificially extend the gap |
| stdin | Closed immediately | Prevents child process blocking waiting for input |
| stdout/stderr | Merged and drained to null | Prevents pipe buffer deadlock for verbose commands |
