# Issue Reinspection Results (v6)

Static-only reinspection of the same previously reported issues (no runtime execution).

## Summary
- Fixed: **7**
- Partially Fixed: **0**
- Not Fixed: **0**

## Issue-by-Issue Status

1) **High** - Configurable metadata fields requirement not implemented
- Status: **Fixed**
- Evidence:
  - Field-definition model present: `app/src/main/java/com/eaglepoint/libops/data/db/entity/CatalogEntities.kt:206`
  - DB registration: `app/src/main/java/com/eaglepoint/libops/data/db/LibOpsDatabase.kt:96`
  - Built-in/system field seeding: `app/src/main/java/com/eaglepoint/libops/data/db/FieldDefinitionSeeder.kt:17`
  - Record editor renders from field definitions: `app/src/main/java/com/eaglepoint/libops/ui/records/RecordsActivity.kt:180`

2) **High** - Cover image import memory requirement not wired into import flows
- Status: **Fixed**
- Evidence:
  - Import path integration in CSV/JSON/Bundle:
    - `app/src/main/java/com/eaglepoint/libops/imports/CsvImporter.kt:273`
    - `app/src/main/java/com/eaglepoint/libops/imports/JsonImporter.kt:271`
    - `app/src/main/java/com/eaglepoint/libops/imports/BundleImporter.kt:295`
  - Attachment DAO availability: `app/src/main/java/com/eaglepoint/libops/data/db/LibOpsDatabase.kt:125`
  - Import wiring with DAO + cover dir: `app/src/main/java/com/eaglepoint/libops/ui/imports/ImportsActivity.kt:69`
  - Negative/large-image test coverage added: `tests/unit_tests/CoverImageImportTest.kt:110`

3) **High** - Strict role isolation is UI-gated; mutating operations lack explicit capability checks
- Status: **Fixed**
- Evidence:
  - Explicit capability revalidation on mutating actions (UI): `app/src/main/java/com/eaglepoint/libops/ui/records/RecordsActivity.kt:225`, `app/src/main/java/com/eaglepoint/libops/ui/imports/ImportsActivity.kt:315`
  - Service-layer capability enforcement:
    - `app/src/main/java/com/eaglepoint/libops/domain/catalog/CatalogService.kt:30`
    - `app/src/main/java/com/eaglepoint/libops/domain/catalog/CatalogService.kt:44`
    - `app/src/main/java/com/eaglepoint/libops/domain/orchestration/ImportService.kt:25`
    - `app/src/main/java/com/eaglepoint/libops/domain/orchestration/ImportService.kt:42`

4) **Medium** - Slow Room query and exception observability only partially connected
- Status: **Fixed**
- Evidence:
  - QueryTimer now wired in many runtime paths, including records/imports/orchestration/admin/audit/alerts:
    - `app/src/main/java/com/eaglepoint/libops/ui/records/RecordsActivity.kt:86`
    - `app/src/main/java/com/eaglepoint/libops/ui/imports/ImportsActivity.kt:88`
    - `app/src/main/java/com/eaglepoint/libops/orchestration/SourceIngestionPipeline.kt:56`
    - `app/src/main/java/com/eaglepoint/libops/ui/audit/AuditLogsActivity.kt:150`
  - Exception capture in app flows: `app/src/main/java/com/eaglepoint/libops/orchestration/CollectionRunWorker.kt:110`, `app/src/main/java/com/eaglepoint/libops/ui/imports/ImportsActivity.kt:169`
  - Tests for query-timer/exception observability: `tests/unit_tests/ObservabilityPipelineTest.kt:149`, `tests/unit_tests/ObservabilityPipelineTest.kt:319`

5) **Medium** - Biometric unlock discoverability/flow incomplete on login
- Status: **Fixed**
- Evidence:
  - Username observer + debounce now present: `app/src/main/java/com/eaglepoint/libops/ui/LoginActivity.kt:92`, `app/src/main/java/com/eaglepoint/libops/ui/LoginActivity.kt:98`
  - Dynamic biometric visibility refresh: `app/src/main/java/com/eaglepoint/libops/ui/LoginActivity.kt:104`

6) **Medium** - Default test command does not execute 1M-row Room instrumentation benchmark
- Status: **Fixed**
- Evidence:
  - Documentation includes benchmark command and runner flag: `README.md:53`, `README.md:58`
  - Runner now attempts instrumented benchmark in default flow (`RUN_INSTRUMENTED=auto`) and in Docker path: `run_tests.sh:186`, `run_tests.sh:190`, `run_tests.sh:231`, `run_tests.sh:237`
  - Still environment-conditional skip path exists: `run_tests.sh:271`
- Remaining acceptable gap:
  - Benchmark can still be skipped when no device/emulator is available; hard enforcement requires `RUN_INSTRUMENTED=1` in a prepared environment.

7) **Low** - Generated build/test artifacts in workspace snapshot
- Status: **Fixed**
- Evidence:
  - Current `app/` listing has no `build/` directory: `app` contains only `build.gradle.kts`, `proguard-rules.pro`, `schemas/`, `src/`.
  - `glob` for `app/build/**` returns no files.
