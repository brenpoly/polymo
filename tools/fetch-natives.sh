#!/bin/sh
# Fetch the native sources the Android build links against.
#
# They are NOT in this repository on purpose: they are ~1.3 GB of unmodified
# third-party code, several of them git clones whose own .git would become a
# broken gitlink here. What this repo keeps instead is the exact commit each
# one was built from, so a build can be reproduced rather than approximated.
#
# WHAT THIS SCRIPT DOES NOT DO, stated first because a setup script that
# overstates itself wastes more time than one that does nothing:
#
#   * It fetches SOURCES only. Two prebuilt shared libraries still have to be
#     obtained separately and placed in android/app/src/main/jniLibs/arm64-v8a/:
#     libonnxruntime.so (from an ONNX Runtime Android release) and
#     libespeak-ng.so (built from the espeak-ng source this script fetches).
#     android/app/src/main/cpp/CMakeLists.txt imports both as SHARED IMPORTED.
#
#   * Three dependencies -- fmt, spdlog and piper-phonemize -- have NO RECORDED
#     VERSION. They were obtained without leaving a tag or commit behind. This
#     script fetches their default branch, which is very unlikely to match what
#     the released APK was built from. See NOTICE.
#
#   * IT HAS NOT BEEN RUN END TO END. The commits below were read off the
#     working clones it is meant to reproduce, so they are correct; that a
#     fresh fetch then compiles is untested. Treat a first run as the test.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

pin() {  # pin <dir> <url> <commit>
  if [ -d "$1/.git" ]; then
    echo "  $1 already present - leaving it alone"
    return
  fi
  echo "  fetching $1 @ $3"
  git clone --quiet "$2" "$ROOT/$1"
  git -C "$ROOT/$1" checkout --quiet "$3"
}

echo "Pinned sources:"
pin llama.cpp        https://github.com/ggerganov/llama.cpp.git  bec4772f6a25
pin whisper.cpp      https://github.com/ggerganov/whisper.cpp.git 6fc7c33b4c3a
pin piper            https://github.com/rhasspy/piper.git         73c04d81d559
pin deps/opus        https://github.com/xiph/opus.git             3da9f7a6db1c
pin deps/espeak-ng-src https://github.com/espeak-ng/espeak-ng.git fbe4b3764285

echo
echo "UNPINNED - default branch, will not match the released build:"
for u in "deps/fmt https://github.com/fmtlib/fmt.git" \
         "deps/spdlog https://github.com/gabime/spdlog.git" \
         "deps/piper-phonemize https://github.com/rhasspy/piper-phonemize.git"; do
  set -- $u
  [ -d "$ROOT/$1" ] && { echo "  $1 already present"; continue; }
  echo "  fetching $1 (UNPINNED)"
  git clone --quiet "$2" "$ROOT/$1"
done

cat <<'DONE'

Sources fetched. Still required before the native build will link:
  android/app/src/main/jniLibs/arm64-v8a/libonnxruntime.so
  android/app/src/main/jniLibs/arm64-v8a/libespeak-ng.so
DONE
