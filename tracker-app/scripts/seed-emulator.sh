#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
adb_cmd="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
if [ "$($adb_cmd shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]; then
    echo 'A single connected Android emulator is required.' >&2
    exit 1
fi
"$adb_cmd" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_cmd" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb_cmd" shell am instrument -w -e class com.utbildning.tracker.ui.EmulatorDemoSeedTest -e seedDemo true -e seedToday true com.utbildning.tracker.test/androidx.test.runner.AndroidJUnitRunner
"$adb_cmd" shell am start -W -n com.utbildning.tracker/.MainActivity
