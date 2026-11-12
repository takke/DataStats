Change Log
==========

v4.1.0 (2026.11.12)
-------------------

- Fix: the meter could disappear by itself and never come back on Android 15+ — when the service was (re)started in the background, `startForeground` was rejected with `ForegroundServiceStartNotAllowedException` and the service crashed on every restart; the rejection is now handled and the service retries after the overlay becomes visible
- Fix: avoid a crash when starting the service at boot / from the notification buttons / from the Quick Settings tile is rejected by the background start restriction (falls back to a normal service start)
- Feature: restart the service automatically after the app is updated (when "start on boot" is enabled)
- UI: show a warning card with a shortcut to the settings on the main screen while battery optimization is still enabled
- UI: onboarding now marks "Ignore battery optimizations" as recommended (was optional) and explains how to turn it off
- Update library
    - Gradle 9.6.1 -> 9.9.1
    - AGP 9.3.1 -> 9.5.1
    - Kotlin 2.4.0 -> 2.4.21
    - AndroidX Core 1.19.0 -> 1.19.1
    - AppCompat 1.7.1 -> 1.8.0
    - Compose BOM 2026.06.01 -> 2026.09.00

v4.0.1 (2026.08.03)
-------------------

- Support Android 16 (targetSdkVersion 35 -> 36)
- Update library
    - AGP 9.2.1 -> 9.3.1

v4.0.0 (2026.07.09)
-------------------

- UI: revamp the settings screen with Jetpack Compose + Material 3 (DayNight, Material You dynamic colors), grouped into Display / Behavior / Startup sections with per-setting descriptions
- Feature: Quick Settings tile to toggle the overlay on/off from the notification shade
- Feature: overlay customization — vertical position (top / bottom edge), opacity slider, background color presets, and display style (text + bar / text only / bar only)
- Feature: "Show only on mobile network" and "Mobile-only measurement" options for users on metered data plans (Wi-Fi / Ethernet / VPN are treated as non-mobile)
- Feature: optional sparkline overlay showing the last ~60 seconds of upload/download traffic on top of the meter (toggle in Display section)
- Feature: optional small network-type badge on the overlay meter (W / M / E / V)
- Feature: optional auto unit scaling — automatically switch to MB/s (Mbps) or GB/s (Gbps) on fast connections instead of showing e.g. "102400.0KB/s"
- Feature: add 5 sec / 10 sec update interval options for battery-conscious users; the interval description now clarifies that the service auto-pauses while the screen is off
- UI: preview area now defaults to a live traffic display showing the current upload/download speed; the classic slider/sample-button injection UI is still available via a toggle
- UI: first-launch onboarding screen explaining why the overlay / notification / battery-optimization settings are needed; the resident service is turned on by default when onboarding completes
- i18n: add Simplified Chinese (zh-rCN), Korean (ko) and Spanish (es) translations
- Fix: hide-when-fullscreen now works reliably on Android 11+ (especially Android 14)
- Fix: overlay X position stayed anchored to the old screen width after rotation; the service now re-applies the WindowManager LayoutParams and invalidates its cached layout on configuration change / overlay resize
- Fix: keep the notification during screen off (previously the foreground notification could not be dismissed by cancel())
- Fix: the notification's "hide temporarily" (10 sec) timer is now cancelled by a Show/Hide tap within the window (previously the overlay could pop back up against an explicit hide) and no longer fires after the service is stopped
- Fix: handle TrafficStats UNSUPPORTED (-1) devices and clamp negative diffs caused by counter reset
- Fix: use SystemClock.elapsedRealtime() for interval measurement to avoid wall-clock changes affecting the speed calculation
- Fix: stop the service immediately when started without overlay permission (previously it stayed resident invisibly when auto-started at boot)
- Fix: use startForegroundService from the notification action receiver to avoid background start restriction exceptions
- Fix: interrupt the gather thread on stop to avoid blocking the main thread up to the update interval (potential ANR)
- Fix: dispatch onScreenOff to the main thread from the gather worker to avoid races on state flags and handler registration
- Fix: prevent ServiceConnection leak when Activity is destroyed before onServiceConnected
- Fix: toggling "Hide when in fullscreen" now takes effect immediately while the settings screen is open
- Fix: changing the text size no longer flashes a bogus preview value for a moment
- Fix: add @Volatile to shared thread flags to fix memory visibility issues
- Perf: remove the legacy AlarmManager keep-alive that woke the service every 60 seconds; recovery after a process kill now relies on the foreground service + START_STICKY, saving battery
- Perf: cache updateWidgetSize inputs to skip layout / getIdentifier calls when nothing changed each second
- Perf: reuse Paint / Matrix / cached Resources values in MySurfaceView.myDrawFrame instead of per-frame allocation
- Perf: replace 3-point Lagrange interpolation with 2-point linear interpolation (removes DoubleArray allocation and overshoot guards)
- Perf: MyLog.d { ... } inline lambda overload skips string concatenation when debug logging is disabled
- Cleanup: migrate the remaining Java sources (Config, C, MyLog, IOUtil, TkConfig, TkConsts) to Kotlin; the project is now 100% Kotlin
- Cleanup: replace all remaining deprecated APIs (Resources.getColor, scaledDensity, status_bar_height lookup, PowerManager.isScreenOn, TYPE_TOAST dead code); text sizing now supports Android 14+ non-linear font scaling
- Refactor: route force-redraw requests through AIDL (ILayerService.forceRedraw) instead of Activity poking the SurfaceView static
- Dev: add unit tests for the speed formatting / bar scaling logic and run them (with lint) on GitHub Actions CI
- compileSdkVersion 36 -> 37
- Java 8 -> 11
- Update library
    - BuildTools 36.0.0 -> 37.0.0
    - Gradle 9.4.0 -> 9.6.1
    - AGP 9.0.1 -> 9.2.1
    - Kotlin 2.3.10 -> 2.4.0
    - AndroidX Core 1.17.0 -> 1.19.0

