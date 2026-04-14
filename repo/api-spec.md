# LibOps API Specification (Offline/Internal)

## 1. Scope

LibOps does **not** expose an HTTP/REST server API in this repository.

This specification documents the available **offline internal APIs**:
- Activity route contract (intent/navigation routes)
- Service/repository command APIs (Kotlin interfaces/methods)
- File-integration API (CSV, JSON, signed bundle payload contracts)
- Standard response/result types

## 2. API Conventions

## 2.1 Result envelope

Most domain operations return `AppResult<T>`:

```kotlin
sealed interface AppResult<out T> {
  data class Success<T>(val data: T)
  data class ValidationError(val fieldErrors: List<FieldError>)
  data class PermissionDenied(val permission: String)
  data class Conflict(val entity: String, val reason: String)
  data class NotFound(val entity: String)
  data class Locked(val minutesRemaining: Int)
  data class SystemError(val correlationId: String, val message: String)
}
```

## 2.2 Data types
- `id`: `Long`
- `timestamp`: epoch millis (`Long`)
- `correlationId`: opaque string
- `capability`: string from `Capabilities` constants

## 2.3 Authorization
- Route-level authorization: `AuthorizationGate.requireAccess(activity, requiredCapability)`
- Action-level authorization: `AuthorizationGate.revalidateSession(activity, requiredCapability)`
- Service-level authorization: `Authorizer.require(capability)` in write services

## 3. Activity Route Specification

All routes below are in-app routes (activities), not network routes.

| Route Key | Activity | Required Capability | Request Params | Response Type |
|---|---|---|---|---|
| `login` | `LoginActivity` | none | none | UI state transitions to authenticated/locked/error |
| `main` | `MainActivity` | authenticated session | none | navigation hub rendering visible routes |
| `dashboard` | `AnalyticsActivity` | `analytics.read` | none | analytics cards/list data |
| `collection_runs` | `CollectionRunsActivity` | `jobs.read` | none | source/job list and actions |
| `source_editor` | `SourceEditorActivity` | `sources.manage` | `sourceId?: Long` via intent extra | create/update source form |
| `records` | `RecordsActivity` | `records.read` | none | record list + edit workflows |
| `duplicates` | `DuplicatesActivity` | `duplicates.read` | none | duplicate queue list |
| `merge_review` | `MergeReviewActivity` | `duplicates.resolve` | `duplicateId: Long` via intent extra | merge decision workflow |
| `alerts` | `AlertsActivity` | `alerts.read` | none | alert queue and SLA actions |
| `audit_logs` | `AuditLogsActivity` | `audit.read` | none | filterable audit/event list |
| `imports` | `ImportsActivity` | `imports.run` | file/tree URI from picker | import/export actions |
| `admin` | `AdminActivity` | `users.manage` | none | users/roles/permissions management |
| `runtime_settings` | `RuntimeSettingsActivity` | `users.manage` | none | runtime controls |
| `secrets` | `SecretsActivity` | `secrets.read_masked` | none | masked secret list + reveal/update ops |

## 4. Auth API

## 4.1 `AuthRepository.login(rawUsername, password)`

### Request schema

```json
{
  "rawUsername": "string",
  "password": "char[]"
}
```

### Success payload (`AppResult.Success<ActiveSession>`)

```json
{
  "sessionId": "long",
  "userId": "long",
  "username": "string",
  "activeRoleName": "administrator|collection_manager|cataloger|auditor",
  "capabilities": ["string"],
  "authenticatedAt": "epochMillis",
  "lastActiveMillis": "epochMillis",
  "biometricEnabled": "boolean",
  "passwordResetRequired": "boolean"
}
```

### Error responses
- `ValidationError` (blank or invalid credentials)
- `Locked` (remaining lockout minutes)
- `Conflict(entity="user", reason="account_disabled")`
- `Conflict(entity="role", reason="no_role_assigned")`

## 4.2 `AuthRepository.resumeViaBiometric(rawUsername)`

### Request

```json
{
  "rawUsername": "string"
}
```

### Response
- `Success<ActiveSession>`
- `NotFound(entity="user")`
- `Locked(minutesRemaining)`
- `Conflict(entity="biometric", reason="not_eligible")`
- `Conflict(entity="user", reason="status_<state>")`

