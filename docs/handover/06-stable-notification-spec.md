# Spec: Stable-Group Notification Feature

**Date:** 2026-05-27  
**Status:** Implementing  
**Feature:** Fire a configurable REST API call when a DICOM series / study / patient has been idle (no new objects) for a configurable timeout.

---

## Overview

Two new components work together:

| Component | Type | Package |
|---|---|---|
| `StableNotificationPlugin` | CTP Plugin | `org.rsna.ctp.plugin` |
| `StabilityMonitorProcessor` | CTP Processor stage | `org.rsna.ctp.stdstages` |

The **Processor** tracks arriving DICOM objects and detects when a group (series / study / patient) has become idle. The **Plugin** holds the HTTP connection configuration and fires the actual REST call.

All DICOM objects pass through the processor to the next pipeline stage unchanged. Non-DICOM objects bypass the processor entirely.

---

## Component 1 — StableNotificationPlugin

**File:** `source/java/org/rsna/ctp/plugin/StableNotificationPlugin.java`  
**Extends:** `AbstractPlugin`  
**Declared in config.xml as:** `<Plugin class="org.rsna.ctp.plugin.StableNotificationPlugin" ...>`

### Configuration Attributes

| Attribute | Required | Default | Description |
|---|---|---|---|
| `id` | yes | — | Unique ID; referenced by processor's `targetID` |
| `name` | yes | — | Display name |
| `root` | no | — | Working directory (inherited from AbstractPlugin) |
| `baseUrl` | yes | — | REST endpoint URL (e.g. `https://example.com/api/notify`) |
| `method` | no | `POST` | HTTP method: `GET`, `POST`, or `PUT` |
| `contentType` | no | `json` | Body format for POST/PUT: `json` or `form` |
| `arguments` | no | — | Semicolon-delimited `key:dicomtag` pairs for dynamic values |
| `otherArguments` | no | — | Semicolon-delimited `key=value` static pairs |
| `timeout` | no | `5000` | HTTP connect + read timeout in milliseconds |
| `retry` | no | `3` | Number of retry attempts on failure |
| `enable` | no | `yes` | Set to `no` to disable without removing from config |
| `logDetails` | no | `no` | Log full response body on success as well as failure |

### Argument Formats

**`arguments`** — dynamic values resolved from the representative DICOM object at call time:

```
arguments="patientID:00100020;studyUID:0020000D;accession:00080050"
```

The DICOM tag can be either a hex tag number (`00100020`) or a keyword (`PatientID`). Resolution uses `DicomObject.getElementValue(tagName, "")`.

**`otherArguments`** — static literal values always included:

```
otherArguments="source=CTP;facility=HOSP1"
```

Both sets are merged into a single parameter map at call time (static arguments added after dynamic ones; duplicate keys use the static value).

### HTTP Request Behaviour

| method | contentType | Request |
|---|---|---|
| `GET` | (ignored) | All params appended as URL query string; no body |
| `POST` / `PUT` | `json` | `Content-Type: application/json; charset=UTF-8` with `{"key":"value",...}` body |
| `POST` / `PUT` | `form` | `Content-Type: application/x-www-form-urlencoded; charset=UTF-8` body |

### Retry & Logging

- On each failed attempt: `WARN` log with attempt number.
- After all attempts exhausted: `ERROR` log.
- On success with `logDetails="no"`: `INFO` log with HTTP response code only.
- On success with `logDetails="yes"`: `INFO` log with code + full response body.
- On failure: `WARN` log with code + full response body always.

### Public API (called by the processor)

```java
public boolean notify(DicomObject representative)
```

Thread-safe. Returns `true` on HTTP 2xx; `false` otherwise.

---

## Component 2 — StabilityMonitorProcessor

**File:** `source/java/org/rsna/ctp/stdstages/StabilityMonitorProcessor.java`  
**Extends:** `AbstractPipelineStage`  
**Implements:** `Processor`, `Scriptable`  
**Declared in config.xml as:** `<Processor class="org.rsna.ctp.stdstages.StabilityMonitorProcessor" ...>`

