# LibOps — Implementation Assumptions

This file documents assumptions made while implementing the PRD. It is a living
document; update as assumptions change.

## Platform

- **Language:** Kotlin 1.9.22. Chosen because the PRD specifies Android with
  Room, WorkManager, RecyclerView, DiffUtil — Kotlin is the de-facto default
  and provides coroutines / null-safety for the required concurrency model.
- **Android Gradle Plugin:** 8.2.2, Gradle 8.5. Matches Docker build image.
- **compileSdk / targetSdk / minSdk:** 34 / 34 / 26. minSdk 26 required for
  androidx.security-crypto (EncryptedSharedPreferences), BiometricPrompt on
  all device tiers, and adaptive icons.
- **Java target:** 17. Required by AGP 8.2.

## Security

- **Password KDF:** `PBKDF2WithHmacSHA256` at **210,000 iterations**. The PRD
  prefers Argon2id where available; however, Argon2id is not in Android's
  default JCE providers and adding BouncyCastle (or PHC reference) expands
  the offline footprint and increases attack surface. PBKDF2 at 210k iterations
  meets OWASP 2023 guidance and is supported on all target devices.
- **Secret encryption-at-rest:** relies on `androidx.security:security-crypto`
  with Android Keystore-backed `MasterKey`. Access to plaintext requires
  `secrets.read_full` capability plus re-authentication.
- **Audit hash chain:** local SHA-256 chain stored on each event row. Any
  broken chain raises an integrity alert on verification.

## Similarity algorithm

- **Jaro-Winkler** with prefix scaling factor 0.1 and max common prefix 4.
  Deterministic, pure math, O(n*m) time, no external model. Thresholds match
  PRD: 0.85 (ISBN-match duplicate) and 0.95 (possible duplicate without ISBN
  and with publisher match).

## Session policy

- Session timeout defaults: 15 min standard / 10 min privileged, as fixed by
  the PRD.

## Retention defaults

- `AuditEvent`: 365 days; `ExceptionEvent`: 180 days; `PerformanceSample`:
  90 days (raw) then summarized; `MetricSnapshot`: indefinite unless manually
  archived; `ImportRowResult`: 90 days unless linked to unresolved merge.

## Import/export

- Max file size 100 MB; max rows 250,000. CSV is UTF-8 only.
- Rows are streamed/chunked. Signed bundle manifests include creator ID,
  timestamp, checksum, and signature metadata.

## Taxonomy

- Multi-taxonomy classification supported via `record_taxonomy` mapping with
  an optional "primary" flag. Phase 1 UI can expose a single primary taxonomy
  while the data model accepts multiple.

## Single active role

- A user may be assigned multiple roles, but a session carries one active
  role context to reduce privilege ambiguity. Selection happens at login or
  role-switch and is audited.

## Secret reveal

- Full reveal restricted to **Administrator** by default and requires re-auth.

## Concurrency defaults

- WorkManager parallelism default 3; admin-configurable 1–6.

## Battery threshold

- Default 15%. Admin can configure 10–25%.

## Rate limiting

- Import/integration actions: 30 per rolling 60-minute window per user.
  Admin may reduce to 1–30.