## 4.3 Other auth commands
- `restoreSession(): ActiveSession?`
- `logout(reason = "user_logout"): Unit`
- `enableBiometric(): AppResult<Unit>`
- `verifyPasswordForStepUp(password): Boolean`

## 5. Catalog Command API

Service: `CatalogService`

## 5.1 `insertRecord(entity, userId)`

### Payload schema (`MasterRecordEntity`)

```json
{
  "title": "string",
  "titleNormalized": "string",
  "publisher": "string|null",
  "pubDate": "epochMillis|null",
  "format": "string|null",
  "category": "book|journal|other",
  "isbn10": "string|null",
  "isbn13": "string|null",
  "language": "string|null",
  "notes": "string|null",
  "status": "draft|active|archived",
  "sourceProvenanceJson": "string|null",
  "createdByUserId": "long",
  "createdAt": "epochMillis",
  "updatedAt": "epochMillis"
}
```

### Response
- Success: `Long` record ID
- Errors: `SecurityException` (missing `records.manage`)

## 5.2 `updateRecord(entity, userId)`
- Request: same as `MasterRecordEntity`
- Response: `Int` affected rows

## 5.3 `insertVersion(version, userId)`

Payload schema (`MasterRecordVersionEntity`):

```json
{
  "recordId": "long",
  "version": "int",
  "snapshotJson": "string",
  "editorUserId": "long",
  "changeSummary": "string|null",
  "createdAt": "epochMillis"
}
```

## 5.4 `createTaxonomyNode(node, userId)`

```json
{
  "parentId": "long|null",
  "name": "string",
  "description": "string|null",
  "archived": "boolean",
  "createdAt": "epochMillis",
  "updatedAt": "epochMillis"
}
```

Response: created node ID (`Long`)

## 5.5 `assignTaxonomy(binding, userId)`

```json
{
  "recordId": "long",
  "taxonomyId": "long",
  "primary": "boolean"
}
```

Response: `Unit`

## 5.6 `addHolding(holding, userId)`

```json
{
  "masterRecordId": "long",
  "location": "string",
  "totalCount": "int",
  "availableCount": "int",
  "lastAdjustmentReason": "string|null",
  "lastAdjustmentUserId": "long|null",
  "createdAt": "epochMillis",
  "updatedAt": "epochMillis"
}
```

Response: holding ID (`Long`)

## 5.7 `assignBarcode(barcode, userId)`

```json
{
  "code": "string (8-14 alphanumeric)",
  "masterRecordId": "long",
  "state": "available|assigned|suspended|retired|reserved_hold",
  "assignedAt": "epochMillis|null",
  "retiredAt": "epochMillis|null",
  "reservedUntil": "epochMillis|null",
  "createdAt": "epochMillis",
  "updatedAt": "epochMillis"
}
```

Response:
- Success: barcode ID (`Long`)
- Errors: `IllegalStateException` for collisions/no holdings

## 6. Import Command API

Service: `ImportService`

## 6.1 `importCsv(filename, source, userId, userRecentImportsInWindow)`

### Request

```json
{
  "filename": "string",
  "source": "InputStream(csv)",
  "userId": "long",
  "userRecentImportsInWindow": "int"
}
```

### CSV row schema

```json
{
  "title": "string",
  "publisher": "string?",
  "pub_date": "string?",
  "format": "string?",
  "category": "book|journal|other",
  "isbn10": "string?",
  "isbn13": "string?",
  "language": "string?",
  "notes": "string?",
  "cover_path": "string?"
}
```

### Response schema (`CsvImporter.Summary`)

```json
{
  "batchId": "long",
  "accepted": "int",
  "rejected": "int",
  "duplicatesSurfaced": "int",
  "finalState": "string"
}
```

## 6.2 `importJson(filename, source, userId, userRecentImportsInWindow)`

### Request schema

```json
{
  "filename": "string",
  "source": "InputStream(json)",
  "userId": "long",
  "userRecentImportsInWindow": "int"
}
```

### JSON import payload schema

```json
{
  "records": [
    {
      "title": "string",
      "publisher": "string?",
      "isbn10": "string?",
      "isbn13": "string?",
      "format": "string?",
      "category": "book|journal|other",
      "language": "string?",
      "notes": "string?",
      "cover_path": "string?"
    }
  ]
}
```

