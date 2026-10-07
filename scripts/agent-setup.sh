#!/usr/bin/env bash
# Prepares a fresh Linux container (cloud agent session, CI-like box) to build this project:
#   - Android SDK (command-line tools, platform and build tools matching gradle/libs.versions.toml)
#   - local.properties pointing at it
#   - optional Gradle init script that routes Maven Central through Google's mirror
#
# Idempotent: re-running only installs what is missing. Needs JDK 21 on PATH and network access to
# dl.google.com (SDK), Google Maven, Gradle and Maven Central (or the mirror).
#
# Usage:
#   scripts/agent-setup.sh                 # SDK + local.properties + Central mirror
#   scripts/agent-setup.sh --no-mirror     # keep repo.maven.apache.org as is
#   scripts/agent-setup.sh --design-check  # also Roboto + Python libs for design-check/ (render and compare)
#   ANDROID_HOME=/path scripts/agent-setup.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
USE_MIRROR=1
DESIGN_CHECK=0
for arg in "$@"; do
    case "$arg" in
        --no-mirror) USE_MIRROR=0 ;;
        --design-check) DESIGN_CHECK=1 ;;
    esac
done

log() { printf '[agent-setup] %s\n' "$*"; }

# Read a version from the catalog so the SDK always matches the build.
catalog_version() {
    sed -n "s/^$1 *= *\"\([^\"]*\)\".*/\1/p" "$ROOT/gradle/libs.versions.toml" | head -1
}
COMPILE_SDK="$(catalog_version compileSdk)"
BUILD_TOOLS="${BUILD_TOOLS:-${COMPILE_SDK}.0.0}"

# --- JDK -------------------------------------------------------------------------------------------
java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
if [[ -z "$java_major" || "$java_major" -lt 21 ]]; then
    log "JDK 21+ is required on PATH (found: ${java_major:-none})"; exit 1
fi

# --- Android command-line tools --------------------------------------------------------------------
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
    log "installing Android command-line tools into $ANDROID_HOME"
    tmp="$(mktemp -d)"
    curl -sSfL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q -o "$tmp/tools.zip" -d "$tmp"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp"
fi

# --- SDK packages ----------------------------------------------------------------------------------
packages=("platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS" "platform-tools")
missing=()
for p in "${packages[@]}"; do
    [[ -d "$ANDROID_HOME/${p//;//}" ]] || missing+=("$p")
done
if (( ${#missing[@]} )); then
    log "installing SDK packages: ${missing[*]}"
    yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install "${missing[@]}" >/dev/null
fi

# --- local.properties (git-ignored) ---------------------------------------------------------------
echo "sdk.dir=$ANDROID_HOME" > "$ROOT/local.properties"

# --- Maven Central mirror (user-level, outside the repo) -------------------------------------------
# Shared cloud egress addresses get 429 from repo.maven.apache.org; Google's mirror serves the same files.
INIT="${GRADLE_USER_HOME:-$HOME/.gradle}/init.d/central-mirror.gradle"
if (( USE_MIRROR )); then
    mkdir -p "$(dirname "$INIT")"
    cat > "$INIT" <<'GRADLE'
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def redirect = { RepositoryHandler repos ->
    repos.withType(MavenArtifactRepository).configureEach { r ->
        if (r.url.toString().startsWith('https://repo.maven.apache.org/maven2')) r.url = mirror
    }
}
beforeSettings { s ->
    redirect(s.pluginManagement.repositories)
    redirect(s.dependencyResolutionManagement.repositories)
    s.gradle.allprojects { p -> redirect(p.buildscript.repositories); redirect(p.repositories) }
}
GRADLE
    log "Maven Central mirror: $INIT"
    # Robolectric fetches android-all itself, outside Gradle's repositories (used by -Ppf.designcheck).
    PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
    touch "$PROPS"
    grep -q '^pf.robolectric.repo=' "$PROPS" || echo 'pf.robolectric.repo=https://maven-central.storage-download.googleapis.com/maven2' >> "$PROPS"
else
    rm -f "$INIT"
fi

# --- design check: Roboto for the mockup renderer, Python libs for the comparison -----------------------
if (( DESIGN_CHECK )); then
    if [[ "$(fc-match -f "%{family}" Roboto 2>/dev/null)" != *Roboto* ]]; then
        log "installing Roboto (google/fonts, OFL) into ~/.fonts"
        mkdir -p "$HOME/.fonts"
        curl -sSfL -o "$HOME/.fonts/Roboto-wght.ttf" \
            "https://raw.githubusercontent.com/google/fonts/main/ofl/roboto/Roboto%5Bwdth%2Cwght%5D.ttf"
        fc-cache -f >/dev/null 2>&1 || true
    fi
    python3 -c "import PIL, skimage" 2>/dev/null || pip install -q --break-system-packages pillow scikit-image
    node -e "require('playwright')" 2>/dev/null || NODE_PATH="$(npm root -g)" node -e "require('playwright')" \
        || log "playwright for Node is missing: npm i -g playwright (Chromium must be installed for it)"
fi

log "ready: ANDROID_HOME=$ANDROID_HOME (android-$COMPILE_SDK, build-tools $BUILD_TOOLS)"
log "next: ANDROID_HOME=$ANDROID_HOME ./gradlew assembleMockDebug testMockDebugUnitTest"
