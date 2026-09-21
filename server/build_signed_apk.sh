#!/usr/bin/env bash
set -euo pipefail

APP_DIR=/opt/init-media-ai
SIGN_DIR=/opt/init-media-signing
SDK_ROOT=/opt/android-sdk
GRADLE_DIR=/opt/gradle-9.5.0
GRADLE_ZIP=/tmp/gradle-9.5.0-bin.zip
CMDLINE_ZIP=/tmp/cmdline-tools.zip
APK_DEST="$APP_DIR/server/latest.apk"
ENV_FILE="$SIGN_DIR/signing.env"
KEYSTORE="$SIGN_DIR/init-media-release.jks"

apt-get update
apt-get install -y openjdk-17-jdk unzip curl git openssl

mkdir -p "$SIGN_DIR" "$SDK_ROOT/cmdline-tools"

if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  rm -rf "$SDK_ROOT/cmdline-tools/latest" "$SDK_ROOT/cmdline-tools/cmdline-tools"
  curl -fL     https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip     -o "$CMDLINE_ZIP"
  unzip -q -o "$CMDLINE_ZIP" -d "$SDK_ROOT/cmdline-tools"
  mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$SDK_ROOT/build-tools/36.0.0:$PATH"

yes | sdkmanager --licenses >/dev/null || true
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
  curl -fL https://services.gradle.org/distributions/gradle-9.5.0-bin.zip -o "$GRADLE_ZIP"
  unzip -q -o "$GRADLE_ZIP" -d /opt
fi

if [ ! -f "$ENV_FILE" ]; then
  STORE_PASS="$(openssl rand -hex 24)"
  KEY_ALIAS="initmedia"

  # PKCS12 uses the same password for the store and private key.
  keytool -genkeypair     -storetype PKCS12     -keystore "$KEYSTORE"     -storepass "$STORE_PASS"     -keypass "$STORE_PASS"     -alias "$KEY_ALIAS"     -keyalg RSA     -keysize 4096     -validity 10000     -dname "CN=INIT Media AI, OU=Media AI, O=INIT, L=Pamplona, ST=Navarra, C=ES"

  cat > "$ENV_FILE" <<EOF
export INIT_KEYSTORE_PATH=$KEYSTORE
export INIT_KEYSTORE_PASSWORD=$STORE_PASS
export INIT_KEY_ALIAS=$KEY_ALIAS
export INIT_KEY_PASSWORD=$STORE_PASS
EOF

  chmod 600 "$ENV_FILE" "$KEYSTORE"
fi

source "$ENV_FILE"

# Repair the first signing setup created with separate passwords. Java's default
# PKCS12 keystore ignores a distinct -keypass, so the real private-key password
# is the store password. Keep the existing key; only correct the environment.
export INIT_KEY_PASSWORD="$INIT_KEYSTORE_PASSWORD"

cat > "$ENV_FILE" <<EOF
export INIT_KEYSTORE_PATH=$INIT_KEYSTORE_PATH
export INIT_KEYSTORE_PASSWORD=$INIT_KEYSTORE_PASSWORD
export INIT_KEY_ALIAS=$INIT_KEY_ALIAS
export INIT_KEY_PASSWORD=$INIT_KEYSTORE_PASSWORD
EOF
chmod 600 "$ENV_FILE" "$INIT_KEYSTORE_PATH"

echo "=== VERIFY SIGNING KEY ==="
keytool -list   -keystore "$INIT_KEYSTORE_PATH"   -storepass "$INIT_KEYSTORE_PASSWORD"   -alias "$INIT_KEY_ALIAS" >/dev/null
echo "Signing key: OK"

git -C "$APP_DIR" pull --ff-only
cd "$APP_DIR"

"$GRADLE_DIR/bin/gradle" :app:clean :app:assembleRelease --stacktrace

SOURCE_APK="$(find app/build/outputs/apk/release -name '*.apk' -type f | head -1)"
if [ -z "$SOURCE_APK" ]; then
  echo "No release APK produced" >&2
  exit 1
fi

# Verify the final APK before publishing it.
apksigner verify --verbose "$SOURCE_APK"

cp "$SOURCE_APK" "$APK_DEST"
chmod 644 "$APK_DEST"

echo
echo "=== INIT SIGNED APK ==="
ls -lh "$APK_DEST"
sha256sum "$APK_DEST"
echo
echo "Stable signing key stored only on this server:"
echo "$INIT_KEYSTORE_PATH"
