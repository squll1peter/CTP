# Performance Plan (Active)

## Phase 1: Measure

- Keep stage-level timing collection enabled in status reporting.
- Capture baseline with current pipeline and representative workload.

## Phase 2: Low-Risk Reduction

- Preserve script/property caching improvements in anonymizer path.
- Focus on I/O and configuration impacts before introducing concurrency.

## Phase 3: Optional Concurrency

- Only after baseline comparison and risk review.
- Guard shared-state components and preserve ordering/quarantine semantics.

## Current Priority

- SSL and login stability are currently stabilized.
- Resume performance work after deployment certificate and config hardening checks.
