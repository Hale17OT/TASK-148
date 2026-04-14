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

For local development without Docker, you also need:
* JDK 17 (Temurin recommended)
* Android SDK 34 with build-tools 34.0.0

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
   On first install, the app generates a unique administrator password and
   displays it once in a modal dialog. See the Seeded Credentials section below.

4. **Stop the build container:**
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

- **Docker is the primary runner.** Builds the `builder` target of `Dockerfile`
  and runs `./gradlew testDebugUnitTest` inside the container with a pinned
  toolchain (Temurin 17, Gradle 8.5, Android SDK 34, build-tools 34.0.0).
- **Fallback to local host** if Docker is not available.
- **Force local execution** with `FORCE_LOCAL=1 ./run_tests.sh`.
- **1M-row benchmark** runs via Robolectric in the default unit test suite
  (`RoomQueryScaleUnitTest`). Indexed point lookups assert <50ms. The
  instrumented variant (`RoomQueryScaleTest`) also runs automatically when a
  device/emulator is detected.

### Instrumented performance tests

```bash
# Run the 1M-row performance benchmark on a connected device
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.class=com.eaglepoint.libops.tests.RoomQueryScaleTest

# Or force instrumented tests in the test runner
RUN_INSTRUMENTED=1 ./run_tests.sh
```

### Test catalog

29 unit test files covering:

- **Auth:** PasswordPolicy, LockoutPolicy, PasswordHasher, Authorizer,
  AuthRepository integration, BiometricLogin, AuthorizationGate,
  BootstrapCredential, BootstrapIntegration
- **Catalog:** IsbnValidator, BarcodeValidator, RecordValidator,
  SimilarityAlgorithm, CoverImageImport
- **State machines:** all 8 lifecycle state machines from PRD section 10
- **Orchestration:** JobOrdering, JobSchedulerRetry, BundleImporter,
  SourceIngestionPipeline, WorkerIntegration
- **Observability:** ObservabilityPipeline (anomaly + QueryTimer emission),
  AlertPolicy, AuditChain, AuditFilter
- **Performance:** RoomQueryPerf (CPU-bound), RoomQueryScaleUnit (1M-row
  Robolectric benchmark), ImageDecoder
- **Quality:** QualityScore

## Seeded Credentials

The app generates credentials dynamically on first launch. There are no
hard-coded passwords — each install gets a unique bootstrap secret.

| Role | Username | Password | Notes |
| :--- | :--- | :--- | :--- |
| **Admin** | `admin` | Shown once at first launch | Generated via `SecureRandom` (20 chars, upper/lower/digit/special). Must be changed immediately — account starts in `password_reset_required` status. |

Additional roles (collection_manager, cataloger, auditor) are created by the
admin after initial setup via the Admin screen. Each role maps to a predefined
set of capabilities per PRD section 11.

The bootstrap password is stored in Keystore-backed encrypted storage
(`EncryptedSharedPreferences` with AES-256) until acknowledged. After
acknowledgement, it is permanently removed from both encrypted storage and
in-memory state. The plaintext is never written to the audit log — only a
SHA-256 confirmation marker is recorded.

See `ASSUMPTIONS.md` for documented implementation decisions.
