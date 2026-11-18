#!/usr/bin/env bash
# Compile go-librespot (Spotify Connect) pour le robot : Android 6, ARMv7, binaire PIE.
# Le binaire est rangé comme bibliothèque native (lib….so) pour être extrait par Android
# dans le dossier des bibliothèques de Toque, d'où il peut être exécuté.
set -euxo pipefail
VERSION="v0.10.7"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/armeabi-v7a/libgolibrespot.so"
if [ -s "$OUT" ]; then echo "déjà compilé"; exit 0; fi
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"  # NDK 27 (le 30 refuse la config Android de vcpkg)
export ANDROID_NDK_HOME="$NDK"
echo "NDK=$NDK"; ls "$NDK" | head; env | grep -i ndk || true
W="${RUNNER_TEMP:-/tmp}/glr"
rm -rf "$W"; mkdir -p "$W"; cd "$W"
git clone --depth 1 --branch "$VERSION" https://github.com/devgianlu/go-librespot src
git clone https://github.com/microsoft/vcpkg vcpkg
./vcpkg/bootstrap-vcpkg.sh -disableMetrics
cd src
# Bibliothèques de décodage (ogg/vorbis, flac, mpg123) en statique pour Android ARM ; pas d'ALSA sur Android.
"$W/vcpkg/vcpkg" install --triplet arm-neon-android --x-install-root="$W/inst" || {
  for f in "$W"/vcpkg/buildtrees/*/*.log; do echo "=== $f"; tail -n 40 "$f"; done
  exit 1
}
P="$W/inst/arm-neon-android"
ls "$P/lib" "$P/lib/pkgconfig"
export CGO_ENABLED=1 GOOS=android GOARCH=arm GOARM=7
export CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi23-clang"
export PKG_CONFIG_PATH="$P/lib/pkgconfig" PKG_CONFIG_LIBDIR="$P/lib/pkgconfig"
# vcpkg compile les bibliothèques pour une API Android récente : trois fonctions de la libc
# n'existent qu'à partir d'Android 7. Équivalents simples, liés dans le binaire (Android 6).
cat > "$W/compat.c" <<'C'
#include <stdio.h>
#include <unistd.h>
__attribute__((weak)) int fseeko64(FILE *f, long long off, int whence) { return fseek(f, (long) off, whence); }
__attribute__((weak)) long long ftello64(FILE *f) { return (long long) ftell(f); }
__attribute__((weak)) ssize_t __write_chk(int fd, const void *buf, size_t count, size_t buf_size) { (void) buf_size; return write(fd, buf, count); }
C
"$CC" -c -fPIC -O2 -o "$W/compat.o" "$W/compat.c"
export CGO_CFLAGS="-I$P/include" CGO_LDFLAGS="-L$P/lib -lm $W/compat.o"
mkdir -p "$(dirname "$OUT")"
go build -trimpath -buildmode=pie -ldflags "-s -w -X github.com/devgianlu/go-librespot.version=${VERSION#v}" -o "$OUT" ./cmd/daemon
file "$OUT" || true
ls -l "$OUT"
