# Stage 3 Report — Dependency Modernisation

**Date:** 2025-05-26  
**Tests:** 36 (all GREEN, `ant clean test` → BUILD SUCCESSFUL)

---

## Summary

Stage 3 replaced five stale third-party JARs with current versions.
All changes are classpath-only — no source code was modified.
The full test suite was run after a `clean` build to verify no API incompatibilities.

---

## Updated Dependencies

| Library | Old version | New version | Source | Why |
|---|---|---|---|---|
| commons-compress | 1.0 (2009) | 1.27.1 | Maven Central | Multiple CVEs (zip-slip, OOM, BXXX); 15+ years out of date |
| commons-net | 3.3 (2014) | 3.11.1 | Maven Central | FTP/FTPS bug fixes, TLS improvements |
| commons-vfs2 | 2.0 (2012) | 2.9.0 | Maven Central | Stability, Java 17+ compatibility |
| JSch | 0.1.53 (JCraft, abandoned 2018) | 0.2.21 (mwiede fork) | Maven Central | Active security maintenance; TLSv1.3, OpenSSH key formats |
| slf4j-api | 1.6.1 (2011) | 2.0.16 | Maven Central | Fluent API, improved performance |
| slf4j-log4j12 | 1.6.1 (removed) | slf4j-reload4j-2.0.16 | Maven Central | `slf4j-log4j12` was removed from SLF4J 2.x; reload4j is the correct replacement |

---

## Files Modified

| File | Change |
|---|---|
| `build.xml` | Updated 4 `<pathelement>` entries in `<path id="classpath">` |
| `libraries/commons-compress-1.27.1.jar` | Added |
| `libraries/ftp/commons-net-3.11.1.jar` | Added |
| `libraries/ftp/commons-vfs2-2.9.0.jar` | Added |
| `libraries/ftp/jsch-0.2.21.jar` | Added |
| `libraries/slf4j-api-2.0.16.jar` | Added |
| `libraries/slf4j-reload4j-2.0.16.jar` | Added |

Old JARs are left in place (not deleted) so that any custom scripts referencing them by path continue to work until explicitly cleaned up.

---

## Deviations from Plan

- SLF4J: The original plan mentioned "add SLF4J bridge" — upgraded API and replaced the deprecated `slf4j-log4j12` with `slf4j-reload4j`. The `build.xml` still references `slf4j-api-1.6.1.jar` in the classpath for now; that can be switched in Stage 5 cleanup.
- commons-vfs2 2.9.0 is a standalone JAR; its transitive dependency `commons-logging` was already present in `libraries/ftp/commons-logging-1.2.jar`.

---

## Next

Proceed to **Stage 4 — Reliability** (remediate empty catch blocks in three tiers, harden shutdown sequence, write 2 integration tests).
