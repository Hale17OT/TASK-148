# LibOps Offline Collection Orchestrator - Clarification Questions



## 1. Secret Encryption Scope and Key Lifecycle



**Question:** The prompt requires encrypted-at-rest storage for secrets (keys, proxy credentials), but does not define key generation, storage, rotation, or recovery behavior. How should the secret encryption key lifecycle be handled offline?



**My Understanding:** A per-installation data-encryption key should be generated locally, wrapped with Android Keystore, and never stored plaintext. Rotation should be supported with re-encryption of stored secret rows.



**Solution:** Use Android Keystore-backed AES key material to wrap/unwrap a randomly generated app secret key. Store wrapped key metadata in local preferences, and store encrypted payload + IV in Room (`secrets`). Add key-versioned ciphertext format and a background rekey job for rotation.



## 2. Password Policy Edge Cases (Character Classes)



**Question:** The prompt says password must be at least 12 characters with local login, but does not explicitly define exact composition requirements beyond length. Should number+symbol be mandatory?



**My Understanding:** For consistent enforcement and auditability, minimum 12 + at least one digit + at least one symbol should be required, with clear validation messages.



**Solution:** Enforce regex-level checks in shared auth policy (`PasswordPolicy`) and mirror the same checks at UI validation and repository write paths so import/admin resets cannot bypass rules.



## 3. Lockout Reset Semantics



**Question:** After a 5-attempt lockout for 15 minutes, should failed-attempt counters reset only on successful login, on lockout expiry, or both?



**My Understanding:** Counter should reset on successful login; expiry should remove temporary lock but preserve recent failed-attempt telemetry for audit.



**Solution:** Keep `failedAttempts` and `lockoutUntil` fields. On successful password verification, zero attempts and clear lockout. On expiry, allow retry but only clear lockout timestamp; preserve attempts for reporting until success.



## 4. Biometric Eligibility Window



**Question:** The prompt allows biometric unlock after first successful login, but does not define invalidation events (password change, role changes, admin revoke). What should invalidate biometric eligibility?



**My Understanding:** Eligibility should be revoked after password change, account disable, admin-forced logout, and security-policy updates.



**Solution:** Persist last-password-login and last-password-change timestamps, and evaluate biometric policy against invalidation timestamps before allowing biometric session resume.



## 5. Active Role Resolution for Multi-Role Users



**Question:** If one user has multiple roles (e.g., Cataloger + Auditor), which capability set is active at login?



**My Understanding:** One active role should be selected per session for least privilege and simpler audit attribution, with optional role switch flow.



**Solution:** Persist `activeRoleId` in session row; default to assigned primary role or most restrictive configured default. Add explicit role-switch action requiring revalidation and audit event.



## 6. Collection Source Schedule Timezone and DST



**Question:** Scheduling is required, but prompt does not specify timezone/DST handling for cron expressions in fully offline devices.



**My Understanding:** Schedules should be interpreted using device local timezone with persisted timezone snapshot to keep behavior explainable.



**Solution:** Store cron + timezone ID per source (`scheduleCron`, `timezoneId`). On timezone change, log audit event and recompute next execution times.



## 7. Incremental vs Full Refresh Semantics



**Question:** The prompt defines incremental/full modes but not merge semantics (delete missing records? preserve manual edits?).



**My Understanding:** Incremental should append/update without destructive deletes; full refresh should reconcile source-owned records only, while preserving manual catalog-only fields.



**Solution:** Add provenance flags and last-seen markers for source-owned fields. In full refresh, soft-archive missing source-owned records; do not overwrite manually curated fields unless explicitly mapped.



## 8. Duplicate Detection Normalization Rules



**Question:** Duplicate match requires ISBN + title similarity 0.85, but prompt does not define title normalization rules or similarity algorithm.



**My Understanding:** Normalization should lowercase, trim punctuation/spacing, and remove stopword noise before similarity scoring.



**Solution:** Use deterministic title normalization plus a documented similarity algorithm (e.g., Jaro-Winkler). Store algorithm version and score in duplicate candidate rows for explainability.



## 9. Merge Conflict Precedence



**Question:** Guided merge preserves audit trail, but prompt does not define field-level precedence when two records disagree.



