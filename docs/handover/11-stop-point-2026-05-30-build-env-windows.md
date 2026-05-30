# Stop-Point Handover (2026-05-30, New Machine Build Environment + Windows Validation)

## What Is Done

### Build environment bootstrap (complete)

1. Verified this machine initially had no Java or Ant commands available.
2. Installed JDK 21 (`EclipseAdoptium.Temurin.21.JDK`) and validated runtime/compiler availability.
3. Installed Apache Ant 1.10.15 from official binary zip under user tools directory.
4. Persisted user environment variables:
   - `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`
   - `ANT_HOME=C:\Users\house\tools\apache-ant-1.10.15`
   - User `Path` updated with both `bin` directories.

### Cross-platform test stabilization (complete)

5. `StabilityExecPluginTest` failed on Windows due to hardcoded Unix command paths (`/bin/true`, `/bin/false`, `/bin/echo`).
6. Test class updated to use OS-specific command helpers:
   - Windows: `cmd /c exit 0`, `cmd /c exit 1`, `cmd /c echo`
   - Non-Windows: existing Unix commands retained.
7. Behavior coverage unchanged; assertions remain focused on exit code and command token behavior.

### Build/test/package verification (complete)

8. `ant test` executed successfully after test portability fix.
9. `ant jar` executed successfully; `libraries/CTP.jar` rebuilt.
10. `ant installer` executed successfully; `products/CTP-installer.jar` rebuilt.

## Current Operational State

1. New machine is build-ready for this repository.
2. Full test suite passes on Windows with JDK 21 and Ant 1.10.15.
3. Packaging targets are green and distributables are freshly rebuilt.

## Files Changed In This Stop-Point

1. `source/test/java/org/rsna/ctp/plugin/StabilityExecPluginTest.java`
2. `libraries/CTP.jar`
3. `products/CTP-installer.jar`
4. `docs/report/stage-20.md`
5. `docs/handover/11-stop-point-2026-05-30-build-env-windows.md`
6. `docs/report/README.md`

## Immediate Next Start

1. Commit this stop-point set (test portability fix + rebuilt artifacts + docs).
2. Continue next iteration work from:
   - `docs/handover/02-open-items.md`
   - `docs/handover/03-next-iteration-plan.md`
3. For future fresh machines, reuse the environment settings in this stop-point.
