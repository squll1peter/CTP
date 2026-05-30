# Stable Notification Local Receiver Test

This guide sets up a local REST counterpart for `StabilityWebhookPlugin` using Python standard library only.

## 1) Start the receiver

From the repository root:

```bash
python3 source/files/examples/stability_notify_receiver.py --port 18080
```

Default behavior:
- Accepts GET, POST, PUT
- Logs request summaries to console
- Writes full request records to `stable_notify_requests.jsonl`
- Returns HTTP 200

Retry test mode (fail first request once):

```bash
python3 source/files/examples/stability_notify_receiver.py --port 18080 --fail-once --fail-status 500
```

## 2) Configure CTP plugin target

Use this plugin in your `config.xml` test setup:

```xml
<Plugin
    name="StabilityWebhookPlugin"
    class="org.rsna.ctp.plugin.StabilityWebhookPlugin"
    id="StableNotify"
    baseUrl="http://127.0.0.1:18080/api/notify"
    method="POST"
    contentType="json"
    arguments="patientID={PatientID};studyUID={StudyInstanceUID};seriesUID={SeriesInstanceUID};accession={AccessionNumber};source=CTP"
    timeout="5000"
    retry="3"
    enable="yes"
    logDetails="yes"/>
```

Ensure your `StabilityMonitorProcessor` points to this plugin:

```xml
<Processor
    name="SeriesStabilityMonitor"
    class="org.rsna.ctp.stdstages.StabilityMonitorProcessor"
    root="roots/StabilityMonitor"
    level="series"
    targetID="StableNotify"
    timeout="120"
    dicomScript="scripts/StabilityMonitor.script"/>
```

Current-source note: `otherArguments` appears in templates, but the runtime currently parses only `arguments`. Put static values such as `source=CTP` directly in `arguments`.

## 3) Run test and verify

1. Start receiver.
2. Start CTP with your test config.
3. Send/ingest a small DICOM set that reaches stability timeout.
4. Check receiver terminal output for request method/path/body.
5. Check `stable_notify_requests.jsonl` for exact payload details.

Expected success:
- Receiver prints one request per stable group.
- Response in receiver log is 200.
- CTP logs indicate notification succeeded.

Expected retry behavior with `--fail-once`:
- First call returns 500.
- Plugin retries (based on `retry` value).
- Next call returns 200 and success is logged.

## 4) Quick standalone sanity checks

POST JSON:

```bash
curl -i -X POST "http://127.0.0.1:18080/api/notify" \
  -H "Content-Type: application/json" \
  -d '{"patientID":"P1","studyUID":"1.2.3","source":"CTP"}'
```

GET query:

```bash
curl -i "http://127.0.0.1:18080/api/notify?patientID=P1&studyUID=1.2.3&source=CTP"
```

## 5) Notes

- The receiver binds to localhost by default (`127.0.0.1`).
- If CTP runs on another host/container, set `--host 0.0.0.0` and point `baseUrl` to reachable host IP.
- Log file is append-only JSONL; remove it between test runs if you want a clean baseline.
