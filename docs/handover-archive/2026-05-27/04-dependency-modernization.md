# Stage 3 — Dependency Modernization

## Background

All CTP dependencies are vendored JARs in `libraries/`. No Maven or Gradle dependency management exists. Several JARs are severely outdated with documented CVEs; the oldest (`commons-compress-1.0.jar`) was released in 2009. Because the project processes DICOM files — many of which are delivered as ZIP archives — a zip-bomb or path-traversal vulnerability in commons-compress is directly exploitable in the import pipeline.

Each upgrade is a JAR replacement. The 36-test suite from Stages 1 and 2 serves as the regression net. Where the API changed between versions, affected source files are identified below.

Stages 3, 4, and 5 may proceed in parallel once Stage 2 is complete. There are no dependencies between them.

---

## Summary

Replace five vendored library groups with current versions. One (`jsch`) requires a vendor decision: full migration to Apache MINA SSHD 2.x (breaking API) vs. the API-compatible `com.github.mwiede:jsch` fork. This document specifies the near-term fork approach; the MINA migration is a separate work item.

After each JAR swap, compile and run the full test suite before proceeding to the next. Do not swap multiple JARs simultaneously — one swap at a time makes regression isolation straightforward.

---

## Steps

### Step 3.1 — commons-compress 1.0 → 1.26.2

**Current:** `libraries/commons-compress-1.0.jar` (released 2009)

**CVEs:**
- CVE-2021-35515: StackOverflow via infinite recursion in 7z handler (zip-bomb vector)
- CVE-2021-35516: OutOfMemoryError via 7z bomb (DoS)
- CVE-2021-35517: StackOverflow in TAR handler
- CVE-2012-2098: bzip2 DoS via large block size
- Path traversal in `ZipArchiveEntry.getName()` (pre-1.21)

**Affected source:** Verify before swapping:
```
grep -r "org.apache.commons.compress" source/java/
```
If CTP uses `java.util.zip` directly (not commons-compress API), no source changes are needed. `Installer.java` uses `java.util.zip.ZipFile` — confirm this is not the commons-compress equivalent.

**Action:** Replace `libraries/commons-compress-1.0.jar` with `commons-compress-1.26.2.jar`. Update any `build.xml` classpath reference. Run `ant compile && ant test`.

**Goal:** Zip-bomb and path-traversal CVEs resolved. All 36 tests pass.

---

### Step 3.2 — commons-net 3.3 → 3.11.x

**Current:** `libraries/ftp/commons-net-3.3.jar` (released 2013)

**Issues:** Multiple FTP/FTPS security improvements between 3.3 and 3.11, including improved TLS session resumption handling, fixes for `FTPSClient` PASV mode data channel TLS, and passive mode IP address validation. Combined with the Step 1.10 TLS protocol pin, this ensures FTPS uses both a patched library and restricted protocol versions.

**Affected source:**
- [FtpsExportService.java](../../source/java/org/rsna/ctp/stdstages/FtpsExportService.java)
- [FtpExportService.java](../../source/java/org/rsna/ctp/stdstages/FtpExportService.java) (if present)

The `FTPSClient` and `FTPClient` APIs are stable across this range; no source changes are expected.

**Action:** Replace `libraries/ftp/commons-net-3.3.jar` with `commons-net-3.11.x.jar`. Run `ant compile && ant test`.

**Goal:** FTPS library CVEs resolved. The TLS protocol pin from Step 1.10 remains in effect.

---

### Step 3.3 — commons-vfs2 2.0 → 2.9.x

**Current:** `libraries/ftp/commons-vfs2-2.0.jar` (released 2015)

**Issues:**
- CVE-2022-26336: transitive dependency on vulnerable Apache POI (if POI is present)
- Multiple SFTP provider configuration security fixes between 2.0 and 2.9
- Provider configuration key renames in some versions

**Affected source:** Find all usages before upgrading:
```
grep -r "commons.vfs2\|org.apache.commons.vfs" source/java/
```

**API caution:** Between VFS 2.0 and 2.9, some SFTP provider property key names changed. Review the VFS changelog for your specific upgrade path and cross-check against the affected source files. If key names changed, update the affected source before compiling.

**Action:** Replace JAR. If source changes are needed, apply them. Run `ant compile && ant test`.

**Goal:** VFS CVEs resolved. SFTP provider configuration unchanged.

---

### Step 3.4 — jsch 0.1.53 → mwiede fork 0.2.22

**Current:** `libraries/ftp/jsch-0.1.53.jar` (released 2014; original upstream abandoned ~2018)

**Issues:**
- CVE-2016-5725: path traversal in SFTP `get()`/`put()` operations
- Weak default SSH algorithm negotiation (accepts DH group 1, SHA-1 MACs)
- Upstream `com.jcraft:jsch` has had no releases since 2018

**Affected source:**
- [SftpExportService.java](../../source/java/org/rsna/ctp/stdstages/SftpExportService.java) — imports from `com.jcraft.jsch`

**Fork details:** `com.github.mwiede:jsch` maintains the same package names (`com.jcraft.jsch.*`) and API, so no source changes are expected. Verify by running `ant compile` after the swap.

**Action:** Replace `libraries/ftp/jsch-0.1.53.jar` with `mwiede-jsch-0.2.22.jar`. Run `ant compile && ant test`.

**Future work (tracked separately):** Full migration to Apache MINA SSHD 2.x provides a more actively maintained SSH implementation but requires rewriting `SftpExportService` (breaking API change). Plan this as a separate work item after Stage 5 is complete.

**Goal:** Abandoned jsch replaced with an actively maintained, CVE-patched fork. SSH connections use stronger default algorithms.

---

### Step 3.5 — slf4j 1.6.1 → 2.x

**Current:** `libraries/slf4j-api-1.6.1.jar`, `libraries/slf4j-log4j12-1.6.1.jar` (released 2012)

**Issues:** slf4j 1.6.1 is over a decade old. The `slf4j-log4j12` bridge binds to the log4j 1.x API, but CTP's deployment replaces log4j 1.x with log4j2 via `log4j-1.2-api-2.17.2.jar`. This creates a bridge-on-bridge configuration (`slf4j → log4j 1.x bridge → log4j2`). Upgrading to slf4j 2.x normalizes this.

**slf4j 2.x binding change:** slf4j 2.x uses `ServiceLoader` for provider binding instead of static class name detection. Use `slf4j-reload4j` (2.x) or verify `slf4j-log4j12-2.x` is the correct bridge for the log4j2 API bridge already in place. Do not use the 1.x bridge with slf4j 2.x.

**Affected source:** slf4j is typically used as a logging facade; if production code imports `org.slf4j.*` directly, verify those imports compile with the 2.x API (which is largely backward-compatible at the call-site level).

**Action:** Replace both SLF4J JARs with their 2.x equivalents. Confirm the bridge chain (slf4j → log4j2) produces log output on startup. Run `ant compile && ant test`.

**Goal:** SLF4J bridge chain modernized. Log output format unchanged.

---

## Stage 3 Checkpoint

After all five replacements, run the full test suite:

| Check | Expected |
|-------|---------|
| `ant compile` | Zero new compilation errors |
| `ant test` (36 tests) | All GREEN |
| Manual: start CTP with minimal config | Startup log appears correctly |
| Manual: ZIP import round-trip | File processed successfully (validates commons-compress) |
| Manual: FTPS export to test server | Connection succeeds over TLS 1.2+ only |

If any test goes RED after a JAR swap, roll back that specific JAR and examine the exception. Do not proceed to the next swap until the affected tests are GREEN again.