### Response schema (`JsonImporter.Summary`)

```json
{
  "batchId": "long",
  "accepted": "int",
  "rejected": "int",
  "duplicatesSurfaced": "int",
  "finalState": "string"
}
```

## 6.3 `importBundle(bundleDir, trustedKeys, userId, userRecentImportsInWindow)`

### Request schema

```json
{
  "bundleDir": "local filesystem directory",
  "trustedKeys": ["PublicKey"],
  "userId": "long",
  "userRecentImportsInWindow": "int"
}
```

### Required bundle files
- `manifest.json`
- `content.json`
- signature file(s) expected by verifier

### `content.json` schema

```json
{
  "records": [
    {
      "title": "string",
      "publisher": "string?",
      "isbn10": "string?",
      "isbn13": "string?",
      "format": "string?",
      "category": "book|journal|other",
      "language": "string?",
      "notes": "string?",
      "cover_path": "string?"
    }
  ]
}
```

### Response type (`BundleImporter.ImportResult`)

```json
{
  "type": "Success|VerificationFailed|AlreadyImported|RateLimited",
  "summary?": {
    "batchId": "long",
    "bundleEntityId": "long",
    "accepted": "int",
    "rejected": "int",
    "duplicatesSurfaced": "int",
    "finalState": "string",
    "manifestVersion": "string"
  },
  "reason?": "string",
  "checksum?": "string"
}
```

## 7. Route-Level Request/Response Notes

## 7.1 Route params
- `SourceEditorActivity`: intent extra `sourceId` (`Long`, optional)
- `MergeReviewActivity`: intent extra `duplicateId` (`Long`, required)

## 7.2 UI command response handling
- Commands are mapped to snackbar/toast/UI list refresh behavior.
- Error states are surfaced as user-readable messages and audit entries.

## 8. Standard Data Schemas

## 8.1 `FieldDefinitionEntity`

```json
{
  "id": "long",
  "fieldKey": "string(unique)",
  "label": "string",
  "fieldType": "text|number|date|select",
  "required": "boolean",
  "optionsJson": "string|null",
  "displayOrder": "int",
  "archived": "boolean",
  "system": "boolean",
  "entityColumn": "string|null",
  "createdByUserId": "long",
  "createdAt": "epochMillis",
  "updatedAt": "epochMillis"
}
```

## 8.2 `CollectionSourceEntity`

```json
{
  "id": "long",
  "name": "string(unique)",
  "entryType": "site|ranking_list|artist|album|imported_file",
  "refreshMode": "incremental|full",
  "priority": "1..5",
  "retryBackoff": "1_min|5_min|30_min",
  "enabled": "boolean",
  "state": "draft|active|disabled|archived",
  "scheduleCron": "string|null",
  "batteryThresholdPercent": "int",
  "maxRetries": "int"
}
```

## 8.3 `JobEntity`

```json
{
  "id": "long",
  "sourceId": "long",
  "status": "scheduled|queued|running|retry_waiting|paused_low_battery|failed|terminal_failed|succeeded|cancelled|cancelled_partial",
  "priority": "int",
  "retryCount": "int",
  "refreshMode": "string",
  "correlationId": "string",
  "scheduledAt": "epochMillis",
  "startedAt": "epochMillis|null",
  "finishedAt": "epochMillis|null"
}
```

## 8.4 Alert schema

```json
{
  "id": "long",
  "category": "job_failure|slow_query|audit_integrity|rate_limit|overdue_alerts",
  "severity": "info|warn|critical",
  "title": "string",
  "body": "string",
  "status": "open|acknowledged|overdue|resolved|reopened|auto_suppressed",
  "ownerUserId": "long|null",
  "correlationId": "string|null",
  "dueAt": "epochMillis"
}
```

## 9. Error Mapping

- Validation issues: `ValidationError.fieldErrors[]`
- Auth lockout: `Locked(minutesRemaining)`
- Permission boundary breach: `PermissionDenied` or `SecurityException`
- Duplicate import bundle: `AlreadyImported(checksum)`
- Tampered/invalid signed bundle: `VerificationFailed(reason)`
- Rate limiting: `RateLimited`

## 10. Non-HTTP Disclaimer

Because LibOps is designed for fully offline operation, this API spec is intentionally centered on local application contracts rather than network endpoints.
