# Stage 1 — Security Fixes

## Background

Two full review passes identified ten security vulnerabilities. The project handles PHI in clinical trial DICOM data; the most critical findings (password disclosure, unauthenticated endpoints) represent direct breach risk. The fixes range from single-line changes to small refactors required to make logic testable.

All ten fixes follow the same micro-cycle:
1. Extract any private or GUI-entangled logic into a package-visible static method (if needed)
2. Write the test — it must **fail RED** before the fix is applied
3. Apply the fix
4. Confirm the test passes GREEN
5. Run the full suite — no regressions

**Key constraint:** The HTTP server classes (`HttpRequest`, `HttpResponse`, `User`) are in `util.jar` — compiled Java 8 bytecode with no source. Mockito 5 can mock them via byte-buddy because none are `final`. Constructor injection is not possible (constructors require a live socket/server), so all servlet tests use `Mockito.mock()` with `RETURNS_DEFAULTS` and stub only the methods under test.

---

## Summary

Ten fixes: two credential-disclosure issues, one concurrency race, two TLS bypasses, two XXE injection points, two hard-coded passwords, one missing TLS enforcement, one session management failure, and one protocol downgrade path.

---

## Steps

### Step 1.1 — Config log password exposure

**File:** [Configuration.java:158](../../source/java/org/rsna/ctp/Configuration.java)

**Background:** `logger.info("Configuration:\n" + XmlUtil.toPrettyString(root))` dumps the fully-parsed DOM tree of `config.xml` at INFO level on every startup. Every `password`, `keystorePassword`, and `truststorePassword` attribute is included verbatim. Any log file access, log aggregation, or SIEM integration exposes all service credentials in plaintext.

**Extract first:** The test cannot access `logger` or the startup flow directly. Extract the sanitization logic into a package-visible static method on `Configuration`:

```java
static String sanitizeForLogging(Element root)
```

This method deep-clones the DOM, walks all elements, and replaces the value of any attribute named `password`, `keystorePassword`, or `truststorePassword` with `[redacted]`. The existing logger call becomes:

```java
logger.info("Configuration:\n" + sanitizeForLogging(root));
```

**Goal:** No plaintext password appears in any log output at any level.

**Supporting tests:** `org/rsna/ctp/security/ConfigLogSanitizationTest`

| Test method | What it checks | State |
|-------------|---------------|-------|
| `testPasswordAttributeIsRedacted` | `password="secret"` becomes `[redacted]` | RED → GREEN |
| `testKeystorePasswordAttributeIsRedacted` | `keystorePassword="kspw"` becomes `[redacted]` | RED → GREEN |
| `testTruststorePasswordAttributeIsRedacted` | `truststorePassword="tspw"` becomes `[redacted]` | RED → GREEN |
| `testNonSensitiveAttributesArePreserved` | `url`, `port`, `name` are unchanged | RED → GREEN |
| `testUsernameIsNotRedacted` | `username` is not a secret, must survive | RED → GREEN |

---

### Step 1.2 — ServerServlet unauthenticated config disclosure

**File:** [ServerServlet.java:47](../../source/java/org/rsna/ctp/servlets/ServerServlet.java)

**Background:** `ServerServlet.doGet()` has no authentication check. Any client that can reach the CTP admin port can call `GET /server` and receive the full configuration XML, including all plaintext passwords. `ConfigurationServlet` correctly guards with `req.userHasRole("admin")`; `ServerServlet` has no such guard at all.

**Refactor first:** `doGet()` calls `Configuration.getInstance()` (a live singleton). Extract the response-building logic into a package-visible static method:

```java
static String buildConfigResponse(Configuration config, String ip, String port,
                                   String build, String java)
```

The auth guard test then tests the guard behaviour independently of `Configuration`.

**Fix:** At the top of `doGet()`, before any configuration access:

```java
if (!req.userHasRole("admin")) {
    res.setResponseCode(HttpResponse.forbidden);
    res.send();
    return;
}
```

**Goal:** Unauthenticated `GET /server` returns HTTP 403 with no body. Config XML is never written for non-admin callers.

**Supporting tests:** `org/rsna/ctp/servlets/ServerServletAuthTest`

