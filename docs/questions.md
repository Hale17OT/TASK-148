# LibOps Offline Collection Orchestrator - Clarification Questions



## 1. Secret Encryption Scope and Key Lifecycle



**Question:** The prompt requires encrypted-at-rest storage for secrets (keys, proxy credentials), but does not define key generation, storage, rotation, or recovery behavior. How should the secret encryption key lifecycle be handled offline?



**My Understanding:** A per-installation data-encryption key should be generated locally, wrapped with Android Keystore, and never stored plaintext. Rotation should be supported with re-encryption of stored secret rows.



**Solution:** Rely on the platform's hardware-backed key store as the root of trust. Secrets should be encrypted under a locally generated key that is itself stored only in protected hardware, never in plaintext. Each encrypted value should carry enough metadata to identify the key version used, so that rotation can re-encrypt existing values without data loss.



## 2. Password Policy Edge Cases (Character Classes)



**Question:** The prompt says password must be at least 12 characters with local login, but does not explicitly define exact composition requirements beyond length. Should number+symbol be mandatory?



**My Understanding:** For consistent enforcement and auditability, minimum 12 + at least one digit + at least one symbol should be required, with clear validation messages.



**Solution:** Define a single, authoritative password policy that covers both length and character class requirements. All entry points that set or reset passwords — including imports and admin flows — must apply the same policy so that there is no way to introduce a non-compliant credential through a side channel.



## 3. Lockout Reset Semantics



**Question:** After a 5-attempt lockout for 15 minutes, should failed-attempt counters reset only on successful login, on lockout expiry, or both?



**My Understanding:** Counter should reset on successful login; expiry should remove temporary lock but preserve recent failed-attempt telemetry for audit.



**Solution:** Treat the lockout timer and the failure counter as independent concerns. Expiry should lift the access block but leave the counter readable for audit purposes. Only a confirmed successful authentication should clear the counter, so that a pattern of near-misses followed by wait cycles remains visible in the record.



## 4. Biometric Eligibility Window



**Question:** The prompt allows biometric unlock after first successful login, but does not define invalidation events (password change, role changes, admin revoke). What should invalidate biometric eligibility?



**My Understanding:** Eligibility should be revoked after password change, account disable, admin-forced logout, and security-policy updates.



**Solution:** Biometric eligibility should be evaluated dynamically at each unlock attempt against a set of invalidation signals rather than being a static flag set at enrolment. Any security-relevant change to the account or policy should cause the next biometric attempt to fall back to full credential verification.



## 5. Active Role Resolution for Multi-Role Users



**Question:** If one user has multiple roles (e.g., Cataloger + Auditor), which capability set is active at login?



**My Understanding:** One active role should be selected per session for least privilege and simpler audit attribution, with optional role switch flow.



**Solution:** A session should operate under exactly one role at a time. The system should establish a default selection rule (such as most restrictive, or explicitly designated primary role) and permit a deliberate mid-session switch that is separately audited, so that every action can be attributed to a single, identifiable role context.



## 6. Collection Source Schedule Timezone and DST



**Question:** Scheduling is required, but prompt does not specify timezone/DST handling for cron expressions in fully offline devices.



**My Understanding:** Schedules should be interpreted using device local timezone with persisted timezone snapshot to keep behavior explainable.



**Solution:** A schedule should be stored together with the timezone it was authored in, not just the device's current offset, so that its intended firing time is unambiguous if the device locale changes. Any such locale change should be recorded so that operators can reason about runs that may have shifted.



## 7. Incremental vs Full Refresh Semantics



**Question:** The prompt defines incremental/full modes but not merge semantics (delete missing records? preserve manual edits?).



**My Understanding:** Incremental should append/update without destructive deletes; full refresh should reconcile source-owned records only, while preserving manual catalog-only fields.



**Solution:** Each field in a record should carry a notion of ownership — whether it comes authoritatively from a source or was curated manually. A full refresh should only reconcile source-owned fields; any manually curated field should be treated as out-of-scope for source-driven updates. Records absent from a full refresh payload should be soft-retired rather than deleted outright, preserving history.



## 8. Duplicate Detection Normalization Rules



**Question:** Duplicate match requires ISBN + title similarity 0.85, but prompt does not define title normalization rules or similarity algorithm.



**My Understanding:** Normalization should lowercase, trim punctuation/spacing, and remove stopword noise before similarity scoring.



**Solution:** Title comparison should apply a deterministic, documented normalization step before any similarity scoring, so that two representations of the same title produce the same canonical form regardless of punctuation or casing. The choice of similarity algorithm and the normalization rules should be versioned together so that results are reproducible and auditable.



## 9. Merge Conflict Precedence



**Question:** Guided merge preserves audit trail, but prompt does not define field-level precedence when two records disagree.



**My Understanding:** Operator choice should be explicit per field, with a safe default favoring non-empty validated value and latest verified source timestamp.



