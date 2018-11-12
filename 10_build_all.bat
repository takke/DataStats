@echo off

::: clean, APK
call gradlew clean ^
 publishRelease bundlePublishRelease

pause
