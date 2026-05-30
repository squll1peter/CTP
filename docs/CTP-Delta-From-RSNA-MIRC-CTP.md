# Clinical Trial Processor (CTP)

This article describes local extensions and modifications in this repository relative to:
https://mircwiki.rsna.org/index.php?title=MIRC_CTP

## Standard Plugins (Local Additions)

### StabilityWebhookPlugin

The StabilityWebhookPlugin is a plugin that sends an HTTP request when notified by a stage.

The configuration element for the StabilityWebhookPlugin is:

```xml
<Plugin
    name="StabilityWebhookPlugin"
    id="StableNotify"
    class="org.rsna.ctp.plugin.StabilityWebhookPlugin"
    baseUrl="https://example.org/api/notify"
    method="POST"
    contentType="json"
    arguments="patientID={PatientID};studyUID={StudyInstanceUID};source=CTP"
    timeout="5000"
    retry="3"
    enable="yes"
    logDetails="no"/>
```

where:

1. name is any text to be used as a label on configuration and status pages.
2. id is text to be used to uniquely identify the plugin.
3. baseUrl is the destination URL.
4. method is the HTTP method. Allowed values are GET, POST, and PUT. The default is POST.
5. contentType specifies the body format for POST and PUT. Allowed values are json and form. The default is json.
6. arguments is a semicolon-separated list of key=value pairs.
7. timeout is the connect/read timeout in milliseconds. The default is 5000.
8. retry is the number of attempts before failure is reported. The default is 3.
9. enable determines whether calls are emitted. Values are yes and no. The default is yes.
10. logDetails determines whether successful responses include body logging. Values are yes and no. The default is no.

Notes:

1. Values in braces in the arguments attribute are resolved from the representative DICOM object, for example {PatientID}.
2. Values not in braces are treated as literals.
3. For GET, parameters are placed on the query string.
4. For POST and PUT, parameters are encoded in the request body according to contentType.
5. If enable is no, notify returns success without sending an outbound call.
6. The current runtime parses only arguments. The template also exposes otherArguments, but that attribute is not currently consumed by code.

### StabilityExecPlugin

The StabilityExecPlugin is a plugin that executes a local command when notified by a stage.

The configuration element for the StabilityExecPlugin is:

```xml
<Plugin
    name="StabilityExecPlugin"
    id="StabilityExec"
    class="org.rsna.ctp.plugin.StabilityExecPlugin"
    command="C:/scripts/on-stable.cmd"
    arguments="-i=C:/input/{StudyInstanceUID} -o=C:/output --quiet"
    dryRun="no"
    minInterval="0"
    maxQueueSize="100"
    workingDir=""
    enable="yes"/>
```

where:

1. name is any text to be used as a label on configuration and status pages.
2. id is text to be used to uniquely identify the plugin.
3. command is the command template to execute.
4. arguments is a whitespace-separated list of command-line argument templates.
5. dryRun determines whether resolved commands are logged without execution. Values are yes and no. The default is no.
6. minInterval is the minimum milliseconds between command starts. The default is 0.
7. maxQueueSize is the queue capacity for pending executions. The default is 100.
8. workingDir is the process working directory.
9. enable determines whether execution is active. Values are yes and no. The default is yes.

Notes:

1. Executions are queued and processed by one worker thread.
2. ProcessBuilder is used directly; shell expansion is not performed.
3. If the queue is full, requests are dropped and notify returns failure.
4. DICOM placeholders in command tokens and arguments use the same style as the DirectoryStorageService structure option, for example {StudyInstanceUID} or (0020,000D).
5. If enable is no, notify returns success without queueing execution work.
6. The runtime does not support the old semicolon key=value argument syntax for StabilityExecPlugin.

## Standard Stages (Local Additions)

### Import Services

#### ObjectInlet

The ObjectInlet is an ImportService stage that accepts file injection from other stages.

The configuration element for the ObjectInlet is:

```xml
<ObjectInlet
    name="ObjectInlet"
    id="ObjectInlet"
    class="org.rsna.ctp.stdstages.ObjectInlet"
    root="roots/ObjectInlet"
    enable="yes"/>
```

where:

1. name is any text to be used as a label on configuration and status pages.
2. id is text to be used to uniquely identify the stage.
3. root is a directory for use by the stage for queueing and temporary files.
4. enable is present in templates as an optional yes/no attribute.

Notes:

1. Injection is copy-based. The source file is not modified.
2. ObjectFork and ObjectRouter resolve ObjectInlet by id.
3. The ObjectInlet runtime currently does not enforce an enable check in its inject path.

### Processors

#### StabilityMonitorProcessor

The StabilityMonitorProcessor is a processor stage that groups DICOM objects and detects idle groups.

The configuration element for the StabilityMonitorProcessor is:

```xml
<StabilityMonitorProcessor
    name="StabilityMonitorProcessor"
    id=""
    class="org.rsna.ctp.stdstages.StabilityMonitorProcessor"
    root="roots/StabilityMonitorProcessor"
    level="series"
    targetID="StableNotify"
    timeout="60"
    dicomScript=""/>
```

where:

1. name is any text to be used as a label on configuration and status pages.
2. id is an optional unique stage identifier.
3. root is the working directory. Representative files are stored under root/rep.
4. level is the grouping key. Allowed values are series, study, and patient. The default is series.
5. targetID is the plugin id to notify after stability is detected.
6. timeout is the idle threshold in seconds. The default is 60.
7. dicomScript is an optional filter script. Only matching DICOM objects are tracked.

