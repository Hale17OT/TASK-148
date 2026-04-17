# Test Coverage Audit

## Backend Endpoint Inventory
- **Inventory result:** 0 HTTP endpoints (`METHOD + PATH`) discovered.
- **Evidence:**
  - `app/src/main/AndroidManifest.xml:19`–`app/src/main/AndroidManifest.xml:41` defines Android `Activity` entries, not HTTP routes.
  - Repository-wide static route pattern scan found no HTTP routing declarations (`@GetMapping`, `@PostMapping`, `routing {}`, `app.get(...)`, Retrofit HTTP annotations).

## API Test Mapping Table

| Endpoint | Covered | Test Type | Test Files | Evidence |
|---|---|---|---|---|
| _None (no HTTP endpoints found)_ | no | unit-only / indirect | `tests/unit_tests/*Test.kt`, `app/src/androidTest/java/com/eaglepoint/libops/tests/RoomQueryScaleTest.kt` | No HTTP endpoint definitions found in source; no HTTP request tests found in test tree |

## Coverage Summary
- Total endpoints: **0**
- Endpoints with HTTP tests: **0**
- Endpoints with TRUE no-mock HTTP tests: **0**
- HTTP coverage %: **N/A** (no HTTP endpoints)
- True API coverage %: **N/A** (no HTTP endpoints)

## API Test Classification
1. **True No-Mock HTTP:** none
2. **HTTP with Mocking:** none
3. **Non-HTTP (unit/integration without HTTP):** all discovered tests
   - `tests/unit_tests`: **74** `*Test.kt` files
   - `app/src/androidTest`: **1** `*Test.kt` file

## Mock Detection
- No framework mock signatures found (`jest.mock`, `vi.mock`, `sinon.stub`, `Mockito`, `mockk`, `@Mock`, `@Spy`, `whenever(...)`).
- Fake/stub patterns still exist (classified as non-HTTP indirect testing):
  - `tests/unit_tests/fakes/FakeDaos.kt:20` (`FakeUserDao`, `FakePermissionDao`, `FakeSessionDao`, `FakeAuditDao`)
  - `tests/unit_tests/CatalogServiceTest.kt:224` (custom DAO stubs)
  - `tests/unit_tests/LoginViewModelTest.kt:214` (`StubAuthRepository`), `tests/unit_tests/LoginViewModelTest.kt:236` (`SuspendingStubAuthRepository`)
  - `tests/unit_tests/SecretsOperationsIntegrationTest.kt:126` (`IdentitySecretCipher` override)

## Unit Test Summary
- **Controllers:** N/A (no HTTP controller layer exists).
- **Services covered:**
  - `CatalogService`: `tests/unit_tests/CatalogServiceTest.kt:31`, `tests/unit_tests/RecordsOperationsIntegrationTest.kt:75`
  - `ImportService`: `tests/unit_tests/ImportServiceTest.kt`, `tests/unit_tests/ImportsOperationsIntegrationTest.kt:60`
  - `AuthRepository`: `tests/unit_tests/AuthRepositoryIntegrationTest.kt:26`, `tests/unit_tests/AuthLockoutRealRoomIntegrationTest.kt:28`
  - `AnalyticsRepository`: `tests/unit_tests/AnalyticsRepositoryTest.kt`, `tests/unit_tests/AnalyticsRepositoryRealRoomTest.kt:29`, `tests/unit_tests/AnalyticsDashboardIntegrationTest.kt:33`
  - `ObservabilityPipeline`: `tests/unit_tests/ObservabilityPipelineTest.kt`, `tests/unit_tests/ObservabilityPipelineRealRoomTest.kt:24`, `tests/unit_tests/ObservabilityAnomalyDetectionRealRoomTest.kt`
