# Stage 2 — Characterization Tests

## Background

Stage 3 (dependency modernization) and Stage 4 (reliability) will eventually motivate a crypto migration: `AnonymizerFunctions.hash()` uses MD5, and `encrypt()`/`decrypt()` use Blowfish — both cryptographically weak. However, changing either algorithm changes the pseudonymization output format. In a clinical trial, existing patient ID mappings would break: a patient whose ID was hashed as `4829301847` under MD5 would receive a different hash under SHA-256, destroying the linkage between old and new submissions.

Before any dependency is touched, the current behavior of the anonymization functions must be frozen as executable tests. If a future stage accidentally changes the hash output, the test will catch it immediately.

A secondary target is `AbstractPipelineStage.getConfigHTML()`, which already correctly suppresses passwords for non-admin users. The Stage 1 CTPServlet refactor (`AuthState` value object) touches the authentication path. This test guards against any regression in that suppression.

**These tests must be GREEN immediately**, before any fix is applied. Their purpose is not to catch current bugs — it is to detect future regressions.

---

## Summary

Write two test classes that freeze the current behavior of the anonymization primitives and the pipeline stage HTML output. Both must pass on the unmodified production code at the start of this stage and must remain passing through all subsequent stages.

---

## Steps

### Step 2.1 — Anonymizer function characterization

**File:** [AnonymizerFunctions.java](../../source/java/org/rsna/ctp/stdstages/anonymizer/AnonymizerFunctions.java)

**Background:** `AnonymizerFunctions` provides all de-identification primitives: deterministic hashing for patient IDs, integer hashing for study/series UIDs, and symmetric encryption for reversible pseudonymization. The methods are pure static functions with no framework dependency — the simplest class in the codebase to test.

Current algorithms (to be preserved until migration is explicitly planned):
- `hash(String uid, int maxlen)` — MD5-based, returns a decimal numeric string
- `hashPtID(String siteid, String ptid, String idtype)` — same MD5 primitive, site-scoped
- `encrypt(String text, String key)` / `decrypt(String text, String key)` — Blowfish ECB

All eight tests must be **GREEN immediately** with no production code changes. Write them against the existing output, not against an expected algorithm.

**Goal:** Any future change that alters hash output format, encryption format, numeric range, or error handling fails these tests explicitly before reaching code review.

**Supporting tests:** `org/rsna/ctp/stdstages/anonymizer/AnonymizerFunctionsTest`

| Test method | What it characterizes | Expected state |
|-------------|----------------------|---------------|
| `testHashProducesDeterministicOutput` | same input always produces same result | GREEN immediately |
| `testHashOutputIsNumericString` | output matches `[0-9]+` (decimal, no letters) | GREEN immediately |
| `testHashWithMaxlenTruncatesOutput` | `hash("test", 5)` returns ≤ 5 characters | GREEN immediately |
| `testHashPtIDProducesConsistentResult` | same siteid+ptid always maps to same pseudonym | GREEN immediately |
| `testHashPtIDWithNullSiteid` | null siteid does not throw; returns a deterministic value | GREEN immediately |
| `testEncryptDecryptRoundTrip` | `decrypt(encrypt(x, key), key).equals(x)` | GREEN immediately |
| `testEncryptedValueDiffersFromOriginal` | ciphertext is not equal to plaintext | GREEN immediately |
| `testDecryptWithWrongKeyProducesGarbage` | wrong key produces output that does not equal original plaintext | GREEN immediately |

---

### Step 2.2 — Pipeline stage config HTML password suppression

**File:** [AbstractPipelineStage.java:279](../../source/java/org/rsna/ctp/pipeline/AbstractPipelineStage.java)

**Background:** `AbstractPipelineStage.getConfigHTML(User user)` already correctly suppresses `username` and `password` attribute values for non-admin users, replacing them with `[suppressed]` in the admin UI HTML. This behavior is correct and present in the unmodified code.

The Stage 1 CTPServlet refactor changes how `User` objects flow through the authorization path. This test guards against any regression in the suppression logic introduced by that refactor or any future stage.

**Requires Mockito:** `User` is a concrete class in `util.jar` with no source. Constructing a real `User` requires a live `Authenticator` and server context. Mock it via `Mockito.mock(User.class)` and stub only `hasRole("admin")`.

**Requires a concrete subclass:** `AbstractPipelineStage` is abstract. Define a minimal anonymous concrete subclass inside the test class that passes a known `Element` (with `password="test-secret"`, `port="8080"`, `name="TestStage"`) to the super constructor.

All four tests must be **GREEN immediately** with no production code changes.

**Goal:** Password suppression behavior is executable documentation. Any change that causes passwords to appear for non-admin users fails a test immediately.

**Supporting tests:** `org/rsna/ctp/pipeline/AbstractPipelineStageConfigHtmlTest`

| Test method | What it characterizes | Expected state |
|-------------|----------------------|---------------|
| `testPasswordHiddenFromNonAdminUser` | mock `user.hasRole("admin") → false`; `"test-secret"` does not appear in HTML | GREEN immediately |
| `testPasswordShownToAdmin` | mock `user.hasRole("admin") → true`; password value appears in HTML | GREEN immediately |
| `testNonSensitiveAttributesAlwaysVisible` | `port`, `name` appear in HTML for both admin and non-admin users | GREEN immediately |
| `testNullUserTreatedAsNonAdmin` | `getConfigHTML(null)` suppresses passwords (null = unauthenticated) | GREEN immediately |

---

## Stage 2 Checkpoint

Run `ant test`. All Stage 1 and Stage 2 tests must be GREEN before proceeding to Stages 3, 4, or 5:

| Test suite | Count | Expected |
|-----------|-------|---------|
| All Stage 1 tests | 24 | ✅ GREEN |
| `AnonymizerFunctionsTest` | 8 | ✅ GREEN immediately |
| `AbstractPipelineStageConfigHtmlTest` | 4 | ✅ GREEN immediately |
| **Total** | **36** | **All GREEN** |

**If any Stage 2 test is RED before any code change is applied:** the production code has diverged from expectations since the analysis was done. Investigate the divergence — do not proceed to Stage 3 until the cause is understood. The test may need to be corrected, or a regression may have been introduced in Stage 1.

Once Stage 2 is GREEN, Stages 3, 4, and 5 may proceed in parallel. The 36-test suite is the regression net for all three.
