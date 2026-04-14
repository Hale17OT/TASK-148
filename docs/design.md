# LibOps Offline Collection Orchestrator - Design

## 1. Purpose

LibOps is an offline-first Android application for library acquisitions and catalog operations. It supports four roles (Administrator, Collection Manager, Cataloger, Auditor) and prioritizes local execution, role-based control, auditability, and resilience under constrained devices.

This design covers:
- App architecture and module boundaries
- Data model and persistence strategy
- Security and authorization model
- Orchestration, import/export, and observability pipelines
- Performance and non-functional constraints

## 2. Product Goals and Constraints

## 2.1 Goals
- Manage collection runs from local sources and imported files
- Maintain high-quality master records with validation, taxonomy, holdings, and barcodes
- Provide duplicate detection and guided merge with audit trail
- Enable signed offline import/export bundle workflows
- Provide local analytics, quality scoring, and closed-loop alert resolution

## 2.2 Hard constraints
- Fully offline operation (no runtime network dependency)
- Local username/password auth with optional biometric resume
- 5 failed attempts lockout for 15 minutes
- Secrets encrypted at rest; masked by default in UI
- Background orchestration via WorkManager with battery-aware behavior

## 3. High-Level Architecture

LibOps follows a layered architecture:

1. UI Layer (`ui/*`)
- Activities for each feature area
- `AuthorizationGate` for route-level and action-level access checks
- RecyclerView + DiffUtil driven list rendering

2. Domain Layer (`domain/*`)
- Policy and validation logic (`auth`, `catalog`, `dedup`, `alerts`, `orchestration`)
- Service-layer write boundaries (`CatalogService`, `ImportService`)
- Result contracts via `AppResult`

3. Data Layer (`data/db/*`)
- Room entities + DAOs + DB wiring
- Indexed schema tuned for common read patterns
- Audit/observability entities persisted locally

4. Integration Layer (`imports/*`, `exports/*`, `security/*`)
- CSV/JSON/signed-bundle ingestion
- Signed export bundle creation and verification
- Cover image processing and attachment persistence

5. Runtime Infrastructure (`orchestration/*`, `observability/*`, `LibOpsApp.kt`)
- WorkManager scheduler/worker
- Query/exception/performance capture
- App bootstrapping and service composition

## 4. Module Responsibilities

## 4.1 App bootstrap
- `LibOpsApp` wires DB, repositories, services, observability, and periodic work.
- Seeds base data and metadata field definitions at startup.

## 4.2 Identity and auth
- `AuthRepository` handles login, lockout, session lifecycle, biometric resume, logout.
- `SessionStore` keeps active session in-memory; `user_sessions` persists authoritative session state.
- `Authorizer` + capability constants provide deterministic access checks.

## 4.3 Authorization model
- Route-level: every feature activity uses `AuthorizationGate.requireAccess(...)`.
- Action-level: mutating handlers use `AuthorizationGate.revalidateSession(..., capability)`.
- Service-level: `CatalogService` and `ImportService` enforce capability checks before writes.

## 4.4 Collection orchestration
- `CollectionSourceEntity` defines source entry type, refresh mode, priority, retry backoff.
- `JobScheduler` and `CollectionRunWorker` manage job lifecycle and retries.
- `SourceIngestionPipeline` validates rows, detects duplicates, stages accepted records, and records metrics.

## 4.5 Catalog operations
- Core records in `master_records` with version snapshots.
- Taxonomy tree with assignment table (`record_taxonomy`).
- Holdings and barcode assignment with collision checks.
- Configurable metadata via `field_definitions` + `record_custom_fields`.

## 4.6 Import/export and offline integration
- `CsvImporter`, `JsonImporter`, `BundleImporter` support local file ingestion.
- Signed bundle verification via `BundleVerifier` and checksum dedupe (`imported_bundles`).
- `CoverImageProcessor` integrates downsampled image handling and attachment persistence.

## 4.7 Observability and governance
- Audit events with correlation IDs and severity
- Exception events and performance samples
- Alert lifecycle: open -> acknowledged -> overdue/resolved
- Quality score snapshots for staff performance trend tracking

## 5. Data Model Overview

Core entity groups (Room `LibOpsDatabase`):

- Identity: `users`, `roles`, `permissions`, `role_permissions`, `user_roles`, `user_sessions`, `secrets`
- Orchestration: `collection_sources`, `crawl_rules`, `jobs`, `job_attempts`, `import_batches`, `import_row_results`, `duplicate_candidates`, `merge_decisions`, `imported_bundles`
- Catalog: `master_records`, `master_record_versions`, `taxonomy_nodes`, `record_taxonomy`, `holding_copies`, `barcodes`, `record_attachments`, `field_definitions`, `record_custom_fields`
- Audit/observability: `audit_events`, `exception_events`, `performance_samples`, `policy_violations`, `alerts`, `alert_acknowledgements`, `alert_resolutions`, `metric_snapshots`, `quality_score_snapshots`

Key indexes include:
- `master_records(isbn13, titleNormalized)`
- `jobs(status, priority, scheduledAt)`
- `alerts(status, severity, createdAt)`

## 6. Primary Workflows

## 6.1 Authentication
1. User submits local username/password.
2. Password hash verified and lockout policy applied.
3. Existing sessions revoked; new authenticated session issued.
4. Optional biometric resume supported after prior successful password login.

## 6.2 Collection run execution
1. Source configured with schedule/priority/backoff/retries.
2. Worker loads queued jobs up to configured parallelism.
3. Pipeline ingests entries, validates, duplicate-checks, and stages records.
4. Job transitions and attempts are persisted and audited.

## 6.3 Import and duplicate merge
1. Import file/bundle is validated and row-staged.
2. Duplicate candidates generated using ISBN + similarity logic.
3. Operator resolves duplicates through merge review.
4. Merge decision and provenance are persisted in auditable tables.

## 6.4 Alert lifecycle
1. Anomaly/violation creates alert.
2. Operator acknowledges alert.
3. Operator resolves alert with required note.
4. Overdue sweeper marks unresolved acknowledged/open alerts as overdue.

## 7. Security and Privacy Design

- No feature activities are exported except launcher login activity.
- Secrets are encrypted with keystore-backed crypto and masked by default.
- Capability checks occur at screen entry, action dispatch, and service write boundaries.
- Failed logins, lockouts, and authz denials are audit logged.
- Session idle timeout enforcement occurs at route access and action revalidation.

## 8. Performance and Reliability

- Room query timing instrumentation via `QueryTimer` in high-traffic paths.
- Background jobs executed off main thread via WorkManager.
- Battery threshold guard pauses/suppresses job progression when needed.
- Cover image path uses bounded decode dimensions and attachment persistence.
- Scale/perf tests include 1M-row indexed lookup instrumentation benchmark.

## 9. Failure Handling Strategy

- User-visible failures are surfaced as validation/conflict messages.
- Import failures are row-scoped; one bad row/source should not block all work.
- Retry policy separates transient and terminal failures.
- Observability pipeline records exceptions, slow operations, and actionable alerts.

## 10. Build and Operational Model

- Build via Docker or local Android toolchain.
- Unit tests run by default; instrumented tests for perf are available and optionally enforced.
- All integrations (CSV/JSON/signed bundles) are local filesystem-based.

## 11. Future Extension Points

- Additional signed integration bundle types
- Stronger policy engine for per-field and per-role constraints
- Structured migration/versioning strategy for field definitions and taxonomy
- Optional kiosk-mode/operator workflow hardening for shared devices
