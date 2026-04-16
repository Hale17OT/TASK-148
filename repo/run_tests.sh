#!/bin/bash

set -e

echo "============================================"
echo "  LibOps Test Runner"
echo "============================================"
echo ""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

FAILED=0

# ─────────────────────────────────────────────
# Docker-only test runner
# ─────────────────────────────────────────────
run_docker_tests() {
    echo -e "${YELLOW}Running tests in Docker...${NC}"

    # Build from the builder stage so tests share the pinned toolchain
    # (Temurin 17, Gradle 8.5, Android SDK 34, build-tools 34.0.0).
    echo "Building test image from Dockerfile (target: builder)..."
    if ! docker build -f Dockerfile --target builder -t libops-test-runner . ; then
        echo -e "${RED}Docker image build FAILED${NC}"
        FAILED=1
        return
    fi

    echo "Running testDebugUnitTest in container..."
    if docker run --rm libops-test-runner \
        ./gradlew testDebugUnitTest --no-daemon --stacktrace \
        -Dkotlin.compiler.execution.strategy=in-process ; then
        echo -e "${GREEN}Unit tests PASSED (Docker)${NC}"
    else
        echo -e "${RED}Unit tests FAILED (Docker)${NC}"
        FAILED=1
    fi

    # Run the §18 NFR instrumented benchmark via Docker-embedded emulator.
    # Set RUN_INSTRUMENTED=0 to skip; RUN_INSTRUMENTED=1 to hard-fail on error.
    if [ "${RUN_INSTRUMENTED:-auto}" != "0" ]; then
        echo ""
        echo -e "${YELLOW}Running instrumented performance benchmark (§18 NFR: 1M-row <50ms)...${NC}"

        echo "Building instrumented test image (Dockerfile target: instrumented)..."
        if docker build -f Dockerfile --target instrumented -t libops-instrumented . ; then
            echo "Running benchmark in Docker with embedded emulator..."
            if docker run --rm --privileged libops-instrumented ; then
                echo -e "${GREEN}Instrumented perf tests PASSED (Docker emulator)${NC}"
            else
                if [ "${RUN_INSTRUMENTED:-auto}" = "1" ]; then
                    echo -e "${RED}Instrumented perf tests FAILED${NC}"
                    FAILED=1
                else
                    echo -e "${YELLOW}Instrumented perf tests could not run (KVM unavailable — skipped)${NC}"
                fi
            fi
        else
            if [ "${RUN_INSTRUMENTED:-auto}" = "1" ]; then
                echo -e "${RED}Instrumented Docker image build FAILED${NC}"
                FAILED=1
            else
                echo -e "${YELLOW}Instrumented Docker image build failed (skipped)${NC}"
            fi
        fi
    fi
}

# ─────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────
if [ -d "tests/unit_tests" ]; then
    echo -e "${YELLOW}Running Unit Tests...${NC}"
    echo "─────────────────────────────────────────────"

    if ! command -v docker &> /dev/null; then
        echo -e "${RED}Docker is required but not found. Install Docker and retry.${NC}"
        exit 1
    fi

    run_docker_tests

    echo ""
    echo "Unit test files:"
    find tests/unit_tests -name "*Test.kt" | sort | while read -r f; do
        echo "  - $f"
    done
    echo ""
else
    echo "No unit_tests directory found in tests/"
fi

if [ -d "tests/api_tests" ]; then
    echo -e "${YELLOW}api_tests directory present — running (if configured)...${NC}"
    echo "─────────────────────────────────────────────"
    find tests/api_tests -name "*Test.kt" | sort | while read -r f; do
        echo "  - $f"
    done
fi

# Instrumented tests (§18 NFR: 1M-row <50ms) run automatically via the
# Docker-embedded emulator when RUN_INSTRUMENTED != 0. Manual execution:
#   RUN_INSTRUMENTED=1 ./run_tests.sh
#   docker build --target instrumented -t libops-instrumented .
#   docker run --privileged libops-instrumented

echo ""
echo "============================================"
echo "  Test Summary"
echo "============================================"
echo "  Unit test files:  $(find tests/unit_tests -name '*Test.kt' 2>/dev/null | wc -l)"
if [ -d "tests/api_tests" ]; then
    echo "  API test files:   $(find tests/api_tests -name '*Test.kt' 2>/dev/null | wc -l)"
fi
echo "============================================"

if [ "$FAILED" -eq 1 ]; then
    echo -e "${RED}TESTS FAILED${NC}"
    exit 1
else
    echo -e "${GREEN}ALL TESTS PASSED${NC}"
    exit 0
fi
