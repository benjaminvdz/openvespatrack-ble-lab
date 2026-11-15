#!/bin/sh
# Keep real UI captures available with device-test reports, including failed runs.
set -eu
result=0
./gradlew --no-daemon :app:connectedDebugAndroidTest || result=$?
mkdir -p app/build/reports/screenshots
adb pull /sdcard/Android/data/org.anthrobios.vespasim/files/screenshots/. app/build/reports/screenshots/ || true
exit "$result"