v3.0.0 (2026.03.11)
-------------------

- Fix blocking system apps
- compileSdkVersion 35 -> 36
- Update library
    - BuildTools 35.0.0 -> 36.0.0
    - Gradle 8.14 -> 9.4.0
    - AGP 8.9.2 -> 9.0.1
    - Kotlin 2.1.20 -> 2.3.10
    - AndroidX Core 1.16.0 -> 1.17.0
    - AppCompat 1.7.0 -> 1.7.1

v2.8.7 (2025.05.07)
-------------------

- Avoid crash when the app is killed by the system on Android 15 or later

v2.8.6 (2025.04.30)
-------------------

- Support Android 15 (targetSdkVersion 34 -> 35)
- Update library
    - Gradle from 8.8 to 8.14
    - Kotlin 2.0.0 -> 2.1.20
    - AndroidX Core from 1.13.1 to 1.16.0
    - AGP 8.5.1 -> 8.9.2
    - BuildTools 34.0.0 -> 35.0.0

v2.8.5 (2024.07.16)
-------------------

- Support Android 14 (targetSdkVersion 33 -> 34)
- Update library
    - Gradle from 8.7 to 8.8
    - AGP from 8.4.1 to 8.5.1
    - AppCompat from 1.6.1 to 1.7.0

v2.8.4 (2024.05.29)
-------------------

- Update library
    - Gradle 8.4 -> 8.7
    - BuildTools 33.0.1 -> 34.0.0
    - AGP from 8.1.2 to 8.3.0
    - Kotlin from 1.9.10 to 2.0.0
    - core-ktx from 1.12.0 to 1.13.1

v2.8.3 (2023.11.07)
-------------------

- Support Android 13
- targetSdkVersion 31 -> 33
- compileSdkVersion 31 -> 34
- minSdkVersion 14 -> 23
- Update library
    - Gradle 7.4.2 -> 8.4
    - AGP 7.2.0 -> 8.1.2
    - Kotlin 1.6.21 -> 1.9.10
    - BuildTools 30.0.3 -> 33.0.1
    - Android X Core 1.3.2 -> 1.12.0
    - AppCompat 1.3.0-beta01 -> 1.6.1
    - Preference 1.1.1 -> 1.2.1

v2.8.2 (2022.05.19)
-------------------

- Support Android 12
- compileSdkVersion 30 -> 31
- targetSdkVersion 30 -> 31