| Test method | What it checks | State |
|-------------|---------------|-------|
| `testUnauthenticatedRequestGets403` | mock `userHasRole("admin") → false`; verify `res.setResponseCode(403)` called | RED → GREEN |
| `testUnauthenticatedRequestNeverWritesBody` | verify `res.write(anyString())` is never called | RED → GREEN |
| `testAuthenticatedAdminIsNotRejected` | mock `userHasRole("admin") → true`; verify 403 is NOT set | RED → GREEN |

---

### Step 1.3 — CTPServlet shared auth state race condition

**File:** [CTPServlet.java:51–53](../../source/java/org/rsna/ctp/servlets/CTPServlet.java)

**Background:** `userIsAdmin`, `userIsStageAdmin`, and `userIsAuthorized` are instance fields written by `loadParameters(HttpRequest req)`. Servlet instances are singletons shared across all threads. Under concurrent load, Thread A (admin) sets `userIsAdmin = true`, then Thread B (anonymous) sets `userIsAdmin = false` before Thread A reads the value — or vice versa, elevating an anonymous user to admin for Thread A's request.

Additionally, `CTPServlet.java` has a duplicate `import java.util.LinkedList;` on lines 11 and 13 (fix the duplicate import while editing this file).

**Design change:** Change `loadParameters()` from `void` (writing instance fields) to returning an immutable value object:

```java
// Before:
protected void loadParameters(HttpRequest req)  // sets this.userIsAdmin, etc.

// After:
protected AuthState loadParameters(HttpRequest req)  // returns new AuthState(...)
```

`AuthState` is a simple immutable class with `final boolean isAdmin`, `final boolean isStageAdmin`, `final boolean isAuthorized`. All subclass `doGet()` methods receive and use the returned `AuthState` instead of reading instance fields.

**Goal:** Concurrent requests from different users cannot interfere with each other's authorization state.

**Supporting tests:** `org/rsna/ctp/servlets/CTPServletConcurrencyTest`

| Test method | What it checks | State |
|-------------|---------------|-------|
| `testAuthStateForAdminRequest` | mock `userHasRole("admin") → true`; returned `AuthState.isAdmin == true` | RED → GREEN |
| `testAuthStateForNonAdminRequest` | mock → false; `AuthState.isAdmin == false` | RED → GREEN |
| `testConcurrentCallsProduceIndependentAuthState` | 500 admin + 500 anon threads concurrently; each `AuthState` matches its own request | RED → GREEN |

---

### Step 1.4 — AcceptAllHostnameVerifier disables TLS

**File:** [Runner.java:258](../../source/java/org/rsna/runner/Runner.java)

**Background:** The static inner class `Runner.AcceptAllHostnameVerifier` overrides `HostnameVerifier.verify()` and unconditionally returns `true`. It is installed on all HTTPS connections between the Launcher/Runner and the CTP process — including the shutdown endpoint and config fetch. A network attacker can present any certificate and complete the TLS handshake; the hostname is never checked.

**Fix:** Delete `AcceptAllHostnameVerifier` entirely. Replace `HttpsURLConnection.setDefaultHostnameVerifier(new AcceptAllHostnameVerifier())` with the JDK's default PKIX verifier (no call needed — the default is already PKIX). If self-signed certificates are required in development, document that as a deployment concern (trust anchor, not hostname bypass).

**Goal:** Mismatched TLS certificate hostnames are rejected. The class `AcceptAllHostnameVerifier` no longer exists in the source.

**Supporting tests:** `org/rsna/runner/AcceptAllHostnameVerifierTest`

| Test method | What it checks | State |
|-------------|---------------|-------|
| `testVerifyWithMismatchedHostReturnsFalse` | after fix, the JDK verifier is used; this is a compilation-level test — `AcceptAllHostnameVerifier` must not compile | RED → GREEN |
| `testDefaultVerifierIsNotAcceptAll` | `HttpsURLConnection.getDefaultHostnameVerifier()` is not an instance that always returns true | RED → GREEN |

---

### Step 1.5 — XXE-unprotected XML parsers

**Files:** [Runner.java:290](../../source/java/org/rsna/runner/Runner.java), [Installer.java:1146](../../source/java/org/rsna/installer/Installer.java)

