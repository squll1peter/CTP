# Stage 4 — Reliability and Observability

## Background

The codebase contains 158+ empty `catch` blocks of the form `catch (Exception ex) { }` or `catch (Exception e) { }`. This is not a style concern — it is an operational risk. Pipeline stage load failures, quarantine I/O errors, and export loop exceptions are silently discarded. A clinical trial system processing PHI cannot lose data silently. Operators must be able to see what failed, when, and why.

A secondary concern is the shutdown sequence: the current implementation polls for up to 40 seconds and then calls `System.exit(0)` regardless of pipeline state, with no log output distinguishing a clean shutdown from a forced exit. Post-mortem analysis of a crash or mid-processing shutdown is impossible without this information.

Finally, two of the most critical Stage 1 security fixes (password disclosure in logs and the unauthenticated `/server` endpoint) should be verified end-to-end at the HTTP layer, not just in isolation.

---

## Summary

Three tiers of empty-catch remediation ordered by operational impact, shutdown sequence hardening with an outcome log, and two integration tests that start a real CTP server to verify the Stage 1 security fixes end-to-end.

---

## Steps

### Step 4.1 — Empty catch remediation — pipeline stage constructors (Priority 1)

**Files:** [Configuration.java:205](../../source/java/org/rsna/ctp/Configuration.java), [Pipeline.java:63](../../source/java/org/rsna/ctp/pipeline/Pipeline.java)

**Background:** These catches surround `Class.forName(className).newInstance()` calls that load pipeline stages. If a stage class is missing from the classpath, has a constructor that throws, or has a configuration error, the exception is swallowed and the pipeline silently runs with one fewer stage than configured. The operator sees the pipeline running but is not told that a stage is missing.

**Fix pattern:** Replace each empty catch with:
```java
catch (Exception ex) {
    logger.error(name + ": Failed to load stage '" + className
                 + "': " + ex.getMessage(), ex);
}
```
The full stack trace (`ex` as second argument) is included so the root cause is visible in the log.

**Goal:** Every stage load failure produces an ERROR-level log entry with the class name and exception. Silent missing-stage failures are eliminated.

**Supporting tests:** Stage constructors can be partially tested by supplying a config element with a non-existent class name and verifying that the error is logged. Use a log4j2 `ListAppender` in the test to capture log output.

---

### Step 4.2 — Empty catch remediation — quarantine operations (Priority 2)

**Files:** [Quarantine.java](../../source/java/org/rsna/ctp/pipeline/Quarantine.java) (~20 instances), [QueueManager.java:301,324](../../source/java/org/rsna/ctp/pipeline/QueueManager.java)

**Background:** Quarantine I/O failures — file moves, index writes, directory creation — are silently swallowed. A file that should have been quarantined for review may be lost without any indication. In a clinical trial, unquarantined files represent an unreviewed data quality issue that goes unnoticed until audit.

`QueueManager` returns `null` from dequeue/enqueue on exception. Downstream callers check for null and treat it as an empty queue, masking the underlying I/O failure indefinitely.

**Fix:** Add `logger.error()` in all quarantine catch blocks. For `QueueManager`, log the exception before returning null. For catches that are genuinely intentional and benign (e.g., a file-not-found that is expected), add an explanatory comment:

```java
catch (IOException ex) {
    logger.error("Quarantine.store: failed to move file to quarantine: "
                 + ex.getMessage(), ex);
}
```

**Goal:** Quarantine failures are logged at ERROR level. No silent data-loss scenarios remain in the quarantine path.

---

### Step 4.3 — Empty catch remediation — export/import loops (Priority 3)

**Files:** [AbstractExportService.java](../../source/java/org/rsna/ctp/pipeline/AbstractExportService.java), [AbstractImportService.java](../../source/java/org/rsna/ctp/pipeline/AbstractImportService.java), and concrete export service implementations

**Background:** The export/import service loops are the hot paths — they run continuously while CTP is active. Empty catches here suppress network timeouts, connection resets, and I/O errors silently. While the service counters (failed/passed/quarantined) track some outcomes, the specific exception causing a repeated failure is lost, making root-cause diagnosis extremely difficult.

