package org.rsna.ctp.stdstages.anonymizer;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Characterisation tests for AnonymizerFunctions.
 * These tests document existing behaviour and will catch regressions.
 */
public class AnonymizerFunctionsTest {

    // ---- initials ----

    @Test
    public void initials_standardDicomName_returnsInitialsLastMoved() {
        // "Last^First^Middle" → FM+last_initial; Smith → S
        assertEquals("FMS", AnonymizerFunctions.initials("Smith^Fred^Michael"));
    }

    @Test
    public void initials_nullName_returnsX() {
        assertEquals("X", AnonymizerFunctions.initials(null));
    }

    @Test
    public void initials_emptyName_returnsX() {
        assertEquals("X", AnonymizerFunctions.initials(""));
    }

    // ---- round ----

    @Test
    public void round_age055Y_roundsToNearestFive() throws Exception {
        // 55 / 5 = 11.0 → 11 * 5 = 55; result is zero-padded to even length → "055Y"
        assertEquals("055Y", AnonymizerFunctions.round("55Y", 5));
    }

    @Test
    public void round_age054Y_roundsDown() throws Exception {
        // 54 / 5 = 10.8 → rounds to 11 * 5 = 55; zero-padded → "055Y"
        assertEquals("055Y", AnonymizerFunctions.round("54Y", 5));
    }

    @Test
    public void round_nullAge_returnsEmpty() throws Exception {
        assertEquals("", AnonymizerFunctions.round(null, 5));
    }

    // ---- hash ----

    @Test
    public void hash_sameInput_sameOutput() throws Exception {
        String h1 = AnonymizerFunctions.hash("patient-001");
        String h2 = AnonymizerFunctions.hash("patient-001");
        assertEquals("Hash must be deterministic", h1, h2);
    }

    @Test
    public void hash_differentInputs_differentOutputs() throws Exception {
        String h1 = AnonymizerFunctions.hash("patient-001");
        String h2 = AnonymizerFunctions.hash("patient-002");
        assertNotEquals("Different inputs must produce different hashes", h1, h2);
    }
}