**Background:** Both `getDocument(File f)` static methods create a `DocumentBuilderFactory` with no `setFeature()` calls. An attacker supplying a crafted XML file (e.g., a modified `config.xml`) can include a `<!DOCTYPE>` external entity declaration and cause the parser to read arbitrary local files, including SSH keys, other config files, or `/etc/shadow`.

**Fix:** Apply to both `DocumentBuilderFactory` instances:

```java
DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
dbf.setXIncludeAware(false);
dbf.setExpandEntityReferences(false);
```

**Goal:** DOCTYPE declarations in parsed XML are rejected with a parser error. External entity references are never resolved.

**Supporting tests:** `org/rsna/ctp/security/XxeProtectionTest`

| Test method | What it checks | State |
|-------------|---------------|-------|
| `testRunnerGetDocumentRejectsXxePayload` | write temp XML with `<!ENTITY xxe SYSTEM "file:///etc/passwd">`; `Runner.getDocument()` must throw or return null, not file contents | RED → GREEN |
| `testInstallerGetDocumentRejectsXxePayload` | same for `Installer.getDocument()` | RED → GREEN |
| `testRunnerGetDocumentParsesLegalXml` | normal config XML still parses correctly after hardening | RED → GREEN |

---

### Step 1.6 — Hard-coded installer default password

**File:** [Installer.java:650](../../source/java/org/rsna/installer/Installer.java)

**Background:** `adjustConfiguration()` writes `keystorePassword="edge1234"` and `truststorePassword="edge1234"` into `config.xml` for every ISN installation where the EdgeServer keystore is absent. This is a publicly known default present in the source since at least 2013. Every such deployment shares the same keystore credential until an operator manually changes it.

**Extract first:** `adjustConfiguration()` is `private` and GUI-entangled (it reads Swing component state). Extract two static package-visible methods:

```java
static String generateInstallPassword()
// Returns a 20-character random alphanumeric string using SecureRandom.

static void applySslDefaults(Element sslElement, String password)
// Sets keystorePassword, truststorePassword, keystore, truststore attributes on sslElement.
```

`adjustConfiguration()` then calls `applySslDefaults(ssl, generateInstallPassword())`.

**Goal:** Every new installation receives a unique randomly-generated keystore password. The string `"edge1234"` cannot appear as any credential value in a newly-generated config.

**Supporting tests:** `org/rsna/installer/InstallerPasswordTest`

| Test method | What it checks | State |
|-------------|---------------|-------|
| `testGeneratedPasswordIsNotEdge1234` | `generateInstallPassword()` never returns `"edge1234"` across 1000 calls | RED → GREEN |
| `testGeneratedPasswordIsNotCtpstore` | same for `"ctpstore"` | RED → GREEN |
| `testGeneratedPasswordLengthIsAtLeast16` | length ≥ 16 | RED → GREEN |
| `testConsecutiveCallsProduceDifferentPasswords` | 100 consecutive calls produce 100 distinct values | RED → GREEN |
| `testApplySslDefaultsSetsGeneratedPassword` | build DOM, call `applySslDefaults(ssl, "test-pw-xyz!")`, assert `keystorePassword == "test-pw-xyz!"` | RED → GREEN |

---

### Step 1.7 — Hard-coded keystore password in Launcher

**File:** [Launcher.java:54–55](../../source/java/org/rsna/launcher/Launcher.java)

**Background:** `System.setProperty("javax.net.ssl.keyStorePassword", "ctpstore")` sets a well-known default as a JVM-wide system property at startup. Any class in the process can read it via `System.getProperty()`. The value `"ctpstore"` is visible in the public source repository.

**Fix:** Read from environment variable first, fall back to `"ctpstore"` only if absent, and log a warning when the fallback is used:

```java
String ksPw = System.getenv("CTP_KEYSTORE_PASSWORD");
if (ksPw == null || ksPw.isBlank()) {
    ksPw = "ctpstore";
    logger.warn("CTP_KEYSTORE_PASSWORD not set; using default keystore password. " +
                "Set this environment variable in production.");
}
System.setProperty("javax.net.ssl.keyStorePassword", ksPw);
```

**Goal:** Production deployments do not use the hard-coded default without a visible warning. `CTP_KEYSTORE_PASSWORD` can be rotated without source changes.

