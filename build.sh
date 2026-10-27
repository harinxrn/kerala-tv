#!/bin/bash
# Manual APK build (no Gradle): aapt2 -> javac/ECJ -> d8 -> zipalign -> apksigner
#
# Portable paths - everything machine-specific can be overridden from the
# environment, and the defaults work on a normal development machine:
#   ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK location (default ~/android-sdk)
#   JAVA_HOME                        JDK location (default: java/javac on PATH)
#   ECJ_JAR                          Eclipse compiler jar, used only when the
#                                    JDK has no javac (default ~/workspace/build-tools/ecj.jar)
#   KEYSTORE                         Signing keystore. Default: the project's
#                                    historical debug keystore if it is present
#                                    (keeps existing installs updatable), else
#                                    ./debug.keystore, generated on first build.
# Note: Android only installs an update when the signature matches the
# installed app, so a build signed with a fresh keystore will NOT update an
# install signed with a different key - uninstall once in that case.
set -e

# --- locate the Android SDK ---
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
if [ ! -d "$SDK" ]; then
  echo "Android SDK not found at $SDK - set ANDROID_HOME to your SDK location." >&2
  exit 1
fi
# Prefer build-tools 34.0.0; otherwise the newest installed.
BT="$SDK/build-tools/34.0.0"
if [ ! -d "$BT" ]; then
  BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
  BT="${BT%/}"
fi
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
  echo "Android platform 34 not found under $SDK/platforms - install it first (sdkmanager \"platforms;android-34\")." >&2
  exit 1
fi
export PATH="$BT:$PATH"

# --- locate Java ---
JAVAC=""
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
  [ -x "$JAVA_HOME/bin/javac" ] && JAVAC="$JAVA_HOME/bin/javac"
else
  JAVA="$(command -v java || true)"
  JAVAC="$(command -v javac || true)"
fi
if [ -z "$JAVA" ] && [ -x "$HOME/workspace/build-tools/jdk-17.0.20.1+1-jre/bin/java" ]; then
  # Last resort used by the original build machine: a JRE with no javac,
  # which is what the ECJ fallback below exists for.
  JAVA="$HOME/workspace/build-tools/jdk-17.0.20.1+1-jre/bin/java"
  JAVAC=""
fi
if [ -z "$JAVA" ]; then
  echo "No java found - install a JDK 17 and/or set JAVA_HOME." >&2
  exit 1
fi
ECJ_JAR="${ECJ_JAR:-$HOME/workspace/build-tools/ecj.jar}"
# The SDK tools (d8 in particular) shell out to `java` - make sure the one
# we just located is the one they find.
export PATH="$(dirname "$JAVA"):$PATH"

# --- signing keystore ---
PROJ="$(cd "$(dirname "$0")" && pwd)"
if [ -n "$KEYSTORE" ]; then
  KS="$KEYSTORE"
elif [ -f "$HOME/workspace/instacount/build-tmp/debug.keystore" ]; then
  # Historical default on the original build machine: every shipped build
  # was signed with this key, so keeping it means installs update in place.
  KS="$HOME/workspace/instacount/build-tmp/debug.keystore"
else
  KS="$PROJ/debug.keystore"
  if [ ! -f "$KS" ]; then
    echo "No keystore found - generating a debug keystore at $KS"
    echo "NOTE: builds signed with it will not update installs signed with another key."
    "$(dirname "$JAVA")/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null
  fi
fi

B="$PROJ/build"
# Launcher images are generated artifacts (not committed); regenerate if missing.
if [ ! -f "$PROJ/app/src/main/res/drawable/banner.png" ]; then
  python3 "$PROJ/assets-img/make_img.py"
fi
rm -rf "$B" && mkdir -p "$B/compiled" "$B/classes" "$B/dex" "$B/gen"
aapt2 compile --dir "$PROJ/app/src/main/res" -o "$B/compiled/"
sed 's|<manifest xmlns|<manifest package="com.hari.indiatv" xmlns|' "$PROJ/app/src/main/AndroidManifest.xml" > "$B/AndroidManifest.xml"
aapt2 link -o "$B/base.apk" -I "$ANDROID_JAR" --manifest "$B/AndroidManifest.xml" \
  --min-sdk-version 21 --target-sdk-version 34 --version-code 16 --version-name "2.6.1" \
  --java "$B/gen" "$B"/compiled/*.flat
if [ -n "$JAVAC" ]; then
  "$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$ANDROID_JAR" -d "$B/classes" \
    "$PROJ"/app/src/main/java/com/hari/indiatv/*.java "$B/gen/com/hari/indiatv/R.java"
elif [ -f "$ECJ_JAR" ]; then
  "$JAVA" -jar "$ECJ_JAR" -source 1.8 -target 1.8 -nowarn -cp "$ANDROID_JAR" -d "$B/classes" \
    "$PROJ"/app/src/main/java/com/hari/indiatv/*.java "$B/gen/com/hari/indiatv/R.java"
else
  echo "No javac and no ECJ jar at $ECJ_JAR - install a full JDK 17 or set ECJ_JAR." >&2
  exit 1
fi
d8 --min-api 21 --lib "$ANDROID_JAR" --output "$B/dex" $(find "$B/classes" -name "*.class" | tr '\n' ' ')
(cd "$B/dex" && aapt add ../base.apk classes.dex >/dev/null)
zipalign -f -p 4 "$B/base.apk" "$B/india-tv-aligned.apk"
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$B/KeralaTv-v2.6.1.apk" "$B/india-tv-aligned.apk"
apksigner verify "$B/KeralaTv-v2.6.1.apk" && echo "OK: $B/KeralaTv-v2.6.1.apk"
