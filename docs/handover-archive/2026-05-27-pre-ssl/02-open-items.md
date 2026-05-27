# Open Items

## Primary Remaining Track

1. No unchecked suppressions remain in the scanned `source/java/org/rsna/ctp/**/*.java` set.
2. Implement the agreed performance plan (see `04-performance-plan.md`):
   - Phase 1 (measure) must run before Phase 2 or Phase 3.
   - Phase 3 (worker pool) has known risks around `IntegerTable`, `DAScript`,
     and `LookupTable` shared state — see the risk table in the plan.
	 - Decompressor tuning is out of immediate scope because decompressor is
		 expected to be removed from the active pipeline.
3. Keep future refactors behavior-preserving and test-gated.
4. Address new maintenance items only as they arise.

## Operational Follow-Up

1. UI/resource updates are applied to running instances only after rebuild +
	CTP restart.
2. Keep cache-busting version values synchronized when servlet/shell assets are
	changed again.
3. If performance work adds async workers, preserve quarantine and ordering
	semantics.

## Guardrails

- Do not widen migration scope across fragile legacy API boundaries.
- Keep compatibility exceptions explicit (example: `Vector<Shape>` at PixelMed edge).
- Prefer local helper methods for unchecked-cast narrowing rather than class-level suppressions.

## Risks to Watch

- Legacy raw collections can hide accidental behavior changes.
- Singleton-heavy startup paths make broad refactors risky without strict test gating.
- Over-eager modernization may break runtime assumptions in vendored libraries.