**Supporting tests:** Not independently unit-testable (depends on JVM system properties and Swing initialization). Verified by code inspection and deployment test: start CTP with `CTP_KEYSTORE_PASSWORD` set and confirm no warning appears; start without it and confirm the warning appears.

---

### Step 1.8 — Email TLS downgrade permitted

**File:** [EmailSender.java:42](../../source/java/org/rsna/ctp/stdstages/email/EmailSender.java)

**Background:** `props.put("mail.smtp.starttls.enable", Boolean.toString(tls))` is set but `mail.smtp.starttls.required` is absent. JavaMail treats STARTTLS as optional: if the SMTP server does not advertise the capability, the session silently falls back to plaintext. Credentials and DICOM metadata in email notifications travel unencrypted to any server that does not support STARTTLS.

**Fix:** Add the required property immediately after the existing `starttls.enable` line:

```java
props.put("mail.smtp.starttls.required", Boolean.toString(tls));
```

**Goal:** When TLS is configured, a server that cannot provide STARTTLS causes the connection to fail rather than downgrading silently to plaintext.

**Supporting tests:** `org/rsna/ctp/stdstages/email/EmailSenderTlsTest`

| Test method | What it checks | State |
|-------------|---------------|-------|
| `testTlsEnabledSetsRequiredProperty` | construct `EmailSender` with `tls=true`; captured `Properties` object has `mail.smtp.starttls.required == "true"` | RED → GREEN |
| `testTlsDisabledSetsRequiredFalse` | with `tls=false`, `mail.smtp.starttls.required == "false"` | RED → GREEN |

---

### Step 1.9 — Session timeout disabled globally

**File:** [ClinicalTrialProcessor.java:178](../../source/java/org/rsna/ctp/ClinicalTrialProcessor.java)

**Background:** `Authenticator.getInstance().setSessionTimeout(0L)` sets an infinite session lifetime for all users. A captured session token (from a log entry, network capture, or shoulder-surfing) grants permanent access. This is a non-compliant session management configuration under OWASP ASVS and most healthcare security frameworks.

**Fix:**

```java
// 4 hours — adjust per site security policy; externalize if needed
private static final long SESSION_TIMEOUT_MS = 4L * 60L * 60L * 1000L;
// ...
Authenticator.getInstance().setSessionTimeout(SESSION_TIMEOUT_MS);
```

**Goal:** All sessions expire after a bounded period. The timeout is a named constant with a comment.

**Supporting tests:** Not independently unit-testable (`Authenticator` is in `util.jar` with no source). Verified by code inspection: the constant must not be zero, and the comment must identify the unit.

---

### Step 1.10 — FTPS without TLS version pinning

**File:** [FtpsExportService.java:99](../../source/java/org/rsna/ctp/stdstages/FtpsExportService.java)

**Background:** `new FTPSClient(false)` negotiates TLS with no protocol restriction. FTPS servers still advertising TLS 1.0 or TLS 1.1 (both deprecated by RFC 8996 in 2021) are accepted without warning, exposing the connection to BEAST and POODLE-class downgrade attacks.

**Fix:**

```java
FTPSClient client = new FTPSClient("TLS", false);
client.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
```

**Goal:** FTPS connections only complete over TLS 1.2 or TLS 1.3.

**Supporting tests:** Requires a live FTPS server. Deferred to Stage 4 integration tests (Step 4.5). The two-line change has no logic branching; verify by inspection and the TLS protocol negotiation logs on first use.

---

## Stage 1 Checkpoint

Run `ant test`. All tests must be GREEN before proceeding to Stage 2:

| Test class | Expected |
|-----------|---------|
| `ConfigLogSanitizationTest` (5 tests) | ✅ GREEN |
| `ServerServletAuthTest` (3 tests) | ✅ GREEN |
| `CTPServletConcurrencyTest` (3 tests) | ✅ GREEN |
| `AcceptAllHostnameVerifierTest` (2 tests) | ✅ GREEN |
| `XxeProtectionTest` (3 tests) | ✅ GREEN |
| `InstallerPasswordTest` (5 tests) | ✅ GREEN |
| `EmailSenderTlsTest` (2 tests) | ✅ GREEN |
| `SmokeTest` | ✅ GREEN |

Any RED at this checkpoint indicates either an incomplete fix or a regression from one fix interfering with another. Do not proceed until all are GREEN. Total: 24 tests.
