#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

if [[ $# != 3 ]]; then
  echo "Usage: $0 unsigned-directory bundletool.jar output-directory" >&2
  exit 2
fi
unsigned=$1
bundletool=$2
output=$3

: "${BUILD_TOOLS:?Set the Android SDK build-tools directory}"
: "${PF_STORE_KEYSTORE_BASE64:?Configure the signing keystore secret}"
: "${PF_STORE_KEYSTORE_PASSWORD:?Configure the keystore password secret}"
if [[ ! "${PF_STORE_SIGNER_SHA256:-}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Configure PF_STORE_SIGNER_SHA256 as the lowercase FIN-121 certificate digest" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
printf '%s' "$PF_STORE_KEYSTORE_BASE64" | base64 --decode > "$tmp/release.p12"
unset PF_STORE_KEYSTORE_BASE64

apk="plainfin-$PF_VERSION_NAME+$PF_VERSION_CODE-rustore.apk"
aab="plainfin-$PF_VERSION_NAME+$PF_VERSION_CODE-google-play.aab"
release="$tmp/verified"
mkdir "$release"
alias_name=plainfin-android-release
"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$unsigned/unsigned.apk" "$tmp/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$tmp/release.p12" --ks-key-alias "$alias_name" \
  --ks-pass env:PF_STORE_KEYSTORE_PASSWORD --out "$release/$apk" "$tmp/aligned.apk"
jarsigner -keystore "$tmp/release.p12" -storetype PKCS12 -storepass:env PF_STORE_KEYSTORE_PASSWORD \
  -signedjar "$release/$aab" "$unsigned/unsigned.aab" "$alias_name"
unset PF_STORE_KEYSTORE_PASSWORD
rm -f -- "$tmp/release.p12"

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$release/$apk" > "$tmp/apk-signature.txt"
grep -Fxq 'Verified using v2 scheme (APK Signature Scheme v2): true' "$tmp/apk-signature.txt"
apk_signer="$(awk '/^Signer #[0-9]+ certificate SHA-256 digest: /{print $NF}' "$tmp/apk-signature.txt")"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$release/$apk"
jarsigner -J-Duser.language=en -verify -verbose "$release/$aab" > "$tmp/aab-signature.txt"
grep -Fxq 'jar verified.' "$tmp/aab-signature.txt"
if grep -qi 'unsigned entries' "$tmp/aab-signature.txt"; then
  echo "AAB contains unsigned entries" >&2
  exit 1
fi
aab_signer="$(keytool -J-Duser.language=en -printcert -jarfile "$release/$aab" |
  awk '/SHA256:/{print $NF}' | tr -d ':' | tr '[:upper:]' '[:lower:]')"
if [[ "$apk_signer" != "$PF_STORE_SIGNER_SHA256" || "$aab_signer" != "$PF_STORE_SIGNER_SHA256" ]]; then
  echo "The AAB and APK must each be signed with the expected FIN-121 certificate" >&2
  exit 1
fi
echo "AAB and APK signer SHA-256: $apk_signer"

java -jar "$bundletool" validate --bundle="$release/$aab" > "$tmp/bundle-validation.txt"
# A universal APK exposes the bundle's packaged manifest and binary resources to aapt2.
# It is used only for inspection and is never uploaded.
java -jar "$bundletool" build-apks --bundle="$release/$aab" --mode=universal --output="$tmp/inspect.apks"
unzip -p "$tmp/inspect.apks" universal.apk > "$tmp/universal.apk"
python3 scripts/check-store-release.py "$BUILD_TOOLS/aapt2" "$tmp/universal.apk" "$release/$apk"

(cd "$release" && shasum -a 256 "$aab" "$apk" > SHA256SUMS)
mkdir "$output"
cp "$release/$aab" "$release/$apk" "$release/SHA256SUMS" "$output/"