**My Understanding:** Operator choice should be explicit per field, with a safe default favoring non-empty validated value and latest verified source timestamp.



**Solution:** Merge UI should display per-field left/right selection + "manual edit" option; persist full merge decision JSON and rationale in `merge_decisions`.



## 10. Taxonomy Governance Constraints



**Question:** Taxonomy maintenance is required, but prompt does not define cycle prevention and archive semantics for parent-child graphs.



**My Understanding:** Cycles must be forbidden, and archived nodes must remain readable for historical records but blocked for new assignment.



**Solution:** Validate graph updates for cycle detection before commit; enforce assignment constraints on archived nodes; keep historical links immutable unless explicit migration tool is used.



## 11. Holdings and Barcode Lifecycle States



**Question:** Barcode format/collision rules are specified, but lifecycle states (assigned, retired, suspended) and transitions are not fully defined.



**My Understanding:** A finite state model is needed to prevent invalid transitions and inventory inconsistencies.



**Solution:** Define barcode state machine with allowed transitions and reasons; enforce in domain layer and audit every transition with actor/time.



## 12. Analytics Metric Source of Truth



**Question:** Dashboard metrics are defined (configured->queued->processed->accepted and spend/order/returned placeholders), but aggregation windows and recalculation policy are unspecified.



**My Understanding:** Metrics should be snapshot-based and reproducible by date grain (hour/day) from local event logs.



**Solution:** Compute `metric_snapshots` on schedule and on-demand refresh with explicit grain, org scope, and capture timestamp; retain immutable snapshots for auditability.



## 13. Staff Quality Score Formula Governance



**Question:** Score range 0-100 is required, but exact weighting and decay behavior for failures/violations is not specified.



**My Understanding:** Weights and windows should be policy-driven and configurable by admins, with explainable change history.



**Solution:** Store scoring policy config (weights, windows, caps) and compute score snapshots with full factor breakdown persisted for drill-down explanations.



## 14. Alert SLA Interpretation



**Question:** Every alert must be acknowledged and resolved with note within 7 days, but when does SLA clock start (createdAt vs acknowledgedAt), and what about reopened alerts?



**My Understanding:** SLA should start at `createdAt`; reopen should reset due date only if policy allows and must be explicitly audited.



**Solution:** Persist `dueAt` at alert creation, enforce note-required resolution, and on reopen either retain original due date or set policy-driven new due date with reason code.



## 15. Observability Log Retention and Storage Pressure



**Question:** Prompt requires local structured logs and stack traces, but does not define retention limits on storage-constrained devices.



**My Understanding:** Retention needs bounded policies to prevent database bloat while preserving audit-critical records.



**Solution:** Configure retention by class: immutable audit events retained longest; high-volume perf/exception samples use rolling windows and compaction jobs with admin-configurable thresholds.



## 16. 1,000,000-Record Performance Verification Contract



**Question:** Prompt sets <50 ms common query target at up to 1,000,000 rows, but does not define device class baseline or acceptance test environment.



**My Understanding:** Performance SLAs should be tied to a documented reference hardware profile and repeatable benchmark protocol.



**Solution:** Define benchmark suite with fixed schema/data shape/indexes and reference device matrix. Report median/p95 with warm/cold cache notes in local test artifacts.



## 17. File-Based Integration Trust Model



**Question:** Signed import/export bundles are required, but key distribution and trust onboarding for verification keys are not specified.



**My Understanding:** Trust anchors should be managed by administrators via offline key import with fingerprint confirmation and audit logging.



**Solution:** Add trusted-key registry (fingerprint, source, createdAt, revokedAt). Bundle verification accepts only active trusted keys; key add/revoke actions are permission-gated and audited.



## 18. Idempotency and Re-import Behavior



**Question:** Prompt defines rate limiting (e.g., 30 imports/hour/user) but not exact duplicate-import behavior (same file reimported intentionally vs accidentally).



**My Understanding:** Imports should be idempotent by checksum + manifest identity by default, with explicit override path for forced reprocessing.



**Solution:** Store bundle/file checksum and reject duplicates as `already_imported` unless user has elevated capability and provides an override reason logged in audit trail.