- **Repositories/DAOs covered:** substantial real-Room integration coverage (`tests/unit_tests/RealRoomIntegrationTest.kt:34`, `tests/unit_tests/CrossDaoForeignKeyIntegrationTest.kt`, `tests/unit_tests/SessionExpiryRealRoomTest.kt`, `tests/unit_tests/AuditChainRealRoomIntegrationTest.kt`).
- **Auth/guards/middleware-equivalent covered:**
  - Authorization gate and capability checks: `tests/unit_tests/AuthorizationGateRobolectricTest.kt`, `tests/unit_tests/ActivityCapabilityGateTest.kt:40`
  - End-to-end session lockout lifecycle: `tests/unit_tests/AuthLockoutRealRoomIntegrationTest.kt:67`
- **Important modules not directly UI-driven tested (still mostly operation-mirrored):**
  - `app/src/main/java/com/eaglepoint/libops/ui/admin/RuntimeSettingsActivity.kt`
  - `app/src/main/java/com/eaglepoint/libops/ui/collection/CollectionRunsActivity.kt`
  - `app/src/main/java/com/eaglepoint/libops/ui/alerts/AlertsActivity.kt`

## API Observability Check
- **Result:** weak / not applicable for API layer.
- **Reason:** no HTTP tests provide explicit `METHOD + PATH`, request payload/query/params, or HTTP response assertions.
- **Evidence:** no `tests/api_tests` directory and no HTTP request helper usage in `tests/**/*.kt`.

## Tests Check
- `run_tests.sh` is Docker-based (compliant with Docker-runner expectation): `run_tests.sh:29`–`run_tests.sh:38`, `run_tests.sh:52`–`run_tests.sh:55`.
- **Local dependency flag:** Docker must exist on host (`run_tests.sh:82`–`run_tests.sh:84`).

## Test Coverage Score (0–100)
- **75/100**

## Score Rationale
- Positive:
  - Strong breadth and depth of non-HTTP tests, including many real-Room integration tests.
  - Auth, validation, failure paths, and role/capability boundaries are extensively asserted.
  - Assertions are generally concrete (row state, audit side-effects, computed KPIs), not superficial pass/fail only.
- Negative:
  - Zero HTTP endpoint inventory and zero HTTP API tests under strict endpoint definition.
  - Some key flows still validated by operation mirroring rather than full UI-interaction lifecycles.
  - Continued reliance on test doubles in selected paths.

## Key Gaps
- No HTTP API surface; API coverage criteria cannot be satisfied.
- No true no-mock HTTP tests (none possible with current architecture).
- Remaining UI behaviors beyond capability gates are not comprehensively tested in full interaction depth.

## Confidence & Assumptions
- Confidence: **High** on endpoint absence and classification; **Medium** on sufficiency score.
- Assumptions: endpoint definition is strictly HTTP `METHOD + PATH` per requested rules.

## Test Coverage Verdict
- **PARTIAL PASS**

# README Audit

## High Priority Issues
- None.

## Medium Priority Issues
- `README.md` references `ASSUMPTIONS.md` (`README.md:20`, `README.md:218`), but the file is absent in repository root.

## Low Priority Issues
- README test catalog count is outdated: it states `49 unit test files` (`README.md:142`), while current static inventory shows `74` `*Test.kt` files under `tests/unit_tests`.
- `adb` is required by run steps (`README.md:74`) but not explicitly listed in prerequisites.

## Hard Gate Failures
- None.

## README Verdict (PASS / PARTIAL PASS / FAIL)
- **PASS**

## README Compliance Evidence
- Project type declared at top: Android (`README.md:3`, `README.md:7`).
- README exists at required path: `README.md`.
- Android startup instructions present:
  - Build with Docker Compose (`README.md:68`)
  - Install on device/emulator (`README.md:74`)
- Access method present (mobile flow): `README.md:72`–`README.md:93`.
- Verification method present (role-based workflow checks): `README.md:82`–`README.md:93`.
- Environment rules compliance:
  - No `npm install`, `pip install`, `apt-get`, runtime install, or manual DB setup instructions found.
  - Docker-centered build/test instructions present (`README.md:68`, `README.md:119`–`README.md:121`).
- Demo credentials for auth present with roles:
  - Admin credentials: `README.md:182`–`README.md:185`
  - Non-admin roles and credentials: `README.md:204`–`README.md:209`
