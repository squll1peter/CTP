# Next Iteration Plan

## 1) SSL Production Readiness

- Install trusted certificate and full chain.
- Verify browser behavior on Chrome and Firefox with no warning interstitial.
- Re-check login and logout flows on HTTPS only.

## 2) Authentication and LDAP Review

- Validate LDAP providerURL uses ldaps:// in all active deployments.
- Confirm no plaintext secrets are committed in config snapshots.

## 3) Performance Continuation

- Run workload after decompressor removal in active pipeline.
- Compare stage timings for IDMap, anonymizer, and storage stages.

## 4) Optional LoginServlet Hardening

- If warning suppression is not sufficient for operations, implement code-level allowlist/normalization for benign browser probes.