**Fix:** At minimum, log `ex.getMessage()` at DEBUG level in every catch block in the export/import hot paths. For catches that are intentionally silent (e.g., `Thread.sleep` interrupts, connection-retry loops), preserve the interrupt flag and add a comment:

```java
catch (InterruptedException ex) {
    Thread.currentThread().interrupt(); // preserve interrupt status for caller
    break;
}
```

**Goal:** Every unexpected exception in the export/import path has a log entry. Intentionally-silent catches are explicitly documented with comments explaining why they are silent.

---

### Step 4.4 — Shutdown sequence hardening

**File:** [ShutdownServlet.java](../../source/java/org/rsna/ctp/servlets/ShutdownServlet.java) (around line 95)

**Background:** The current shutdown polls `config.pipelinesAreDown()` up to 20 times with 2-second sleeps (40 seconds total), then calls `System.exit(0)` regardless of pipeline state. There is no log message distinguishing:
- A clean shutdown (all pipelines confirmed stopped within the window)
- A forced exit (timeout expired, pipelines still running)

After a crash or unplanned restart, operators cannot determine from the logs whether shutdown was orderly.

**Fix:**

```java
boolean pipesClean = false;
long deadline = System.currentTimeMillis() + 60_000L;
for (int k = 0; k < maxTries; k++) {
    if (config.pipelinesAreDown()) {
        pipesClean = true;
        break;
    }
    if (System.currentTimeMillis() > deadline) {
        logger.error("Graceful shutdown timed out after 60s; forcing exit.");
        break;
    }
    try {
        Thread.sleep(2000);
    } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        break;
    }
}
if (pipesClean) {
    logger.info("All pipelines confirmed stopped. Shutdown complete.");
} else {
    logger.warn("Shutdown forced; some pipelines may not have stopped cleanly.");
}
System.exit(0);
```

**Goal:** Every shutdown produces a log entry stating whether it was clean or forced. `InterruptedException` is handled correctly (interrupt flag preserved). The 60-second timeout is a named constant.

---

### Step 4.5 — Integration tests (Tier C)

**Background:** Tier A and B tests run in isolation without the full CTP server. Two of the most impactful Stage 1 fixes — password redaction in logs and the `ServerServlet` auth guard — have server-dependent behavior. The integration tests in this step confirm they work end-to-end at the HTTP layer.

These tests start a real `HttpServer` with a minimal `config.xml`, make HTTP requests, and assert on responses and log output. They are slower than unit tests (~5 seconds each) and run as part of the normal `ant test` suite.

---

**Test C1 — `ServerServletHttpIntegrationTest`**

**File:** `source/test/java/org/rsna/ctp/servlets/ServerServletHttpIntegrationTest.java`

Setup: Start a minimal CTP instance with a config containing one stage with `password="integration-test-secret"`. Wait for startup.

| Test method | What it verifies | Expected state |
|-------------|----------------|---------------|
| `testUnauthenticatedGetServerReturns403` | `GET /server` without credentials → HTTP 403 body does not contain `"integration-test-secret"` | GREEN |
| `testAuthenticatedAdminGetServerReturns200` | `GET /server` with admin credentials → HTTP 200; body does not contain `"integration-test-secret"` (password redacted) | GREEN |

---

**Test C2 — `ConfigLogPasswordIntegrationTest`**

**File:** `source/test/java/org/rsna/ctp/ConfigLogPasswordIntegrationTest.java`

Setup: Register a log4j2 `ListAppender` before starting `Configuration`. Start `Configuration` with a config containing `password="log-test-sentinel-xyz"`.

| Test method | What it verifies | Expected state |
|-------------|----------------|---------------|
| `testStartupLogContainsNoPlaintextPassword` | No captured log message at any level (TRACE through ERROR) contains `"log-test-sentinel-xyz"` | GREEN |

---

## Stage 4 Checkpoint

| Check | Expected |
|-------|---------|
| `ant test` — all 38+ tests | All GREEN |
| Manual: startup log | No plaintext passwords visible at any log level |
| Manual: `GET /server` unauthenticated | HTTP 403 |
| Manual: `GET /server` as admin | HTTP 200, no passwords in response body |
| Manual: shutdown CTP | Log contains "confirmed stopped" or "forced" entry |
| Manual: remove a stage class from classpath | ERROR log entry names the missing class |
