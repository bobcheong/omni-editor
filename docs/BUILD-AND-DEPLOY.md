# Building and installing Omni Editor

An Android build needs three things: a JDK 17, the Android SDK, and Gradle. Nothing else —
Android Studio is convenient but never required.

---

## Option A — Linux machine, command line (recommended)

Best fit for Claude Code: headless, scriptable, no IDE.

```bash
# 1. JDK 17
sudo apt update && sudo apt install -y openjdk-17-jdk unzip wget
java -version                                  # expect 17.x

# 2. Android SDK command-line tools
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip && mv cmdline-tools latest && rm commandlinetools-linux-*.zip

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
# Persist both exports in ~/.bashrc.

# 3. SDK packages (match compileSdk in app/build.gradle.kts)
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# 4. Gradle wrapper — one time only, see gradle/wrapper/README.md
sudo apt install -y gradle && gradle wrapper --gradle-version 8.11.1

# 5. Build
cd /path/to/omni-editor
./gradlew assembleDirectDebug
# → app/build/outputs/apk/direct/debug/app-direct-debug.apk
```

Disk: roughly 6–8 GB for the SDK and Gradle caches. RAM: 8 GB works, 16 GB is comfortable
for Compose and KSP. First build downloads a lot and takes several minutes; later builds
are seconds.

Add an emulator only if you want Tier 3 tests (see `adr/001-test-environment.md`):

```bash
sdkmanager "system-images;android-35;google_apis;x86_64" "emulator"
avdmanager create avd -n omni -k "system-images;android-35;google_apis;x86_64"
emulator -avd omni -no-window -gpu swiftshader_indirect &
```
An emulator needs KVM. On a VPS without nested virtualisation it will not start — use a
physical device instead.

---

## Option B — Android Studio (Windows, macOS, Linux)

Installs the JDK, SDK, emulator and wrapper for you. Open the project folder, let it sync,
press Run. Best when you want to look at the UI while it is being built; unnecessary if
Claude Code is doing the work.

---

## Option C — No build machine at all: build in CI

`.github/workflows/release.yml` produces a signed APK on GitHub's runners. Push, wait a
few minutes, download the artifact on your phone, tap it, install. This is a genuinely
complete path — you never install an SDK anywhere.

One-time setup:

```bash
# Generate the release key. Back this file up. Losing it means users must uninstall
# before they can update (DIST-4).
keytool -genkeypair -v -keystore omni-release.jks -alias omni \
        -keyalg RSA -keysize 4096 -validity 10000

base64 -w0 omni-release.jks > keystore.b64     # macOS: base64 -i omni-release.jks
```

Add four repository secrets: `OMNI_KEYSTORE_BASE64` (contents of `keystore.b64`),
`OMNI_KEYSTORE_PASSWORD`, `OMNI_KEY_ALIAS`, `OMNI_KEY_PASSWORD`. Then run the workflow
from the Actions tab, or push a `v0.1.0` tag.

Trade-off: a 5–10 minute round trip per build, which is fine for releases and painful for
development. Best used alongside Option A, not instead of it.

---

## Option D — Building on the phone itself

Possible with Termux, but not recommended for this project.

```bash
pkg install openjdk-17 gradle android-tools
```

What breaks: `aapt2` and the other build-tools binaries Google ships are compiled for
x86_64 Linux and will not run on Android's ARM userspace. Termux community repositories
carry rebuilt versions, and Gradle can be pointed at them with
`-Pandroid.aapt2FromMavenOverride=`, but the setup is fragile and breaks on AGP upgrades.
Layered on top of that, this project uses KSP and Hilt annotation processing plus the
Compose compiler — the heaviest parts of a Kotlin build. Expect very long build times,
frequent OOM kills, and time spent fighting the toolchain rather than writing the app.

If the goal is *working from a phone* rather than *compiling on a phone*, do this instead:
run Claude Code on a Linux box or VPS and reach it from the phone over SSH (Termux) or
via the Claude mobile app's remote sessions. You get a real build machine and a phone-sized
interface, which is the actual requirement.

---

## Installing on a device

The `direct` flavour needs `MANAGE_EXTERNAL_STORAGE`, which is granted through a system
settings screen after install — the app opens it for you at first file access.

**Over USB**

```bash
# Enable Developer options (tap Build number 7 times) → USB debugging
adb devices                       # confirm the device appears
adb install -r app/build/outputs/apk/direct/debug/app-direct-debug.apk
adb logcat -s OmniEditor          # watch logs
```

**Wirelessly** (Android 11+, so every supported device)

```bash
# On the phone: Developer options → Wireless debugging → Pair device with pairing code
adb pair 192.168.1.50:41234       # enter the six-digit code
adb connect 192.168.1.50:5555
adb install -r app-direct-debug.apk
```

**By hand, no computer**
Transfer the APK to the phone however you like, open it in Files, allow "install unknown
apps" for that app when prompted, install.

**Updating** — `adb install -r` replaces in place and keeps data, but only if the new APK
is signed with the same key. A signature mismatch fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` and the only fix is uninstalling, which destroys
saved sessions. This is why DIST-4 exists.

---

## Recommended setup

| You have | Do this |
|---|---|
| A Linux machine or VPS | Option A. Claude Code builds and tests locally; fastest loop. |
| Linux box plus a physical Android device | Option A with wireless adb. This is the ideal setup — real hardware for Tier 3 and Tier 4. |
| Only a phone | Option C. Claude Code pushes, CI builds, you sideload the artifact. |
| A Mac or Windows machine | Option B, or Option A under WSL2 on Windows. |

Whichever you choose, record it in `docs/adr/001-test-environment.md` so the build plan's
acceptance criteria can be gated honestly.
