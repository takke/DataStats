Change Log
==========

v2.8.6 (2025.04.30)
-------------------

- Update library
    - Gradle from 8.8 to 8.14
    - Kotlin 2.0.0 -> 2.1.20
    - AndroidX Core from 1.13.1 to 1.16.0
    - AGP 8.5.1 -> 8.9.2
    - BuildTools 34.0.0 -> 35.0.0

v2.8.5 (2024.07.16)
-------------------

- targetSdkVersion 33 -> 34
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