Notes:

1. Objects pass through unchanged.
2. Non-DICOM objects are not tracked.
3. Notifications are emitted asynchronously by a notifier thread.
4. In the current runtime, targetID resolves to StabilityWebhookPlugin.
5. If targetID is unresolved, warnings are logged and notifications are dropped.

#### ObjectFork

The ObjectFork is a processor stage that passes objects downstream and injects copies into one or more ObjectInlet stages.

The configuration element for the ObjectFork is:

```xml
<ObjectFork
    name="ObjectFork"
    id="ObjectFork"
    class="org.rsna.ctp.stdstages.ObjectFork"
    root="roots/ObjectFork"
    targets="InletA;InletB"
    script="scripts/ObjectFork.script"
    enable="yes"/>
```

where:

1. name is any text to be used as a label on configuration and status pages.
2. id is text to be used to uniquely identify the stage.
3. root is a directory for use by the stage.
4. targets is a semicolon-separated list of ObjectInlet ids.
5. script is an optional DICOM filter script.
6. enable determines whether forking is active. Values are yes and no. The default is yes.

Notes:

1. The input object always continues downstream unchanged.
2. For DICOM objects, script controls whether the fork fires.
3. For non-DICOM objects, forking occurs regardless of script.
4. Target ids are resolved at stage start. Unresolved ids are skipped with warnings.

#### ObjectRouter

The ObjectRouter is a processor stage that diverts non-matching DICOM objects to an ObjectInlet and passes matching objects downstream.

The configuration element for the ObjectRouter is:

```xml
<ObjectRouter
    name="ObjectRouter"
    id="ObjectRouter"
    class="org.rsna.ctp.stdstages.ObjectRouter"
    root="roots/ObjectRouter"
    script="scripts/ObjectRouter.script"
    target="ObjectInlet"
    quarantine="quarantines/ObjectRouter"
    enable="yes"/>
```

where:

1. name is any text to be used as a label on configuration and status pages.
2. id is text to be used to uniquely identify the stage.
3. root is a directory for use by the stage.
4. script is the DICOM filter script for route decisions.
5. target is the ObjectInlet id for diverted objects.
6. quarantine is the directory used when diversion cannot be completed.
7. enable determines whether routing is active. Values are yes and no. The default is yes.

Notes:

1. If disabled, all objects pass through unchanged.
2. Non-DICOM objects pass through unchanged.
3. Matching DICOM objects pass through unchanged.
4. Non-matching DICOM objects are diverted and removed from downstream flow.
5. If target cannot be resolved, non-matching DICOM objects are quarantined when quarantine is configured.

#### DicomConditionalAuditLogger

The DicomConditionalAuditLogger is a processor stage derived from DicomAuditLogger. It adds an optional dicomScript gate for logging.

The configuration element for the DicomConditionalAuditLogger is:

```xml
<DicomConditionalAuditLogger
    name="DicomConditionalAuditLogger"
    id="DicomConditionalAuditLogger"
    class="org.rsna.ctp.stdstages.DicomConditionalAuditLogger"
    root="roots/DicomConditionalAuditLogger"
    cacheID="ObjectCache"
    auditLogID="AuditLog"
    auditLogTags="PatientID;PatientName;SOPInstanceUID"
    level="instance"
    dicomScript="scripts/DicomConditionalAuditLogger.script"/>
```

where:

1. name is any text to be used as a label on configuration and status pages.
2. id is text to be used to uniquely identify the stage.
3. root is a directory for use by the stage.
4. cacheID is an optional ObjectCache stage id.
5. auditLogID is the id of an AuditLog plugin.
6. auditLogTags is a semicolon-separated list of DICOM elements to include in audit entries.
7. level determines granularity. Allowed values are patient, study, and instance.
8. dicomScript is an optional filter script that determines whether a DICOM object is logged.

Notes:

1. If dicomScript is absent, behavior is the same as DicomAuditLogger.
2. If dicomScript is configured but unreadable at startup, the stage warns and continues in fail-open mode.
3. Script filtering is applied only to DICOM objects.
4. For patient level, one entry is made for each first-seen PatientID.
5. For study level, one entry is made for each first-seen StudyInstanceUID.
6. For instance level, an entry is made for each object received by the stage.
7. Unknown tags in auditLogTags are warned and ignored.

## Local Modifications to Existing Functionality

Relative to the RSNA baseline page, this repository also includes local modifications to existing components.

1. Security and authentication behavior is updated in servlet and login paths.
2. TLS and configuration handling is updated in startup and runtime flows.
3. Pipeline shutdown and reliability behavior is updated.
4. Admin pages include local UI updates, including cache-busting of static assets and expanded status fields.
5. Cross-platform test behavior has been updated for Windows execution.

## Library and Build Notes

The active runtime/build classpath differs from legacy RSNA distributions.

1. commons-compress updated from 1.0 to 1.27.1.
2. commons-net updated from 3.3 to 3.11.1.
3. commons-vfs2 updated from 2.0 to 2.9.0.
4. JSch updated from 0.1.53 to 0.2.21 (mwiede fork).
5. Build and test execution in this repository targets current Java/Ant toolchains.

## Scope Notes

1. This document compares local repository behavior to the RSNA page listed above.
2. This document reflects repository implementation state, including local additions present in source and templates.
3. For rollout sequencing, use the current handover set in docs/handover.
