# Timelapse (Samsung S10)

Put the phone on a stand. It takes photos on its own, even with the screen locked, and builds a video.

## Screens
- **Library**: every timelapse, newest first. Tap one to scrub the photos, make the video, play, share, rename or delete.
- **New timelapse**: pick how long (1 to 12 h, or until a clock time) and how long the video should be. The photo interval is calculated.
  Photos: *Auto* skips frames that are too dark to see anything (a dim room lit by a laptop still counts). *Always* never skips.
- **Every day**: set a start time, an end time and a video length. The phone records and builds the video each day by itself.
  Old timelapses are deleted after 7, 14 or 30 days.

## Control from your computer
The Library shows a link like `http://192.168.1.50:8080/?k=123456` (same Wi-Fi). Tap it to copy. The page can start and stop,
set the daily plan, watch and download videos, rename and delete. The 6-digit key in the link is the password.

## How it works
- Interval = video seconds x 24 fps, spread over the run length (min 3 s, max 300 s).
- Alarms start the runs (`setAlarmClock`, opens an invisible `RunActivity`). Android only gives the camera to an app with a
  screen, so this is needed. It needs two permissions: **Alarms & reminders** and **Display over other apps**
  (the app asks when you turn on Every day).
- Photos: `Pictures/Timelapse/<session>/`. Videos: `Movies/Timelapse/<session>.mp4`.

## Build and install
```
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.faka.timelapse SCHEDULE_EXACT_ALARM allow
adb shell appops set com.faka.timelapse SYSTEM_ALERT_WINDOW allow
```
Test start from adb: `adb shell am start -n com.faka.timelapse/.MainActivity --ez autostart true --ei minutes 2 --ei interval 3 [--ez always true]`
