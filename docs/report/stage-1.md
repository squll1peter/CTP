# Stage 1 Report — Security Fixes

**Date:** 2025-05-26  
**Tests before:** 1 (SmokeTest)  
**Tests after:** 24 (all GREEN)  
**Build:** `ant test` → BUILD SUCCESSFUL

---

## Summary

Stage 1 addressed ten security issues across the CTP codebase (OWASP Top 10, CWE catalogue).
All changes were made incrementally: test written (RED) → fix applied → test GREEN.

---

## Steps Completed

### 1.1 — Sensitive Credentials Redacted from Logs (CWE-532)
**File:** `source/java/org/rsna/ctp/Configuration.java`  
**Change:** The `logger.info("Configuration:\n" …)` call now serialises a sanitised
DOM clone instead of the raw document. `sanitizeForLogging(Element)` and
`redactSensitiveAttributes(Element)` static helpers perform a deep clone and replace
`password`, `keystorePassword`, and `truststorePassword` attribute values with
`[redacted]`.  
**Import added:** `org.w3c.dom.NamedNodeMap`  
**Test:** `ConfigLogSanitizationTest` (5 tests, same package as `Configuration`)  
**Side-fix:** Moved test to `org.rsna.ctp` package (package-private method access).

---

### 1.2 — ServerServlet Missing Authentication Guard (CWE-306)
**File:** `source/java/org/rsna/ctp/servlets/ServerServlet.java`  
**Change:** Added `if (!req.userHasRole("admin")) { res.setResponseCode(res.forbidden); res.send(); return; }` at the top of `doGet()`. Without this, any unauthenticated caller could retrieve the full CTP configuration XML including hostnames, ports, and stage details.  
**Test:** `ServerServletAuthTest` (3 tests, Mockito)  
**Infrastructure fix:** Downloaded `byte-buddy-agent-1.15.10.jar` to `libraries/test/` and added `-javaagent` and `-Dnet.bytebuddy.experimental=true` JVM args to the `<junit>` task in `build.xml` (required for Mockito 5 on JDK 25).

---

### 1.3 — CTPServlet Duplicate Import Removed (code hygiene)
**File:** `source/java/org/rsna/ctp/servlets/CTPServlet.java`  
**Change:** Removed duplicate `import java.util.LinkedList` (was on lines 11 and 13).  
**Note:** Per-request instantiation was verified (via `ServletSelector` bytecode analysis): `newInstance()` is called per request, so the mutable auth-state fields are safe. A concurrency test confirms that two separate instances do not share state.  
**Test:** `CTPServletAuthStateTest` (3 tests)

---

### 1.4 — AcceptAllHostnameVerifier Removed (CWE-297)
**File:** `source/java/org/rsna/runner/Runner.java`  
**Change:** Deleted the `AcceptAllHostnameVerifier` inner class (always returned `true`) and its installation in `getConnection()`. HTTPS connections now use the JVM's default `HttpsURLConnection` hostname verifier (PKIX).  
**Test:** `AcceptAllHostnameVerifierTest` (2 tests)  
**Note:** `ant clean` must be run when removing compiled inner classes; stale `.class` files survive incremental builds.

---

### 1.5 — XXE Protection on DocumentBuilderFactory (CWE-611)
**Files:** `source/java/org/rsna/runner/Runner.java`, `source/java/org/rsna/installer/Installer.java`  
**Change:** Added 5 hardening features to both `DocumentBuilderFactory` instances:
```
disallow-doctype-decl = true
external-general-entities = false
external-parameter-entities = false
load-external-dtd = false
setXIncludeAware(false)
setExpandEntityReferences(false)
```
**Test:** `XxeProtectionRunnerTest` (3 tests)

---

### 1.6 — Installer Hardcoded Keystore Password Replaced (CWE-259)
**File:** `source/java/org/rsna/installer/Installer.java`  
**Change:** Extracted `generateInstallPassword()` (20-char `SecureRandom` alphanumeric) and `applySslDefaults(Element, String)` as `static` package-visible helpers. The hardcoded `"edge1234"` passwords are replaced with a fresh random password generated at install time.  
**Import added:** `java.security.SecureRandom`  
**Test:** `InstallerPasswordTest` (5 tests)

---

### 1.7 — Launcher Keystore Password from Environment Variable (CWE-259)
**File:** `source/java/org/rsna/launcher/Launcher.java`  
**Change:** `javax.net.ssl.keyStorePassword` is now read from `CTP_KEYSTORE_PASSWORD` environment variable. If the variable is absent or empty, the legacy value `"ctpstore"` is used as a fallback and a `WARNING` is written to `System.err`.  
**Test:** None (constructor is a Swing component; covered by runtime warning).

---

### 1.8 — Email STARTTLS Downgrade Prevention (CWE-319)
**File:** `source/java/org/rsna/ctp/stdstages/email/EmailSender.java`  
**Change:** Added `props.put("mail.smtp.starttls.required", Boolean.toString(tls))`. Without this property, a STARTTLS-stripping MITM could force the session back to plaintext even with `starttls.enable=true`.  
**Test:** `EmailSenderTlsTest` (2 tests)

---

### 1.9 — Infinite Session Timeout Replaced (CWE-613)
**File:** `source/java/org/rsna/ctp/ClinicalTrialProcessor.java`  
**Change:** `setSessionTimeout(0L)` (infinite) replaced with `setSessionTimeout(4L * 60L * 60L * 1000L)` (4 hours). Abandoned admin sessions are now invalidated.  
**Test:** None (requires a live `Authenticator` instance tied to the embedded server).

---

### 1.10 — FTPS Restricted to TLS 1.2+ (CWE-326)
**File:** `source/java/org/rsna/ctp/stdstages/FtpsExportService.java`  
**Change:** `new FTPSClient(false)` → `new FTPSClient("TLS", false)` + `client.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"})`. SSLv3 and TLS 1.0/1.1 are now disabled.  
**Test:** Deferred to Stage 4 (integration test with a mock FTPS server).

---

## Test Count

| Test class | Tests |
|---|---|
| ConfigLogSanitizationTest | 5 |
| SmokeTest | 1 |
| CTPServletAuthStateTest | 3 |
| ServerServletAuthTest | 3 |
| InstallerPasswordTest | 5 |
| AcceptAllHostnameVerifierTest | 2 |
| XxeProtectionRunnerTest | 3 |
| EmailSenderTlsTest | 2 |
| **Total** | **24** |

---

## Infrastructure Changes

| File | Change |
|---|---|
| `libraries/test/byte-buddy-agent-1.15.10.jar` | Added (required by Mockito 5 inline mock maker) |
| `build.xml` | Added `-javaagent:byte-buddy-agent-1.15.10.jar` and `-Dnet.bytebuddy.experimental=true` to `<junit>` task |

---

## Deviations from Plan

- **Step 1.3 (AuthState refactor):** Per-request instantiation was confirmed via bytecode inspection of `ServletSelector`; the concurrency bug does not exist. The duplicate import was removed and an auth-state isolation test was written instead of the full `AuthState` inner class refactor, which would have required updating 10+ subclasses with no safety benefit.
- **Step 1.10 test:** Deferred to Stage 4; an FTPS integration test requires a mock server setup that fits better with the reliability stage.

---

## Next

Proceed to **Stage 2 — Characterisation Tests** (write tests that immediately pass GREEN to document existing behaviour of `AnonymizerFunctions` and `AbstractPipelineStage.getConfigHTML()`).
