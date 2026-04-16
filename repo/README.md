# LibOps — Offline Collection Orchestrator

Offline-first Android application for library operations teams. Manages catalog records, taxonomy, holdings, barcodes, collection source orchestration, signed-bundle import/export, duplicate detection, and role-based access control — all backed by a local Room/SQLite database with tamper-evident audit logging.

## Architecture & Tech Stack

* **Platform:** Android (Kotlin, Jetpack)
* **Database:** Room 2.6 over SQLite (32 entity types across 5 domains)
* **Auth:** PBKDF2 password hashing, biometric unlock, role-based capabilities
* **Observability:** QueryTimer performance sampling, exception capture, anomaly alerting
* **Containerization:** Docker & Docker Compose (required for reproducible builds and tests)

## Project Structure

```text
.
├── Dockerfile                   # APK builder + instrumented test stage
├── docker-compose.yml           # Multi-container orchestration
├── run_tests.sh                 # Standardized test execution script
├── ASSUMPTIONS.md               # Documented implementation decisions
├── README.md                    # Project documentation
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml       # Version catalog
│   └── wrapper/
│       └── gradle-wrapper.properties
├── gradlew, gradlew.bat
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/eaglepoint/libops/
│       │   │   ├── LibOpsApp.kt           # Service locator (DB, auth, observability)
│       │   │   ├── analytics/             # AnalyticsRepository (dashboard KPIs)
│       │   │   ├── audit/                 # AuditLogger (tamper-evident hash chain)
│       │   │   ├── auth/                  # AuthRepository, SessionStore, SeedData
│       │   │   ├── data/db/               # Room entities, DAOs, converters, database
│       │   │   ├── domain/                # Pure business logic (fully unit-tested)
│       │   │   ├── exports/               # BundleExporter, BundleSigner, BundleVerifier
│       │   │   ├── imports/               # CsvImporter, JsonImporter, BundleImporter
│       │   │   ├── media/                 # ImageDecoder (two-pass, LRU cache)
│       │   │   ├── observability/         # ObservabilityPipeline, OverdueSweeper
│       │   │   ├── orchestration/         # JobScheduler, CollectionRunWorker
│       │   │   ├── security/              # SecretCipher, SecretRepository
│       │   │   └── ui/                    # Activities (Login, Records, Imports, etc.)
│       │   └── res/                       # Material theme, adaptive icon, layouts
│       └── androidTest/                   # Instrumented 1M-row benchmark
└── tests/
    └── unit_tests/                        # JUnit 4 + Truth + Robolectric
```

## Prerequisites

To ensure a consistent environment, this project is designed to build and test entirely within containers. You must have the following installed:

