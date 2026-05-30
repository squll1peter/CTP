# Completed Work

## Security and Login

- Login popup submits credentials using POST rather than GET query parameters.
- Confirmed server-side login handling exists in bundled util.jar LoginServlet (doGet and doPost are present).

## SSL and Browser Behavior

- HTTPS on 8443 is functioning for current validation flow.
- Browser-dependent probe traffic (especially from Chrome during untrusted-cert handling) was generating repeated "Attack detected" warnings from LoginServlet.
- Logging was tightened to suppress noisy false-positive warnings from org.rsna.servlets.LoginServlet while keeping broader RSNA logs intact.

## Configuration Defaults

- Server default for enableStageProfiling changed from yes to no in configuration templates.
- Runtime fallback default in org.rsna.ctp.Configuration was aligned to disabled profiling.

## Build and Packaging

- Packaging/build validation completed after changes using ant jar and ant installer.
- Runtime tree under build/CTP refreshed with updated resources and config defaults.

## Stable-Group Notification Feature (2026-05-28)

- `StabilityMonitorProcessor` — groups DICOM objects by configurable level (series / study / patient), detects inactivity after a timeout, and fires a registered `StabilityNotificationPlugin`.
- `StabilityWebhookPlugin` — fires an HTTP REST call (GET / POST-JSON / POST-form) with DICOM-resolved and static arguments on group stability. Configurable retry and timeout.
- `StabilityExecPlugin` — command-execution plugin implementation exists, has tests, and is reachable from `StabilityMonitorProcessor` through the shared notification contract.
- StabilityExecPlugin argument syntax is whitespace-delimited command-line templates; DICOM placeholders use DirectoryStorageService structure syntax such as `{StudyInstanceUID}` or `(0020,000D)`.
- Status HTML fields added to both stages and plugins (last file received, last trigger, last called URL / last command / last exit code).
- `ConfigurationTemplates.xml` entries added for both plugins.
- Python test receiver at `source/files/examples/stability_notify_receiver.py`.
- Full test suite: BUILD SUCCESSFUL (0 failures). New tests: 44 (StabilityMonitor + StableNotification) + 15 (LocalCommand).

## Object Branching Feature (2026-05-28)

- `ObjectInlet` added as a queue-backed import stage for programmatic injection.
- `ObjectFork` added as a pass-through processor that copies objects to one or more target inlets.
- `ObjectRouter` added as a selective diversion processor for non-matching DICOM objects.
- `ConfigurationTemplates.xml` updated with templates for all three new stages.
- New tests added and passing:
	- `ObjectInletTest` (11)
	- `ObjectForkTest` (16)
	- `ObjectRouterTest` (15)
- Full test suite: BUILD SUCCESSFUL (0 failures).
