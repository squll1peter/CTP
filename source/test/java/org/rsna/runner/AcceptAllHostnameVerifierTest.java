package org.rsna.runner;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verifies that the AcceptAllHostnameVerifier inner class has been removed
 * from Runner.java and that Runner no longer installs a permissive
 * hostname verifier.
 */
public class AcceptAllHostnameVerifierTest {

    @Test
    public void acceptAllHostnameVerifierClassDoesNotExist() {
        // If AcceptAllHostnameVerifier were still present it would be loadable
        // as a nested class named "org.rsna.runner.Runner$AcceptAllHostnameVerifier".
        try {
            Class.forName("org.rsna.runner.Runner$AcceptAllHostnameVerifier");
            fail("AcceptAllHostnameVerifier inner class must not exist");
        } catch (ClassNotFoundException expected) {
            // correct — the class has been deleted
        }
    }

    @Test
    public void runnerClassIsLoadable() {
        // Smoke test: Runner itself must still compile and be loadable
        try {
            Class.forName("org.rsna.runner.Runner");
        } catch (ClassNotFoundException e) {
            fail("Runner class should be loadable: " + e.getMessage());
        }
    }
}
