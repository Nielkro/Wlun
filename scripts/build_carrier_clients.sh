#!/usr/bin/env bash
set -euo pipefail
ABI=${1:?Android ABI is required}
ROOT=$(pwd)
REF=$(sed -n 's/^THRONE_CORE_REF=//p' nb4a.properties | tr -d '\r[:space:]')
[[ "$REF" =~ ^[0-9a-f]{40}$ ]]
SOURCE=$(mktemp -d "${RUNNER_TEMP:?}/qthrone-carriers.XXXXXX")
OUT="$ROOT/deployment/carriers/$ABI"
mkdir -p "$OUT"
fetch_source() {
  local folder=$1 url=$2 revision=$3
  git init -q "$folder"
  git -C "$folder" fetch -q --depth=1 "$url" "$revision"
  git -C "$folder" checkout -q --detach FETCH_HEAD
  test "$(git -C "$folder" rev-parse HEAD)" = "$revision"
}
fetch_source "$SOURCE/qThrone" https://github.com/ttrroyy/qThrone.git "$REF"
case "$ABI" in
  arm64-v8a) GOARCH=arm64; CLANG=aarch64-linux-android; RUST=aarch64-linux-android ;;
  armeabi-v7a) GOARCH=arm; export GOARM=7; CLANG=armv7a-linux-androideabi; RUST=armv7-linux-androideabi ;;
  x86_64) GOARCH=amd64; CLANG=x86_64-linux-android; RUST=x86_64-linux-android ;;
  *) exit 2 ;;
esac
export GOARCH GOOS=android CGO_ENABLED=1
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/${CLANG}24-clang"
test -x "$CC"
# Only the executable name changes; the desktop's encrypted transport and private IPC stay intact.
python3 - "$SOURCE/qThrone/qwdtt/csqtt_bridge.go" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text(encoding='utf-8')
old='name := "csqtt-transport"'
assert s.count(old)==1
s=s.replace(old, old+'\n\tif runtime.GOOS == "android" { name = "libcsqtt_transport.so" }')
p.write_text(s,encoding='utf-8')
PY
python3 "$ROOT/scripts/prepare_android_captcha.py" "$SOURCE/qThrone/qwdtt"
(cd "$SOURCE/qThrone/qwdtt"; gofmt -w creds.go csqtt_bridge.go; go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libqwdtt.so" .)
cp "$SOURCE/qThrone/qwdtt/LICENSE" "$OUT/qWDTT-LICENSE"
OLC=f3ad8fb7c0d0fa981423f83269526fa94077c23d
fetch_source "$SOURCE/olcrtc" https://github.com/openlibrecommunity/olcrtc.git "$OLC"
mkdir -p "$SOURCE/olcrtc/cmd/qthrone-olcrtc"
cp "$SOURCE/qThrone/script/olcrtc/"*.go "$SOURCE/olcrtc/cmd/qthrone-olcrtc/"
python3 "$ROOT/scripts/secure_carrier_wrappers.py" olcrtc "$SOURCE/olcrtc"
(cd "$SOURCE/olcrtc"; go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libolcrtc.so" ./cmd/qthrone-olcrtc)
cp "$SOURCE/olcrtc/LICENSE" "$OUT/OLCRTC-LICENSE"
FLUX=74cac6d47bf4c27947348ee957538a2c0728a485
fetch_source "$SOURCE/openflux" https://github.com/p1neappleXpress/OpenFlux.git "$FLUX"
cp "$SOURCE/qThrone/script/openflux/"*.go "$SOURCE/openflux/"
python3 "$ROOT/scripts/secure_carrier_wrappers.py" openflux "$SOURCE/openflux"
(cd "$SOURCE/openflux"; go build -trimpath -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$OUT/libopenflux.so" .)
cp "$SOURCE/openflux/LICENSE" "$OUT/OpenFlux-LICENSE"
CSQTT=71712b07dbabe16fa43f5a248dc69e1ab9f3a270
fetch_source "$SOURCE/csqtt" https://github.com/amurcanov/csqtt.git "$CSQTT"
python3 "$SOURCE/qThrone/script/prepare_csqtt.py" "$SOURCE/csqtt"
rustup toolchain install 1.97.1 --profile minimal
rustup target add --toolchain 1.97.1 "$RUST"
cargo +1.97.1 install cargo-ndk --version 4.1.2 --locked --root "$RUNNER_TEMP/qthrone-cargo-tools"
export PATH="$RUNNER_TEMP/qthrone-cargo-tools/bin:$PATH"
export CARGO_TARGET_DIR="$SOURCE/rust-build"
(cd "$SOURCE/csqtt/rust-client"; cargo +1.97.1 ndk -t "$ABI" -P 26 build --release --locked)
cp "$CARGO_TARGET_DIR/$RUST/release/client" "$OUT/libcsqtt_transport.so"
cp "$SOURCE/csqtt/LICENSE" "$OUT/CSQTT-LICENSE"
printf 'qThrone %s\nolcRTC %s\nOpenFlux %s\nCSQTT %s\nAndroid ABI %s\nCSQTT minimum API 26\n' "$REF" "$OLC" "$FLUX" "$CSQTT" "$ABI" > "$OUT/SOURCES.txt"
(cd "$OUT"; sha256sum *.so > SHA256SUMS)
for binary in "$OUT/"*.so; do
  test -s "$binary"
  "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$binary" | grep -q 'Type:.*DYN'
done
