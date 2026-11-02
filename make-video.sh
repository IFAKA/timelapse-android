#!/bin/bash
# Pull timelapse photos from the phone and build an mp4 (24 fps, 1920 wide).
set -u
cd "$(dirname "$0")"
export ANDROID_SERIAL="${ANDROID_SERIAL:-R28M21SJPJV}"
mkdir -p out
LOG=out/make-video.log
D="out/$(date +%Y%m%d_%H%M%S)"
mkdir "$D" || exit 1
{
  echo "pull -> $D"
  adb shell ls /sdcard/Pictures/Timelapse/ | tr -d '\r' | grep '^tl_.*\.jpg$' | while read -r f; do
    adb pull "/sdcard/Pictures/Timelapse/$f" "$D/" >/dev/null
  done
} >>"$LOG" 2>&1
N=$(ls "$D"/*.jpg 2>/dev/null | wc -l | tr -d ' ')
if [ "$N" = 0 ]; then echo "no photos pulled (see $LOG)"; exit 1; fi
if ! command -v ffmpeg >/dev/null; then echo "$N photos in $D; ffmpeg missing: brew install ffmpeg"; exit 1; fi
if ffmpeg -y -framerate 24 -pattern_type glob -i "$D/*.jpg" -vf scale=1920:-2 -c:v libx264 -pix_fmt yuv420p "$D/timelapse.mp4" >>"$LOG" 2>&1; then
  echo "OK $N photos -> $D/timelapse.mp4"
else
  echo "ffmpeg failed (see $LOG)"; exit 1
fi
