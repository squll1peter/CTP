# CTP Modernization — Handover Overview

## Background

CTP (ClinicalTrialProcessor) is a Java medical imaging middleware developed by RSNA (Radiological Society of North America). It handles DICOM de-identification, anonymization, routing, and export for clinical trials. The codebase is Java 5/6-era, built with Apache Ant, and has **zero automated test coverage**. All dependencies are vendored JARs in `libraries/`, the oldest dating to 2009.

Two full review passes identified ten security vulnerabilities, severely outdated dependencies with known CVEs, systemic silent exception swallowing across 158+ catch blocks, and pervasive Java 1.4-era code patterns.

This project handles PHI (Protected Health Information). Any credential exposure or TLS bypass is a potential HIPAA-reportable breach.

---

## Critical Findings Summary

### P0 — Security (addressed in Stage 1)

| # | Finding | File | Severity |
|---|---------|------|----------|
| 1 | Full config XML with plaintext passwords logged at INFO on every startup | [Configuration.java:158](../../source/java/org/rsna/ctp/Configuration.java) | Critical |
| 2 | `GET /server` returns full config XML (incl. passwords) to any unauthenticated caller | [ServerServlet.java:47](../../source/java/org/rsna/ctp/servlets/ServerServlet.java) | Critical |
| 3 | `userIsAdmin`/`userIsAuthorized` are shared instance fields — race between concurrent requests | [CTPServlet.java:51](../../source/java/org/rsna/ctp/servlets/CTPServlet.java) | High |
| 4 | `AcceptAllHostnameVerifier` disables TLS certificate hostname check on control channel | [Runner.java:258](../../source/java/org/rsna/runner/Runner.java) | High |
| 5 | `DocumentBuilderFactory` without XXE protection in two XML parsers | [Runner.java:290](../../source/java/org/rsna/runner/Runner.java), [Installer.java:1146](../../source/java/org/rsna/installer/Installer.java) | High |
| 6 | Hard-coded `"edge1234"` written as keystore/truststore password for every ISN install | [Installer.java:650](../../source/java/org/rsna/installer/Installer.java) | High |
| 7 | Hard-coded `"ctpstore"` set as JVM-wide system property for keystore password | [Launcher.java:54](../../source/java/org/rsna/launcher/Launcher.java) | High |
| 8 | `mail.smtp.starttls.required` absent — TLS silently downgrades to plaintext | [EmailSender.java:42](../../source/java/org/rsna/ctp/stdstages/email/EmailSender.java) | Medium |
| 9 | `setSessionTimeout(0L)` — all sessions never expire | [ClinicalTrialProcessor.java:178](../../source/java/org/rsna/ctp/ClinicalTrialProcessor.java) | Medium |
| 10 | `new FTPSClient(false)` accepts TLS 1.0/1.1 | [FtpsExportService.java:99](../../source/java/org/rsna/ctp/stdstages/FtpsExportService.java) | Medium |

### P1 — Dependency CVEs (addressed in Stage 3)

| JAR | Age | CVEs |
|-----|-----|------|
| `commons-compress-1.0.jar` | 2009 | CVE-2021-35515/16/17, CVE-2012-2098 (zip-bomb, path traversal) |
| `jsch-0.1.53.jar` | 2014 | CVE-2016-5725, abandoned upstream |
| `commons-net-3.3.jar` | 2013 | Multiple FTP security fixes in later versions |
| `commons-vfs2-2.0.jar` | 2015 | CVE-2022-26336 and others |

### P2 — Operational Risk (addressed in Stage 4)

- 158+ empty `catch` blocks — all exceptions silently swallowed across pipeline, quarantine, and export code
- Shutdown sequence has no outcome logging; clean vs. forced exit is indistinguishable

---

## Stage Roadmap

```
Stage 0 ──► Stage 1 ──► Stage 2 ──► Stage 3 ┐
  (infra)   (security)   (freeze)    (deps)   ├─► all parallel
                                     Stage 4 ─┤
                                     (reliable)│
                                     Stage 5 ─┘
                                  (maintenance)
```