* [Docker](https://docs.docker.com/get-docker/)
* [Docker Compose](https://docs.docker.com/compose/install/)

## Running the Application

1. **Build the APK:**
   Use Docker Compose to build the image and produce the debug APK.
   ```bash
   docker compose up --build
   ```
   The APK lands in `./outputs/libops-debug.apk`.

2. **Install on a device/emulator:**
   ```bash
   adb install ./outputs/libops-debug.apk
   ```

3. **First-launch setup:**
   The debug APK uses a fixed bootstrap password (`Admin@Review2024!`) so no
   one-time dialog needs to be captured. On release builds the password is
   generated uniquely per install and displayed once instead.

4. **Verify the app is working:**
   - Launch the app. A one-time admin credential dialog appears showing the
     bootstrap password. Dismiss it.
   - Log in with username `admin` and password `Admin@Review2024!`.
   - Because the account starts in `password_reset_required` status, you will
     be prompted to set a new password. Enter `Admin@Review2024!` to keep the
     documented credential, then confirm.
   - The **Records** screen opens showing an empty catalog — this is correct for a fresh install.
   - Tap **Add Record**, fill in at least the Title field, and save.
   - Confirm the new record appears in the list with the correct title.
   - Open the **Admin** screen and create an additional user assigned to any non-admin role.
   - Log out, log back in as the new user, and confirm that admin-only screens (Admin, Audit Log) are not accessible from that role.

5. **Stop the build container:**
   ```bash
   docker compose down -v
   ```


## Testing

All unit tests (including the 1M-row Room/SQLite performance benchmark) are
executed via a single standardized shell script. This script automatically
handles Docker container orchestration for the test environment.

Make sure the script is executable, then run it:

```bash
chmod +x run_tests.sh
./run_tests.sh
```

The script outputs a standard exit code (`0` for success, non-zero for failure)
to integrate smoothly with CI/CD validators.

**Behavior:**

- **Docker is the sole runner.** Builds the `builder` target of `Dockerfile`
  and runs `./gradlew testDebugUnitTest` inside the container with a pinned
  toolchain (Temurin 17, Gradle 8.5, Android SDK 34, build-tools 34.0.0).
- **1M-row benchmark** runs via Robolectric in the default unit test suite
  (`RoomQueryScaleUnitTest`). Indexed point lookups assert <50ms. The
  instrumented variant (`RoomQueryScaleTest`) also runs automatically when a
  device/emulator is detected.

### Instrumented performance tests

The instrumented benchmark runs inside Docker using the built-in emulator:

```bash
# Build and run the instrumented stage (includes Android emulator)
docker build --target instrumented -t libops-instrumented .
docker run --privileged libops-instrumented

# Or trigger via the test runner (hard-fail on error)
RUN_INSTRUMENTED=1 ./run_tests.sh
```

### Test catalog

49 unit test files covering:

- **Auth:** PasswordPolicy, LockoutPolicy, PasswordHasher, Authorizer,
  AuthRepository integration, BiometricLogin, BiometricPolicy,
  AuthorizationGate, AuthorizationGateRobolectric, BootstrapCredential,
  BootstrapIntegration, LoginViewModel, SessionTimeouts, UsernamePolicy
- **Settings:** AppSettings (default values, clamping, StateFlow emission)
- **Worker:** CollectionRunWorker (empty-batch success, exception isolation)
- **Catalog:** IsbnValidator, BarcodeValidator, RecordValidator,
  SimilarityAlgorithm, CoverImageImport, CatalogService
- **State machines:** all 8 lifecycle state machines from PRD section 10
- **Orchestration:** JobOrdering, JobSchedulerRetry, BundleImporter,
  SourceIngestionPipeline, WorkerIntegration, CsvImporter, JsonImporter,
  ImportService
- **Observability:** ObservabilityPipeline (anomaly + QueryTimer emission),
  AlertPolicy, AnomalyThresholds, AuditChain, AuditFilter
- **Export security:** BundleSignerVerifier (sign/verify roundtrip, digest
  mismatch, wrong key, multi-key trusted-key resolution via BundleVerifier),
  BundleExporter (file creation, manifest structure, audit inclusion,
  audit-event generation), SigningKeyStore contract (keypair lifecycle,
  rotation, trusted-key registry)
- **Secrets:** SecretRepository (upsert create/update, masked listing,
  plaintext reveal, input validation), Masking (null safety, length-based
  star prefixing)
- **Analytics:** AnalyticsRepository (dashboard KPI derivation, SLA check)
- **UI routing:** Navigation (capability-based filtering per role)
- **Activity-level functional:** LoginFlowRobolectric (login → session →
  gate → navigation pipeline, role-based end-to-end scenario)
- **Performance:** RoomQueryPerf (CPU-bound), RoomQueryScaleUnit (1M-row
  Robolectric benchmark), ImageDecoder
- **Quality:** QualityScore

## Seeded Credentials

### Admin (auto-seeded)

The debug APK uses a fixed bootstrap password so all roles have explicit,
reproducible credentials. Release builds generate a unique password per install
via `SecureRandom` and display it once in a modal dialog.

| Role | Username | Password |
| :--- | :--- | :--- |
| **Admin** | `admin` | `Admin@Review2024!` |

The admin account starts in `password_reset_required` status. On first login
you will be prompted to set a new password — enter `Admin@Review2024!` to keep
the documented credential. The bootstrap password is stored in
Keystore-backed `EncryptedSharedPreferences` (AES-256) until acknowledged,
then permanently removed. The plaintext is never written to the audit log —
only a SHA-256 confirmation marker is recorded.

> **Debug vs release:** The fixed password is injected via
> `LibOpsApp.REVIEW_ADMIN_PASSWORD` and is only passed to `SeedData` when
> `BuildConfig.DEBUG` is true. Release builds always generate a unique
> per-install credential.

### Non-admin roles (created by admin after first login)

Non-admin accounts are created through the Admin screen. Reviewers can
reproduce the full role matrix using the following exact usernames and
passwords:

| Role | Username | Password | Capabilities |
| :--- | :--- | :--- | :--- |
| **Collection Manager** | `cm_reviewer` | `Review!2024cm` | RECORDS_MANAGE, HOLDINGS_MANAGE, IMPORTS_RUN, EXPORTS_RUN |
| **Cataloger** | `cat_reviewer` | `Review!2024cat` | RECORDS_MANAGE, TAXONOMY_MANAGE, BARCODES_MANAGE |
| **Auditor** | `aud_reviewer` | `Review!2024aud` | AUDIT_VIEW (read-only audit log and reports) |

**Step-by-step role creation for reviewers:**
1. Log in as `admin` / `Admin@Review2024!`. When prompted, confirm the password reset using the same value.
2. Open the **Admin** screen from the navigation menu.
3. Tap **Add User**, enter username `cm_reviewer`, password `Review!2024cm`, and select role **Collection Manager**. Save.
4. Repeat for `cat_reviewer` / `Review!2024cat` / **Cataloger**.
5. Repeat for `aud_reviewer` / `Review!2024aud` / **Auditor**.
6. Log out and log back in as each role to verify capability isolation.

See `ASSUMPTIONS.md` for documented implementation decisions.