### Configuration Attributes

| Attribute | Required | Default | Description |
|---|---|---|---|
| `id` | no | — | Stage identifier |
| `name` | yes | — | Display name |
| `root` | yes | — | Working dir; used for temp representative files (`root/rep/`) |
| `level` | no | `series` | Grouping level: `series`, `study`, or `patient` |
| `targetID` | yes | — | ID of the `StableNotificationPlugin` to call |
| `timeout` | no | `60` | Inactivity timeout in **seconds** before firing |
| `dicomScript` | no | — | CTP filter script; only matching objects are tracked |

### Processing Logic

```
process(fileObject):
  if not DicomObject → return fileObject immediately (no tracking)
  if dicomScript set and object does not match → return fileObject (pass-through, no tracking)
  groupKey = PatientID | StudyInstanceUID | SeriesInstanceUID  (based on level)
  if groupKey not yet tracked:
      copy fileObject to root/rep/<safeKey>.dcm  (representative)
      add GroupRecord(groupKey, repFile, now) to groups map
  else:
      update groups[groupKey].lastSeen = now
  return fileObject  ← always passes to next stage
```

### Background Notifier Thread

- Started by `start()`; sleeps `max(1s, timeout/2)` between sweeps.
- Each sweep: for every tracked group where `now - lastSeen >= timeout * 1000 ms`:
  1. Remove group from map.
  2. Load `DicomObject` from saved representative file.
  3. Call `plugin.notify(representative)`.
  4. Delete the representative temp file.
- `shutdown()` interrupts the thread and sets `stop = true`.

### Plugin Resolution

Resolved in `start()` via `Configuration.getInstance().getRegisteredPlugin(targetID)`. If the referenced plugin is missing or the wrong type, a `WARN` is logged and no notifications are sent.

---

## Example config.xml Snippet

```xml
<Plugin
    name="StableNotificationPlugin"
    class="org.rsna.ctp.plugin.StableNotificationPlugin"
    id="StableNotify"
    baseUrl="https://example.com/api/study-arrived"
    method="POST"
    contentType="json"
    arguments="patientID:00100020;studyUID:0020000D;seriesUID:0020000E;accession:00080050"
    otherArguments="source=CTP"
    timeout="5000"
    retry="3"
    enable="yes"
    logDetails="no"/>

<Pipeline name="Main Pipeline" root="roots/pipeline">
    <!-- ... import, anonymizer, etc ... -->

    <Processor
        name="SeriesStabilityMonitor"
        class="org.rsna.ctp.stdstages.StabilityMonitorProcessor"
        root="roots/StabilityMonitor"
        level="series"
        targetID="StableNotify"
        timeout="120"
        dicomScript="scripts/StabilityMonitor.script"/>

    <!-- ... storage, export, etc ... -->
</Pipeline>
```

---

## Threading Model

- `groups` (`ConcurrentHashMap`) is safe for concurrent access from pipeline threads and the Notifier.
- `StableNotificationPlugin.notify()` is stateless per-call; all fields are final. Safe to call from multiple threads.
- Representative file name is derived from the sanitized group key (non-alphanumeric chars → `_`), which is unique per group at the chosen level.

---

## File Layout

```
source/java/org/rsna/ctp/plugin/StableNotificationPlugin.java     ← new
source/java/org/rsna/ctp/stdstages/StabilityMonitorProcessor.java ← new
source/resources/ConfigurationTemplates.xml                        ← modified (Plugin + Processor templates added)
```

---

## Open Items / Future Work

- Certificate trust for HTTPS targets: the plugin uses the default JVM trust store. If the target uses a self-signed cert, trust must be added to the JVM keystore separately.
- Body format: currently `json` (flat object) or `form`. Nested JSON is not supported.
- Response-triggered quarantine: deliberately excluded from scope.