| Stage | Document | Goal | Entry Gate | Exit Gate |
|-------|----------|------|------------|-----------|
| **0** | [01-infrastructure.md](01-infrastructure.md) | `ant test` pipeline works | None | `ant test` runs (0 tests) |
| **1** | [02-security-fixes.md](02-security-fixes.md) | All P0 vulnerabilities closed | Stage 0 done | All Tier A+B tests GREEN |
| **2** | [03-characterization-tests.md](03-characterization-tests.md) | Crypto and HTML behavior frozen | Stage 1 done | All new tests GREEN immediately |
| **3** | [04-dependency-modernization.md](04-dependency-modernization.md) | No CVEs in vendored libs | Stage 2 done | Full test suite passes after each JAR swap |
| **4** | [05-reliability.md](05-reliability.md) | Silent failures observable | Stage 2 done | Full suite + integration tests GREEN |
| **5** | [06-maintainability.md](06-maintainability.md) | Modern Java idioms | Stage 2 done | `ant compile` zero new warnings + full suite GREEN |

Stages 3, 4, and 5 may proceed in parallel once Stage 2 is complete.

---

## Test Inventory

| ID | Test Class | Tier | Mock Needed | Writes First | Expected State |
|----|-----------|------|-------------|--------------|---------------|
| A1 | `XxeProtectionTest` | Pure unit | None | Stage 1.5 | RED → GREEN |
| A2 | `AcceptAllHostnameVerifierTest` | Pure unit | None | Stage 1.4 | RED → GREEN |
| A3 | `AnonymizerFunctionsTest` | Pure unit | None | Stage 2.1 | GREEN immediately |
| A4 | `ConfigLogSanitizationTest` | Pure unit | None | Stage 1.1 | RED → GREEN |
| A5 | `InstallerPasswordTest` | Pure unit | None | Stage 1.6 | RED → GREEN |
| B1 | `AbstractPipelineStageConfigHtmlTest` | Mock | `User` | Stage 2.2 | GREEN immediately |
| B2 | `CTPServletConcurrencyTest` | Mock | `HttpRequest`, `User` | Stage 1.3 | RED → GREEN |
| B3 | `ServerServletAuthTest` | Mock | `HttpRequest`, `HttpResponse` | Stage 1.2 | RED → GREEN |
| C1 | `ServerServletHttpIntegrationTest` | Integration | Full server | Stage 4.5 | GREEN |
| C2 | `ConfigLogPasswordIntegrationTest` | Integration | Full server | Stage 4.5 | GREEN |

---

## Toolchain

| Tool | Version | Role |
|------|---------|------|
| JDK | 25 EA (system) | Compiler and runtime |
| Apache Ant | 1.9+ | Build system |
| JUnit | 4.13.2 | Test runner |
| Hamcrest | 1.3 | Assertions |
| Mockito | 5.14.2 | Mocking concrete classes from `util.jar` |
| byte-buddy | 1.15.10 | Mockito's subclassing mechanism |
| objenesis | 3.4 | Mockito's object instantiation |

**JDK 9+ note:** Mockito requires `--add-opens java.base/java.lang=ALL-UNNAMED` and `--add-opens java.base/java.util=ALL-UNNAMED` to subclass classes from vendored JARs under the Java module system. These are set in the `<junit>` Ant task.

---

## Key Architectural Constraints

- **`util.jar` is opaque** — `HttpRequest`, `HttpResponse`, `User`, `Servlet`, `Authenticator` are compiled Java 8 bytecode with no source. Mockito can subclass them (none are `final`), but constructor injection is not possible (constructors require live sockets/server). All servlet tests use `mock()` with `RETURNS_DEFAULTS`.
- **`Configuration` is a singleton** — requires a running CTP context. Tests that need `Configuration` either (a) extract the logic under test into a static method first, or (b) are Tier C integration tests.
- **`Installer` is a Swing GUI** — constructor opens dialogs. All testable logic must be extracted into static package-visible methods before tests are written.
