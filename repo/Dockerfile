FROM eclipse-temurin:17-jdk AS builder

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:/opt/gradle-8.5/bin"

RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Install Gradle 8.5
RUN wget -q "https://services.gradle.org/distributions/gradle-8.5-bin.zip" -O /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    rm /tmp/gradle.zip && \
    gradle --version

# Generate Gradle wrapper jar in an empty temp project (avoids classpath resolution)
RUN mkdir -p /tmp/wrapper-gen && \
    cd /tmp/wrapper-gen && \
    touch settings.gradle && \
    gradle wrapper --gradle-version 8.5 --no-daemon && \
    ls -la gradle/wrapper/gradle-wrapper.jar

# Download and install Android command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept licenses and install required SDK packages
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    "platform-tools"

WORKDIR /app

# Copy the generated wrapper jar & properties
RUN mkdir -p gradle/wrapper
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
RUN cp /tmp/wrapper-gen/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar
COPY gradlew ./
RUN chmod +x gradlew

# Copy build configs for dependency caching
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY app/build.gradle.kts app/build.gradle.kts
COPY app/proguard-rules.pro app/proguard-rules.pro

# Create minimal source structure for dependency resolution
RUN mkdir -p app/src/main/java/com/eaglepoint/libops && \
    mkdir -p app/src/main/res/values
COPY app/src/main/AndroidManifest.xml app/src/main/AndroidManifest.xml
COPY app/src/main/res/values/strings.xml app/src/main/res/values/strings.xml
COPY app/src/main/res/values/themes.xml app/src/main/res/values/themes.xml

# Pre-download dependencies (best-effort; continue on failure so source-dependent tasks don't block)
RUN ./gradlew dependencies --no-daemon 2>/dev/null || true

# Copy full source
COPY . .

# Overwrite wrapper jar and gradlew with known-good versions from the temp project
RUN cp /tmp/wrapper-gen/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar && \
    cp /tmp/wrapper-gen/gradlew ./gradlew && \
    chmod +x gradlew

# Generate local.properties from ANDROID_HOME
RUN echo "sdk.dir=${ANDROID_HOME}" > local.properties

# Build the debug APK
RUN ./gradlew assembleDebug --no-daemon --stacktrace -Dkotlin.compiler.execution.strategy=in-process

# At runtime, copy the APK to the mounted /output volume
CMD cp app/build/outputs/apk/debug/app-debug.apk /output/libops-debug.apk && \
    echo "APK copied to /output/libops-debug.apk"

# ─────────────────────────────────────────────
# Stage for running instrumented tests with an embedded emulator.
# Usage: docker build --target instrumented -t libops-instrumented .
#        docker run --privileged libops-instrumented
# ─────────────────────────────────────────────
FROM builder AS instrumented

# Install emulator dependencies (KVM not available in most CI — use swiftshader)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libpulse0 libgl1 libnss3 libxcomposite1 libxcursor1 libxi6 \
    libxtst6 libasound2t64 procps \
    && rm -rf /var/lib/apt/lists/*

# Install emulator + system image
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager \
    "emulator" \
    "system-images;android-34;default;x86_64"

# Create AVD
RUN echo "no" | avdmanager create avd -n libops_test -k "system-images;android-34;default;x86_64" --force

# Script that boots emulator, waits, runs tests, then shuts down
RUN echo '#!/bin/bash\n\
set -e\n\
echo "Starting emulator..."\n\
$ANDROID_HOME/emulator/emulator -avd libops_test -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -memory 2048 &\n\
EMULATOR_PID=$!\n\
echo "Waiting for emulator boot..."\n\
WAIT=0\n\
while [ $WAIT -lt 180 ]; do\n\
  if adb shell getprop sys.boot_completed 2>/dev/null | grep -q "1"; then\n\
    echo "Emulator booted in ${WAIT}s"\n\
    break\n\
  fi\n\
  sleep 5\n\
  WAIT=$((WAIT + 5))\n\
done\n\
if [ $WAIT -ge 180 ]; then\n\
  echo "Emulator did not boot within 180s"\n\
  kill $EMULATOR_PID 2>/dev/null || true\n\
  exit 1\n\
fi\n\
echo "Running instrumented benchmark..."\n\
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace \
  -Pandroid.testInstrumentationRunnerArguments.class=com.eaglepoint.libops.tests.RoomQueryScaleTest\n\
TEST_EXIT=$?\n\
kill $EMULATOR_PID 2>/dev/null || true\n\
wait $EMULATOR_PID 2>/dev/null || true\n\
exit $TEST_EXIT' > /app/run_instrumented.sh && chmod +x /app/run_instrumented.sh

CMD ["/app/run_instrumented.sh"]
