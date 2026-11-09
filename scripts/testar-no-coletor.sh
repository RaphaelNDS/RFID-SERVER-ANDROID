#!/usr/bin/env bash
# Testes no próprio coletor (androidTest), SÓ no aparelho indicado.
#
# Não use "./gradlew connectedDebugAndroidTest" com mais de um aparelho no adb: o Gradle instala e roda em TODOS os
# aparelhos que o adb enxerga (a variável ANDROID_SERIAL não é respeitada). Em 09/11/2026 os testes rodaram num
# tablet da rede que não era o coletor.
#
# Uso: scripts/testar-no-coletor.sh [classe do teste] [--fotos]
#   COLETOR=<serial do adb> (padrão: o mc3300-01)
#   --fotos: puxa as fotos das telas para build/fotos-coletor antes de desinstalar
set -euo pipefail
cd "$(dirname "$0")/.."

ADB="${ADB:-/c/Users/Dev/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
export JAVA_HOME="${JAVA_HOME:-/c/Users/Dev/.jdks/ms-17.0.20}"
COLETOR="${COLETOR:-adb-22265523024202-VImrNj._adb-tls-connect._tcp}"
CLASSE="${1:-}"
FOTOS="${2:-}"
[ "$CLASSE" = "--fotos" ] && { CLASSE=""; FOTOS="--fotos"; }
APP=br.com.bkpbr.rfidandroid.teste
TESTE=$APP.test

./gradlew assembleDebug assembleDebugAndroidTest -q

"$ADB" -s "$COLETOR" shell "input keyevent KEYCODE_WAKEUP; sleep 1; wm dismiss-keyguard; rm -rf /sdcard/Android/data/$APP/files/telas" || true
"$ADB" -s "$COLETOR" install -r -t app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$COLETOR" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

ARGS=()
[ -n "$CLASSE" ] && ARGS=(-e class "$CLASSE")
set +e
"$ADB" -s "$COLETOR" shell am instrument -w "${ARGS[@]}" "$TESTE/androidx.test.runner.AndroidJUnitRunner" | tee build/resultado-coletor.txt
set -e

if [ "$FOTOS" = "--fotos" ]; then
  mkdir -p build/fotos-coletor
  MSYS_NO_PATHCONV=1 "$ADB" -s "$COLETOR" pull "/sdcard/Android/data/$APP/files/telas/." build/fotos-coletor/ || true
fi

"$ADB" -s "$COLETOR" uninstall "$TESTE" >/dev/null || true
"$ADB" -s "$COLETOR" uninstall "$APP" >/dev/null || true

grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" build/resultado-coletor.txt && { echo "FALHOU no coletor"; exit 1; }
# Só é OK com o resumo "OK (N tests)" do JUnit: coletor que caiu do adb no meio não deixa resumo nenhum
grep -q "^OK (" build/resultado-coletor.txt || { echo "FALHOU: os testes não rodaram até o fim no coletor (caiu do adb?)"; exit 1; }
echo "OK no coletor"