**Solution:** The system should not silently resolve field-level conflicts with a hidden rule. Each field disagreement should be surfaced individually, and the operator's resolution choice — including any manual override — should be recorded as part of the merge audit, making the rationale for the final value traceable.



## 10. Taxonomy Governance Constraints



**Question:** Taxonomy maintenance is required, but prompt does not define cycle prevention and archive semantics for parent-child graphs.



**My Understanding:** Cycles must be forbidden, and archived nodes must remain readable for historical records but blocked for new assignment.



**Solution:** Any modification to the taxonomy graph should be validated for structural integrity — specifically the absence of cycles — before being committed. Archiving a node should disable it for future use while leaving it resolvable for records that were already assigned to it, ensuring historical classification remains meaningful.



## 11. Holdings and Barcode Lifecycle States



**Question:** Barcode format/collision rules are specified, but lifecycle states (assigned, retired, suspended) and transitions are not fully defined.



**My Understanding:** A finite state model is needed to prevent invalid transitions and inventory inconsistencies.



**Solution:** Barcodes should follow a formally defined lifecycle with a limited set of states and explicit rules about which transitions are legal and under what conditions. Every state change should be logged with the actor and reason, providing a traceable history of each barcode's provenance.



## 12. Analytics Metric Source of Truth



**Question:** Dashboard metrics are defined (configured->queued->processed->accepted and spend/order/returned placeholders), but aggregation windows and recalculation policy are unspecified.



**My Understanding:** Metrics should be snapshot-based and reproducible by date grain (hour/day) from local event logs.



**Solution:** Metrics should be derived from immutable event records at a defined granularity and stored as discrete snapshots, not computed on-the-fly from live tables. This makes historical values reproducible and insulates dashboards from data changes that occur after the snapshot was taken.



## 13. Staff Quality Score Formula Governance



**Question:** Score range 0-100 is required, but exact weighting and decay behavior for failures/violations is not specified.



**My Understanding:** Weights and windows should be policy-driven and configurable by admins, with explainable change history.



**Solution:** The scoring formula should be driven by a policy definition that is separate from the computation logic and is itself versioned and audited. Each computed score should retain a breakdown of the contributing factors so that any score can be explained without re-running historical calculations.



## 14. Alert SLA Interpretation



**Question:** Every alert must be acknowledged and resolved with note within 7 days, but when does SLA clock start (createdAt vs acknowledgedAt), and what about reopened alerts?



**My Understanding:** SLA should start at `createdAt`; reopen should reset due date only if policy allows and must be explicitly audited.



**Solution:** The SLA due date should be fixed at the moment the alert is created and should not silently shift based on when it is first viewed. Reopening an alert is a distinct lifecycle event with auditable intent; any change to the due date as a consequence should be governed by policy and recorded explicitly.



## 15. Observability Log Retention and Storage Pressure



**Question:** Prompt requires local structured logs and stack traces, but does not define retention limits on storage-constrained devices.



**My Understanding:** Retention needs bounded policies to prevent database bloat while preserving audit-critical records.



**Solution:** Log records should be classified by criticality, and each class should have an independently configurable retention window. High-volume diagnostic data should age out on a rolling basis, while audit-critical events should be protected from routine compaction and held for a longer, defined period.



## 16. 1,000,000-Record Performance Verification Contract



**Question:** Prompt sets <50 ms common query target at up to 1,000,000 rows, but does not define device class baseline or acceptance test environment.



**My Understanding:** Performance SLAs should be tied to a documented reference hardware profile and repeatable benchmark protocol.



**Solution:** Performance targets are only meaningful relative to a specified environment. A reference device profile and a repeatable, scripted benchmark suite should be defined so that the < 50 ms target can be objectively verified and so that regressions can be detected across development iterations.



## 17. File-Based Integration Trust Model



**Question:** Signed import/export bundles are required, but key distribution and trust onboarding for verification keys are not specified.



**My Understanding:** Trust anchors should be managed by administrators via offline key import with fingerprint confirmation and audit logging.



**Solution:** The system should maintain an explicit registry of trusted verification keys, managed by administrators through a permission-gated process. Adding or revoking a trusted key should require deliberate confirmation and should produce an audit record. Bundle verification should only succeed against keys that are currently in good standing within that registry.



## 18. Idempotency and Re-import Behavior



**Question:** Prompt defines rate limiting (e.g., 30 imports/hour/user) but not exact duplicate-import behavior (same file reimported intentionally vs accidentally).



**My Understanding:** Imports should be idempotent by checksum + manifest identity by default, with explicit override path for forced reprocessing.



**Solution:** The default behavior for reimporting an already-processed file should be to detect and reject the duplicate rather than silently reprocess it. Intentional reprocessing should require an elevated permission and an explicit reason, both of which are recorded, so that the audit trail can distinguish accidental from deliberate re-imports.
