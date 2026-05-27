# Stage 0 — Test Infrastructure

## Background

The CTP codebase has zero automated tests. There is no JUnit or any test dependency anywhere in `libraries/`, no test source directory, and no test target in `build.xml`. The build system is Apache Ant — no Maven or Gradle.

The installed JDK is OpenJDK 25 EA. Mockito 5.x uses byte-buddy to subclass concrete classes from `util.jar` at test time. Under JDK 9+, this requires explicit `--add-opens` module arguments; without them, byte-buddy cannot access the class internals needed to generate subclasses.

No production code is changed in this stage.

---

## Summary

Install five test JARs under `libraries/test/`, extend `build.xml` with `compile-tests` and `test` targets, create the test source directory tree, and verify the pipeline runs cleanly with a trivial smoke test.

**Why JUnit 4 over JUnit 5:** Ant ships with a native `<junit>` task for JUnit 4. JUnit 5 requires a standalone console launcher JAR with manual classpath assembly and a different Ant invocation strategy. JUnit 4 is the lower-friction choice for an Ant project and matches the era of the codebase.

---

## Steps

### Step 0.1 — Download test JARs

Create directory `libraries/test/` and place the following JARs there:

| JAR | Version | Maven coordinates | Purpose |
|-----|---------|-------------------|---------|
| `junit-4.13.2.jar` | 4.13.2 | `junit:junit:4.13.2` | Test runner + `@Test`, `Assert.*` API |
| `hamcrest-core-1.3.jar` | 1.3 | `org.hamcrest:hamcrest-core:1.3` | JUnit 4 required dependency |
| `mockito-core-5.14.2.jar` | 5.14.2 | `org.mockito:mockito-core:5.14.2` | Mock concrete classes from `util.jar` |
| `byte-buddy-1.15.10.jar` | 1.15.10 | `net.bytebuddy:byte-buddy:1.15.10` | Mockito's JDK 9+ subclassing mechanism |
| `objenesis-3.4.jar` | 3.4 | `org.objenesis:objenesis:3.4` | Construct objects without calling constructors |

**Goal:** All five JARs present in `libraries/test/`.

---

### Step 0.2 — Extend `build.xml`

Add the following to `build.xml` after the existing `<path id="classpath">` block:

```xml
<path id="test-classpath">
    <!-- inherit all production classpath entries -->
    <path refid="classpath"/>
    <pathelement location="${build}"/>
    <!-- test dependencies -->
    <pathelement location="${libraries}/test/junit-4.13.2.jar"/>
    <pathelement location="${libraries}/test/hamcrest-core-1.3.jar"/>
    <pathelement location="${libraries}/test/mockito-core-5.14.2.jar"/>
    <pathelement location="${libraries}/test/byte-buddy-1.15.10.jar"/>
    <pathelement location="${libraries}/test/objenesis-3.4.jar"/>
</path>

<target name="compile-tests" depends="compile">
    <mkdir dir="${build}/test-classes"/>
    <javac destdir="${build}/test-classes"
           srcdir="${source}/test/java"
           classpathref="test-classpath"
           includeantruntime="false"
           debug="true"
           debuglevel="lines,vars,source">
        <compilerarg value="-Xlint:unchecked"/>
    </javac>
</target>

<target name="test" depends="compile-tests">
    <junit fork="true" haltonfailure="true" printsummary="yes">
        <!-- Required for Mockito byte-buddy on JDK 9+ (system has JDK 25) -->
        <jvmarg value="--add-opens"/>
        <jvmarg value="java.base/java.lang=ALL-UNNAMED"/>
        <jvmarg value="--add-opens"/>
        <jvmarg value="java.base/java.util=ALL-UNNAMED"/>
        <classpath>
            <path refid="test-classpath"/>
            <pathelement location="${build}/test-classes"/>
        </classpath>
        <batchtest>
            <fileset dir="${build}/test-classes" includes="**/*Test.class"/>
        </batchtest>
        <formatter type="plain" usefile="false"/>
    </junit>
</target>
```

**Goal:** `ant compile-tests` and `ant test` both run without error.

---

### Step 0.3 — Create test source tree

Create `source/test/java/` mirroring the production package structure. Only the directories that will contain tests need to exist:

```
source/test/java/
  org/rsna/
    ctp/
      security/           ← XxeProtectionTest, ConfigLogSanitizationTest
      servlets/           ← CTPServletConcurrencyTest, ServerServletAuthTest
      pipeline/           ← AbstractPipelineStageConfigHtmlTest
      stdstages/
        anonymizer/       ← AnonymizerFunctionsTest
        email/            ← EmailSenderTlsTest
    runner/               ← AcceptAllHostnameVerifierTest
    installer/            ← InstallerPasswordTest
```

Place a trivial smoke test to validate the pipeline end-to-end:

```java
// source/test/java/org/rsna/ctp/SmokeTest.java
package org.rsna.ctp;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class SmokeTest {
    @Test
    public void pipelineWorks() {
        assertTrue(true);
    }
}
```

**Goal:** `ant test` outputs `Tests run: 1, Failures: 0, Errors: 0` and exits 0.

---

### Step 0.4 — Verify `ant clean` safety

Run `ant clean` followed by `ant test`. Confirm the clean target does not delete `libraries/test/`, and that a full clean+rebuild+test cycle completes successfully.

**Goal:** Test pipeline is robust to a clean build.

---

## Supporting Tests

None — this stage establishes the infrastructure that all subsequent tests depend on.

## Checkpoint

| Check | Expected |
|-------|---------|
| `ant compile` | Succeeds, zero warnings added |
| `ant compile-tests` | Succeeds |
| `ant test` | `Tests run: 1, Failures: 0, Errors: 0` |
| `ant clean && ant test` | Same result |
| Production JARs in `libraries/` | Unmodified |