v2.8.1 (2022.05.18)
-------------------

- maxSdkVersion=30 (Android 11)
- Update library
    - AGP 4.2.0-beta04 -> 7.2.0
    - Gradle 6.8.2 -> 7.4.2
    - BuildTools 30.0.2 -> 30.0.3
    - Kotlin 1.4.30 -> 1.6.21

v2.8.0 (2021.02.11)
-------------------

- Change app icon
- Support dark theme
- compileSdkVersion 28 -> 30
- targetSdkVersion 28 -> 30
- Update library
    - Gradle 5.4.1 -> 6.8.2
    - AGP 3.5.2 -> 4.2.0-beta04
    - BuildTools 28.0.3 -> 30.0.2
    - Kotlin 1.3.50 -> 1.4.30
    - Androidx Preference 1.1.1

v2.7.1 (2019.12.22)
-------------------

- Hide notification on lockscreen

v2.7.0 (2019.11.06)
-------------------

- Add timer (hide and resume) button on the notification
- Change notification button text to image
- Fix style of the notification text
- Update library
    - AGP 3.5.1 -> 3.5.2
- Introduce Kotlin

v2.6.0 (2019.10.25)
-------------------

- Fix not start the meter automatically after installation
- Update library
    - Gradle 4.10.1 -> 5.4.1
    - AGP 3.4.0-alpha02 -> 3.5.1
- compileSdkVersion 27 -> 28
- targetSdkVersion 27 -> 28
- Migrate to AndroidX

v2.5.2 (2018.11.12)
-------------------

- Make "auto start" as ON by default
- Distribute with App Bundle
- Update library
    - Gradle 4.1 -> 4.10.1
    - AGP 3.0.1 -> 3.4.0-alpha02
    - BuildTools 27.0.3 -> 28.0.3

v2.5.1 (2018.03.20)
-------------------

- Fix crash on boot (Android 8.0 or later)

v2.5.0 (2018.03.01)
-------------------

- Add show/hide button on the notification bar
- Remove resident setting (always resident)
- Remove notification icon (transparent icon)
- targetSdkVersion 26 -> 27
- Update build tools

v2.4.1 (2017.09.08)
-------------------

- Auto restart when killed by system
- Fix detecting full screen

v2.4.0 (2017.09.05)
-------------------

- Support O
- Support M new permission model (Overlay)
- Fix "service stop" problem
- targetSdkVersion 16 -> 26

v2.3.0 (2016.05.10)
-------------------

- Add resident mode

v2.2.3 (2015.09.18)
-------------------

- Fix to exclude loopback traffics (Android 4.3 or later)

v2.2.2 (2015.05.13)
-------------------

- Improve sleep on/off behavior
- Add restart menu
- Add debug feature (dump logs to internal storage, add WRITE_EXTERNAL_STORAGE permission)

v2.2.1 (2015.05.04)
-------------------

- Fix delay to detect screen off

v2.2.0 (2015.04.15)
-------------------

- Add "Kbps" option
- Fix delaying interval on Android 5.1 devices
- Disable Interpolate Mode when log-bar disabled
- Move "start" and "stop" buttons to ActionBar

v2.1.0 (2015.03.26)
-------------------

- Add text size config

v2.0.1 (2015.03.22)
-------------------

- Save battery life (on interpolation mode)
- Fix some bugs

v2.0.0 (2015.03.20)
-------------------

- Add interpolation mode (Notice decreasing your battery life)
- Improve performance

v1.2.4 (2015.03.03)
-------------------

- Fix screen rotation problem

v1.2.3 (2015.02.19)
-------------------

- Add config to hide bar when in fullscreen

v1.2.2 (2015.02.19)
-------------------

- Hide bar when in fullscreen

v1.2.1 (2015.02.16)
-------------------

- Fix layer problem (ex, unable to touch the install button of APK installer)
- Fix scrolling problem

v1.2.0 (2015.02.14)
-------------------

- Change text color limits
- Others

v1.1.0 (2015.02.12)
-------------------

- Add logarithm bar config
- Add max speed config
- Add auto start on boot feature
- Replace service interface to "bind interface"
- Add ja resource

v1.0.0 (2015.02.10)
-------------------

- Initial release
